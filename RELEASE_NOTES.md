# POKECARD Brasil 5.87.0 — Versões que não existem

- **Sumiram a Comum e a Holográfica que não existem.** O banco de preços recebe do Cardmarket dois preços por carta e dá a eles nomes de versão que confundiam o app:
  - o preço geral da carta vinha como **"Comum"**. Numa carta que só existe holo, ele é o preço da própria holo. Por isso a **Dragonite V 076/078 (Pokémon GO)** aparecia como Comum e Holográfica. Agora aparece só como Holográfica.
  - o preço "holo" do Cardmarket é o do **reverse**, mas vinha como **"Holográfica"**. Por isso cartas comuns como a **Bulbasaur da 151** apareciam com uma Holográfica além do Reverse. Agora ficam só Comum e Reverse.
- **O app usa o que o TCGdex diz da carta** (quais acabamentos ela tem) para saber o que é cada preço. Isso valia para milhares de cartas: o Master Set da **151** passou de 567 para **362 versões**, que é o número de verdade.
- **Preço da holo corrigido.** Em holo raras, a holo podia ficar com o preço do reverse do Cardmarket. Agora ela usa o preço da holo (TCGplayer) ou, nas cartas só holo, o preço da carta no Cardmarket. Exemplo: Charizard 010/078 (Pokémon GO) passa de R$ 20,91 para R$ 18,01. Se o banco não tem preço da holo, ela fica sem preço em vez de mostrar o do reverse.
- **Suas cópias não foram mexidas.** Se você já cadastrou uma carta numa versão que não existe (Comum de uma carta só holo, por exemplo), ela continua aparecendo no cadastro com as suas cópias, para você mudar para a versão certa.

# POKECARD Brasil 5.86.0 — Fichário virtual

- **Tocar numa coleção no Explorar abre um fichário de verdade**, e não mais a aba Coleção. Não há filtro, borda nem informação nas cartas: só a foto de cada uma, em páginas de **9 bolsos (3×3)** com plástico e reflexo, numa folha escura presa por **argolas**.
- **A página vira com o dedo**: arraste para o lado e ela gira presa nas argolas, com sombra caindo na de baixo. Um peteleco rápido também vira, e um arrasto curto desiste e volta. Há setas embaixo, e a régua leva direto a qualquer página (a coleção 151 tem 63).
- **Cada versão ocupa o seu bolso**, lado a lado (Comum, Holo, Reverse...), mesmo com a foto repetida. A lista é a mesma do cadastro da carta e da conta do Master Set. As versões que você excluiu da carta não aparecem.
- **A carta que falta fica como na Coleção**: em cinza, apagada, com contorno tracejado. As holo e reverse que você tem ganham um brilho leve.
- **A capa** tem a cor da coleção, o logo e quantas versões você tem. Ela abre sozinha ao entrar, e reabrir a mesma coleção volta à página em que você parou.
- **Tocar numa carta tira ela do bolso** e mostra grande, com nome, número, versão e quantas cópias você tem. "Abrir carta" leva ao cadastro. Ao fechar, o bolso já mostra o que mudou. O Voltar do celular guarda a carta no bolso e, de novo, fecha o fichário.

# POKECARD Brasil 5.85.0 — Espaço para coleções grandes

- **A coleção não tem mais teto de ~3.500 versões.** Até aqui ela ficava num espaço de uns 5 MB, dividido com os caches de imagem e preço, e cada versão cadastrada ocupava ~1,4 KB. Perto de 3.500 versões, o aparelho parava de salvar. Agora a cópia principal fica no banco do próprio aparelho, que cresce com o espaço livre do celular. Num teste com **9.013 versões**, tudo foi salvo e voltou inteiro ao reabrir.
- **Cada versão ocupa 4 vezes menos.** Os campos que estão no valor padrão (sem nota, sem preço pago, não graduada...) deixam de ser gravados e voltam sozinhos ao abrir. Numa coleção com preço, a versão caiu de ~1,4 KB para ~0,3 a 0,5 KB.
- **A mudança é automática.** Na primeira abertura depois de atualizar, a coleção é copiada para o lugar novo, sem você fazer nada. Foi conferido que o que volta é idêntico ao que existia: cartas, versões, preços, notas, Wishlist, decks e versões excluídas.
- **Continua protegida.** Enquanto couber, o app também guarda uma cópia no lugar antigo, que é usada se o banco falhar. Ao abrir, o app sempre usa a cópia mais recente. Se o aparelho não liberar a leitura da coleção, o app avisa em vez de abrir vazio por cima dela. Se as duas gravações falharem, continua o aviso na tela e o backup de emergência na pasta Download.
- Backups exportados continuam no mesmo formato de antes.

# POKECARD Brasil 5.84.0 — Faxina no código

- **Nada muda na tela.** Esta versão só tira o que o app carregava sem usar. Cada tela foi conferida antes e depois, elemento por elemento: cor, tamanho, espaçamento e posição ficaram iguais.
- **Código morto removido**: 26 funções e 4 tabelas que nada chamava. A maior parte era da câmera antiga (escolha de candidato, idioma, acabamento e diagnóstico do OCR) e de cálculos de preço que o Price Database substituiu. Saíram também as ferramentas de diagnóstico que ficavam abertas no app.
- **Estilos sem uso removidos**: cerca de 180 regras de telas e botões que não existem mais, como a câmera antiga, as bolinhas de energia, a barra de claridade e os controles antigos de quantidade. O arquivo de estilos ficou 24 KB menor.
- **Sprites HD desligados de vez**: o código que procurava sprites em alta resolução estava desligado desde que se viu que eles deixavam a Pokédex lenta. Saíram esse código, o robô do GitHub que os gerava e os scripts dele. As artes 3D já guardadas no aparelho continuam valendo, sem baixar nada de novo.
- **Documentos velhos removidos** do repositório: 20 guias de atualização e relatórios de teste das versões 3 a 9, e a configuração de um serviço de build que não é mais usado.

# POKECARD Brasil 5.83.0 — Versão holo que na verdade é Cosmos

- **Uma carta física não aparece mais duas vezes.** As duas fontes de versões nem sempre concordam: na Oddish 001/094 (Fogo Fantasmagórico), o TCGdex diz que a holográfica dessa carta só existe como **Cosmos Holo**, e o banco de preços chama a mesma carta de "Holo". O app mostrava as duas — "Holográfica" e "Cosmos Holo". Agora, quando o TCGdex conhece as versões da carta e diz que um acabamento (holo ou reverse) só existe em foil especial (Cosmos, Cracked Ice...), a versão "plana" do banco sai: some do cadastro, do formulário "Adicionar", das etiquetas da grade e da conta do Master Set.
- **O preço vai junto.** O preço que o banco publicava como "Holo" é o da Cosmos Holo, então passa para ela — na Oddish, R$ 0,45 — inclusive no valor da coleção quando você cadastra a cópia.
- Quando uma fonte lista uma versão que a outra nem menciona, o app não tem como saber quem está certo e mantém a versão; para esses casos, use o × da versão (com 0 cópias) no cadastro da carta.

# POKECARD Brasil 5.82.0 — Cartas de ponta a ponta na Coleção

- **A carta ocupa toda a largura do cartão** nas grades de 2 e 3 colunas da Coleção. Saíram a margem em volta da arte e a faixa colorida separada na lateral: ficou só a **moldura colorida, grudada direto na carta** — na cor do tipo do Pokémon, dourada nas raras (Double/Ultra/Illustration/Shiny), rosa nas secretas e hiper raras, azul nas promos, e dourada também na carta em que você tem todas as versões.
- **Texto mais junto embaixo da carta**: nome, número · coleção e preço com menos espaço entre as linhas, e menos espaço entre um cartão e outro — os cartões ficaram mais baixos e cabem mais na tela.
- A exibição em lista continua como estava.

# POKECARD Brasil 5.81.0 — Excluir versões que o app puxou sozinho

- **Excluir uma versão da carta.** O app monta sozinho a lista de versões de cada carta — do banco de preços (Comum, Holo, Reverse, os reverses de Poké Ball...) e do TCGdex (carimbos, foils especiais). Quando a fonte lista uma versão que você sabe que não existe, ou que não quer acompanhar, agora dá para tirá-la: no cadastro da carta, na linha de uma versão com **0 cópias**, o botão "−" vira um **×**. Tocando nele (e confirmando), a versão some da lista, dos botões do formulário "Adicionar", das etiquetas da grade e da conta do **Master Set** — só daquela carta.
- **Dá para desfazer.** Embaixo das linhas aparece "N versões excluídas · restaurar", que traz todas de volta. Versão com cópia sua não pode ser excluída (tire as cópias antes), e se você cadastrar de novo uma versão excluída, ela volta a aparecer. As exclusões ficam guardadas no aparelho e vão junto no backup.

# POKECARD Brasil 5.80.0 — Fundo mais escuro, cartões mais claros

- **Fundo da tela 50% mais escuro** (de 68% para 34% de luz) e **cartões 20% mais claros** (de 60% para 72%), nas duas cores de cada Pokémon. No Venusaur, o fundo passa a verde-escuro e os cartões a um rosado claro.
- Com o fundo escuro, o que fica direto sobre ele — títulos das telas e das seções, "Ver todos", subtítulos, contagens, nome das regiões na Pokédex — ganhou letra clara, calculada para cada tema. Dentro dos cartões a letra continua escura.

# POKECARD Brasil 5.79.0 — Tema com as duas cores do Pokémon

- **Cada Pokémon dá duas cores ao app.** O app mediu o sprite de cada um dos 1.025 Pokémon e tirou as duas cores que mais aparecem no corpo: a primeira vai no **fundo da tela**, a segunda nos **cartões, botões e barra de abas**. O Venusaur fica com fundo verde e cartões e botões vermelhos (da flor); o Pikachu, amarelo com o vermelho das bochechas; o Charizard, laranja com o azul das asas; o Gengar, roxo com o vermelho dos olhos. Pokémon de uma cor só usa a cor do tipo como segunda. Na tela de escolha do tema, cada Pokémon mostra as duas cores dele.
- **Sem barra de claridade.** Todos os temas usam a claridade Suave; a barra saiu da tela do tema.
- **Pokédex só com a arte 3D.** A opção "Leve" (ícone pixelado) saiu: a lista usa a arte 3D do Pokémon HOME, e a arte oficial quando o HOME não tem. Sem internet, continua aparecendo o sprite que já vem no app. As artes leves que estavam guardadas no aparelho são apagadas uma vez, para liberar espaço.
- **Modo Laboratório removido**, junto com as medições de desempenho que ele fazia e o último relatório guardado.

