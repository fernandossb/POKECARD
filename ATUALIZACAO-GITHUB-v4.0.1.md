# Atualização do GitHub — POKECARD Brasil 4.0.1

## O que foi integrado

- Banco externo `pokemon-price-database` conectado ao POKECARD Brasil.
- Correspondência pela chave completa: carta, idioma, edição, carimbo e acabamento.
- Normal, holográfica e reversa permanecem separadas.
- 1ª edição não reutiliza o preço da ilimitada.
- Pokébola, Ultrabola, Master Ball e Holográfica de Treinador não reutilizam uma holo genérica.
- Cartas graduadas, assinadas, com erro ou outras tags especiais não recebem preço cru automaticamente.
- Preços com condição diferente de Mint/Near Mint ou carimbo sem fonte estruturada ficam para revisão.
- A Liga Pokémon continua como fonte complementar para variações exatas não cobertas pelo banco.
- O motor de pesquisa e os índices do catálogo foram preservados.

## Como publicar

1. Faça backup da pasta atual do repositório.
2. Substitua os arquivos pelo conteúdo deste projeto, sem apagar a pasta `.git`.
3. No GitHub Desktop, confira as alterações.
4. Commit sugerido: `POKECARD Brasil 4.0.1 - integrar Price Database e variações`.
5. Envie para `main`.
6. Aguarde o GitHub Actions gerar o APK direto e o AAB da Google Play com a assinatura já configurada.

## Banco de preços

O repositório `pokemon-price-database` precisa executar o workflow de atualização pelo menos uma vez para publicar:

- `output/status.json`
- `output/prices-current.json`

O aplicativo mantém o último banco salvo no aparelho quando a internet ou o GitHub estiverem indisponíveis.
