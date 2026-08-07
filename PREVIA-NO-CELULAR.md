# Ver o app no celular sem instalar

Serve para conferir o visual antes de gerar o APK. É o aplicativo de verdade
rodando no navegador — mesmas telas, mesmas cores, mesmos botões.

## Ligar (uma vez só)

1. No GitHub, abra o repositório **POKECARD**
2. **Settings → Pages**
3. Em **Source**, escolha **GitHub Actions**
4. Salve

Pronto. A partir daí funciona sozinho.

## Como usar

Toda vez que você enviar uma mudança na pasta do app, o site se atualiza sozinho
em poucos minutos. O endereço é:

```
https://fernandossb.github.io/POKECARD/
```

Abra esse link no navegador do celular. Vale salvar nos favoritos.

Se quiser forçar uma atualização sem enviar nada:
**Actions → Publicar prévia no navegador → Run workflow**.

## O que funciona e o que não funciona

**Funciona:** todas as telas, cores, temas por Pokémon, troféus, decks, busca,
filtros, cadastro de cartas e os preços do banco.

**Não funciona** (precisa do aplicativo instalado):

- Escanear carta pela câmera
- Backup em arquivo
- Botão de atualizar o app

Nesses casos o navegador simplesmente não faz nada — não trava nem dá erro.

## Importante

A coleção da prévia é **separada** da do aplicativo. O que você cadastrar no
navegador não aparece no celular e vice-versa. A prévia serve para olhar o
visual, não para guardar cartas.
