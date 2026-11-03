# Evolução da CPU — 2026-11-02

Modo atual do aplicativo: **CPU+ com acesso à mão adversária**, autorizado explicitamente pelo usuário após a fase de informação justa. A engine mantém FAIR como opção padrão da API; o controlador do aplicativo seleciona OPPONENT_HAND.

## Implementado e validado

- Removidos `CpuInput.knownOpponentHand` e o parâmetro `revealOpponentHandToCpu`. App e arena usam a mesma fronteira de observação. Revelações exigidas por efeitos continuam disponíveis nas escolhas permitidas.
- `AiDifficultyConfig`: seis presets de trabalho, limites de sequências, beam, candidatos, respostas, continuações e prazo. Configurações inválidas são rejeitadas. Profundidade ainda significa ações dentro do turno, não quatro turnos completos.
- `CpuBoardWeights`: pesos ajustáveis no avaliador existente, com ajuste por prêmios restantes. Início favorece engines e recuperação; final aumenta prontidão, risco de exposição e vantagem de prêmios. A proximidade de vitória de qualquer jogador ativa o final; os fatores são heurísticos, ainda sem calibração por arena.
- `CpuPrizeRace`: acesso ao Banco deixou de ser gratuito. A estimativa aplica custo de tempo para alvos sem acesso conhecido e custo de recurso para gust disponível. A programação dinâmica limita o consumo ao número de cartas conhecidas e legalmente jogáveis; não reutiliza a mesma carta para vários alvos. Gust com restrição de alvo é conservadoramente excluído desta estimativa, mas continua disponível à busca pelas regras normais.
- Configuração propagada por `CpuPlayer`, `CpuTurnSearch`, `CpuCounterplay` e `CpuSearchBudget`. A interface continua usando o preset Mestre; não foi acrescentado seletor de dificuldade.
- Fallback de perfis personalizados usa o catálogo injetado, sem consultar obrigatoriamente o catálogo global.
- `CpuLethalDetector`: reconhece vitória comprovada por efeitos de preparação, resolvendo prêmios obrigatórios pelo motor. Não declara vitória em empate por condições simultâneas nem atravessa escolhas/aleatoriedade como se fossem resultados garantidos.
- Busca reconhece essas vitórias antes de descartar estados fora de MAIN/RESOLVE_CHOICE. Caches por decisão são limpos quando uma instância de busca é reutilizada.
- `CpuBenchSafety`: detecta exposição condicional dos últimos prêmios, inclusive de Pokémon saudáveis. Usa o atacante ativo e as energias públicas, valida ataques pela engine e mascara zonas ocultas antes de estimar dano. Não supõe que o adversário tenha gust; é uma penalidade de risco. O fallback evita um alvo desnecessário quando já existe reserva, e a busca continua podendo explorar a jogada. Não bloqueia colocar a primeira reserva.
- Profiling da arena agora mede somente `CpuPlayer.choose`, excluindo a heurística extra e o trace de diagnóstico; cada partida registra sua decisão Mestre mais lenta. Tempos anteriores incluíam diagnóstico e não devem ser comparados diretamente.

## Validação

Primeiro recorte: 58 testes passaram e compilação Android concluída. Após integrar lethal por habilidade: 60 testes passaram e `:trainer-ui:compileDebugKotlin` concluiu. Suítes: AiDifficultyConfigTest, CpuLethalDetectorTest, MasterCpuTest, CpuCounterplayTest, CpuForecastTest, CpuSearchBudgetTest, DeckLibraryTest e TrainingReviewTest. Não é a suíte completa da engine.

Após custo de acesso ao Banco e pesos por fase: 63 testes das mesmas suítes passaram, sem falhas ou erros, e `:trainer-ui:compileDebugKotlin` concluiu. Novos cenários verificam consumo único de gust, Supporter já usado/bloqueado, restrição de alvos e simetria dos pesos por fase. A rota de prêmios anterior foi atualizada para não pressupor acesso gratuito ao Banco.

Após segurança do Banco: 65 testes dessas suítes passaram, sem falhas ou erros; compilação Android concluída. Os cenários adicionais verificam alvo saudável, energias públicas suficientes/insuficientes, distância da vitória, invariância à mão oculta, decisão Mestre de encerrar em vez de adicionar alvo desnecessário e preservação da primeira reserva.