# POKECARD Brasil 5.78.0 — Master Set contado por versão

- **O Master agora soma as versões da coleção, não as cartas.** O total é a soma de todas as versões encontradas de cada carta da coleção — as que a fonte conhece (normal, holo, reverse, os reverses especiais de Poké Ball e Master Ball...) mais as que **você** cadastrou. Na 151, por exemplo, são 567 versões para 207 cartas; com uma cópia de cada carta, o painel mostra 207/567.
- **Versão nova aumenta o total na hora.** Se você cadastra uma versão que o app ainda não conhecia (pelo "＋ Outra versão", um carimbo, uma 1ª edição...), ela entra no total e no que você tem: na 151, cadastrar uma 1ª edição no Bulbasaur levou o Master de 207/567 para 208/568. Carimbo, edição e foil especial que você **não** tem não são cobrados — a fonte só os conhece de algumas cartas, e eles são coisa de Grand Master.
- **Master confirmado só com as versões em mãos.** As versões vêm dos lotes de preço; ao abrir uma coleção, o app baixa os lotes que faltam e o painel mostra "carregando as versões de N cartas…" até chegarem. Carta que não está no banco de preços conta a Comum e o que você cadastrar — o painel avisa quantas são.

# POKECARD Brasil 5.77.0 — Coleção completa em três níveis, faixa de tipos e carta de destaque

- **Correção importante: "coleção completa" agora é a Coleção Básica de verdade.** Antes o app comparava TODAS as cartas diferentes que você tem na coleção com a contagem oficial — então 20 secretas (166/165...) cadastradas contavam como 20 comuns, e a coleção "completava" faltando cartas da numeração oficial. Agora a Básica conta só as cartas da numeração impressa (1/165 até 165/165), de qualquer raridade, sem exigir reverse ou holo. As que passam da numeração (secretas, galerias) viram "Extras". Ex.: com as 56 extras de Forças Temporais e só 106 das 162 da numeração, o app dizia completa; agora mostra 106/162.
- **Três níveis, como os colecionadores falam:**
  - **Básica (Base Set):** uma cópia de cada carta da numeração oficial.
  - **Master Set:** a coleção inteira — numeração oficial, secretas e galerias — com **todas as versões** de cada carta (normal, holo, reverse...). Só dá para conferir quando o app conhece as versões da carta, isto é, quando o preço da coleção já foi baixado.
  - **Grand Master:** o Master da coleção mais o Master das coleções que nascem dela (Galeria do Treinador, Galeria de Galar, Cofre Brilhante, Classic Collection). Promos de pré-lançamento e de loja variam de colecionador para colecionador e não entram na conta.
- **Onde aparece.** No Explorar, o selo do cartão diz o nível ("✓ Básica", "★ Master", "◇ Grand Master"; o Master e o Grand Master ganham brilho rosa), o tamanho vira "165 + 42 cartas" (oficiais + extras) e o progresso conta só a numeração oficial. O filtro ganhou "Completas (Básica)" e "Master Set". Ao abrir uma coleção na Coleção, um painel mostra os níveis com barra: Básica, Extras, Master e, quando existe, Grand Master. No Início, o aviso de coleções completas diz quantas são Master. A celebração ao completar diferencia o nível, e os Troféus ganharam a medalha "Mestre" (Master Sets).
- **Faixa de tipos na Pokédex.** Os 18 tipos viram discos que rolam para o lado, com quantos Pokémon do tipo você já tem ("Fogo 38/81"). Um toque filtra a lista, outro tira o filtro; o seletor de tipos continua e acompanha.
- **Carta mais valiosa no Início.** Um destaque no topo com a arte, o nome, a coleção, a raridade e o valor da versão de maior preço que você tem — com a moldura de brilho da raridade.

# POKECARD Brasil 5.76.0 — Fonte própria, cor por aba, moldura de raridade e "Sua coleção em cores"

- **Sua coleção em cores (Início).** Duas barras empilhadas contam a sua coleção: de que **tipo** são os Pokémon que você já tem (cada tipo na cor e com o símbolo da Pokédex) e de que **era** são as suas cartas (cada era na cor que ela tem no Explorar). Embaixo de cada barra, os seis maiores por extenso.
- **Cada aba com a sua cor.** A aba aberta acende na cor dela na barra de baixo, o título da tela ganha uma barrinha da mesma cor e o topo da tela um clarão suave. Coleção é verde (o "tenho"), Wishlist é pink (o "quero"), Repetidas é azul (a troca), Produtos prontos é dourado; Explorar é azul, Decks é laranja e a Pokédex é vermelha.
- **Moldura de raridade nas cartas da Coleção.** O selo virou uma medalha redonda com o símbolo da raridade (estrela dourada para Double/Ultra/Illustration/Shiny, brilho rosa para Secret/Hyper, losango azul para promo) e a arte ganha uma moldura que brilha na cor dela. Carta que você ainda não tem fica com a moldura mais apagada.
- **Fonte própria.** Os títulos, os números grandes e os nomes das cartas usam a Nunito (fonte livre, embutida no app: funciona sem internet), mais redonda e simpática que a fonte padrão do celular.
- **Movimento discreto.** A tela "entra" com um leve deslizar quando você troca de aba (e as barras do Início crescem); os botões e cartões respondem ao toque. Nada fica se mexendo em listas, nada recomeça a cada + ou −, e quem pede menos movimento nas configurações do celular não vê animação nenhuma.

# POKECARD Brasil 5.75.0 — Pokédex colorida pelos tipos e ícones próprios

- **Pokédex colorida.** Cada quadradinho veste a cor do tipo do Pokémon: o Bulbasaur fica verde, o Charmander alaranjado, o Squirtle azul. Quando o Pokémon tem dois tipos, o segundo aparece como um clarão no canto de baixo (o Charizard é laranja com um toque de azul). No canto de cada quadradinho ficam os símbolos dos tipos. Os que você ainda não tem continuam transparentes, como antes. Vale nos 6 níveis de claridade — vinho escuro no tema escuro, pastel no claro.
- **Tela do Pokémon.** O cabeçalho vira um cartão na cor do tipo, e os tipos ganham etiquetas com o símbolo (Fogo, Voador...).
- **Símbolos dos tipos, desenhados para o app.** Os 18 tipos mais o Incolor, cada um num disco da cor dele: chama, gota, raio, folha, floco de neve, punho, caveira, ave, espiral, besouro, fantasma, olho de dragão, lua, engrenagem, brilho... Aparecem na Pokédex, nas **Energias básicas** (cada tipo com o seu símbolo e um clarão da cor dele) e nas **medalhas de tipo dos Troféus**.
- **Ícones próprios no lugar dos emojis.** Os emojis cada fabricante de celular desenha de um jeito, e havia letras fazendo papel de ícone ("R$", "E"). Agora tudo vem do mesmo conjunto, no mesmo traço, e acompanha a cor do tema: menu Mais, as 62 medalhas dos Troféus (as de raridade usam círculo, losango e estrela, como nas cartas — antes quase todas caíam num ícone genérico, porque o catálogo traz os nomes em inglês), selos de categoria da carta (Pokémon, Treinador, Energia), acabamentos das versões (comum, holo, reverse, 1ª edição), carimbo, botões do Decks, "ver a carta em tamanho real", câmera e ajustes do scanner, foto da carta e Modo Laboratório.
- **Troféus:** o nome do nível (Bronze, Prata...) aparecia cortado embaixo da medalha; agora aparece inteiro, e a marca d'água do Pokémon fica atrás do ícone.

# POKECARD Brasil 5.74.0 — Cores com significado, profundidade e Explorar colorido

O app era quase todo de uma cor só (o roxo do tema). Agora o roxo fica para o "esqueleto" — botões, abas, cabeçalhos — e o conteúdo ganha cor com sentido:

- **Cada número tem a cor do que significa.** Verde: o que você tem (Únicas, o anel e a barra da Pokédex, o progresso das coleções). Azul: repetidas e o que é para trocar. Dourado: especiais e coleção completa. Rosa: o que você quer e o que falta. Laranja: preço para conferir ("733 para revisar" e o botão "Revisar preços"). O significado é sempre o mesmo; o tom se ajusta ao tema — mais escuro no tema claro, mais vivo no escuro —, conferido nos 6 níveis de claridade e em 21 Pokémon favoritos diferentes.
- **Filtros da Coleção com uma bolinha de cor** (Tenho verde, Faltantes vermelho, Desejo rosa, Duplicadas e Trocar/Vender azul, Preços pendentes laranja). O filtro ligado acende na cor dele. Na carta aberta, a versão que você tem também fica verde.
- **Profundidade.** Os cartões ganharam um fio de luz no topo e sombra por baixo — a luz vem de cima, em vez de blocos chapados. No tema claro a sombra é azulada (a preta suja o claro). Campos de busca e seletores ficam levemente afundados.
- **Explorar colorido.** Cada coleção veste a cor do próprio logo — medida nos 108 logos que já vêm no app; quem não tem cor no logo (Black & White, promos...) usa a cor da era. O logo ganha um halo de luz atrás, para o "Pitch Black" não sumir no fundo escuro. A coleção que você ainda não começou fica um pouco mais quieta, mas continua colorida; a que você tem ganha um brilho leve. Cada cartão mostra a era ("Escarlate e Violeta", "Mega Evolução"...) e a Pokébola de progresso em verde.
- **Coleção completa** ganha borda, barra e Pokébola douradas, e o selo "✓ Completa" numa linha própria — ao lado da era o nome era cortado ("ESCARLATE E VIOLETA" não cabia).
- **Níveis de claridade do meio** (fundo roxo médio): as cores de significado chegavam a quase preto para manter o contraste. Agora têm um piso de claridade — o dourado volta a ser dourado; o texto delas continua com contraste garantido.
- Os produtos criados por você, no Explorar, usam o mesmo cartão colorido.

# POKECARD Brasil 5.73.0 — Energias básicas: escolha pelo tipo e pela tiragem

