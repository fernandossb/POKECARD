# Atualização do GitHub — POKECARD Brasil 3.7.0

1. Faça backup do repositório atual e exporte uma cópia da coleção pelo aplicativo.
2. Extraia o ZIP desta versão.
3. Copie todo o conteúdo da pasta `POKECARD-Brasil-v3.7.0` para a pasta do repositório, preservando somente a pasta `.git` já existente.
4. No GitHub Desktop, confira as alterações e faça o commit `POKECARD Brasil 3.7.0 - visual, variações e botão voltar`.
5. Envie o commit para a branch `main`.
6. Abra **Actions** no GitHub e aguarde **Gerar APK direto e AAB Google Play**.
7. Baixe o artefato **POKECARD-Brasil-APK-e-AAB**. Use o AAB na Play Console e o APK na distribuição direta.

## Assinatura

Não altere o `applicationId`, a chave de assinatura nem os Secrets existentes:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

Esta versão usa `versionCode 72` e `versionName 3.7.0`, mantendo a atualização dos usuários atuais.

## Comportamento do botão Voltar

Em telas internas, o botão Voltar fecha o modal ou retorna ao Início. Quando o usuário já está no Início, o aplicativo vai para segundo plano e não é destruído.
