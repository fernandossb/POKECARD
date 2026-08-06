# Fontes de imagens e preços

## Imagens e metadados

As imagens, nomes de cartas, coleções e enums disponíveis podem usar o catálogo TCGdex já integrado. Esses metadados não são usados como preço direto.

## Preços

A única fonte automática consumida pelo aplicativo é o **Pokémon Price Database Brasil**, em formato dividido:

- `output/status.json`
- `output/card-shard-index.json`
- `output/shards/shard-00.json` até `shard-11.json`

O app baixa somente o shard da carta aberta. Cada shard contém os preços e o catálogo de `variantEnum` exatos daquela carta.

O aplicativo não acessa a Liga Pokémon para precificação e não possui fallback para marketplaces. Quando o enum exato não está precificado, o valor automático fica indisponível.
