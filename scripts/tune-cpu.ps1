param([string]$GradleCommand='gradle', [int]$TrainingGames=56, [int]$ValidationGames=392, [int]$Threads=8,
      [long]$TrainingSeed=400000003, [long]$ValidationSeed=1200000011)
$ErrorActionPreference='Stop'
if ($TrainingGames -lt 4 -or $TrainingGames % 2 -or $ValidationGames -lt 4 -or $ValidationGames % 2) { throw 'Use quantidades pares de partidas, pelo menos quatro.' }
$trainingSeeds=@{}
for($i=0;$i -lt $TrainingGames/2;$i++) {$trainingSeeds[[string]($TrainingSeed+$i*104729L)]=$true}
for($i=0;$i -lt $ValidationGames/2;$i++) {if($trainingSeeds.ContainsKey([string]($ValidationSeed+$i*104729L))){throw 'Seeds de ajuste e validação se sobrepõem.'}}
$root=Split-Path $PSScriptRoot -Parent
Push-Location $root
try {
    $output=Join-Path $root 'trainer-engine/build/cpu-tuning'
    New-Item -ItemType Directory -Path $output -Force|Out-Null
    $hashes=[ordered]@{}
    Get-ChildItem trainer-engine/src/main/kotlin/com/tcgtrainer/engine -Filter '*.kt'|Sort-Object Name|ForEach-Object {$hashes[$_.Name]=(Get-FileHash -LiteralPath $_.FullName).Hash}
    @{approved=$false;status='running';trainingSeed=$TrainingSeed;validationSeed=$ValidationSeed;sourceHashes=$hashes}|ConvertTo-Json -Depth 5|
        Set-Content -LiteralPath (Join-Path $output 'recommendation.json') -Encoding utf8
    function Run-Arena([string]$Name,[string]$Parameters,[int]$Games,[long]$Seed) {
        & $GradleCommand --no-daemon :trainer-engine:cpuArena "-PcpuArenaGames=$Games" "-PcpuArenaThreads=$Threads" "-PcpuArenaSeedBase=$Seed" '-PcpuArenaBaseline=MASTER' "-PcpuArenaTuning=$Parameters" | Out-Host
        if ($LASTEXITCODE -ne 0) { throw "Arena falhou: $Name. Nenhuma configuração será promovida." }
        $path=Join-Path $output "$Name.json"
        Copy-Item -LiteralPath trainer-engine/build/cpu-arena/report.json -Destination $path -Force
        $report=Get-Content -LiteralPath $path -Raw|ConvertFrom-Json
        if ($report.errors -ne 0 -or $report.gamesCompleted -ne $Games -or $report.candidateTuning -ne $Parameters -or
            $report.seedBase -ne $Seed -or $report.baseline -ne 'MASTER') { throw 'Relatório incompleto ou configuração incorreta.' }
        return $report
    }
    # Fixed candidates and seeds are declared before seeing validation outcomes.
    $candidates=@('.5,.25,60','.2,.1,100')
    $training=@()
    for($i=0;$i -lt $candidates.Count;$i++) {
        $report=Run-Arena "training-$i" $candidates[$i] $TrainingGames $TrainingSeed
        $training+= [pscustomobject]@{parameters=$candidates[$i];points=($report.masterWins+.5*$report.draws)/$TrainingGames;report=$report}
    }
    $selected=$training|Sort-Object points -Descending|Select-Object -First 1
    # Holdout seeds are disjoint from tuning seeds; validation never chooses between candidates.
    $validation=Run-Arena 'validation' $selected.parameters $ValidationGames $ValidationSeed
    $pairs=@($validation.outcomes|Group-Object seed|ForEach-Object {
        if($_.Count -ne 2){throw 'Par incompleto'}
        $points=0.0
        foreach($game in $_.Group){if($null -eq $game.winner){$points+=.5}elseif($game.winner -eq $game.masterSeat){$points+=1}}
        $points/2
    })
    $mean=($pairs|Measure-Object -Average).Average
    $variance=($pairs|ForEach-Object {($_-$mean)*($_-$mean)}|Measure-Object -Sum).Sum/($pairs.Count-1)
    $lower=$mean-1.96*[math]::Sqrt($variance/$pairs.Count)
    $approved=$pairs.Count -ge 100 -and $lower -gt .5
    $summary=[ordered]@{selected=$selected.parameters;approved=$approved;validationScore=$mean;pairedLower95=$lower;validationPairs=$pairs.Count;minimumPairs=100;trainingSeed=$TrainingSeed;validationSeed=$ValidationSeed;training=@($training|Select-Object parameters,points);sourceHashes=$hashes;note='Avaliação pareada aproximada; aprovação gera uma recomendação, não altera silenciosamente a CPU instalada. Amostra de smoke não autoriza promoção.'}
    $summary['status']='complete'
    $summary|ConvertTo-Json -Depth 6|Set-Content -LiteralPath (Join-Path $output 'recommendation.json') -Encoding utf8
    [pscustomobject]$summary|Select-Object selected,approved,validationScore,pairedLower95,validationPairs|ConvertTo-Json|Out-Host
} finally {Pop-Location}
