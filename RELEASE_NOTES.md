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
