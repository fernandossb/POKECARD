/* Arte Estendida — obras que se completam entre duas ou mais cartas.

   Os dados (data/arte-estendida-data.js) vêm de um estudo à parte: 90 obras,
   235 cartas, cada uma com o nome em inglês (idioma de origem do
   levantamento) e o número impresso QUANDO ele pôde ser confirmado numa
   fonte — número em branco não é preguiça, é honestidade: a planilha não
   inventa numeração.

   Cada carta da obra é ligada a uma carta do catálogo deste aparelho — por
   número quando dá, por nome quando não dá — e o "tenho" de cada uma vem
   direto da coleção de verdade (quantityFor): não existe uma lista paralela
   de posse só para esta tela. Quem já cadastrou a carta em Coleção já
   completou o quadrinho aqui, sem fazer nada.

   Nem toda coleção do estudo já tem cartas carregadas neste aparelho — é uma
   limitação real do catálogo, mostrada como tal, nunca escondida. Quando o
   casamento automático não acha a carta certa (raridades antigas, promoções
   com numeração própria), um botão deixa vincular à mão, do mesmo jeito que
   a carta escolhida vale para sempre — fica salva, não precisa repetir. */

// Nome da coleção em INGLÊS (como o estudo cita) → id do TCGdex. Só indica
// candidatos: se a coleção não tem cartas carregadas neste aparelho, ela
// conta como "ainda não catalogada" mesmo estando aqui.
const ARTE_ESTENDIDA_NOME_PARA_SET = {
  'ancient origins': 'xy7', 'aquapolis': 'ecard2', 'arceus': 'pl4',
  'breakpoint': 'xy9', 'breakthrough': 'xy8', 'bw black star promos': 'bwp',
  'boundaries crossed': 'bw7', 'celebrations': 'cel25',
  'celebrations classic collection': 'cel25cc', 'chilling reign': 'swsh6',
  'cosmic eclipse': 'sm12', 'crimson invasion': 'sm4', 'crown zenith': 'swsh12.5',
  'crown zenith galarian gallery': 'swsh12.5gg', 'crystal guardians': 'ex14',
  'delta species': 'ex11', 'deoxys': 'ex8', 'destined rivals': 'sv10',
  'dragons exalted': 'bw6', 'ex legend maker': 'ex12', 'evolving skies': 'swsh7',
  'fates collide': 'xy10', 'forbidden light': 'sm6', 'fusion strike': 'swsh8',
  'generations': 'g1', 'hgss black star promos': 'hgssp',
  'hs undaunted': 'hgss3', 'hs unleashed': 'hgss2', 'legendary treasures': 'bw11',
  'lost origin': 'swsh11', 'majestic dawn': 'dp5', 'neo discovery': 'neo2',
  'neo revelation': 'neo3', 'obsidian flames': 'sv03', 'pop series 1': 'pop1',
  'pop series 4': 'pop4', 'paldea evolved': 'sv02', 'paldean fates': 'sv04.5',
  'paradox rift': 'sv04', 'plasma blast': 'bw10', 'plasma storm': 'bw8',
  'rising rivals': 'pl2', 'roaring skies': 'xy6', 'sm black star promos': 'smp',
  'swsh black star promos': 'swshp', 'scarlet and violet': 'sv01',
  'scarlet and violet black star promos': 'svp', 'scarlet and violet promos': 'svp',
  'shining fates': 'swsh4.5', 'shrouded fable': 'sv06.5', 'silver tempest': 'swsh12',
  'skyridge': 'ecard3', 'southern islands': 'si1', 'stellar crown': 'sv07',
  'supreme victors': 'pl3', 'surging sparks': 'sv08', 'sword and shield': 'swsh1',
  'team rocket returns': 'ex7', 'temporal forces': 'sv05',
  'twilight masquerade': 'sv06', 'ultra prism': 'sm5', 'unified minds': 'sm11',
  'vivid voltage': 'swsh4', 'xy black star promos': 'xyp',
};

/* Como as cartas se encaixam. "Horizontal" é uma fileira com todas, na
   ordem do estudo; "Vertical", uma coluna, a primeira em cima; "3×2" e "3×3"
   são largura × altura — 3 por fileira, preenchendo da esquerda para a
   direita, de cima para baixo. `razao` é largura/altura da obra inteira
   (cada carta tem 63 × 88 mm): é ela que deixa a arte do tamanho certo antes
   de as imagens chegarem, sem a lista "pular" enquanto carrega. */
