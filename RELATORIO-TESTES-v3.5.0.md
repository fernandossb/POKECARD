# Relatório de testes — POKECARD Brasil 3.5.0

## Resultado

- Compilação completa aprovada: `assembleDirectRelease` e `bundlePlayRelease`.
- JavaScript validado sem erro de sintaxe.
- Testes de preço aprovados: 4 cenários, 4 acabamentos e 4 identidades especiais.
- Validação visual aprovada no painel, coleções, cartas, menu adicional e preparação do scanner.
- Navegação inferior, busca, filtros, seletores e modais conferidos na prévia real do aplicativo.

## Preservado

- `applicationId br.com.fichariopokemon.pokedex`.
- Firebase e configuração de atualização.
- Catálogo, bancos locais, usuários e dados salvos.
- Imagens das cartas e logos/temas das coleções.
- Scanner, variações, preços, Pokédex, decks, wishlist, repetidas e backup.

## Avisos não bloqueantes

- O Android Gradle Plugin 8.7.3 emite aviso por ter sido testado oficialmente até a API 35, embora a compilação com API 36 tenha sido concluída.
- A compilação informa uso de uma API obsoleta em `MainActivity.java`; não impediu a geração dos pacotes.
- Algumas mensagens de metadados Kotlin aparecem nas dependências do Firebase durante a análise, mas o Gradle encerrou com `BUILD SUCCESSFUL`.
- O APK local de teste ficou sem assinatura porque as chaves não estão armazenadas neste pacote. No GitHub Actions, os Secrets existentes geram os arquivos assinados.

## Pacotes locais de validação

- APK direto não assinado: 50.894.344 bytes.
- AAB Play: 26.392.419 bytes.

Versão: `versionCode 70` / `versionName 3.5.0`.