- **Novo seletor de Energias básicas** (menu Mais → "Energias básicas", ou o atalho que aparece quando você busca "energia" na Coleção). Energia básica era a carta mais difícil de cadastrar: as atuais não têm número impresso — o número que o app mostrava é só do catálogo — e a mesma energia aparece em várias coleções e reimpressões quase iguais, com preços diferentes. Agora você escolhe o tipo (Grama, Fogo, Água...) e vê todas as tiragens, da mais nova para a mais antiga, cada uma com o que a diferencia: coleção, **ano da tiragem**, raridade, se tem número impresso (e qual) — e o **preço de cada versão** (Comum, Holográfica, Reverse), com − / + ali mesmo, sem abrir a carta.
- As energias de Escarlate e Violeta tiveram três tiragens, que o catálogo tratava como se fossem do mesmo ano: agora aparecem como de 2023, 2024 e 2025 (conferido nas datas de lançamento de cada uma). A diferença fica no rodapé da carta.
- "Reverse especial, carimbo e condição ›" abre o cadastro completo daquela tiragem; o ‹ (ou o Voltar do celular) traz de volta para a mesma tiragem.
- A fonte não tem imagem das energias mais novas (e as fontes de reserva trazem a arte de outra tiragem), então nesses casos aparece a cor do tipo em vez de uma imagem errada.
- No cadastro de qualquer carta, carta de que o app ainda não conhece versão nenhuma ganha ao menos a linha "Comum", para cadastrar pelo + sem descer até o formulário.

# POKECARD Brasil 5.72.0 — Preço na linha, versão que falta e coleções completas

- **Preço direto na linha do cadastro.** Na lista de versões da carta, o preço virou um botão (com um ✎): toque, digite o valor e pronto — ele vira o valor manual daquela versão (o mesmo campo do formulário de baixo) e aparece destacado na cor do tema, para não confundir com o preço automático do banco. Apagar o número volta ao preço automático. Confirmar com Enter o MESMO valor renova os 4 meses de validade — é o jeito de dizer "conferi na Liga, ainda vale isso" e tirar a carta de "Preços pendentes" sem mudar o número. Esc desiste.
- **"Adicionar" aceita versões que o app ainda não conhece.** No formulário de nova versão, "＋ Outra versão (não está na lista)" oferece as versões básicas que faltam nos botões da carta — Comum, Holográfica, Reverse Holo, 1ª Edição e 1ª Edição holográfica —, para quando a fonte não trouxe todas. A escolhida vira botão, fica marcada como escolha sua (a dedução automática não a troca) e é salva normalmente. Foil especial, carimbo e edição continuam em "Impressão, carimbo e acabamento especial".
- **Coleções completas.** Saem da lista "Coleções mais completas" do Início — no lugar, um atalho "✓ N coleções completas — ver no Explorar" abre o Explorar já filtrado nelas. No Explorar, a coleção completa ganha borda dourada e o selo "✓ Completa".

# POKECARD Brasil 5.71.0 — Arte Estendida só com as suas obras

- As obras prontas do estudo saíram do app por completo (o arquivo de dados também). A Arte Estendida agora começa vazia e mostra só as obras que você cria em "＋ Criar obra" — do zero, com as cartas que você escolher. As obras que você já criou continuam como estavam.

# POKECARD Brasil 5.70.0 — Crie suas próprias Artes Estendidas

- **Criar obra.** Na Arte Estendida, o botão "＋ Criar obra" abre um editor: nome, como as cartas se encaixam (lado a lado, uma sobre a outra, grade 2×2, 3×2 ou 3×3) e as cartas, escolhidas pela busca — por nome, coleção, raridade ("illustration rare" ajuda a achar a versão certa) ou número impresso completo ("165/162"). A obra aparece montada, colorida, enquanto você escolhe, e dá para mudar a ordem (↑ ↓) ou tirar uma carta (✕). As suas obras ficam no topo da lista, com a etiqueta "Sua obra", e podem ser editadas ou apagadas depois. Ficam gravadas no aparelho e vão junto no backup.
- **Só entram as obras do estudo que o app monta com certeza.** Das 90, saíram do app as 54 em que faltava o número de alguma carta. Das 36 restantes, aparecem só as que o catálogo deste aparelho consegue montar por inteiro: cada carta encontrada pelo número, com imagem, e do Pokémon certo — hoje, 11 obras. (Havia número apontando para outra carta: "SWSH015" levava ao Cinderace V, não ao Scorbunny.) As que faltam aparecem sozinhas se o catálogo ganhar essas cartas; até lá, dá para montá-las como obra sua. Com isso, o vínculo manual de carta deixou de existir: não sobra obra com carta em dúvida.
- **Correção importante: dados que sumiam ao reabrir o app.** Os Produtos prontos criados por você, a lista de lacrados e os vínculos da Arte Estendida eram apagados toda vez que o app abria (e também ao importar um backup) — só cartas e decks eram carregados de volta. Agora tudo o que está gravado volta junto.

# POKECARD Brasil 5.69.0 — Arte Estendida: lista que não perde o lugar e obras montadas do jeito certo

- **A lista não volta mais para o topo.** Entrar numa obra e voltar — pelo ‹ da tela ou pelo Voltar do celular — devolve você exatamente onde estava, com a busca que tinha digitado. Vale também para carta aberta a partir de uma obra: agora ela ganha um ‹ ao lado do ×, que leva de volta à obra (e o Voltar do celular faz o mesmo), mesmo depois de mudar a quantidade ali dentro. Fechar tudo no × e reabrir pelo menu Mais também volta para o mesmo ponto da lista.
- **Cada obra no sentido certo.** Horizontal numa fileira, vertical empilhada (a primeira carta em cima) e grade 3×2 / 3×3 em fileiras de três. A vertical, que antes aparecia como uma faixa vazia, agora aparece inteira. A obra nunca passa da altura da tela: em vez de achatar a arte, ela diminui a largura.
- **Lista no estilo da Coleção.** Cada obra virou um cartão com a arte completa em cima, ocupando a largura toda, e uma descrição curta embaixo: nome, coleção, quantas você já tem (com a barra) e o tipo de encaixe. A carta que ainda falta aparece em cinza, como na Coleção — a obra vai "ganhando cor" conforme você completa.
- A lista também ficou mais rápida para desenhar e para buscar: o vínculo de cada carta com o catálogo é calculado uma vez e reaproveitado.

# POKECARD Brasil 5.68.0 — Mais rápido, sem mudar nada no uso

Medido com uma coleção grande (2.500 cartas, 5 mil versões). Nada muda na tela nem no resultado — só o tempo que leva:

- **Ordenar a Coleção ficou de 10 a 17 vezes mais rápido.** Ordenar o catálogo inteiro por número levava cerca de 1/3 de segundo num computador (bem mais no celular), e isso acontecia toda vez que a Coleção abria; por preço, quase um segundo inteiro. A ordem continua exatamente a mesma — conferido carta a carta em todas as ordenações, no catálogo e na sua coleção —, só o jeito de comparar ficou mais leve.
- **Cada toque em + / − ficou mais leve.** O cabeçalho do app refazia, a cada mudança, o resumo completo da coleção (preço de todas as versões, Pokédex...) só para mostrar duas contagens — num texto que o visual atual nem exibe. Agora ele faz só a conta das duas contagens; o resumo completo fica para quando o Início é aberto.
- **Voltar ao Início reaproveita a conta dos preços** ("X de Y cartas com preço", "para revisar") enquanto nada mudou na coleção nem no banco de preços.
- **O app abre mais rápido.** Toda abertura regravava a coleção inteira no aparelho antes de mostrar a primeira tela, e conferia o preço de cada carta duas vezes. A regravação agora acontece logo depois da tela aparecer, e a conferência é feita uma vez só.

# POKECARD Brasil 5.67.0 — Quadradinho virou linha, com quantidade e preço no cadastro

- No cadastro de cartas, os quadradinhos de versão (comum, holo, reverse, carimbo, edição, foil especial...) viraram uma lista de linhas: cada versão mostra o preço da unidade e um controle de − / + para ajustar a quantidade direto ali, sem precisar abrir o formulário "Cadastrar nova versão" para cada acabamento. A primeira cópia de uma versão nova é criada sozinha (português, Near Mint, Brasil — os mesmos padrões de sempre) assim que você toca em "+"; se a carta ainda não tem nenhuma cópia e o app não sabe que Pokémon ela representa, o "+" pede para escolher antes, do mesmo jeito que o formulário completo já pedia. Os quadradinhos da grade da Coleção continuam do jeito que estavam — a mudança é só na tela de cadastro.

# POKECARD Brasil 5.66.0 — Preços pendentes revisados, carimbo em toda a Coleção, e mais um pisca-pisca corrigido

- **"Preços pendentes" agora é sobre confirmação MANUAL, não sobre a confiança do banco.** Antes só entravam nesse filtro as cartas cujo preço automático precisava de revisão (correspondência incerta). Agora toda carta sem um valor manual seu entra em "Preços pendentes" — mesmo que o Price Database já tenha um preço "verificado" para ela — porque o objetivo é você mesmo conferir o valor real, na Liga Pokémon ou onde for possível. Enquanto não houver valor manual, a carta continua mostrando o preço do Pokémon Price Database Brasil normalmente, como sempre mostrou — só a etiqueta de "revisar" que mudou de critério.
- **Valor manual expira em 4 meses.** Preço manual sem reconferência por mais de 4 meses volta sozinho para "Preços pendentes", com um aviso explicando desde quando ele está parado. Isso evita que a coleção continue "valendo" um preço de meses atrás só porque foi digitado uma vez. A tela de cadastro agora mostra, embaixo do campo "Valor manual", quando ele foi confirmado pela última vez (ou que a carta ainda não tem nenhum).
- **Carimbo, edição e foil especial agora aparecem em quadradinho na Coleção inteira, não só nas cartas já escaneadas ou abertas.** Antes, os quadradinhos com essas variações (o mesmo "🏷 Carimbo, edição ou foil" do scanner) só apareciam depois que a carta específica tinha sido consultada — então a maior parte do catálogo ficava sem eles, mesmo tendo a variação. Agora a Coleção busca essa informação sozinha, aos poucos, para as cartas que vão passando pela tela (poucas por vez, para não sobrecarregar); o quadradinho de carimbo aparece pendente ou marcado como "você tem", conforme o caso, tanto na grade da Coleção quanto no cadastro manual.
- **Mais uma causa do "pisca a tela ao abrir a Coleção", encontrada e corrigida.** Doze segundos depois de QUALQUER abertura do aplicativo, uma rotina interna redesenhava a tela inteira para atualizar o gráfico de valorização — mesmo que a pessoa já tivesse saído do Início e estivesse navegando a Coleção havia alguns segundos. Como esse gráfico só existe na tela Início, esse redesenho completo não tinha por que acontecer em nenhuma outra tela; agora só acontece quando o Início está mesmo na tela. De quebra, os preços e as variações que chegam aos poucos em segundo plano (a busca acima) pararam de redesenhar a grade inteira a cada lote — agora só a(s) carta(s) que realmente mudaram trocam de figura, sem recarregar as imagens das demais.

