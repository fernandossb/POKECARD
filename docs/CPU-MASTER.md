# CPU Mestre

O padrão de `CpuPlayer` é `CpuStrategy.MASTER`. `HEURISTIC` conserva o avaliador de ações sem busca; `TACTICAL` acrescenta a busca do próprio turno. Ambos recebem a mesma observação protegida usada por `MASTER`, permitindo comparar as políticas sem conceder informação extra a uma delas.

## Decisões

1. A entrada substitui mãos adversárias, baralhos e Prêmios ocultos por cartas anônimas. A seed real e a posição do gerador aleatório não entram na busca.
   As previsões de dano também usam essa posição protegida. Ataques dependentes da composição da mão adversária, do topo do baralho ou de moeda recebem intervalos e marcação de incerteza; a CPU não trata o resultado mais favorável como garantido.
2. A busca explora sequências de preparação e ataque, com até sete etapas e 260 transições. Cópias equivalentes de cartas não desperdiçam o orçamento; diferentes categorias de ação permanecem na seleção.
3. Os melhores candidatos são reavaliados após o ataque. Efeitos determinísticos, descarte de Energia, bloqueios, nocautes e promoção são resolvidos pelo próprio motor quando possível. Resultados aleatórios não são escolhidos pela sorte da simulação.
4. A resposta adversária explora habilidades públicas, recuos pagos, promoções e ataques, em até quatro etapas e 28 transições. Cartas da mão oculta não são inventadas para essa busca.
5. A disputa de Prêmios estima rotas por alvos visíveis: HP, fraqueza, custo efetivo de Energia, preparação, dano previsto e Prêmios de cada alvo. A estimativa não é uma garantia de vitória nem conhece futuras compras.
6. Cada ação real provoca novo planejamento. Buscas ou compras que revelam informação passam a ser avaliadas quando essa informação fica legitimamente disponível.

O adversário aleatório prioriza os decks competitivos elegíveis. A Academia 2022 mantém seu próprio conjunto quando esse formato é selecionado. A coleção pessoal não é modificada.

## Avaliação offline

`gradle :trainer-engine:cpuArena -PcpuArenaGames=2000 -PcpuArenaThreads=8 -PcpuArenaSeedBase=9000001 -PcpuArenaBaseline=MIXED`

A tarefa é explícita: a suíte normal de testes não executa milhares de partidas. Cada par mantém decks e seed iguais e troca qual lado recebe MASTER. Com `MIXED`, metade das partidas compara com `TACTICAL` e metade com `HEURISTIC`, reutilizando os mesmos pares de decks e seeds. Os resultados de cada comparação aparecem separados em `byBaseline`. É possível escolher uma política individual com `-PcpuArenaBaseline=TACTICAL` ou `HEURISTIC`.

O relatório inclui resultados, erros, preservação das 120 cartas e distribuição do tempo de decisão da CPU Mestre. O cronômetro mede `CpuPlayer.choose`, sem a montagem de `cpuInput`, aplicação da ação e animações. O tempo é medido no computador que executa o teste, não representa uma medição no celular. As 14 listas elegíveis são decks reais, mas a arena não representa o metagame de um campeonato nem partidas contra jogadores humanos.

Saída: `trainer-engine/build/cpu-arena/report.json`. Cada partida concluída também é registrada em `outcomes.ndjson`. As falhas não são removidas silenciosamente: aparecem no relatório, com seed, decks e últimas ações para reprodução, e fazem a tarefa falhar. O processo limita cada partida a mil ações para detectar ciclos.

Isso é uma avaliação de políticas de busca, não treinamento de uma rede neural. Mudanças de pesos e critérios precisam ser justificadas por resultados e regressões táticas; uma amostra pequena ou uma taxa de vitórias isolada não estabelece nível de campeonato.

## Resultados desta implementação

Foram concluídas 2.000 partidas de desenvolvimento e 784 de validação final, todas sem erros de execução, ações ilegais ou falhas na conservação das cartas. Houve um empate no desenvolvimento. Os dois lotes usam seeds diferentes. O lote final foi executado após corrigir a identificação de derrota ao perder o último Pokémon e a consulta de ataques de evoluções anteriores.

| Lote | Referência | Partidas | Vitórias MASTER | Derrotas | Empates |
|---|---|---:|---:|---:|---:|
| Desenvolvimento | HEURISTIC | 1.000 | 527 | 472 | 1 |
| Desenvolvimento | TACTICAL | 1.000 | 508 | 492 | 0 |
| Validação final | HEURISTIC | 392 | 199 | 193 | 0 |
| Validação final | TACTICAL | 392 | 199 | 193 | 0 |

Na validação final, cada referência enfrentou todos os 196 pares de decks, com dois jogos por par e troca de lado. Comando: `gradle :trainer-engine:cpuArena -PcpuArenaGames=784 -PcpuArenaThreads=8 -PcpuArenaSeedBase=17000003 -PcpuArenaBaseline=MIXED`.

A taxa final foi 50,8% contra cada referência. A avaliação não demonstrou uma vantagem média grande ou nível de mestre. Os testes direcionados demonstram decisões melhores em posições específicas: combinar habilidade e troca para vencer, poupar os últimos Prêmios e escolher um ataque defensivo diante de uma habilidade seguida de resposta letal. A busca continua limitada e usa aproximações para efeitos incertos e cartas não reveladas.

Passaram 33 testes direcionados de busca, contra-ataque, privacidade, compatibilidade e resumo da partida, além da compilação Android `:trainer-ui:compileDebugKotlin`. Esse recorte não é a suíte completa do motor. A análise do resumo mantém sua própria busca limitada e usa a heurística nas escolhas internas, evitando buscas completas aninhadas.

Dados resumidos e hashes das fontes finais: [CPU-MASTER-EVALUATION.json](CPU-MASTER-EVALUATION.json). Os registros individuais ficam em `trainer-engine/build/cpu-arena/development-2000.json` e `validation-784.json`.
