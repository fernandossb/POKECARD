# Atualização do GitHub — POKECARD Brasil 3.6.0

1. Faça backup do repositório atual e, dentro do aplicativo, exporte uma cópia da coleção.
2. Extraia o ZIP desta versão.
3. Copie todo o conteúdo extraído para a pasta do repositório, preservando somente a pasta `.git` já existente.
4. No GitHub Desktop, confira as alterações, faça o commit `POKECARD Brasil 3.6.0 - imagens e preços Liga` e envie para `main`.
5. Abra **Actions** no GitHub e aguarde **Gerar APK direto e AAB Google Play**.
6. Baixe o artefato **POKECARD-Brasil-APK-e-AAB**. Use o AAB na Play Console e o APK na distribuição direta.

Não altere o `applicationId`, a chave nem os Secrets de assinatura. Esta versão usa `versionCode 71` e `versionName 3.6.0`, mantendo a atualização dos usuários atuais.

O preço automático depende de acesso à página da Liga Pokémon. Dados de coleção e valores já salvos continuam locais; quando não houver anúncio com identidade exata, informe um valor manual.