# POKECARD Brasil 5.65.1 — Ajuste: sem teto na Pokédex

- O teto de "Mostrar mais" da versão anterior (item 4 do aviso de desempenho) valia também para a Pokédex — mas ela não precisa: é um universo fixo e pequeno (pouco mais de mil espécies e formas, contando tudo), bem diferente do catálogo de 13 mil e tantas cartas ou de uma coleção com milhares de cópias. A Coleção continua limitada a 300 cartas por vez; a Pokédex volta a mostrar tudo, sem parar antes do fim.

# POKECARD Brasil 5.65.0 — App travando e ficando pesado com o uso: 6 causas corrigidas

Depois de cadastrar bastante e usar por um tempo seguido, o aplicativo vinha ficando lento, travando em qualquer tela e "piscando" como se estivesse recarregando a coleção sozinho, até lotar a memória do celular. Investiguei a fundo e encontrei seis causas reais, todas corrigidas nesta versão:

- **Um vigia que reprocessava a tela inteira a cada mudança.** O sistema que busca a arte de cada carta ficava de olho em QUALQUER alteração na página inteira — e o aplicativo troca conteúdo o tempo todo (abrir um menu, digitar na busca, atualizar um preço). A cada uma dessas mudanças, ele reconferia TODAS as cartas já desenhadas na tela, não só o que de fato mudou. Numa lista grande, aberta atrás de um cadastro, isso rodava a cada tecla digitada no cadastro. Agora ele olha só para o que acabou de aparecer.
- **Duas listas de controle de imagem que só cresciam.** Duas listas internas (quais endereços de imagem já foram tentados, e quais cartas têm foto local) guardavam uma entrada por carta tocada — abrir, escanear, rolar até ela aparecer — e nunca esqueciam nada. Numa coleção de milhares de cartas, numa sessão longa, isso ia se acumulando sem parar. Agora têm um teto: passado ele, a mais antiga sai para a mais nova entrar.
- **Um relógio que rodava 60 vezes por segundo à toa.** Uma função só para medir a velocidade de tela do "Modo Laboratório" ficava rodando a cada quadro desde a hora que o aplicativo abria — mesmo que ninguém jamais tivesse aberto esse modo (a grande maioria dos usuários). Agora só liga quando o laboratório é aberto de verdade.
- **"Mostrar mais" sem limite.** Cada toque em "Mostrar mais", na Coleção ou na Pokédex, só somava à lista — nunca tirava nada de cena. Numa coleção grande, dava para chegar a centenas de cartas montadas ao mesmo tempo na tela, e como a lista inteira é redesenhada do zero a cada busca ou filtro, esse tanto de carta era destruído e reconstruído por completo a cada pequena mudança. Agora há um teto generoso (300 cartas, 720 Pokémon); passado ele, a busca e os filtros continuam sendo o caminho para achar o resto.
- **Um cache de ordenação sem limite.** Cada coleção combinada com cada tipo de ordenação guardava uma lista própria na memória, para sempre. Numa sessão passeando por muitas coleções diferentes, isso empilhava sem parar. Agora tem teto: as mais antigas saem para dar lugar às mais novas.
- **Nenhuma reação ao aviso de memória baixa do Android.** O aplicativo só "descobria" que estava pesado demais quando o sistema já tinha derrubado a tela — e a recuperação (que já existia, para não fechar o aplicativo de vez) é justamente esse "piscar e recarregar a coleção" que você viu. Agora, ao primeiro aviso do Android de que a memória está ficando escassa, o aplicativo solta sozinho o que é só cache interno — nunca a coleção, nunca o que está na tela — na tentativa de nunca chegar a precisar recarregar.

# POKECARD Brasil 5.64.0 — Carimbo e foil especial nos quadradinhos de versão

- Os quadradinhos que mostram as versões da carta (embaixo da arte, na Coleção) agora enxergam carimbo, edição e foil especial — a mesma lista que o botão "🏷 Carimbo, edição ou foil" do scanner usa. Uma cópia carimbada, sem sombra ou num padrão de foil raro (Cosmos, Gold, Poké Bola...) ganha um quadradinho próprio, com o nome certo ao segurar o dedo, e conta como "você tem" — antes ela desaparecia dentro do quadradinho genérico "Comum". Só aparecem as variações que o TCGdex já confirmou para aquela carta específica (quando ela foi aberta ou escaneada ao menos uma vez) mais o que você já cadastrou à mão; nada é buscado só para preencher a lista, então nenhuma tela ficou mais lenta.
- A mesma lista de quadradinhos agora também aparece na tela de cadastro manual (ao abrir uma carta direto da Coleção, do Explorar ou da Pokédex), no topo, junto dos outros dados da carta — antes só o scanner mostrava isso.

# POKECARD Brasil 5.63.0 — Filtros da Coleção corrigidos de vez, e Arte Estendida

- **Filtros da Coleção, a causa raiz**: a 5.62.0 já tinha corrigido o contraste (botões de ordenar quase invisíveis), mas isso sozinho não resolvia o que você relatou — abrir "Filtros" e tocar em qualquer botão de ordenar (ou no × de fechar) podia, na prática, acionar o seletor de coleção por baixo. Causa: um ícone de 48×34 usado no botão "Todas as coleções" tinha uma camada interna (pensada para preencher um cartão de coleção inteiro) que, sem uma linha de CSS, se esticava e cobria quase a gaveta inteira — invisível, mas roubando o toque de tudo que estivesse por cima, inclusive os botões de ordenar e cada linha da lista de coleções/artistas. Testado botão a botão, com e sem coleção escolhida, incluindo o × de fechar: todos respondem ao toque certo agora.
- **Arte Estendida** (nova, em Mais): obras Pokémon que só aparecem inteiras com duas ou mais cartas lado a lado — 90 obras, 235 cartas, catalogadas a partir de um estudo à parte. Cada carta da obra é ligada automaticamente à carta correspondente do seu catálogo (por número quando a fonte confirmou um, por nome quando não); o que já está na sua coleção aparece com ✓ verde, o que falta com ✕. Não existe lista de posse separada — é a mesma Coleção de sempre, só mostrada lado a lado na ordem certa, para a arte completa aparecer de verdade (cartas conectadas ficam coladas, sem espaço entre elas). Quando o casamento automático não acha a carta certa — ou a coleção dela ainda não está catalogada neste aparelho — um botão deixa vincular à mão, com busca por nome ou número; a escolha fica salva.

# POKECARD Brasil 5.62.0 — Botões de ordenar e filtrar sem contraste

- Correção de contraste nos botões de "Ordenar e filtrar" da Coleção, no botão Filtros, no alternador Lista/Grade e nos seletores de Coleção/Artista: usavam uma borda quase invisível (pensada para cartões passivos, não para botões) em vez da borda normal do tema. Publicado antes do relatório completo do problema — veja 5.63.0 para a causa de fundo (o toque caindo no lugar errado).

# POKECARD Brasil 5.61.0 — Estabilidade, filtros e carimbo no scanner

- **O app fechando sozinho depois de um tempo de uso**: corrigido. Com a coleção grande, o banco de preços (12 arquivos de ~7 MB) era recarregado por inteiro e regravado por inteiro a cada atualização — depois de mil cartas cadastradas, isso passava do limite de memória do WebView e o Android matava o aplicativo. Agora cada arquivo do banco é lido uma vez só, sob demanda, e fica compacto na memória; o consumo caiu de forma acentuada. Além disso, se a tela cair mesmo assim (qualquer outro motivo), o app agora recarrega sozinho, com um aviso, em vez de fechar — a coleção continua salva no aparelho.
- **Pequeno dashboard de volta**: no topo da Coleção, seis números — Total de cartas, Únicas, Repetidas, Versões, Especiais e Quero. Tocar em "Repetidas" já filtra a lista por elas.
- **Gráfico de valorização**: a linha não sai mais para fora da moldura do gráfico.
- **Filtros da Coleção**: dois problemas encontrados e corrigidos. O filtro "Faltantes" podia ficar com uma lista antiga depois de mexer na coleção. E, em alguns teclados Android, buscar pelo nome enquanto ainda se digitava um acento (ã, ç, é) podia travar a busca no meio da palavra.
- **Visualização em fichário removida**: saiu do seletor de exibição da Coleção. Ficam Lista e Grade (2 e 3 colunas).
- **Scanner: carimbo, edição e foil especial.** Carta com carimbo de pré-lançamento, de liga, de campeonato, do Pokémon Center etc., ou com um padrão de foil especial (Cosmos, Gold, Poké Bola...), ou 1ª edição sem sombra, agora tem onde entrar: o botão "🏷 Carimbo, edição ou foil", no painel de leitura, mostra as variações que o TCGdex conhece daquela carta específica — um toque e pronto — e também deixa montar qualquer outra à mão. A revisão final também ganhou o campo de carimbo, e junta com uma linha igual se você mudar para "sem carimbo". Nenhuma fonte de preço separa essas variações: elas entram na coleção sem preço automático, para você completar.
  - A pergunta de onde vem essa lista: o TCGdex publica, carta a carta, todas as variações físicas que ela teve (`variants_detailed` — acabamento, carimbo, padrão de foil, subtipo de impressão e tamanho), e é isso que o app agora consulta. Não existe uma fonte só, única e completa, para todas as variações de todas as cartas — o TCGdex é a mais próxima disso hoje, e cobre a maioria das promoções e reedições.
- **Scanner: reflexo do foil.** A câmera não sabe olhar para uma carta e dizer se ela é holo ou reverse — as duas brilham igual, e a diferença é só onde a imagem por baixo aparece; isso nenhuma câmera de celular resolve sozinha. O que ajuda de verdade: (1) um botão "Acabamento" na tela da câmera, para avisar de uma vez que o monte que vem a seguir é todo reverse (ou todo holo, ou todo comum) — a carta lida já é marcada nesse acabamento, quando ela existe nele; (2) quando o brilho da lâmpada estraga a leitura, o app agora percebe (mede o quanto da imagem ficou branco estourado), escurece a câmera automaticamente para a mancha encolher, avisa "reflexo forte — incline a carta" e tenta ler de novo mais rápido, sem esperar o intervalo normal.

