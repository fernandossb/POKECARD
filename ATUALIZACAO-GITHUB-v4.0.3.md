# POKECARD Brasil 4.0.3 — enums dinâmicos do Price Database

## O que mudou

- O preço deixa de depender das listas fechadas `normal`, `holo` e `reverse`.
- Cada carta recebe os valores exatos encontrados no TCGdex, TCGplayer e Cardmarket.
- Um enum novo, ainda desconhecido pelo aplicativo, é incluído automaticamente nas variantes daquela carta.
- O cadastro grava `pricingVariant` exatamente como veio da fonte, sem tradução, alias ou migração de nomes antigos.
- A consulta usa a chave `cardId::language::variantEnum`.
- Enums sem preço permanecem disponíveis no cadastro e aparecem identificados como “sem preço exato”.
- A Liga Pokémon não é usada como fonte ou fallback de preço.

## Ordem de publicação

1. Publique primeiro o repositório `pokemon-price-database` v0.7 e execute o workflow de atualização.
2. Confirme que `output/status.json` contém `schemaVersion: 4` e `format: "sharded-v2"`.
3. Depois publique o POKECARD Brasil 4.0.3.

O `applicationId` não foi alterado. Use a mesma chave de assinatura utilizada nas versões anteriores.
