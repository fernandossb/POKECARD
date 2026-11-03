# Comparação com a versão anterior

## Congelar antes de alterar

Execute na raiz do repositório:

```powershell
./scripts/freeze-cpu-baseline.ps1 -Name 'nome-unico-da-versao'
```

O script compila um JAR isolado de testes, salva o fonte completo do módulo e registra hashes SHA-256. Inclui alterações locais ainda não commitadas. Nunca sobrescreve snapshots. O artefato não entra no APK.

## Confronto direto

```powershell
$baseline = (Resolve-Path '.cpu-baselines/nome-unico-da-versao/engine.jar').Path
gradle.bat :trainer-engine:cpuArena '-PcpuArenaGames=20' '-PcpuArenaDeck=lucario' '-PcpuArenaThreads=4' '-PcpuArenaBaseline=FROZEN' "-PcpuArenaBaselineJar=$baseline" '-PcpuArenaSeedBase=58000003'
```

Use o executável Gradle instalado caso não esteja no PATH. Vinte jogos significam dez sementes, cada uma com os assentos invertidos. Ambos usam Mestre determinístico, o mesmo baralho e parâmetros padrão. Não ajuste `cpuArenaTuning` nessa comparação sem declarar que a configuração também mudou.

A arena carrega classes e catálogo da versão anterior num classloader isolado. Um bridge JSON transporta estado/ação; a CPU antiga recebe a observação filtrada por seu próprio adapter. As ações retornadas são validadas pela engine da partida. Incompatibilidade de schema ou ação ilegal falha o teste. Não há fallback silencioso para CPU atual.

O report registra o hash do JAR anterior e latências separadas. Cronômetros incluem apenas `CpuPlayer.choose`, excluindo serialização, montagem de entrada e diagnóstico. O teste offline sem prazo não valida latência Android. Tempos em JVM concorrente são observações sujeitas a aquecimento e carga, não microbenchmark controlado.

Preserve `trainer-engine/build/cpu-arena/report.json` em `docs/` com nome único. Os binários e fontes congelados ficam em `.cpu-baselines/`, fora do Git; transfira-os com verificação de hash se precisar reproduzir em outro computador.

## Modo de informação

Para a comparação solicitada do CPU+ com acesso à mão, acrescente `-PcpuArenaKnowledge=OPPONENT_HAND`. O padrão da arena continua FAIR; a aplicação usa `CpuKnowledge.appMode`, atualmente OPPONENT_HAND. O relatório identifica essa assimetria: vantagem de informação não é evidência isolada de melhor inteligência em condições iguais. Decks, prêmios e RNG permanecem mascarados.

Novos snapshots incorporam `CpuKnowledge.appMode` no bridge e informam `baselineKnowledge`. Snapshots antigos sem esse método retornam `UNREPORTED_LEGACY`; confira o fonte e manifesto preservados. O snapshot `reply-before-hand-advantage-20261102` é anterior ao CPU+ e usa FAIR. Não recompile o snapshot antigo para atualizar metadados, pois isso mudaria a referência.

## Primeiro snapshot

`bench-safety-before-cache-20261102`, imediatamente anterior ao cache de previsões de contra-jogada.

JAR SHA-256: `b939fc242fba5cfa8b9857e00da062940e93d12dd0918b0b1e8faf5472014585`.

O commit-base sozinho não identifica essa versão: havia alterações locais, preservadas no arquivo `source.zip` e seu manifesto.