# POKECARD Brasil 5.60.0 — Exportar cartas em PDF ou Excel

- Nova tela "Exportar" na aba Coleção (botão no alto, ao lado do título). Escolha quais cartas: a coleção inteira, a lista que está na tela (com a busca e os filtros que você ligou), o Quero ou as Duplicadas. Cada carta aparece com a foto, a coleção, o número, as versões que você tem e quanto vale. Toque para marcar ou desmarcar, busque pelo nome e use "Marcar todas" ou "Desmarcar".
- PDF: uma lista pronta para imprimir ou mandar, com a foto de cada carta, nome, coleção e número, raridade e ilustrador, as versões (com idioma e estado quando não são os de sempre) e o preço de cada uma, a quantidade e o valor. O total vem no fim, e as páginas são A4 numeradas.
- Excel: uma planilha (.xlsx) com a foto de cada carta na primeira coluna e colunas de carta, coleção, número, raridade, versões, quantidade, valor em reais e artista, com filtro no cabeçalho e linha de total. Quantidade e valor são números de verdade, para somar e ordenar.
- As fotos são as mesmas que o app mostra: a da sua cópia, se você fotografou; a imagem que você adicionou; a do catálogo; ou outra fonte, quando o catálogo não tem (como nas Energias básicas). O valor é o mesmo do Portfólio, e a carta do Quero leva o preço de mercado de uma cópia comum.
- No celular, o arquivo pronto pode ser salvo onde você escolher ou compartilhado direto (WhatsApp, e-mail, Drive). Até 1.000 cartas por arquivo.

# POKECARD Brasil 5.59.0 — Consulta rápida de preço

- Nova consulta de preço, para a loja e a mesa de troca: "Consultar preço" (no Início e em "Mais") abre a câmera do scanner num modo em que nada é cadastrado. Aponte para a carta e veja na hora o preço de cada versão (comum, holo, reverse...), no idioma e no estado escolhidos. O painel também mostra se você já tem a carta e de quais versões, quantas você tem com o mesmo nome de outras coleções, em quais decks ela entra e quantas faltam neles, e se ela está na Wishlist. Dá para pôr ou tirar da Wishlist ali mesmo, sem sair da câmera.
- As cartas consultadas ficam numa faixa embaixo, cada uma com o preço, e com a soma de todas, para avaliar um lote ou uma troca. Tocar numa miniatura mostra a carta de novo; o × tira a carta da conta.
- Comprou? "Cadastrar" leva a mesma carta para o cadastro normal, com a versão já escolhida. Depois de adicionar, a câmera volta sozinha para o modo preço.
- No topo da câmera, "Cadastrar | Preço" troca de modo a qualquer momento, sem perder as cartas consultadas. Sair da consulta não abre a revisão do cadastro; se houver cartas esperando para cadastrar, a Pokébola avisa, como antes.
- Correção no scanner: a carta digitada (⌨ Digitar carta) não mostra mais "Confira a coleção — não li a numeração", já que foi você quem escolheu. Ela também começa sempre na versão comum, sem herdar a versão e as quantidades da carta anterior.

# POKECARD Brasil 5.58.0 — Regras de jogo de verdade nos decks

- Colar lista do Pokémon TCG Live e do Limitless: a lista em inglês, com a sigla da coleção e o número ("4 Boss's Orders PAL 172"), entra na impressão exata. Antes o app só procurava pelo nome em português e quase nenhum Treinador era reconhecido. Também valem só o nome (em inglês ou português), as Galerias de Treinador ("BRS TG23"), as promos nas duas grafias ("SVP 27" e "PR-SV 27") e a Energia em qualquer grafia ("Basic {R} Energy", "Fire Energy", "Energia de Fogo"). Sem a sigla, entra a impressão que você tem ou, se não tiver nenhuma, a comum mais recente. Ao final, uma janela mostra o que não foi reconhecido e o que passou do limite.
- Energia básica: qualquer impressão serve para jogar, e todo jogador tem de sobra. Ela não aparece mais como "faltando", não entra na lista de compras nem no custo do deck, e não fica "reservada" num deck. Os decks montados pelo app usam a impressão comum. Antes podia entrar a dourada, 12 vezes.
- Energia especial agora tem o limite de 4 cópias, como qualquer carta. Antes era ilimitada, como a básica. A fonte de dados classifica como "básicas" algumas especiais (Energia Reversa, de Ignição, Energia da Equipe Rocket...). O app agora confere também o nome.
- O limite de 4 cópias soma a carta em português com a mesma em inglês ("Ordem da Chefia" e "Boss's Orders").
- ACE SPEC: o app reconhece pela raridade. Pelo nome a regra nunca disparava, e um deck com 4 ACE SPEC passava como válido. O mesmo vale para Pokémon Radiante: no máximo 1 de cada.
- Evolução do jeito do jogo de cartas: Pokémon V e Radiante são Básicos ("Lumineon V está sem Finneon" era erro falso), Pikachu não precisa de Pichu, Snorlax não precisa de Munchlax, e um Estágio 2 com Doce Raro no deck não precisa do Estágio 1. VMAX e VSTAR passam a exigir o V de mesmo nome. O teste de mão inicial conta os Básicos certos.
- Rotação carta a carta: o formato Padrão é conferido pela marca impressa em cada carta (hoje valem H, I e J). A lista antiga era por coleção e dava como legais Charizard Radiante, Lumineon V e outras cartas que já rodaram. Treinador e Energia especial antigos continuam valendo quando foram reimpressos (o Doce Raro de Escarlate e Violeta vale). A marca aparece no detalhe da carta. Ao buscar cartas novas, o app atualiza essas marcas sozinho.
- A Energia dos decks montados pelo app segue o custo dos ataques, não o tipo do Pokémon no videogame: Dragonite pede Água e Elétrica, Rayquaza pede Fogo e Elétrica, e o Eevee ex pede três cores. Decks de Dragão e Incolor deixaram de sair inválidos por falta de Energia, e os Pokémon de apoio atacam com a Energia do deck. Os avisos de Energia também usam o custo: um Manaphy de apoio não pede mais Energia de Água.
- Correções pequenas: "Energia a Jato" e outras especiais sem tipo aparecem como "especiais", não mais "sem tipo identificado". Deck em planejamento não diz mais "somente as cartas que você possui". As sugestões do montador mostram o número real de Treinadores (mostravam 0).

# POKECARD Brasil 5.57.0 — Um só padrão visual

- A cor de cada tema finalmente aparece. Um erro antigo no cálculo das cores deixava o texto, o texto secundário e a cor de destaque todos quase brancos. Agora cada tema tem hierarquia: texto principal, texto secundário mais apagado e o destaque na cor do Pokémon favorito (roxo no Gengar, laranja no Charizard, amarelo no Pikachu...). O contraste foi conferido em 8 temas × 6 níveis de claridade: todas as cores ficam acima de 4,5:1.
- Saíram as sobras dos temas antigos, que apareciam iguais em qualquer tema: quadradinhos brancos com letra roxa na Pokédex e nas linhas dos decks, o botão azul "Com duplicadas", o botão roxo "Adicionar", textos cinza-esverdeados, painéis verde-menta e amarelo-claro (preço, validação do deck) e as faixas roxa e verde dos decks. Tudo segue o tema agora, e os níveis claros de claridade ficaram legíveis.
- As etiquetas de versão (N, F, RF...) passaram a mostrar as cores de legenda que deveriam ter (holo em laranja, reverse em azul...). Antes, uma regra antiga pintava todas por cima.
- Uma escala só no app inteiro: 4 tamanhos de letra (12, 15, 20 e 27) e 3 arredondamentos (10, 16 e 24), no lugar de 14 tamanhos e 10 arredondamentos. As exceções são as etiquetas de versão, que são selos com letra, e o valor do Portfólio.
- O ajuste automático de contraste deixou de ser remendo: antes ele corrigia vários textos por tela, na hora de desenhar. Agora, no tema padrão, as telas principais (Início, Coleção, cadastro da carta, Pokédex e Decks) não precisam de nenhuma correção.

# POKECARD Brasil 5.56.0 — O app responde quando a carta entra

- Mais uma cópia de uma carta que você já tinha: o cartão dá um pulinho e o número de cópias "estoura". A carta nova continua "ganhando cor", como na versão anterior.
- O celular dá um toque de vibração quando uma carta é lida no scanner ou adicionada à coleção, e um toque duplo ao gravar as cartas do scanner, completar uma coleção ou ganhar um troféu. No scanner, isso avisa que a leitura entrou sem precisar olhar para a tela. Usa a vibração ao tocar do próprio Android: se ela estiver desligada nas configurações do celular, o app também não vibra.
- Se você fecha o scanner sem tocar em "Adicionar", as cartas lidas continuam esperando. Agora a Pokébola da barra de baixo balança de tempos em tempos, como nos jogos, com o número de cartas esperando, até você gravar ou descartar.

# POKECARD Brasil 5.55.0 — Início mais limpo e seletores com busca

- Início: o painel "Preços da coleção" virou uma linha de estado, como "✓ Preços atualizados hoje às 08:20 · 18 de 18 cartas com preço", com atalho para revisar os preços pendentes. Os números técnicos do banco, a explicação da fonte e os percentuais por condição foram para "Sobre os preços".
- Corrigido: o botão "Atualizar" do cartão Portfólio não fazia nada (chamava uma função inexistente). Agora atualiza os preços da coleção.
- Filtros da Coleção: coleção e artista agora abrem uma lista com busca, em vez do seletor do Android com centenas de nomes. As coleções aparecem com logo, data e o quanto você já tem; os artistas com a quantidade de cartas. O botão Voltar do celular volta para a gaveta de filtros.
- O aviso de backup virou uma faixa de uma linha, e o botão "PB" ganhou um ponto vermelho enquanto faltar backup.

# POKECARD Brasil 5.54.0 — Capas vazias e páginas de fichário

