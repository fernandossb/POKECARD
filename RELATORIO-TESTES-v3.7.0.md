# Relatório de testes — POKECARD Brasil 3.7.0

## Resultado

- JavaScript validado sem erros de sintaxe.
- Cálculo de preços aprovado para média, filtragem por IQR e identidade exata da variante.
- Coerência do montador de decks aprovada.
- Navegação visual conferida em viewport móvel, sem erros de console durante o fluxo validado.
- Variações específicas conferidas: a interface usa as opções retornadas para cada carta, mantendo casos raros na exceção manual recolhida.
- Contraste da grade de cartas corrigido e revisado por captura lado a lado.
- Compilação local aprovada para `assembleDirectRelease` e `bundlePlayRelease`.
- Manifestos conferidos: a versão direta mantém `REQUEST_INSTALL_PACKAGES`; a versão Play não inclui essa permissão.
- Metadados preservados: `applicationId br.com.fichariopokemon.pokedex`, `versionCode 72`, `versionName 3.7.0` e alvo API 36.
- Tratamento do botão Voltar compilado: tela/modal anterior primeiro; na tela inicial, `moveTaskToBack(true)`.

## Pacotes locais de validação

Os APK/AAB locais foram gerados sem assinatura porque a chave privada não faz parte do projeto. Eles não foram incluídos no ZIP. O workflow do GitHub usa os Secrets existentes para gerar e verificar os pacotes assinados.

- APK direto local: 50.907.004 bytes — SHA-256 `C5EC470B8E06D221D40A6228F4229B31526C929739DF6F3D1E7DC981BEA0FCDC`.
- AAB Play local: 26.405.602 bytes — SHA-256 `AFF22E6C01CC59FADCE3017E41A2EFD7030D14C39454C0D32A7D025B6CF12113`.
- Os dois pacotes contêm a correção final de contraste e o cache de recursos `v=80`.

## Preservado

- `applicationId`, Firebase, assinatura e atualização direta.
- Usuários, coleção salva, bancos locais, backup e migração de dados.
- Imagens das cartas, temas, coleções, Pokédex, scanner, decks, wishlist e demais funções.

## Riscos e validações pendentes

- O gesto físico do botão Voltar deve ser confirmado em pelo menos um aparelho Android; o caminho foi revisado e compilado, mas não havia aparelho conectado.
- O Android Gradle Plugin 8.7.3 avisa que foi testado oficialmente até API 35, embora a compilação com API 36 termine com sucesso.
- Dependências do Firebase podem emitir avisos não fatais de metadados Kotlin.
- A leitura da Liga depende da estrutura pública da página e pode exigir manutenção se o HTML mudar.
- Os preços representam anúncios ativos, não vendas concluídas.
- O uso de nomes, imagens de cartas, sprites e outros materiais ligados à franquia Pokémon continua sendo um risco de propriedade intelectual para publicação.
