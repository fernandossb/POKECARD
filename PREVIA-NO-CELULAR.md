# Ver o app no celular sem instalar

Serve para conferir o visual antes de gerar o APK. É o aplicativo de verdade
rodando no navegador — mesmas telas, mesmas cores, mesmos botões.

## Ligar

Não precisa configurar nada: o próprio robô ativa o Pages na primeira vez
(`enablement: true` no workflow).

Só é preciso que as permissões de escrita estejam liberadas, o que já vale para
os outros robôs do projeto: **Settings → Actions → General → Workflow
permissions → Read and write permissions**.

Se mesmo assim aparecer o erro *"Get Pages site failed / Not Found"*, dá para
ligar à mão em **Settings → Pages → Source → GitHub Actions** e rodar de novo.

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