- A carta que você ainda não tem virou uma capa vazia do fichário: arte em cinza, bem apagada, atrás de um plástico com reflexo e contorno tracejado. Antes o cartão inteiro só ficava meio transparente.
- Quando a carta entra na coleção, ela "ganha cor" com uma animação curta: sai do cinza, dá um pulinho e brilha na cor do tema. Se você cadastrou pelo painel da carta, a animação espera o painel fechar para você ver.
- Novo modo de exibição "Páginas de fichário": a Coleção vira páginas de 3×3 bolsos, como um fichário de verdade, com os furos da argola, o número de cada página e quantos bolsos já estão preenchidos ("6/9", ou "✓ completa" em dourado). Os bolsos das cartas que faltam mostram o número da carta. Fica mais legal com uma coleção escolhida e o filtro "Tudo".
- A etiqueta "Quero" subiu para o canto de cima da carta. Embaixo ela ficava escondida atrás das etiquetas de versão (N, F, RF).

# POKECARD Brasil 5.53.0 — Coleção mais limpa e barra de baixo sem ícones repetidos

- Coleção: a busca divide a linha com um botão "Filtros". Ordenação, coleção e artista ficam numa gaveta que mostra na hora quantas cartas vão aparecer. Os filtros rápidos (Tudo, Tenho, Faltantes…) viram uma fileira que rola para o lado, e o que está ligado aparece como etiqueta com × para desligar num toque. As cartas começam na metade de cima da tela, e não mais embaixo de dois terços de botões.
- Barra de baixo: o "Mais" ganhou o ícone de três pontos (antes era igual ao de Decks) e o "Explorar" ganhou uma bússola (antes era uma pokébola ao lado da Pokébola do scanner).
- As cartas não mostram mais a bolinha "x1": a quantidade só aparece a partir de x2.

# POKECARD Brasil 5.52.0 — Últimas adicionadas, filtro por artista e lista/2/3 colunas

- Tela inicial ganhou a faixa "Adicionadas recentemente": as últimas cartas que entraram na coleção, rolando para o lado, com "hoje", "ontem", "há 3 dias"... Tocar abre a carta; "Ver todas" abre a Coleção já ordenada.
- Coleção: nova ordenação "Adição: recente → antiga". O app passou a guardar a data em que cada cópia entrou na coleção (botão +, cadastro, scanner). Cartas cadastradas antes desta versão usam a data do último cadastro delas, e corrigir o acabamento ou a condição de uma carta antiga não a faz subir para o topo — só entrada de cópia nova conta.
- Coleção: novo filtro por artista (quem desenhou a carta), com 322 artistas e a quantidade de cartas de cada um. Com uma coleção escolhida, a lista mostra só os artistas dela. No cadastro da carta aparece "Ilustração: nome do artista" — tocando, a Coleção abre com tudo o que ele desenhou.
- Coleção: botões para mostrar as cartas em lista, grade de 2 colunas ou grade de 3 colunas. A escolha fica salva no aparelho e vale também para Trocar/Vender e para as cartas na tela de cada Pokémon. Na lista aparece também o nome do artista.
- O catálogo embutido voltou a trazer o tipo de cada carta de Treinador (Item, Apoiador, Estádio, Ferramenta) e passou a trazer o artista. Quem já tinha atualizado o catálogo pelo app recebe os artistas automaticamente.
- Corrigido: os nomes das cartas na grade podiam aparecer em letra escura sobre o cartão escuro, quase ilegíveis. O ajuste automático de contraste confundia a marca d'água do tipo (quase transparente) com o fundo do cartão.

# POKECARD Brasil 5.51.0 — Um deck por linha evolutiva, não por Pokémon

- Deck temático agora é por LINHA evolutiva, não por Pokémon: montar o deck do Venusaur também marca Bulbasaur e Ivysaur como completos na Pokédex de decks — os três já eram atacantes do mesmo baralho, não fazia sentido pedir três decks quase iguais.
- Exceção automática para famílias que se ramificam (Eevee é a mais conhecida, mas vale para qualquer uma): cada evolução vira um deck focado nela (Eevee + aquela evolução) e uma entrada própria na Pokédex de decks, já que são Pokémon diferentes de verdade. Escolher a Eevee sozinha, sem indicar uma evolução, monta um deck só dela.

# POKECARD Brasil 5.50.0 — Wishlist é o espelho dos seus decks

- A aba Wishlist agora abre com "Faltam para seus decks": soma quanto cada carta é pedida em todos os seus decks (reais e em planejamento) e desconta o que você já tem, mostrando exatamente o que falta comprar — com preço, coleção/número e quais decks pedem cada carta. Quando duas decks pedem a mesma carta, a conta soma a demanda certo (2+2 com 2 na coleção falta 2, não 0 e 0 separados).
- A marcação manual de "Wishlist" no cadastro continua funcionando, agora como uma segunda lista "Marcadas manualmente" logo abaixo — para cartas que você quer mesmo sem elas estarem em nenhum deck ainda.

# POKECARD Brasil 5.49.0 — Uma carta não entra em dois decks

- Agora não dá mais para colocar a mesma cópia física de uma carta em dois decks reais ao mesmo tempo: o "+" no editor e o buscador de cartas descontam o que já está reservado em outros decks, não só o total que você possui. Antes só havia um aviso depois de já ter feito isso; agora a ação é bloqueada na hora, com um aviso explicando o motivo.
- Decks em modo planejamento continuam de fora dessa regra — são lista de desejo, não reserva física, então podem repetir carta com outros decks (inclusive planejamento) normalmente.

# POKECARD Brasil 5.48.1 — Ajuste no olho do cadastro

- O olho do cadastro de carta virou um botão isolado no canto superior direito (par do × no canto esquerdo), sem miniatura nenhuma ocupando espaço — o nome, número e selos da carta agora ocupam a largura toda no topo da tela.

# POKECARD Brasil 5.48.0 — Pokébola de verdade, carta em tamanho real e correções

- O botão do scanner agora usa uma pokébola de verdade em vez do ícone genérico.
- O cadastro de carta mostra só uma miniatura da arte, com um botão de olho 👁 ao lado: tocando nele, a carta abre em um modal separado no tamanho real de uma carta TCG (63 × 88 mm). Ganha espaço no formulário e ainda dá para ver a arte grande quando precisar.
- Corrigido: no filtro "Trocar/Vender" da Coleção, as cartas apareciam distorcidas e "fugindo" do quadro — a linha dessa lista usava uma estrutura HTML antiga, incompatível com a grade atual.
- Menu "Mais opções" perdeu os atalhos duplicados: Decks (já é aba fixa), Pokédex (já tem atalho no Início) e Backup (já é o botão "PB" do cabeçalho) saíram por já estarem sempre a um toque de distância por outro caminho.
- Continuação da repaginada visual: cartões de Explorar, chips de filtro ativos, decks válidos e modais em geral ganham o mesmo arredondamento e brilho do tema que a tela de Início já tinha.

# POKECARD Brasil 5.47.0 — Mais contraste e cor por tema

- Aumentado o contraste entre fundo e cartões em todos os 18 temas por tipo — cada camada de cartão agora se destaca visivelmente do fundo, não só uma leve variação de tom.
- Cada tema ganha duas cores "companheiras" vívidas, giradas a partir da cor de destaque escolhida: um tema de Charizard, por exemplo, passa a combinar vermelho, laranja e amarelo em vez de só tons de um laranja só. As três estatísticas da coleção no Início usam essas cores.
- O anel da Pokédex agora respira (brilho pulsando bem devagar) para dar mais vida à tela de Início.
- Corrige uso de `color-mix()` no CSS novo do Início, que não funciona em WebView Android mais antigo — trocado por variáveis prontas em rgba, calculadas junto com o resto da paleta do tema.

# POKECARD Brasil 5.46.0 — Início mais divertido (passo 1)

- Primeiro passo de uma repaginada visual, começando pela tela de Início: cartão do Portfólio com cantos mais redondos, número maior e um brilho holográfico sutil que se move devagar, como o de uma carta especial.
- As três estatísticas da coleção (Cartas, Versões, Especiais) ganham selos redondos coloridos no lugar do ícone chapado.
- O anel da Pokédex fica mais grosso e com um brilho na cor do tema escolhido.
- "Coleções mais completas" ganha pódio de verdade: 1º lugar em ouro, 2º em prata, 3º em bronze.
- Tudo usa as mesmas variáveis de cor que já mudam com o Pokémon favorito escolhido — o brilho e os selos acompanham o tema, não ficam presos a uma cor fixa.

# POKECARD Brasil 5.45.0 — Época coerente no deck temático

- Deck temático agora só usa cartas de 2016 em diante, e a carta mais nova e a mais velha do baralho não ficam a mais de 3 anos de distância — a janela escolhida é a que cobre mais estágios da linha evolutiva com Treinadores e Energia suficientes, não simplesmente a mais recente.
- A explicação do deck ("Como jogar") agora mostra o intervalo de anos das cartas usadas.
- Corrige reconhecimento de tipo de Energia pelo nome: cartas como "Energia Lightning Básica" (nome em inglês) não apareciam mais em "Energia por tipo" — agora usam o mesmo reconhecimento do montador de deck.

# POKECARD Brasil 5.44.0 — Deck temático e Pokédex de decks

- Nova opção "Criar deck temático" na aba Decks: escolha qualquer um dos 1.025 Pokémon e o app monta um baralho de 60 cartas com ele e toda a linha evolutiva como atacantes principais, completado com Pokémon do mesmo tipo, Treinadores e Energia.
- Nova visão "Pokédex de decks" dentro da aba Decks: uma grade nos moldes da Pokédex, um quadradinho por Pokémon, para acompanhar para quais deles você já tem um deck temático — completo ou ainda em andamento.
- O status de cada deck temático é recalculado a partir da coleção atual: o quadradinho vira completo assim que a última carta que faltava é cadastrada.
- Montador automático geral preservado, com o mesmo motor de regras agora compartilhado entre as duas formas de montar deck.

# POKECARD Brasil 5.43.0 — Todas as variações e arte mais rápida

- Cadastro manual da carta agora cobre qualquer variação: carimbo (1ª Edição, Staff, Juiz, Liga Poké/Ultra/Master Bola, Regional/Nacional/Internacional, Mundial, Vencedor, Campeão, Pokémon Center, Professor, aniversários…), acabamento especial (Poké Bola, Master Ball, Great/Ultra Ball, Cosmos, Cracked Ice, Tinsel, Mirror, Gold, Rainbow) e arte (alt art, full art, Trainer Gallery, jumbo, erro de impressão). Vocabulário do TCGdex.
- O campo "Carimbo (outro)" tem autocomplete com mais de 90 carimbos de evento e assinatura — dá para registrar qualquer um.
- O scanner continua igual: só Comum, Holográfica e Reverse Holo. As variações raras ficam no cadastro da carta, sem atrasar a leitura em lote.
- Variações sem preço de fonte (carimbo, Poké Bola, etc.) entram na coleção com valor manual e não contam no total automático.
- A grade de cartas usa a arte já resolvida na sessão anterior e em miniatura — as imagens param de "sumir e reaparecer" a cada abertura.
- Menos consultas ao armazenamento na primeira tela: abre mais rápido.