function arteEstendidaGeometria(obra) {
  const n = obra.cartas.length || 1;
  const encaixe = String(obra.encaixe || 'Horizontal');
  const grade = encaixe.match(/^(\d+)\s*[×x]\s*(\d+)$/);
  let tipo = 'horizontal', colunas = n, rotulo = 'Horizontal';
  if (grade) { tipo = 'grade'; colunas = Number(grade[1]) || 3; rotulo = `Grade ${grade[1]}×${grade[2]}`; }
  else if (/vertical/i.test(encaixe)) { tipo = 'vertical'; colunas = 1; rotulo = 'Vertical'; }
  const linhas = Math.ceil(n / colunas);
  return { tipo, colunas, linhas, rotulo, razao: (colunas * 63) / (linhas * 88) };
}

function arteEstendidaNormalizarNomeSet(texto) {
  return String(texto || '').toLowerCase().trim()
    .replace(/&/g, 'and').replace(/[^a-z0-9]+/g, ' ').trim();
}

/* Os candidatos de verdade: só contam as coleções que JÁ têm carta carregada
   neste aparelho. `cardsBySet` é o mesmo índice que o resto do app usa —
   quando o catálogo crescer (Buscar cartas e coleções novas), mais obras
   passam a se resolver sozinhas, sem precisar tocar nesta tela. */
function arteEstendidaSetIdsCandidatos(colecaoTexto) {
  const partes = String(colecaoTexto || '').split(' / ');
  const ids = new Set();
  for (const parte of partes) {
    const id = ARTE_ESTENDIDA_NOME_PARA_SET[arteEstendidaNormalizarNomeSet(parte)];
    if (id && cardsBySet.get(id)?.length) ids.add(id);
  }
  return [...ids];
}

/* Tenta achar, sozinho, qual carta do catálogo é esta. Por número quando a
   fonte confirmou um (fração "165/162" ou código de promo "SWSH061"); por
   nome quando não. Só devolve resposta quando ela é a ÚNICA candidata —
   ambiguidade não vira palpite, vira "vincule à mão". */
function arteEstendidaResolverAutomatico(setIds, nomeIngles, numeroBruto) {
  if (!setIds.length) return null;
  const pool = setIds.flatMap(id => cardsBySet.get(id) || []);
  if (!pool.length) return null;
  const porNomeIngles = lista => lista.filter(c => normalize(c.name) === normalize(nomeIngles));

  if (numeroBruto) {
    const fracao = numeroBruto.match(/^0*(\d+)\/\d+$/);
    if (fracao) {
      const alvo = fracao[1];
      let candidatos = pool.filter(c => String(Number(String(c.localId || '').match(/\d+/)?.[0] ?? -1)) === alvo);
      if (candidatos.length > 1) candidatos = porNomeIngles(candidatos).length ? porNomeIngles(candidatos) : candidatos;
      if (candidatos.length === 1) return candidatos[0].id;
    } else {
      // Sem barra: número cru de promo/galeria ("SWSH061", "GG42", "XY74").
      const alvo = normalize(numeroBruto).replace(/\s+/g, '');
      const candidatos = pool.filter(c => normalize(String(c.localId || '')).replace(/\s+/g, '') === alvo);
      if (candidatos.length === 1) return candidatos[0].id;
    }
  }
  const porNome = porNomeIngles(pool);
  if (porNome.length === 1) return porNome[0].id;
  return null;
}

function arteEstendidaChaveDoSlot(obraId, ordem) { return `${obraId}:${ordem}`; }

function arteEstendidaVinculoManual(obraId, ordem) {
  return state.arteEstendida?.[arteEstendidaChaveDoSlot(obraId, ordem)] || '';
}

// Guarda para sempre: vinculou uma vez, a próxima abertura já vem com a carta certa.
function arteEstendidaSalvarVinculo(obraId, ordem, cardId) {
  state.arteEstendida = state.arteEstendida || {};
  const chave = arteEstendidaChaveDoSlot(obraId, ordem);
  if (cardId) state.arteEstendida[chave] = cardId;
  else delete state.arteEstendida[chave];
  saveState();
}

/* A carta de um slot: o que foi vinculado à mão vale sempre; sem isso, tenta
   sozinho. `cardId` volta vazio quando nada resolve — a tela mostra o botão
   de vincular. */