Testes novos verificam invariância da decisão ao trocar a mão oculta em todos os presets, preservação do snapshot original, configuração de prazo, catálogo injetado e uma posição com cartas reais em que Cursed Blast de Dusclops conquista os últimos prêmios. Outro teste verifica que o nocaute do próprio Dusclops não pode ser anunciado como vitória garantida quando também entrega o último prêmio adversário.

Arena após remover a informação privilegiada e integrar acesso ao Banco/pesos por fase: Mega Lucario ex espelhado, 40 jogos, seedBase 58000003, MIXED, quatro workers. Mestre venceu 18/40 (45%): 10/20 contra TACTICAL e 8/20 contra HEURISTIC; 22 derrotas, nenhum empate ou erro. São dez sementes pareadas entre assentos e modos, não quarenta amostras independentes. Relatório preservado em `CPU-ARENA-LUCARIO-58000003.json`. Resultado não comprova ganho nem nível competitivo; não foi feito A/B com uma versão anterior congelada nas mesmas sementes.

Latência offline: mediana 37,38 ms, p95 1435,23 ms, máximo 17782,32 ms. A arena usa modo determinístico sem prazo de parede, em computador e com quatro workers; esses números não validam o orçamento Android. O pico exige atenção antes de declarar desempenho mobile aprovado. Um smoke anterior com outras duas sementes terminou 8/8 sem erros (5 vitórias), amostra pequena demais para inferir força.

Relatórios anteriores com `masterKnowsOpponentHand=true` não são referência justa para esta implementação. A arena agora registra `false`.

Repetição após segurança do Banco, mesmas 40 partidas/sementes: 18 vitórias e 22 derrotas, zero erros; 10/20 contra TACTICAL e 8/20 contra HEURISTIC. Não houve aumento da taxa neste lote. Relatório: `CPU-ARENA-LUCARIO-BENCH-SAFETY-58000003.json`. A melhoria é compartilhada pelos modos da CPU; isto não é um duelo contra um executável anterior congelado. No jogo 0, a heurística deixou de colocar o alvo imediatamente, mas a busca voltou a escolhê-lo em turno posterior, mostrando que a correção pontual não resolve o planejamento completo.

Profiling corrigido: mediana 35,07 ms, p95 1410,38 ms, máximo 12019,17 ms. Maior pico: jogo 16, turno 15, MAIN, ligação de Energia em Mega Lucario ex. Outros picos ocorreram antes de evoluir Riolu e jogar Suplemento Premium Pro. São decisões em busca determinística sem orçamento de parede; é preciso instrumentar expansões/avaliações para localizar o custo interno e medir ADAPTIVE separadamente. Não se conclui ganho de velocidade comparando com o cronômetro antigo.

## Comparação obrigatória com versão anterior

A partir da solicitação do usuário de comparar sempre com a versão anterior, o protocolo principal é `FROZEN`, documentado em `CPU-BENCHMARK-PROTOCOL.md` e persistido em `trainer-engine/AGENTS.md`. O snapshot `bench-safety-before-cache-20261102` preserva a versão anterior ao cache, incluindo alterações locais. Classes e catálogo são carregados isoladamente; ações retornadas são validadas. O hash do JAR anterior entra no relatório.

A primeira alteração sob esse protocolo é memoização limitada a 128 estados por análise de contra-jogada, limpa em cada `evaluate`. Não muda pontuações ou regras. Os 34 testes direcionados (CpuCounterplayTest, MasterCpuTest e FrozenCpuVersionTest) passaram com a referência configurada; compilação Android concluída. A integração compara 30 decisões sequenciais nas mesmas posições e verifica determinismo e legalidade da referência congelada.

Confronto direto concluído: `CPU-ARENA-FROZEN-CACHE-58000003.json`, 20 partidas Mega Lucario ex espelhadas, dez sementes com assentos invertidos, nova Mestre contra Mestre congelada. Resultado 10–10, sem empates ou erros. Mediana da nova 36,092 ms contra 39,0138 ms anterior; p95 da nova 1543,4627 ms contra 1496,6976 ms anterior. Sem ganho de força demonstrado e sem evidência consistente de ganho de velocidade (mediana melhor, cauda pior). Cronômetros medem somente decisão, não bridge/entrada/UI. Execução determinística, concorrente, desktop; não é validação mobile. O hash da referência no relatório coincide com o manifesto congelado.