# POKECARD Brasil 5.42.0 — Coleção mais protegida e app mais enxuto

- Se o aparelho recusar salvar a coleção (armazenamento cheio, por exemplo), o app agora avisa na hora, mostra uma faixa fixa no painel e grava um backup de emergência na pasta Download — antes a falha era silenciosa.
- Uma falha de gravação durante a abertura não zera mais a coleção que já foi carregada.
- O app pede ao sistema para tratar o armazenamento local como persistente.
- Catálogo, Pokédex e formas regionais deixam de ir no pacote em dobro: ~3 MB de dados duplicados a menos dentro do app (o download fica cerca de 0,3 MB menor).
- Removido código sem uso (consulta antiga de preço por busca externa, modo de scanner "uma foto por vez") e arquivos de apoio obsoletos.
- Catálogo, coleção, scanner ao vivo, Pokédex, decks, assinatura, Firebase, temas e imagens preservados.

# POKECARD Brasil 4.0.3 — variantEnum dinâmico

- O Price Database publica todos os enums exatos encontrados por carta e idioma.
- O app não possui allowlist para o campo de preço; enums futuros entram automaticamente.
- O cadastro grava `pricingVariant` sem tradução ou alias.
- A chave de preço passa a ser `cardId::language::variantEnum`.
- Enums sem preço permanecem disponíveis e são identificados como sem preço exato.
- O app continua sem consultar a Liga Pokémon ou marketplaces como fonte de preço.

# POKECARD Brasil 4.0.2 — Price Database exclusivo

- Remove todas as consultas de preço à Liga Pokémon.
- Remove a WebView invisível e a ponte Android usadas para ler anúncios.
- Usa somente o Pokémon Price Database Brasil para preços automáticos.
- Descarta caches e preços automáticos antigos de marketplaces.
- Mantém valor manual e variações por idioma, edição, carimbo e acabamento.

# POKECARD Brasil 4.0.1 — Price Database e variações completas

- Integração ativa com o repositório externo Pokémon Price Database Brasil.
- Correspondência por ID da carta, idioma, edição, carimbo e acabamento.
- 1ª edição deixa de reutilizar silenciosamente o preço da ilimitada.
- Normal, holográfica e reversa recebem chaves independentes.
- Pokébola, Ultrabola, Master Ball e Holográfica de Treinador não usam holo genérica.
- Condições não NM, carimbos estimados e distribuições especiais ficam para revisão.
- Cartas graduadas ou com tags especiais continuam bloqueadas para preço cru automático.
- Liga Pokémon preservada como fonte brasileira complementar e prioritária quando há anúncio exato.
- Banco salvo em IndexedDB e sincronizado em segundo plano quando o aparelho está ocioso.
- Motor de pesquisa, catálogo, scanner, coleção, assinatura, Firebase e dados locais preservados.

# POKECARD Brasil 3.7.0 — Visual, variações específicas e botão voltar

- Nova interface escura em verde-água para Início, Explorar, Coleção, detalhe da carta e preparação do scanner.
- A tela de cada carta mostra inicialmente somente os acabamentos e variações confirmados para aquela carta específica.
- Dados da TCGdex são combinados com os registros individuais da Liga para reconhecer acabamento, idioma, condição, edição, distribuição, arte e tags quando disponíveis.
- O preço automático usa a média filtrada dos anúncios compatíveis da Liga, com remoção de extremos quando há amostra suficiente.
- Cadastros antigos incompatíveis são sinalizados para revisão, sem apagar a coleção do usuário.
- A opção de variação manual rara continua disponível em uma seção recolhida, sem alongar o fluxo normal.
- O botão Voltar do telefone fecha primeiro a tela ou o modal atual; na tela inicial, coloca o aplicativo em segundo plano em vez de encerrá-lo.
- Corrigido o contraste dos nomes, metadados e preços na grade compacta de cartas.
- Preservados `applicationId`, Firebase, assinatura, atualização direta, usuários, bancos, dados, temas, coleções, imagens das cartas e demais funcionalidades.

# POKECARD Brasil 3.6.0 — Imagens por variante e preço exato da Liga

- Imagens das cartas passam a acompanhar o idioma cadastrado quando a fonte TCGdex disponibiliza a arte correspondente.
- A imagem específica retornada pela página da Liga tem prioridade quando houver correspondência do anúncio; a imagem-base continua como alternativa segura.
- O cadastro mostra a fonte da imagem e um selo do acabamento, sem inventar uma arte diferente quando a fonte pública não a fornece.
- Preço automático calculado somente a partir de anúncios de lojistas da Liga Pokémon.
- Cada amostra é separada por acabamento, idioma, condição, edição, distribuição, variação artística, região derivada do idioma, graduação, nota e tags reconhecidas.
- O aplicativo calcula a média dos anúncios compatíveis e, com pelo menos cinco valores, remove extremos pela regra de 1,5 × IQR.
- Menor preço, média filtrada, maior preço, anúncios encontrados, anúncios aproveitados, lojas e valores excluídos ficam visíveis no cadastro.
- A página dinâmica da Liga é lida em uma WebView auxiliar fora da tela, preservando textos, ícones de idioma/condição, dados do lojista e imagens.
- Valores antigos de Banco Preço Brasil, Cardmarket e TCGplayer não são mais usados como preço automático desta versão.
- Preserva `applicationId`, assinatura, Firebase, catálogo, usuários, bancos locais, temas, coleções, imagens existentes e demais funcionalidades.

# POKECARD Brasil 3.5.0 — Visual premium verde-água

- Interface redesenhada no estilo visual compacto do aplicativo de referência, com identidade própria em grafite, verde-água e âmbar.
- Navegação principal movida para a barra inferior, com scanner em destaque no centro.
- Painel reorganizado com três indicadores rápidos, valor da coleção, preços e ações essenciais.
- Coleções e cartas agora usam grades visuais mais densas, cartões arredondados e filtros consistentes.
- Modais, campos, estados vazios e botões foram unificados no novo tema.
- Mantém o nome e o ícone atuais do POKECARD Brasil.
- Preserva catálogo, imagens das cartas, temas das coleções, usuários, bancos locais, Firebase, assinatura e todas as funções existentes.

# POKECARD Brasil 3.4.0 — Variações contextuais no scanner

- Calcula o preço médio usando vários anúncios brasileiros, em vez de copiar apenas a primeira oferta.
- Remove automaticamente anúncios muito baixos ou altos pela regra estatística do intervalo interquartil (IQR), quando há pelo menos cinco ofertas.
- Mostra menor preço, média filtrada, maior preço, total de anúncios e quantidade de extremos removidos.
- Exige pelo menos três anúncios aproveitáveis para considerar o preço automaticamente validado.
- A confirmação do scanner agora mostra Comum, Holográfica e Reversa, além de idioma e condição, antes de cadastrar.
- Inclui preços e cadastros separados para Pokébola, Ultrabola, Master Ball e Holográfica de Treinador.
- Acabamentos especiais nunca reutilizam automaticamente o preço holográfico genérico quando não há amostra exata.
- Variantes agora separam acabamento, edição, distribuição/carimbo, arte, idioma, região, condição, graduação e tags livres.
- Inclui 1ª Edição, Shadowless, Pré-release, Staff, Winner, League, Championship, Stamped, Promo e Professor Program.
- Inclui PSA, CGC, Beckett/BGS, Black Label e SGC, com campo independente para a nota.
- Preços genéricos são bloqueados para edições, carimbos, artes, regiões, tags e graduações especiais sem correspondência exata.
- O scanner consulta a ficha exata da carta e mostra inicialmente somente os acabamentos disponíveis para ela.
- 1ª Edição e Promo só aparecem na área principal quando a fonte confirma essas variantes para a carta.
- Opções raras permanecem acessíveis em “Variação não listada”, recolhidas por padrão para manter a tela curta.
- Cartas escaneadas são separadas por acabamento, idioma e condição e iniciam a consulta do preço do acabamento escolhido.
- Mantém Banco Preço Brasil, Cardmarket e TCGplayer como alternativas quando não há amostra brasileira suficiente.
- Preserva coleção, temas, imagens, usuários, bancos locais, Firebase, identificador e assinatura do aplicativo.

# Versão 2.2.2 — Estabilidade e memória

- A abertura do cadastro continua consultando o preço automaticamente quando não existe valor local atualizado.
- O preço salvo ou do banco central aparece imediatamente, sem impedir a atualização automática em segundo plano.
- Liga Pokémon tenta primeiro uma requisição leve fora da interface e usa WebView auxiliar apenas como fallback.
- Gravações do cache e dos diagnósticos de imagens foram agrupadas para evitar dezenas de acessos síncronos durante a rolagem.
- A recuperação de imagens evita varreduras duplicadas no mesmo quadro.
- Pré-carregamento de artes reduzido para uma operação e quatro cartas à frente, diminuindo pressão de memória.

# Versão 2.2.1 — Modo Laboratório

- Botão **Modo Laboratório** no menu de backup.
- Mede abertura, renderizações, troca de abas, buscas, cadastro e consultas de preço.
- Exibe FPS, tarefas longas e memória JavaScript quando disponível.
- Gera relatório JSON local, sem enviar dados automaticamente.
- Tema Gengar preservado.

# v2.1.1 — Performance e fluidez

- Cache das buscas e ordenações do catálogo enquanto filtros e dados não mudam.
- Filtros e ordenação atualizam somente a lista de cartas, sem reconstruir a aba inteira.
- Pré-carregamento moderado das próximas imagens, limitado a três operações simultâneas.
- Animações e efeitos caros pausam apenas durante a rolagem e voltam automaticamente ao parar.
- Renderização fora da tela otimizada com `content-visibility`.
- Lotes de “Mostrar mais” reduzidos para manter a interface responsiva.
- Tema Gengar preservado.

# v2.1.0 — Performance estrutural

