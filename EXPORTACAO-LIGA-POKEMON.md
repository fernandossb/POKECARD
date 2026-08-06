# Exportação para a Liga Pokémon

## O que faz

Gera uma planilha CSV com as cartas da coleção marcadas para venda, troca ou lista de desejos, para colar no cadastro de anúncio do Bazar da Liga Pokémon ou na Loja Virtual.

## Fonte dos dados

Usa somente a coleção salva localmente no aparelho. Carta, coleção e número usam a mesma resolução (`ligaCardNumber`, `ligaSetCode`) que a busca de preço automática já usa para localizar a carta na Liga, então o texto bate com o que a página da Liga espera para aquela carta. Nenhum dado vem de fora da coleção do usuário.

## Colunas

Tipo, Carta, Coleção, Código Liga, Número, Idioma, Acabamento, Condição, Edição, Distribuição, Variação artística, Graduação, Quantidade, Preço unitário (R$), Observações.

- **Tipo**: combinação de Venda / Troca / Desejo, conforme os marcadores da carta.
- **Preço unitário**: usa o valor manual quando existe; senão, a cotação automática já validada da Liga. Fica em branco quando nenhum dos dois está disponível — não é inventado.
- **Código Liga**, **Variação artística** e **Graduação** ficam em branco quando não há valor correspondente, em vez de repetir um valor padrão.

## Formato do arquivo

CSV com `;` como separador (padrão do Excel/Sheets em português), vírgula como separador decimal do preço, sem símbolo de moeda, e BOM UTF-8 para abrir a acentuação corretamente.

## Onde fica

Painel de Backup da coleção → botão **Exportar p/ Liga Pokémon**.

## Limitação conhecida

A área de cadastro/importação da Liga Pokémon (Bazar e Loja Virtual) exige login e não ficou acessível para leitura pública, então não foi possível confirmar o nome exato de coluna que a ferramenta de importação da Liga espera receber. O cabeçalho usado aqui segue o vocabulário já adotado no restante do aplicativo (mesmos nomes de Acabamento, Condição, Edição, Distribuição do cadastro manual). Se a Liga pedir um cabeçalho diferente para colar no Bazar ou para importar na Loja Virtual, ajuste apenas a primeira linha do CSV — os dados de cada carta nas linhas seguintes já estão corretos.

## Testes

`scripts/test-liga-export.cjs` cobre formatação de cabeçalho, escaping de campos com `;`/aspas, preço em vírgula decimal, e colunas em branco quando não há graduação/variação artística/código Liga resolvido.