/* O casamento automático varre as coleções candidatas carta a carta — feito
   para as 235 cartas a cada desenho da lista, custava ~50 ms num PC (bem
   mais no celular), em cada letra digitada na busca e em cada volta à lista.
   A resposta só muda com vínculo manual novo (que passa por saveState) ou
   catálogo novo, então fica guardada até um dos dois acontecer. */
let arteEstendidaSlotsCache = { carimbo: '', mapa: new Map() };
function arteEstendidaCartaDoSlot(obra, slot) {
  const carimbo = `${stateRevision}|${cards.length}|${cardMap.size}`;
  if (arteEstendidaSlotsCache.carimbo !== carimbo) arteEstendidaSlotsCache = { carimbo, mapa: new Map() };
  const chave = arteEstendidaChaveDoSlot(obra.id, slot[0]);
  let resposta = arteEstendidaSlotsCache.mapa.get(chave);
  if (!resposta) {
    resposta = arteEstendidaResolverSlot(obra, slot);
    arteEstendidaSlotsCache.mapa.set(chave, resposta);
  }
  return resposta;
}

function arteEstendidaResolverSlot(obra, slot) {
  const [ordem, nomeIngles, numeroBruto] = slot;
  const manual = arteEstendidaVinculoManual(obra.id, ordem);
  if (manual && cardMap.has(manual)) return { cardId: manual, manual: true };
  const setIds = arteEstendidaSetIdsCandidatos(obra.colecao);
  const auto = arteEstendidaResolverAutomatico(setIds, nomeIngles, numeroBruto);
  return { cardId: auto || '', manual: false, setIds };
}

/* Progresso de uma obra: quantas das cartas ligadas você já tem. Cartas sem
   vínculo nenhum não contam nem no total nem no "tenho" — não dá pra saber
   se falta a carta ou falta achar ela no catálogo. */
function arteEstendidaProgressoDaObra(obra) {
  let ligadas = 0, tenho = 0;
  for (const slot of obra.cartas) {
    const { cardId } = arteEstendidaCartaDoSlot(obra, slot);
    if (!cardId) continue;
    ligadas++;
    if (quantityFor(cardId) > 0) tenho++;
  }
  return { tenho, ligadas, total: obra.cartas.length, completa: ligadas === obra.cartas.length && tenho === ligadas };
}

// Teaser para o menu "Mais": "42 de 137 cartas ligadas já na coleção".
function resumoArteEstendida() {
  const obras = window.__ARTE_ESTENDIDA__ || [];
  if (!obras.length) return null;
  let tenho = 0, ligadas = 0, completas = 0;
  for (const obra of obras) {
    const p = arteEstendidaProgressoDaObra(obra);
    tenho += p.tenho; ligadas += p.ligadas;
    if (p.completa && p.ligadas) completas++;
  }
  return { totalObras: obras.length, tenho, ligadas, completas };
}

/* ---------- A arte montada: as cartas no encaixe certo ----------

   Uma grade só serve para os três encaixes: `colunas` diz quantas por
   fileira (todas, uma, ou três), e a proporção da obra inteira fica
   declarada no CSS. Assim a vertical deixa de sumir (antes era uma fileira
   "deitada" com altura zero) e nada muda de tamanho quando a imagem chega —
   é isso que permite a lista voltar exatamente para onde estava.

   Na lista a arte é só imagem (o cartão inteiro abre a obra); dentro da obra
   cada carta é um botão: abre a carta, ou abre o vínculo à mão. */
function arteEstendidaImagemDaCarta(card, grande) {
  const url = grande && card.imageUrl ? upgradeCardImageUrl(card.imageUrl) : cardGridImage(card, null);
  // Imagem que o TCGdex não tem (acontece com tiragens em português): vira o
  // mesmo quadro "TCG" da Coleção, em vez de ícone de imagem quebrada.
  return url
    ? `<img src="${esc(url)}" alt="${esc(card.name)}" loading="lazy" decoding="async" onerror="this.outerHTML='<span class=&quot;card-placeholder&quot;>TCG</span>'">`
    : '<span class="card-placeholder">TCG</span>';
}

