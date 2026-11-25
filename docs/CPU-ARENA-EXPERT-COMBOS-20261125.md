# TCG Trainer — combinações para finalizar partidas

Referência congelada: `expert-combos-before-20261125` (commit `6f72880`).
SHA-256 do manifesto: `8f8e4d4055eda7d3ef79d6307d2309c7060562da5873ca9c7290e08c49b16ff4`.
SHA-256 do JAR: `48fb72ed8d6d123d96b87d05dcb131258b45b38825fff6dc8e661c1499a3f7ee`.

Arena local em modo FROZEN, Master determinístico, informação pública (FAIR) de ambos os lados,
ajustes padrão e espelho Mega Lucario ex. Cada semente é jogada duas vezes com os lados trocados.

| Revisão | Partidas | Sementes | Vitórias | Derrotas | Empates | Erros | Mediana | p95 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Seleção de linhas de finalização | 8 | 4 | 4 | 4 | 0 | 0 | 7,50 ms | 1.566,68 ms |
| Ordenação com pontuação calculada uma vez | 16 | 8 | 8 | 8 | 0 | 0 | 2,81 ms | 2.347,41 ms |
| Remoção do bônus por só expor alvo de vários Prêmios | 16 | 8 | 9 | 7 | 0 | 0 | 2,38 ms | 3.245,98 ms |
| Finalização segura (sementes novas, base 3000001) | 12 | 6 | 7 | 5 | 0 | 0 | 2,35 ms | 2.408,57 ms |
| Finalização segura sem atalho (sementes novas, base 5000001) | 16 | 8 | 8 | 8 | 0 | 0 | 3,09 ms | 4.717,44 ms |
| Finalização segura sem atalho (repetição da base 3000001) | 12 | 6 | 7 | 5 | 0 | 0 | 2,36 ms | 2.490,98 ms |
| Diagnóstico complementar com espelho Dragapult (base 7000001) | 12 | 6 | 7 | 5 | 0 | 0 | 2,70 ms | 4.017,29 ms |
| Repetição solicitada, mesma lista de Mega Lucario ex nos dois lados (base 9000001) | 12 | 6 | 6 | 6 | 0 | 0 | 1,00 ms | 1.643,21 ms |

Tempos medidos somente em `CpuPlayer.choose` no computador, com partidas concorrentes.
As três primeiras rodadas reutilizam sementes. A quarta usou seis sementes
novas. Os placares de 9–7 e 7–5 favorecem as respectivas revisões nessa amostra
de Mega Lucario ex, com margem pequena.
Na rodada com base 5000001, o mesmo lado venceu nas duas partidas de cada
semente. A versão candidata e a congelada terminaram empatadas nesse conjunto.
A revisão final preservou os vencedores das 12 partidas da base 3000001.
Somando somente as duas amostras distintas do código final, foram 15 vitórias
e 13 derrotas em 28 partidas, sem erros. A diferença observada vem de um único
par de partidas que a candidata venceu dos dois lados.
O diagnóstico de Dragapult acrescentou 7 vitórias e 5 derrotas. Ao todo, o
código final terminou com 22 vitórias, 18 derrotas e nenhum erro em 40 partidas
nas rodadas iniciais (20 sementes distintas). A repetição de Mega Lucario ex
empatou em 6–6, elevando o total para 28 vitórias e 24 derrotas em 52 partidas
sem erros, com 26 sementes distintas.
Na semente `1628377`, a revisão da escolha de alvos venceu nas duas posições.
Na partida com a CPU candidata no segundo assento, o turno final registrou
três Suplementos Premium Pro antes da Megavalentia e a retirada dos três Prêmios
restantes. A decisão mais lenta dessa partida levou cerca de 12 segundos no
modo determinístico da arena. Esse tempo não é uma medição no celular.
Os relatórios brutos foram preservados no diretório da referência congelada.
