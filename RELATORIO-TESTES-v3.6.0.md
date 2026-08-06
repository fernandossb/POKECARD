# Relatório de testes — POKECARD Brasil 3.6.0

## Resultado

- Compilação completa aprovada: `assembleDirectRelease` e `bundlePlayRelease`.
- Metadados confirmados no APK: `applicationId br.com.fichariopokemon.pokedex`, `versionCode 71`, `versionName 3.6.0`, mínimo Android 8/API 26 e alvo API 36.
- JavaScript validado sem erro de sintaxe.
- Teste de preço aprovado para média simples, média filtrada por IQR, remoção de valor alto/baixo, separação de acabamento, idioma, condição, graduação e tags.
- Extrator de anúncios validado com registros individuais de lojistas, contagem de lojas e imagem retornada pela fonte.
- Teste de coerência de decks aprovado.
- Catálogo conferido: 13.845 cartas, 123 coleções e 123 imagens offline de coleções.
- Endpoints de imagem por idioma testados. A cobertura varia por carta/idioma; o aplicativo mantém a imagem-base quando a arte localizada não existe.

## Preservado

- `applicationId br.com.fichariopokemon.pokedex`.
- Firebase, atualização direta e configuração de assinatura.
- Usuários, coleção salva, bancos locais, backup e migração de dados.
- Imagens das cartas, logos, símbolos, temas e fotos das coleções.
- Scanner, Pokédex, decks, wishlist, repetidas e demais funções.

## Pacotes locais de validação

- APK direto: 50.898.612 bytes.
- AAB Play: 26.396.981 bytes.
- Os pacotes locais estão sem assinatura porque a chave privada não foi incluída no projeto. O workflow do GitHub usa os Secrets existentes para gerar os pacotes assinados.

## Avisos e testes pendentes no aparelho

- O Android Gradle Plugin 8.7.3 informa que foi testado oficialmente até API 35, embora a compilação com API 36 tenha terminado com `BUILD SUCCESSFUL`.
- Dependências do Firebase emitem mensagens sobre metadados Kotlin durante a análise, sem falhar a compilação.
- `MainActivity.java` ainda usa uma API Android obsoleta já existente; isso não impediu a geração dos pacotes.
- Não havia aparelho Android conectado para testar câmera, cookies e carregamento real da Liga dentro do WebView. A página pública foi inspecionada e o parser foi testado, mas uma mudança futura no HTML da Liga poderá exigir manutenção.
- Preços representam anúncios ativos, não vendas concluídas. Amostras com menos de três anúncios exigem confirmação e não entram automaticamente no total.

Versão: `versionCode 71` / `versionName 3.6.0`.