function arteEstendidaCelula(obra, slot, resolvido, interativa, grande) {
  const [ordem, nomeIngles, numeroBruto] = slot;
  const card = resolvido.cardId ? cardMap.get(resolvido.cardId) : null;
  if (card) {
    const tem = quantityFor(card.id) > 0;
    // Na lista a arte fica limpa: o que falta já aparece em cinza, como na
    // Coleção. Dentro da obra entram os selos ✓/✕ e a marca de vínculo manual.
    const miolo = `${arteEstendidaImagemDaCarta(card, grande)}${interativa
      ? `<span class="arte-estendida-selo ${tem ? 'ok' : ''}" aria-hidden="true">${tem ? '✓' : '✕'}</span>${resolvido.manual ? '<span class="arte-estendida-manual" title="Vinculada à mão">✎</span>' : ''}`
      : ''}`;
    return interativa
      ? `<button type="button" class="arte-celula ${tem ? 'tem' : 'falta'}" onclick="abrirCartaDaObraArteEstendida(${obra.id},'${esc(card.id)}')" title="${esc(card.name)} · ${esc(card.setName)}" aria-label="${esc(card.name)}${tem ? ' (você tem)' : ' (falta)'}">${miolo}</button>`
      : `<span class="arte-celula ${tem ? 'tem' : 'falta'}">${miolo}</span>`;
  }
  // Sem vínculo: a coleção pode nem estar catalogada ainda, ou o casamento
  // automático não achou candidato único — os dois casos abrem o vínculo à
  // mão, que também serve para digitar/buscar quando a coleção já existe.
  if (!interativa) {
    return `<span class="arte-celula vazio"><span class="arte-estendida-slot-nome">${esc(nomeIngles)}</span></span>`;
  }
  const semColecao = !resolvido.setIds || !resolvido.setIds.length;
  return `<button type="button" class="arte-celula vazio" onclick="abrirVinculoArteEstendida(${obra.id},${ordem})">
    <span class="arte-estendida-slot-nome">${esc(nomeIngles)}</span>
    <span class="arte-estendida-slot-ajuda">${semColecao ? 'Coleção ainda não catalogada' : 'Toque para vincular'}</span>
    ${numeroBruto ? `<span class="arte-estendida-slot-numero">${esc(numeroBruto)}</span>` : '<span class="arte-estendida-slot-numero fraco">nº não confirmado</span>'}
  </button>`;
}

function arteEstendidaComposicao(obra, resolvidos, { interativa = false, grande = false } = {}) {
  const g = arteEstendidaGeometria(obra);
  const celulas = obra.cartas.map((slot, i) => arteEstendidaCelula(obra, slot, resolvidos[i], interativa, grande)).join('');
  return `<div class="arte-composicao ${g.tipo}" style="--arte-colunas:${g.colunas};--arte-linhas:${g.linhas};--arte-razao:${g.razao.toFixed(4)}">${celulas}</div>`;
}

/* ---------- Tela: lista das 90 obras ----------

   Cada obra vira um cartão no estilo da Coleção: a arte completa em cima,
   ocupando a largura toda, e uma descrição curta embaixo.

   A lista lembra a busca e o ponto da rolagem: entrar numa obra (ou numa
   carta aberta a partir dela) e voltar devolve você exatamente onde estava.
   Antes, voltar redesenhava a lista do zero — topo, busca apagada. */
let arteEstendidaBusca = '';
let arteEstendidaRolagemLista = 0;
let arteEstendidaRolagemObra = { obraId: null, topo: 0 };

function arteEstendidaFolha() {
  return document.getElementById('modal-content');
}

function arteEstendidaListaFiltrada() {
  const obras = window.__ARTE_ESTENDIDA__ || [];
  const termo = normalize(arteEstendidaBusca);
  if (!termo) return obras;
  return obras.filter(obra => normalize(`${obra.nome} ${obra.colecao}`).includes(termo));
}

