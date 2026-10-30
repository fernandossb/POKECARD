# CPU Mestre

## Extensão local após a versão 5.99.43

### Tempo adaptativo no aplicativo

`GameViewModel` usa `CpuThinking.ADAPTIVE`: posições simples recebem até 200 ms; as demais, 800 ms; quando algum lado tem até dois Prêmios restantes, o alvo sobe para 2.000 ms. Esse é um teto de trabalho, não um atraso obrigatório. Uma vitória imediata reconhecida pode ser devolvida sem busca adicional. A montagem da entrada, a persistência e as animações não fazem parte desse orçamento.

O relógio é monotônico e a interrupção é cooperativa: a busca verifica o prazo entre operações, preserva uma decisão legal já avaliada e retorna à heurística se ainda não houver um plano completo. Uma operação isolada ou pausa do sistema pode causar pequeno excesso sobre o alvo. Cerca de 60% do orçamento é destinado à preparação; o restante fica disponível para respostas adversárias. A ordenação calcula a pontuação de cada posição uma única vez e a continuação reutiliza avaliações da mesma posição.

Testes e ajustes offline mantêm `CpuThinking.DETERMINISTIC`, com limites de nós e sem corte por relógio, para que carga da máquina não determine os resultados comparativos. Os resultados anteriores de ajuste de parâmetros não medem a força competitiva do novo modo adaptativo.

O teste Android é um source set separado (`cpuDeviceSmokeJar`), fora do APK. Ele executa partidas sintéticas no ART, sem acessar coleção, banco de dados ou partida salva do PokeCard, e mede decisões legais em posições reais dessas partidas. A expiração também é exercitada com prazos forçados de 0, 1 e 10 ms. O método verifica o motor em um processo isolado; não é uma medição completa da interface ou uma prova de pior caso para todos os decks.

Validação do tempo adaptativo: passaram 36 testes direcionados e a compilação Android. No POCO X7, seis decisões em três posições de uma partida sintética levaram de 15,9 a 159,8 ms, todas legais. As verificações com orçamento forçado de 0, 1 e 10 ms também retornaram ações legais; os tempos observados foram aproximadamente 0,05, 1,04 e 16,64 ms. O último caso demonstra o pequeno excesso possível por uma operação indivisível. A verificação também cobre a limpeza da decisão anterior quando uma instância de busca é reutilizada com prazo esgotado. Dados e hashes: [CPU-THINKING-EVALUATION.json](CPU-THINKING-EVALUATION.json). Essa medição não instalou código novo no aplicativo do usuário.

O ajuste offline usa `scripts/tune-cpu.ps1`: dois candidatos fixos alteram o peso da resposta adversária, da disputa de Prêmios e da vantagem exigida para substituir a decisão heurística. Ambos enfrentam MASTER com os parâmetros padrão nas mesmas seeds de ajuste. Somente o vencedor do ajuste enfrenta seeds reservadas de validação, sem escolher novamente entre candidatos com base nessa validação.

Execução inicial: 32 partidas de ajuste e 200 de validação, com zero erros. O candidato `.5,.25,60` obteve 8 vitórias em 16 partidas de ajuste; `.2,.1,100` obteve 9 e foi selecionado. Na validação, o selecionado obteve 103 vitórias e 97 derrotas em cem pares. Média de pontos: 51,5%; limite inferior aproximado: 46,8%. A mudança foi rejeitada e os padrões `.35,.15,80` foram preservados. Os resultados medem os candidatos contra a CPU local com os itens 1–3, não contra o binário publicado em 5.99.43. Dados e hashes: [CPU-TUNING-EVALUATION.json](CPU-TUNING-EVALUATION.json).

Comando desse lote: `pwsh scripts/tune-cpu.ps1 -GradleCommand gradle -TrainingGames 16 -ValidationGames 200 -Threads 8 -TrainingSeed 400000003 -ValidationSeed 1200000011`. Ao modificar candidatos depois de examinar a validação, reserve novas seeds para a avaliação seguinte. O processo desativa cache da arena e verifica configuração, seed, referência, quantidade de partidas e erros antes de utilizar cada relatório. Uma nova execução invalida a aprovação anterior enquanto estiver em andamento.

Exemplo: `pwsh scripts/tune-cpu.ps1 -GradleCommand gradle -TrainingGames 56 -ValidationGames 392`. A aprovação exige zero erros, partidas completas, pelo menos cem pares de validação e limite inferior aproximado de 95% da média dos pontos por par acima de 50%. As duas partidas de uma mesma seed são tratadas como um par, não como observações independentes. O resultado, parâmetros e hashes das fontes são gravados em `trainer-engine/build/cpu-tuning/recommendation.json`, com os relatórios individuais arquivados. A recomendação não altera silenciosamente os parâmetros do aplicativo. Um lote curto testa o processo, mas não autoriza adoção.

A avaliação de posição agora compara os dois campos: atacantes com ataque legal disponível, reservas prontas, investimento de Energia, habilidades de compra/aceleração, recuperação conhecida na mão, bloqueios ativos e exposição de Pokémon feridos no Banco. A prontidão consulta o validador do motor; recuperação bloqueada ou sem alvo útil não recebe o bônus. A exposição considera Prêmios concedidos e a existência de ataques públicos que alcançam o Banco, sem assumir cartas de troca na mão oculta. Esses componentes são heurísticos, não probabilidades de vitória calibradas. O avaliador participa tanto da busca do turno atual quanto da continuação no próximo turno.

As previsões passam a usar a composição conhecida do próprio deck menos a mão e as cartas públicas. Baralho e Prêmios fechados são tratados como um conjunto não observado, sem ler sua distribuição real. Compras úteis usam probabilidade sem reposição; ataques que descartam Energias do topo usam a proporção esperada do tipo necessário. Listas ausentes ou contagens inconsistentes desativam a estimativa.

Para dano dependente da mão adversária, há uma hipótese de categorias baseada nas listas de treino compatíveis com cartas públicas reveladas. Cada lista compatível recebe o mesmo peso; isso é uma aproximação do repertório cadastrado, não uma probabilidade calibrada para qualquer deck personalizado. Sem lista compatível, mantém-se o intervalo conservador anterior. O modelo não consulta a lista escolhida ou a mão real do adversário e não cria uma mão fictícia como fato.

A avaliação de candidatos agora pode continuar depois de uma resposta adversária determinística até o próximo turno da CPU. Nesse turno, explora ligações de Energia, evoluções, entrada de Básicos, recuos e ataques legais com recursos conhecidos. O valor dessa continuação participa da avaliação de Prêmios do candidato atual.

O orçamento adicional limita a quatro continuações adversárias, três etapas de preparação, 24 transições por continuação e duas posições mantidas por etapa. Escolhas ainda pendentes e resultados aleatórios não são resolvidos como se fossem conhecidos. Esta extensão ainda não inclui busca probabilística ou todos os tipos de preparação do turno seguinte. As métricas de arena registradas abaixo pertencem à implementação publicada em 5.99.43, não validam a força desta extensão.

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
