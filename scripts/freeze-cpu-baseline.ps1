param(
    [Parameter(Mandatory=$true)][ValidatePattern('^[a-zA-Z0-9_-]+$')][string]$Name,
    [string]$GradleCommand
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
$destination = Join-Path $repo ".cpu-baselines/$Name"
if (Test-Path -LiteralPath $destination) { throw "Baseline already exists: $destination. Choose a new name; snapshots are never overwritten." }
if (-not $GradleCommand) {
    $installed = Get-Command gradle.bat -ErrorAction SilentlyContinue
    if ($installed) { $GradleCommand = $installed.Source }
    else { $GradleCommand = Get-ChildItem "$env:USERPROFILE/.gradle/wrapper/dists/gradle-8.13-bin" -Filter gradle.bat -Recurse | Select-Object -First 1 -ExpandProperty FullName }
}
if (-not $GradleCommand) { throw 'Supply -GradleCommand with the installed Gradle executable.' }
Push-Location $repo
try {
    & $GradleCommand :trainer-engine:cpuBaselineJar
    if ($LASTEXITCODE -ne 0) { throw 'Baseline build failed.' }
    $artifacts = @(Get-ChildItem 'trainer-engine/build/libs/*-cpu-baseline.jar')
    if ($artifacts.Count -ne 1) { throw 'Expected exactly one baseline JAR.' }
    $artifact = $artifacts[0]
    New-Item -ItemType Directory -Path $destination | Out-Null
    Copy-Item -LiteralPath $artifact.FullName -Destination (Join-Path $destination 'engine.jar')
    Compress-Archive -LiteralPath 'trainer-engine/src', 'trainer-engine/build.gradle.kts' -DestinationPath (Join-Path $destination 'source.zip')
    [ordered]@{
        name = $Name
        createdUtc = [DateTime]::UtcNow.ToString('o')
        gitHead = (& git rev-parse HEAD)
        includesWorkingTreeChanges = $true
        jarSha256 = (Get-FileHash (Join-Path $destination 'engine.jar') -Algorithm SHA256).Hash.ToLowerInvariant()
        sourceSha256 = (Get-FileHash (Join-Path $destination 'source.zip') -Algorithm SHA256).Hash.ToLowerInvariant()
        mode = 'MASTER / DETERMINISTIC / default tuning'
        knowledgePolicy = 'Embedded in FrozenCpuBridge using CpuKnowledge.appMode; query knowledgeMode for exact mode'
    } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $destination 'manifest.json') -Encoding utf8
    Write-Output "Frozen baseline: $destination"
} finally { Pop-Location }