function arteEstendidaCartaoDaObra(obra) {
  const resolvidos = obra.cartas.map(slot => arteEstendidaCartaDoSlot(obra, slot));
  const ligadas = resolvidos.filter(r => r.cardId);
  const tenho = ligadas.filter(r => quantityFor(r.cardId) > 0).length;
  const total = obra.cartas.length;
  const completa = ligadas.length === total && tenho === total;
  const pct = total ? Math.round((tenho / total) * 100) : 0;
  const g = arteEstendidaGeometria(obra);
  return `<button type="button" class="arte-obra-cartao${completa ? ' completa' : ''}" data-obra-id="${obra.id}" onclick="abrirObraArteEstendida(${obra.id})">
    <span class="arte-obra-cartao-arte">${arteEstendidaComposicao(obra, resolvidos)}</span>
    <span class="arte-obra-cartao-texto">
      <strong>${esc(obra.nome)}</strong>
      <small>${esc(obra.colecao)}</small>
      <span class="arte-obra-cartao-progresso">
        <span class="arte-estendida-barra"><i style="width:${pct}%"></i></span>
        <b>${completa ? '✓ completa' : `${tenho}/${total}`}</b>
      </span>
      <small class="arte-obra-cartao-encaixe">${esc(g.rotulo)} · ${total} cartas${ligadas.length < total ? ` · ${total - ligadas.length} sem vínculo` : ''}</small>
    </span>
  </button>`;
}

function arteEstendidaListaHtml() {
  const obras = arteEstendidaListaFiltrada();
  return obras.length
    ? obras.map(arteEstendidaCartaoDaObra).join('')
    : '<div class="empty">Nenhuma obra encontrada com esse nome.</div>';
}

function renderArteEstendidaLista() {
  const resumo = resumoArteEstendida();
  return `
    <button class="modal-close" onclick="closeModal()" aria-label="Fechar">×</button>
    <h2>🖼 Arte Estendida</h2>
    <p class="screen-subtitle">Obras que só aparecem inteiras quando duas ou mais cartas ficam lado a lado. ${resumo ? `${resumo.completas} de ${resumo.totalObras} obras completas na sua coleção.` : ''}</p>
    <label class="vision-search arte-estendida-busca"><span>${tabIcon('pokedex')}</span>
      <input value="${esc(arteEstendidaBusca)}" placeholder="Buscar obra ou coleção..." autocomplete="off"
        oninput="arteEstendidaBusca=this.value;atualizarArteEstendidaLista()"></label>
    <div class="arte-estendida-lista">${arteEstendidaListaHtml()}</div>
    <p class="arte-estendida-fonte">Fonte: estudo "Artes conectadas Pokémon TCG" (levantamento Deck Certo). Cartas físicas em inglês; Pocket excluído. Números não confirmados na fonte não entram como certos — a carta é vinculada à mão quando isso acontece.</p>`;
}

// Guarda a rolagem da tela que está saindo, seja ela a lista ou uma obra.
function arteEstendidaGuardarRolagem() {
  const folha = arteEstendidaFolha();
  if (!folha || document.getElementById('modal')?.classList.contains('hidden')) return;
  if (folha.classList.contains('arte-estendida-lista-sheet')) arteEstendidaRolagemLista = folha.scrollTop;
  else if (folha.classList.contains('arte-estendida-obra-sheet') && !folha.classList.contains('arte-estendida-vinculo-sheet') && folha.dataset.obraId) {
    arteEstendidaRolagemObra = { obraId: Number(folha.dataset.obraId), topo: folha.scrollTop };
  }
}

function arteEstendidaRolarPara(topo) {
  const folha = arteEstendidaFolha();
  if (!folha) return;
  folha.scrollTop = topo;
  // Segunda tentativa depois do desenho: o navegador pode ter ajustado o
  // tamanho do painel no mesmo quadro.
  requestAnimationFrame(() => { if (folha.scrollTop !== topo) folha.scrollTop = topo; });
}

// Entrada pelo menu "Mais": abre a lista onde ela ficou da última vez.
function abrirArteEstendida() {
  arteEstendidaGuardarRolagem();
  arteEstendidaCartaAberta = null;
  showModal(renderArteEstendidaLista(), 'arte-estendida-sheet arte-estendida-lista-sheet');
  arteEstendidaRolarPara(arteEstendidaRolagemLista);
}

// Só a lista é redesenhada: o campo de busca (e o teclado) ficam como estão.
function atualizarArteEstendidaLista() {
  const folha = arteEstendidaFolha();
  const lista = folha?.classList.contains('arte-estendida-lista-sheet') ? folha.querySelector('.arte-estendida-lista') : null;
  if (!lista) return;
  lista.innerHTML = arteEstendidaListaHtml();
}

/* ---------- Tela: uma obra ---------- */