- Mantém integralmente o Tema Gengar.
- Botões de quantidade atualizam somente o cartão afetado, evitando reconstruir a aba inteira.
- Wishlist e listas são atualizadas parcialmente quando possível.
- Gravações da coleção no localStorage são agrupadas por 180 ms, reduzindo travadas a cada toque.
- Persistência forçada ao ocultar ou fechar o aplicativo.
- Resumo da coleção e estatísticas da Pokédex passam a usar cache por revisão do estado.
- Cabeçalho reutiliza o resumo calculado, evitando percorrer a coleção várias vezes.

# Fichário Pokémon 2.0.2 — Firebase Analytics

- Integração oficial com Firebase Analytics.
- Contagem anônima de usuários ativos e novos usuários.
- Relatórios de versões do aplicativo, modelos de aparelhos e versões do Android.
- Registro automático de sessões e abertura do aplicativo.
- Nenhum nome, e-mail, foto ou conteúdo individual da coleção é enviado por esta integração.

# v2.0.1 — Prioridade absoluta para imagem local

- A foto escolhida pelo usuário passa a ter prioridade sobre catálogo, cache e buscas online.
- A imagem local é aplicada imediatamente após ser salva e reaplicada após a renderização da tela.
- A busca automática não pode mais substituir uma foto local existente.
- Ao remover a foto local, o aplicativo volta normalmente à cascata de imagens online.

# v2.0.0 — Botão de foto acessível

- O botão para adicionar ou trocar a foto local da carta foi movido para fora da área da arte.
- O botão agora ocupa toda a largura logo abaixo do cabeçalho do cadastro, facilitando o toque mesmo quando a imagem não carrega.
- Nenhuma outra função ou parte visual foi alterada.

# v1.3.7 — Layout das cartas do deck

- Arte da carta passa a ocupar uma coluna fixa e independente.
- Nome e coleção não invadem mais o espaço da imagem.
- Quantidade e botões +/− ficam em área própria, inclusive em telas estreitas.
- Cartas sem arte mantêm um espaço reservado com “Buscando arte…”.

# v1.3.6 — Contraste final e preços em tons pastéis

- Rótulos de Quantidade, Condição, Acabamento, Idioma, armazenamento e demais formulários agora usam texto claro sobre o fundo escuro.
- Legendas dos cartões do painel receberam contraste branco/lilás claro.
- Cartas não possuídas ficaram translúcidas como os Pokémon ausentes da Pokédex, mantendo nomes, números e controles legíveis.
- Preços encontrados usam fundo verde pastel e texto verde-escuro.
- Cartas sem preço usam fundo amarelo pastel e texto marrom-escuro.
- Avisos de validação e botões preservam cores próprias com contraste alto.

# v1.3.5 — Contraste adaptativo e leitura corrigida

- Corrigido texto escuro sobre filtros e campos escuros do Tema Gengar.
- Cartões brancos de cartas, Pokémon e decks usam roxo-escuro de alto contraste.
- Cartas não possuídas não deixam mais o cartão inteiro transparente; apenas a miniatura fica suavizada.
- Selos de quantidade, preço, raridade, wishlist e variantes receberam combinações específicas de fundo e texto.
- Botões de quantidade mantêm símbolos brancos e o número central permanece legível.
- Estados desabilitados e placeholders receberam contraste mínimo consistente.

# v1.3.4 — Contraste dos cartões claros

- Textos, números e rótulos em cartões brancos agora usam roxo-escuro.
- Correção aplicada a cartas, Pokémon registrados, decks, painel, coleções e formulários.
- Campos brancos e placeholders receberam contraste aprimorado.
- Botões ativos, selos e indicadores coloridos preservam suas cores originais.

# v1.3.3 — Safe-area e contraste

- Cabeçalho fixo agora ocupa a área da barra de status do Android.
- Removida a folga vazia entre a borda superior do aparelho e o cabeçalho.
- Espaçamento seguro preservado para relógio, rede, Wi-Fi e bateria.
- Textos e números em superfícies brancas usam roxo-escuro de alto contraste.
- Ajustado contraste de Pokémon registrados, listas de deck e seletores claros.

# v1.3.2 — Wallpaper Gengar e Pokédex nítida

- Mantido o sprite antigo do Gengar no cabeçalho.
- Imagem enviada aplicada como papel de parede fora do cabeçalho, com 20% de transparência.
- Pokémon com cartas registradas aparecem na Pokédex com fundo branco e sprite totalmente nítido.
- Pokémon ainda não registrados permanecem translúcidos.

# v1.3.0 — Tema Gengar

- Tema visual roxo e preto inspirado no Gengar.
- Cabeçalho animado com Gengar, névoa e brilho leve.
- Abas superiores convertidas em botões com ícones.
- Removida a necessidade de navegação inferior.
- Cartões, filtros, formulários, Pokédex, decks e modais adaptados ao novo tema.
- Animações leves, respeitando a configuração de redução de movimento do aparelho.
- Mantidas todas as funções e otimizações de performance da v1.2.

# Fichário Pokémon v1.2.0

- Catálogo otimizado para mais de 23 mil cartas.
- Pesquisa com índice normalizado e debounce de 250 ms.
- Filtros da coleção processam somente cartas cadastradas.
- Apenas 40 cartas são renderizadas inicialmente.
- Miniaturas carregadas de forma assíncrona e sob demanda.
- Banco central de preços deixa de bloquear a abertura do aplicativo.
- Cache de ordenação para catálogo e coleções.
- Cabeçalho e abas unidos em uma única área fixa, eliminando a faixa onde o texto aparecia ao rolar.
# 2.8.0 — Busca rápida de Pokémon no cadastro

- A lista de 1.026 itens foi substituída por um campo de busca.
- Busca por nome completo ou parcial, como `Arcanine` ou `arca`.
- Busca por número com ou sem zeros, como `59`, `059` ou `0059`.
- Resultados aparecem com imagem, nome e número para seleção por toque.
- Energia / Ferramenta continua disponível pesquisando pelo nome ou por `1026`.
- Vínculos automáticos e escolhas manuais existentes são preservados.

# 2.7.1 — Correspondência mais inteligente

- Nome da carta comparado de forma aproximada, tolerando pequenas falhas do OCR.
- Palavras podem aparecer em ordem diferente sem perder a correspondência.
- Formas regionais em inglês e português são normalizadas.
- Nome do Pokémon passa a reforçar a identificação da carta.
- Números isolados recebem menos peso; frações completas continuam prioritárias.
- Resultados sem evidência de nome/Pokémon deixam de vencer apenas por coincidência numérica.
- A confirmação permite abrir o texto efetivamente reconhecido pela câmera.

# 2.7.0 — Leitura aprimorada da numeração

- OCR separado da carta inteira, faixa inferior e canto inferior.
- Número ampliado em até 4×, convertido para tons de cinza e com contraste reforçado.
- Reconhecimento prioritário de frações como `084/196`.
- Correção de confusões comuns entre `O/0` e `I/1`.
- Coleção opcional na preparação do scanner para reduzir correspondências ambíguas.
- Guia visual mostrando onde manter a numeração durante a captura.
- Correção automática da orientação EXIF antes de recortar a região do número.

# 2.6.1 — Confirmação visual do scanner

- A arte mais provável aparece em destaque antes do cadastro.
- Botão verde com ✓ confirma e adiciona a carta.
- Botão vermelho com × recusa a arte e abre as demais correspondências.
- Nenhuma carta é adicionada antes da confirmação explícita.

# 2.6.0 — Scanner assistido de cartas

- Pré-configuração da sessão: Comum, Holográfica ou Reversa.
- Captura pela câmera traseira e leitura local de nome/numeração com ML Kit.
- Sugestões do catálogo com confirmação antes do cadastro.
- Cadastro sequencial, contador da sessão e botão para fotografar a próxima carta.
- Cartas reconhecidas recebem automaticamente o acabamento escolhido.
- Correspondências sem vínculo com a Pokédex continuam exigindo Pokémon ou Energia / Ferramenta.

# 2.5.0 — Vínculo manual com a Pokédex

- Cadastro da carta agora mostra uma lista com os 1.025 Pokémon.
- Inclui a opção especial `Nº 1026 — Energia / Ferramenta`, que não conta na Pokédex.
- Quando o catálogo não reconhece automaticamente a carta, a escolha passa a ser obrigatória.
- O vínculo escolhido fica salvo nos dados locais da carta e atualiza imediatamente a Pokédex.
- O botão rápido `+` abre o cadastro quando uma carta ainda precisa dessa classificação.

# 2.4.1 — Ano mais visível

- Ano das coleções reforçado em amarelo-claro, com tamanho mínimo e sombra de alto contraste.
- Regra aplicada também às coleções vazias/dessaturadas.
- Cache do JavaScript e versão Android atualizados para garantir a exibição após instalar a atualização.

# 2.4.0 — Grade offline de coleções

- Coleções em ordem cronológica decrescente, usando data local vinculada ao ID exato.
- Três cartões por linha em celulares, com nome e ano na mesma linha.
- 123 imagens WebP locais e otimizadas, com fallback também local.
- Coleções iniciadas ficam nítidas; coleções vazias ficam dessaturadas sem perder o clique.
- Catálogos antigos salvos no IndexedDB recebem os novos metadados sem apagar cartas ou quantidades.
- `versionCode` 55 e `versionName` `2.4.0-collection-grid-offline`.
# Versão 2.9.0 — Montador Automático de Decks

- Novo fluxo de montagem com formato, fonte das cartas, objetivo, tipo e carta favorita.
- Três sugestões determinísticas com nota, confiança, cartas possuídas e faltantes.
- Validação de 60 cartas, limite por nome, Pokémon Básico, ACE SPEC e Pokémon Radiante.
- Simulação reproduzível de 1.000 mãos para estimar mulligan, Básico, Energia e busca/compra.
- Explicação do plano, pontos fortes, limitações dos dados e teste visual de mão inicial.
- Decks anteriores e toda a coleção continuam no mesmo armazenamento local.
# Versão 2.9.1 — Decks coerentes por tipo e Energia

- Corrige a confusão entre cartas de Energia, Ferramentas e Treinadores ligados ao item 1026.
- Remove cartas do Pokémon TCG Pocket dos formatos do TCG físico.
- Forma cada candidato ao redor de um único núcleo energético coerente.
- Seleciona apenas Pokémon compatíveis com o tipo de Energia planejado.
- Garante de 8 a 14 Energias reais e prioriza Energia Básica compatível.
- Reduz cartas situacionais ou dependentes de recursos ausentes.
- A validação agora rejeita decks sem Energia real ou com Pokémon incompatíveis.
