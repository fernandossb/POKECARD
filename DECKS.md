# Aba de Decks de Batalha

## Como chegar

**Decks** é uma aba fixa na barra inferior (antes ficava escondida em "Mais opções").

## Montador avançado

O botão **🧠 Montador avançado — 3 opções analisadas** aciona o motor de `deck-auto.js`, que já existia no projeto mas não estava ligado a nenhum botão. Ele:

- Prioriza (ou usa exclusivamente) as cartas que você possui — a fonte é escolhida no formulário
- Simula 1.000 a 5.000 mãos iniciais para medir consistência
- Completa linhas evolutivas automaticamente
- Verifica se a energia do deck é compatível com os atacantes escolhidos
- Pontua legalidade, consistência, velocidade de setup, sinergia, eficiência de energia e posse

Os pesos e os formatos (Padrão, Expandido, Gym Leader Challenge, Casual) ficam em `data/deck-rules.json`.

O botão **⚔️ Montar deck forte**, mais simples e direto, continua disponível.

## Análise de balanceamento

Todo deck aberto no editor mostra um painel com a distribuição real e sugestões concretas.

Faixas de referência usadas (deck de 60 cartas):

| Grupo | Faixa usual |
|---|---|
| Pokémon | 12–20 |
| Apoiadores | 8–16 |
| Itens | 12–26 |
| Energias | 8–15 |

São convenções de construção do formato, não regra oficial — por isso aparecem como sugestão, nunca como erro de validação. Além disso o painel alerta quando não há Pokémon Básico (o deck não abre a partida) ou quando há menos de 6.

Quando um grupo está abaixo da faixa, o painel oferece botões para adicionar direto **cartas que você possui e que ainda não estão alocadas neste deck**.

## Pré-requisito: enriquecer o catálogo

Para separar **Item**, **Apoiador**, **Ferramenta** e **Estádio**, o catálogo precisa dos campos `category` e `trainerType`. O catálogo original só tinha `id, nome, set, número, raridade, imagem, pokemonIds`, então o app agrupava tudo como "Treinador".

Há dois caminhos — escolha um.

### Opção A: pelo GitHub (sem instalar nada)

**Actions → Enriquecer catálogo (tipos de Treinador) → Run workflow.**

O workflow roda o script, confere se os campos foram realmente gravados (falha se não) e commita `catalog.json` e `catalog-data.js` sozinho. Só roda quando acionado manualmente.

Requer **Settings → Actions → General → Workflow permissions → Read and write permissions** marcado.

Observação: como `build-apk.yml` dispara em push para `main`, o commit do catálogo vai gerar um APK novo em seguida — normalmente é o que se quer.

### Opção B: na sua máquina

Precisa do Node instalado (`winget install OpenJS.NodeJS.LTS`, depois abrir um terminal novo):

```bash
node scripts/enriquecer-catalogo.mjs
```

Em ambos os casos o script puxa os dados da API GraphQL do TCGdex em lotes de 500 cartas (não uma requisição por carta) e reescreve `data/catalog.json` e `data/catalog-data.js`.

Enquanto não for rodado, o app continua funcionando: os treinadores aparecem como "Treinadores (tipo não identificado)" e o painel exibe um aviso indicando o comando. As faixas de Itens e Apoiadores só ficam confiáveis depois do enriquecimento.