function renderObraArteEstendida(obraId) {
  const obra = (window.__ARTE_ESTENDIDA__ || []).find(o => o.id === obraId);
  if (!obra) return '<button class="modal-close" onclick="closeModal()">×</button><h2>Obra não encontrada</h2>';
  const resolvidos = obra.cartas.map(slot => arteEstendidaCartaDoSlot(obra, slot));
  const progresso = arteEstendidaProgressoDaObra(obra);
  const g = arteEstendidaGeometria(obra);

  return `
    <button type="button" class="gaveta-voltar arte-estendida-voltar" onclick="voltarParaListaArteEstendida()" aria-label="Voltar para a lista">‹</button>
    <button class="modal-close" onclick="closeModal()" aria-label="Fechar">×</button>
    <h2>${esc(obra.nome)}</h2>
    <p class="screen-subtitle">${esc(obra.colecao)} · ${esc(g.rotulo)} · ${progresso.tenho} de ${progresso.total} cartas já na sua coleção${progresso.ligadas < progresso.total ? ` · ${progresso.total - progresso.ligadas} sem vínculo ainda` : ''}</p>
    <div class="arte-obra-detalhe">${arteEstendidaComposicao(obra, resolvidos, { interativa: true, grande: true })}</div>
    <p class="arte-estendida-dica">✓ verde = já está na sua coleção · ✕ = ainda falta · toque numa carta reconhecida para abri-la, ou num quadro vazio para vincular a carta certa.</p>`;
}

function abrirObraArteEstendida(obraId) {
  arteEstendidaGuardarRolagem();
  showModal(renderObraArteEstendida(obraId), 'arte-estendida-sheet arte-estendida-obra-sheet');
  const folha = arteEstendidaFolha();
  if (folha) folha.dataset.obraId = String(obraId);
  // Voltando de uma carta ou do vínculo desta mesma obra: onde estava. Obra
  // aberta a partir da lista: do começo.
  arteEstendidaRolarPara(arteEstendidaRolagemObra.obraId === obraId ? arteEstendidaRolagemObra.topo : 0);
  arteEstendidaRolagemObra = { obraId: null, topo: 0 };
}

function voltarParaListaArteEstendida() {
  arteEstendidaRolagemObra = { obraId: null, topo: 0 };
  showModal(renderArteEstendidaLista(), 'arte-estendida-sheet arte-estendida-lista-sheet');
  arteEstendidaRolarPara(arteEstendidaRolagemLista);
}

/* ---------- Carta aberta a partir de uma obra ----------

   O cadastro da carta é o mesmo de sempre (openCard). O que muda é a volta:
   um ‹ no canto leva de novo à obra, na mesma posição — e o Voltar do
   celular faz o mesmo. O × continua fechando tudo, como em qualquer painel. */
let arteEstendidaCartaAberta = null; // { obraId, cardId }

function abrirCartaDaObraArteEstendida(obraId, cardId) {
  arteEstendidaGuardarRolagem();
  arteEstendidaCartaAberta = { obraId, cardId };
  openCard(cardId);
}

function voltarDaCartaParaObraArteEstendida() {
  const alvo = arteEstendidaCartaAberta;
  arteEstendidaCartaAberta = null;
  if (alvo) abrirObraArteEstendida(alvo.obraId);
}

function arteEstendidaPorBotaoVoltarNaCarta() {
  const folha = arteEstendidaFolha();
  if (!folha || folha.querySelector('.arte-estendida-voltar-carta')) return;
  const botao = document.createElement('button');
  botao.type = 'button';
  botao.className = 'gaveta-voltar arte-estendida-voltar-carta';
  botao.setAttribute('aria-label', 'Voltar para a obra');
  botao.textContent = '‹';
  botao.addEventListener('click', voltarDaCartaParaObraArteEstendida);
  folha.prepend(botao);
}

/* O cadastro se redesenha sozinho a cada ação (salvar, +/−), chamando
   openCard de novo — por isso o ‹ é recolocado aqui, depois de cada
   abertura. Abrir a carta com o painel FECHADO (vindo da Coleção, por
   exemplo) ou abrir outra carta encerra o caminho de volta. */
if (typeof openCard === 'function') {
  const abrirCartaAntes = openCard;
  openCard = function (cardId, ...resto) {
    const painelAberto = !document.getElementById('modal')?.classList.contains('hidden');
    if (arteEstendidaCartaAberta && (!painelAberto || arteEstendidaCartaAberta.cardId !== cardId)) arteEstendidaCartaAberta = null;
    const resultado = abrirCartaAntes.call(this, cardId, ...resto);
    if (arteEstendidaCartaAberta) arteEstendidaPorBotaoVoltarNaCarta();
    return resultado;
  };
}

