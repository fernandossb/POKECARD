# Atualização do GitHub — POKECARD Brasil 3.3.0

1. Faça uma cópia de segurança do repositório atual.
2. Extraia o ZIP desta versão.
3. Copie todo o conteúdo extraído para a pasta do repositório, mantendo somente a pasta `.git` que já existe no computador.
4. No GitHub Desktop, confira as alterações, faça o commit `POKECARD Brasil 3.3.0 - variantes multidimensionais` e envie para `main`.
5. Abra **Actions** no GitHub e aguarde **Gerar APK direto e AAB Google Play**.
6. Baixe o artefato **POKECARD-Brasil-APK-e-AAB**. Envie o AAB para a Play Console; o APK é destinado à distribuição direta.

Não altere `applicationId` nem os Secrets de assinatura. Esta versão usa `versionCode 68` e `versionName 3.3.0`.

## Verificação automática

O fluxo valida os recursos, executa os testes da média filtrada, compila as duas distribuições e verifica a assinatura do APK antes de publicar os arquivos.
