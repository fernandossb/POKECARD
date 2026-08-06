# Precificação do POKECARD Brasil

## Fonte automática exclusiva

O aplicativo usa somente o **Pokémon Price Database Brasil**, que consolida exclusivamente **Cardmarket** e **TCGplayer** (obtidos de forma estruturada via TCGdex). O app não consulta marketplaces diretamente e não usa a Liga Pokémon como fonte nem como fallback de preço — nenhum anúncio de lojista é lido ou consultado.

Banco consumido pelo app:

```
https://raw.githubusercontent.com/fernandossb/pokemon-price-database/main/output
```

## Campos usados no cálculo

O preço é a média aritmética simples dos valores de mercado da variante exata:

- **Cardmarket**: `trend`, `avg`, `avg1`, `avg7`, `avg30`, `low`, `average-sell-price` (e as versões `-holo`).
- **TCGplayer**: `marketPrice`, `midPrice`, `lowPrice`, `directLowPrice`.

O campo **`highPrice` do TCGplayer é deliberadamente excluído** do cálculo. Ele não é um preço de mercado: é o teto do anúncio mais caro da listagem, normalmente uma carta graduada, um lote ou um erro de digitação. Como a média é simples e sem remoção de outliers, um único valor desses arruinaria o resultado — o Darumaka 015/094 (`me02-015`), um comum de US$ 0,08, tinha `highPrice` de US$ 999 e por isso saía a **R$ 464,75** em vez de **R$ 0,21**.

`highPrice` continua sendo aceito como prova de que a variante existe (descoberta de enum), mas não entra na valoração.

## Ordem de valor

1. Valor manual informado pelo usuário.
2. Preço exato do Pokémon Price Database Brasil.
3. Sem preço automático quando o enum selecionado não possui valor.

## Identidade dinâmica da variante

A consulta usa:

`cardId::language::variantEnum`

`cardId` é o identificador canônico e estável (ex.: `me02-015`), nunca o nome traduzido ou visual. O banco publica junto a identidade completa para conferência: `setId`, `setName`, `number`, `setTotal` (total oficial impresso), `setTotalWithSecrets` e `rarity`. O cadastro exibe o número como vem impresso na carta — `015/094`.

`variantEnum` é armazenado exatamente como foi publicado pelo TCGdex, TCGplayer ou Cardmarket. Não existe lista fechada nem conversão de nomes. Valores novos entram automaticamente nas opções daquela carta.

Exemplos possíveis:

- `normal`
- `holo`
- `holofoil`
- `reverse-holofoil`
- `1st-edition-holofoil`
- qualquer enum novo publicado futuramente.

Enums confirmados pela fonte, mas ainda sem preço exato, permanecem visíveis e são identificados como “sem preço exato”. O app não reutiliza o preço de outro enum.

## Idioma da carta e origem do preço

Cardmarket e TCGplayer precificam apenas as tiragens **internacional (inglês)** e **japonesa**. Não existe preço publicado para a tiragem **pt-br** nessas fontes: o banco não possui nenhuma chave `pt-br`.

Como o aplicativo assume `pt-br` como idioma padrão, uma consulta literal não encontraria preço para carta nenhuma. Por isso o `language` da chave é resolvido em cadeia:

**idioma da variante → `en` → `ja`**

O `cardId` e o `variantEnum` continuam exatos — apenas o idioma cede. Quando o valor vem de outro mercado, o cadastro informa explicitamente a origem:

> ✓ Price Database: ID e variantEnum exatos · referência do mercado internacional (inglês), pois Cardmarket/TCGplayer não precificam a tiragem pt-br.

O catálogo de variantes do idioma selecionado também passa a exibir os acabamentos das tiragens precificadas, já que o catálogo pt-br do TCGdex marca apenas `normal`.

## Condição da carta (NM/SP/MP/HP/D)

Nenhuma fonte publica preço por condição — Cardmarket e TCGplayer dão um valor único por variante, próximo de NM. O app aplica sobre esse preço-base um **percentual editável**, tratado explicitamente como estimativa:

| Condição | Padrão |
|---|---|
| Nova / Quase nova (NM) | 100% (é a própria base) |
| Usada levemente (SP/LP) | 85% |
| Usada moderadamente (MP) | 70% |
| Muito usada (HP) | 50% |
| Danificada (D) | 35% |

Os percentuais ficam em **Preços da coleção → Percentuais por condição da carta** e valem para toda a coleção. O cadastro sempre mostra o preço-base NM ao lado do valor ajustado, com o rótulo *"estimativa, não é preço de fonte"*. Alterar a tabela recalcula os valores salvos e invalida confirmações manuais feitas sobre o valor anterior.

Observação: a média já mistura um pouco de condição, porque o `low` do Cardmarket costuma ser uma cópia jogada. A base NM é, portanto, levemente conservadora.

## Carimbo (staff, prerelease, league, championship)

Nenhuma fonte precifica carimbo, e o ágio real varia de negativo a vários múltiplos da carta base — por isso o app **não aplica multiplicador** aqui.

Quando a variante é marcada como carimbada, o valor do banco é exibido com aviso de que é o da versão **sem carimbo**, e ele **não entra sozinho no total da coleção** (fica como revisão). O app procura no catálogo versões promocionais da mesma carta — que costumam existir como carta própria, com preço real — e oferece atalho para abri-las. Não havendo nenhuma, o caminho é o valor manual.

## Limitações

O valor é uma referência de mercado internacional convertida para BRL pela cotação diária, não uma cotação do mercado brasileiro nem garantia de venda. O banco não separa condição (NM/SP/MP/HP/D): cartas fora de Near Mint são marcadas para revisão manual antes de entrar no total da coleção. Cartas graduadas e variações com tags específicas não recebem preço automático. Quando nenhuma das tiragens cobertas possui valor para a variação, o app informa a ausência e mantém o preço manual disponível.
