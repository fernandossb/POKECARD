# TCG Trainer dentro do PokeCard

Abra **Decks → Jogar TCG Trainer · contra a máquina**. O jogo roda como uma Activity privada no mesmo APK; o botão Voltar ao PokeCard e o Voltar do Android retornam ao fichário.

Os módulos `trainer-ui` e `trainer-engine` contêm uma cópia independente do TCG Trainer 0.5 (origem: fernandossb/TCGTRAINER, commit 0bebc79). Não dependem de outro checkout para compilar. As telas e o armazenamento existentes do PokeCard foram preservados; o acréscimo na aba Decks é um botão e sua descrição.

A ponte `trainer.js` consulta o estado já carregado do fichário e `quantityFor`, somando as variantes possuídas. Exporta apenas IDs e quantidades para `files/trainer-owned.json`, gravado atomicamente. Não exporta preços, imagens particulares ou outros dados pessoais e não modifica a coleção. Reabrir o jogo atualiza essa cópia. O usuário não precisa cadastrar as cartas novamente.

O editor abre com **Só minhas cartas** e **Só implementadas**. Cada impressão mostra quantidade possuída e disponível; o botão + respeita esse limite. Modelos podem ser copiados e salvos como rascunhos. Tanto iniciar quanto continuar uma partida verificam novamente a lista do jogador contra a coleção carregada. Energias também exigem cópias possuídas. Decks diferentes podem reutilizar a mesma coleção; jogar não consome cartas. A máquina pode usar todos os modelos implementados e decks válidos do jogo, sem ficar limitada à coleção do jogador.

O vínculo usa o ID exato TCGdex, compartilhado pelos dois catálogos. Não confunde variantes nem atribui uma reimpressão à outra por semelhança do nome. Os 65 efeitos implementados e as regras atuais do motor foram mantidos. Possuir uma carta ainda sem efeitos implementados não a libera para partidas; o catálogo adicional continua disponível para consulta. Os decks do jogo são salvos em `tcg-trainer.db`, separado dos decks originais do fichário.

## Verificação

- `node scripts/test-trainer-bridge.cjs`: quantidades atuais, leitura sem alterar coleção/decks, fallback no navegador.
- `gradle :trainer-engine:test`: regras, CPU, catálogo e limites de posse/IDs/impressões/energias/snapshot inválido.
- `gradle :app:assembleDirectDebug :app:lintDirectDebug`: integração Android.
- O baseline de lint registra somente dois avisos de erro já existentes do PokeCard (atributo de tema API 27 e receiver de download). O código dessas funções não foi alterado nesta integração. Novos erros continuam bloqueando a verificação.
- A atualização instalada deve usar a assinatura original do PokeCard. Nunca desinstalar para contornar divergência de assinatura: a coleção fica no armazenamento privado do aplicativo.