/* ---------- Vincular à mão ----------
   Mesma ideia da busca do scanner: digita nome ou número, escolhe da lista.
   A escolha fica salva (arteEstendidaSalvarVinculo) — não precisa repetir. */
let arteEstendidaVinculoAlvo = null; // {obraId, ordem}

function abrirVinculoArteEstendida(obraId, ordem) {
  const obra = (window.__ARTE_ESTENDIDA__ || []).find(o => o.id === obraId);
  const slot = obra?.cartas.find(s => s[0] === ordem);
  if (!obra || !slot) return;
  arteEstendidaGuardarRolagem();
  arteEstendidaVinculoAlvo = { obraId, ordem };
  const [, nomeIngles, numeroBruto] = slot;
  const sheet = document.getElementById('modal-content');
  const html = `
    <button type="button" class="gaveta-voltar arte-estendida-voltar" onclick="abrirObraArteEstendida(${obraId})" aria-label="Voltar para a obra">‹</button>
    <button class="modal-close" onclick="closeModal()" aria-label="Fechar">×</button>
    <h2>Vincular carta</h2>
    <p class="screen-subtitle">${esc(nomeIngles)}${numeroBruto ? ` · nº ${esc(numeroBruto)} (impresso, em inglês)` : ' · número não confirmado na fonte'} — de ${esc(obra.colecao)}</p>
    <label class="vision-search arte-estendida-busca"><span>${tabIcon('pokedex')}</span>
      <input id="arteEstendidaVinculoCampo" placeholder="Nome, número ou coleção..." autocomplete="off"
        value="${esc(nomeIngles)}" oninput="arteEstendidaBuscarVinculo(this.value)"></label>
    <div id="arteEstendidaVinculoLista" class="arte-estendida-vinculo-lista"></div>`;
  if (sheet) { sheet.innerHTML = html; sheet.className = 'modal-sheet arte-estendida-sheet arte-estendida-obra-sheet arte-estendida-vinculo-sheet'; sheet.scrollTop = 0; }
  else showModal(html, 'arte-estendida-sheet arte-estendida-obra-sheet arte-estendida-vinculo-sheet');
  arteEstendidaBuscarVinculo(nomeIngles);
  setTimeout(() => document.getElementById('arteEstendidaVinculoCampo')?.focus(), 100);
}

function arteEstendidaBuscarVinculo(termo) {
  const lista = document.getElementById('arteEstendidaVinculoLista');
  if (!lista) return;
  const texto = String(termo || '').trim();
  if (texto.length < 2) { lista.innerHTML = '<p class="busca-manual-dica">Digite ao menos 2 caracteres para buscar.</p>'; return; }
  const alvo = normalize(texto);
  const achados = cards.filter(card => (cardSearchIndex.get(card.id) || '').includes(alvo)).slice(0, 30);
  if (!achados.length) { lista.innerHTML = '<p class="busca-manual-dica">Nenhuma carta encontrada com esse termo.</p>'; return; }
  lista.innerHTML = achados.map(card => `<button class="busca-manual-item" onclick="arteEstendidaEscolherVinculo('${esc(card.id)}')">
    ${card.imageUrl ? `<img src="${esc(upgradeCardImageUrl(card.imageUrl))}" alt="" loading="lazy">` : '<span class="card-placeholder">TCG</span>'}
    <span><strong>${esc(card.name)}</strong><small>${esc(formatCardNumber(card.localId || card.number, card.setTotal))} · ${esc(card.setName)}</small></span>
  </button>`).join('');
}

function arteEstendidaEscolherVinculo(cardId) {
  if (!arteEstendidaVinculoAlvo) return;
  const { obraId, ordem } = arteEstendidaVinculoAlvo;
  arteEstendidaSalvarVinculo(obraId, ordem, cardId);
  arteEstendidaVinculoAlvo = null;
  vibrar();
  abrirObraArteEstendida(obraId);
}

// Desfaz um vínculo manual errado — volta a tentar sozinho, ou some se não achar mais nada.
function arteEstendidaDesvincular(obraId, ordem) {
  arteEstendidaSalvarVinculo(obraId, ordem, '');
  abrirObraArteEstendida(obraId);
}