## Planejamento após nocaute e no turno seguinte

Referência congelada antes das alterações: `cache-before-reply-planning-20261102`, SHA-256 `6c6e1780ff2ecf317c13d744cb22a9015c18911bfcdf077fb38ca59a2fb7d22c`.

- Corrigida perda de linhas adversárias quando a promoção iniciava o turno e aumentava a mão com uma compra mascarada. A carta desconhecida continua anônima; o atacante público promovido permanece na análise.
- Continuação do turno seguinte passa a explorar Trainers, habilidades, estádio e escolhas determinísticas conhecidas, além de energia, evolução, básicos e recuo. Mantém os limites de profundidade/transições, rejeita embaralhamento, novos draws próprios desconhecidos e buscas com escolhas ocultas. Não representa simulação probabilística completa.
- Dois testes de regressão foram executados primeiro na versão anterior e falharam: resposta vencedora após promover e linha futura Boss → alvo → ataque vencedor. Após a correção, os 36 testes direcionados (CpuCounterplayTest, MasterCpuTest e FrozenCpuVersionTest) passaram; compilação Android concluída.
- A integração com referência congelada agora exige determinismo e legalidade, mas permite diferenças intencionais de estratégia. A igualdade ação por ação usada na mudança anterior era específica da otimização por cache.
- A autorização mais recente permite vantagens de informação, mas esta implementação continua no modo justo. Nenhum acesso à mão oculta, ordem de deck ou prêmios foi acrescentado.

Comparação direta com o snapshot anterior: `CPU-ARENA-FROZEN-REPLY-PLANNING-58000003.json`, 20 jogos, dez sementes pareadas, Mega Lucario ex espelhado. Nova 10 × anterior 10, zero erros. Medianas 38,52 ms nova / 41,23 ms anterior; p95 2848,52 ms nova / 1313,78 ms anterior; máximo da nova 21174,58 ms. A correção é demonstrada nos cenários controlados, mas não aumentou a taxa de vitória nesse lote e aumentou o custo na cauda da busca determinística. Não é validação de latência mobile; prazos ADAPTIVE permanecem cooperativos. Otimizar o custo das continuações antes de ampliar profundidade é uma prioridade.

## CPU+ solicitado: acesso à mão e redução de respostas vencedoras

A instrução posterior do usuário exige informação não permitida numa partida justa, superando a restrição anterior de usar apenas informação pública. O aplicativo agora usa `CpuKnowledge.appMode = OPPONENT_HAND`, identificado como CPU+ na mesa e explicado no lobby/ajuda. `cpuInput` continua FAIR por padrão para testes e consumidores que desejem jogo justo.

O modo acrescenta somente a mão atual adversária à cópia usada na busca. Não revela ordem de decks, prêmios ou RNG; não altera regras, compras, HP ou dano. A contra-jogada pode preparar energias, evoluções e Trainers conhecidos; a preparação continua usando as cartas conhecidas mesmo após aparecer uma compra anônima.

A avaliação registra ataques vencedores distintos por atacante/ataque/alvo e penaliza a quantidade, com `winningReplyPenalty` configurável. Isto favorece reduzir respostas vencedoras encontradas, mas não conta todas as sequências e não garante exatamente uma saída. Estado terminal, aleatoriedade e limite de busca podem impedir qualquer garantia desse tipo.

Referência congelada antes do CPU+: `reply-before-hand-advantage-20261102`, SHA-256 `8f366e1800df0c52c36f9b0cf1723a3fb30ba78ec5a4ccd2ebcebeaa388f7036`. A comparação usa essa versão anterior justa; os relatórios marcam explicitamente a informação adicional concedida à candidata. Snapshots futuros preservam a política CPU+ embutida no bridge.

Validação CPU+: 71 testes passaram em nove suítes, zero falhas/erros; `:trainer-ui:compileDebugKotlin` concluiu. Cenários novos verificam isolamento das zonas ainda ocultas, detecção do Boss real na mão, escolha de ataque protetor para impedir esse lethal e contagem de ataques vencedores distintos. A suíte Mestre continua verificando o modo FAIR. Nenhuma promessa de exatamente uma saída vencedora é feita.

Confronto preservado em `CPU-ARENA-CPU-PLUS-HAND-58000003.json`: 20 partidas Mega Lucario ex espelhadas, dez sementes com assentos invertidos, CPU+ 12 × anterior 8 (60%), zero empates/erros. A candidata usou OPPONENT_HAND; a referência congelada anterior é FAIR, conforme seu fonte (o bridge antigo não possuía método de metadados e retorna UNREPORTED_LEGACY no relatório). O hash corresponde ao manifesto anterior ao CPU+. É evidência deste lote pequeno com vantagem de informação, não comprovação de superioridade em condições iguais nem de que reste uma única saída.

Tempos offline determinísticos: mediana CPU+ 92,76 ms / anterior 52,01 ms; p95 CPU+ 5688,68 ms / anterior 2299,08 ms; máximo CPU+ 32493,75 ms. O acesso à mão ampliou o custo das respostas. A busca ADAPTIVE do aplicativo conserva o orçamento cooperativo, mas não foi medida em aparelho nesta etapa. Nenhum APK novo foi publicado ou instalado nesta etapa.

## Eficiência e diversidade das respostas do CPU+

Referência congelada: `cpu-plus-before-reply-efficiency-20261102`, SHA-256 `7b05c9b7e00ac0808d512e8cf8a759e5aaf74d24f1e08e8fd3fba21fd366c5b8`. Este snapshot preserva OPPONENT_HAND no bridge; a comparação seguinte usa a mesma vantagem dos dois lados.

- A busca de respostas elimina cópias equivalentes antes do limite de ramos e intercala tipos de preparação. Várias cópias de um Trainer deixam de consumir todas as vagas e esconder, por exemplo, uma ligação de energia vencedora. Não impõe uma sequência fixa de jogadas nem aumenta o limite de busca.
- A sonda de preparação por energia/evolução agora gera diretamente esse subconjunto, sem gerar e validar Trainers, ataques e habilidades para descartá-los logo depois. A engine continua validando cada candidato; a ordem e os alvos desse subconjunto são preservados.
- A chave de equivalência foi centralizada e compartilhada, evitando duplicar a lógica da busca principal.
- O cenário com cópias de cura escondendo uma energia vencedora falhou antes da correção. Após a alteração: 74 testes em nove suítes passaram, zero falhas/erros; compilação Android concluída. Novos testes verificam equivalência do gerador focado, respeito à energia manual já usada e preservação de alvos distintos.

## Trabalho pendente

Ainda faltam gerador completo de escolhas sem explosão combinatória; ameaça e recursos estruturados; planejamento de atacantes; refinamento das rotas com custo de mobilidade do próprio atacante, gust condicionado e resposta adversária; retenção de linhas e alternativas; cenários probabilísticos e memória pública; MCTS; explicações e revisão compartilhando o novo serviço. Os custos de acesso são estimativas, não provas de que o adversário promoverá determinado alvo.

O estado e as regras existentes foram preservados, sem alteração do schema de save. Os prazos continuam cooperativos e não cobrem toda a preparação da entrada, persistência ou animações. Não há alegação de nível competitivo comprovado ou conclusão das seis fases.

O documento CPU-COMPETITIVE-ARCHITECTURE.md descreve o estado encontrado antes destas alterações. A fase FAIR removeu o acesso privilegiado; a solicitação posterior de CPU+ reintroduziu somente a mão adversária por um modo explícito e identificado.

Comparação concluída: 20 partidas (10 sementes com lados alternados), Mega Lucario ex espelhado, ambos OPPONENT_HAND: 10 vitórias / 10 derrotas / 0 erros. Mediana 70,43 ms contra 73,01 ms; p95 2810,50 ms contra 2912,69 ms. Tempos somente de choose, arena determinística com quatro workers; não representam o celular nem comprovam ganho de força. Relatório: CPU-ARENA-CPU-PLUS-REPLY-EFFICIENCY-58000003.json.
