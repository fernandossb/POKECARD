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

// Como as cartas se encaixam: nº de colunas do grid (0 = uma linha só).
const ARTE_ESTENDIDA_COLUNAS = { 'Horizontal': 0, 'Vertical': 1, '3×2': 3, '3×3': 3 };

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
function arteEstendidaCartaDoSlot(obra, slot) {
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

/* ---------- Tela: lista das 90 obras ---------- */

let arteEstendidaBusca = '';

function arteEstendidaMiniatura(cardId) {
  const card = cardMap.get(cardId);
  if (!card) return '<span class="card-placeholder">TCG</span>';
  const arte = cardGridImage(card, null);
  return arte ? `<img src="${esc(arte)}" alt="" loading="lazy">` : '<span class="card-placeholder">TCG</span>';
}

function arteEstendidaListaFiltrada() {
  const obras = window.__ARTE_ESTENDIDA__ || [];
  const termo = normalize(arteEstendidaBusca);
  if (!termo) return obras;
  return obras.filter(obra => normalize(`${obra.nome} ${obra.colecao}`).includes(termo));
}

function renderArteEstendidaLista() {
  const obras = arteEstendidaListaFiltrada();
  const linhas = obras.map(obra => {
    const progresso = arteEstendidaProgressoDaObra(obra);
    const pct = progresso.total ? Math.round((progresso.tenho / progresso.total) * 100) : 0;
    const primeiros = obra.cartas.slice(0, 3).map(slot => arteEstendidaCartaDoSlot(obra, slot).cardId).filter(Boolean);
    return `<button type="button" class="arte-estendida-item${progresso.completa ? ' completa' : ''}" onclick="abrirObraArteEstendida(${obra.id})">
      <span class="arte-estendida-item-tiras">${primeiros.length
        ? primeiros.map(id => `<span class="arte-estendida-mini">${arteEstendidaMiniatura(id)}</span>`).join('')
        : '<span class="arte-estendida-mini vazia">◈</span>'}</span>
      <span class="arte-estendida-item-texto">
        <strong>${esc(obra.nome)}</strong>
        <small>${esc(obra.colecao)}</small>
        <span class="arte-estendida-barra"><i style="width:${pct}%"></i></span>
      </span>
      <span class="arte-estendida-item-conta">${progresso.completa ? '✓' : `${progresso.tenho}/${progresso.total}`}</span>
    </button>`;
  }).join('');

  const resumo = resumoArteEstendida();
  return `
    <button class="modal-close" onclick="closeModal()" aria-label="Fechar">×</button>
    <h2>🖼 Arte Estendida</h2>
    <p class="screen-subtitle">Obras que só aparecem inteiras quando duas ou mais cartas ficam lado a lado. ${resumo ? `${resumo.completas} de ${resumo.totalObras} obras completas na sua coleção.` : ''}</p>
    <label class="vision-search arte-estendida-busca"><span>${tabIcon('pokedex')}</span>
      <input value="${esc(arteEstendidaBusca)}" placeholder="Buscar obra ou coleção..." autocomplete="off"
        oninput="arteEstendidaBusca=this.value;atualizarArteEstendidaLista()"></label>
    <div class="arte-estendida-lista">${linhas || '<div class="empty">Nenhuma obra encontrada com esse nome.</div>'}</div>
    <p class="arte-estendida-fonte">Fonte: estudo "Artes conectadas Pokémon TCG" (levantamento Deck Certo). Cartas físicas em inglês; Pocket excluído. Números não confirmados na fonte não entram como certos — a carta é vinculada à mão quando isso acontece.</p>`;
}

function abrirArteEstendida() {
  arteEstendidaBusca = '';
  showModal(renderArteEstendidaLista(), 'arte-estendida-sheet');
}

function atualizarArteEstendidaLista() {
  const sheet = document.getElementById('modal-content');
  if (!sheet || !sheet.classList.contains('arte-estendida-sheet')) return;
  const foco = sheet.querySelector('.arte-estendida-busca input') === document.activeElement;
  const posicaoCursor = foco ? sheet.querySelector('.arte-estendida-busca input').selectionStart : null;
  sheet.innerHTML = renderArteEstendidaLista();
  if (foco) {
    const campo = sheet.querySelector('.arte-estendida-busca input');
    campo?.focus();
    if (campo && posicaoCursor != null) campo.setSelectionRange(posicaoCursor, posicaoCursor);
  }
}

/* ---------- Tela: uma obra, cartas lado a lado ---------- */

function arteEstendidaSlotHtml(obra, slot) {
  const [ordem, nomeIngles, numeroBruto] = slot;
  const { cardId, manual, setIds } = arteEstendidaCartaDoSlot(obra, slot);
  const card = cardId ? cardMap.get(cardId) : null;
  if (card) {
    const arte = card.imageUrl ? upgradeCardImageUrl(card.imageUrl) : cardGridImage(card, null);
    const tem = quantityFor(card.id) > 0;
    return `<button type="button" class="arte-estendida-slot ${tem ? 'tem' : 'falta'}"
        onclick="openCard('${esc(card.id)}')" title="${esc(card.name)} · ${esc(card.setName)}">
      ${arte ? `<img src="${esc(arte)}" alt="Arte de ${esc(card.name)}" loading="lazy">` : '<span class="card-placeholder">TCG</span>'}
      <span class="arte-estendida-selo ${tem ? 'ok' : ''}" aria-hidden="true">${tem ? '✓' : '✕'}</span>
      ${manual ? '<span class="arte-estendida-manual" title="Vinculada à mão">✎</span>' : ''}
    </button>`;
  }
  // Sem vínculo: a coleção pode nem estar catalogada ainda, ou o casamento
  // automático não achou candidato único — os dois casos abrem o vínculo à
  // mão, que também serve para digitar/buscar quando a coleção já existe.
  const semColecao = !setIds || !setIds.length;
  return `<button type="button" class="arte-estendida-slot vazio" onclick="abrirVinculoArteEstendida(${obra.id},${ordem})">
    <span class="arte-estendida-slot-nome">${esc(nomeIngles)}</span>
    <span class="arte-estendida-slot-ajuda">${semColecao ? 'Coleção ainda não catalogada' : 'Toque para vincular'}</span>
    ${numeroBruto ? `<span class="arte-estendida-slot-numero">${esc(numeroBruto)}</span>` : '<span class="arte-estendida-slot-numero fraco">nº não confirmado</span>'}
  </button>`;
}

function renderObraArteEstendida(obraId) {
  const obra = (window.__ARTE_ESTENDIDA__ || []).find(o => o.id === obraId);
  if (!obra) return '<button class="modal-close" onclick="closeModal()">×</button><h2>Obra não encontrada</h2>';
  const colunas = ARTE_ESTENDIDA_COLUNAS[obra.encaixe] ?? 0;
  const classeEncaixe = colunas === 1 ? 'coluna' : colunas ? 'grade' : 'linha';
  const progresso = arteEstendidaProgressoDaObra(obra);
  const slots = obra.cartas.map(slot => arteEstendidaSlotHtml(obra, slot)).join('');

  return `
    <button type="button" class="gaveta-voltar arte-estendida-voltar" onclick="abrirArteEstendida()" aria-label="Voltar para a lista">‹</button>
    <button class="modal-close" onclick="closeModal()" aria-label="Fechar">×</button>
    <h2>${esc(obra.nome)}</h2>
    <p class="screen-subtitle">${esc(obra.colecao)} · ${progresso.tenho} de ${progresso.total} cartas já na sua coleção${progresso.ligadas < progresso.total ? ` · ${progresso.total - progresso.ligadas} sem vínculo ainda` : ''}</p>
    <div class="arte-estendida-obra ${classeEncaixe}" style="${colunas > 1 ? `--arte-colunas:${colunas};` : ''}">${slots}</div>
    <p class="arte-estendida-dica">✓ verde = já está na sua coleção · ✕ = ainda falta · toque numa carta reconhecida para abri-la, ou num quadro vazio para vincular a carta certa.</p>`;
}

function abrirObraArteEstendida(obraId) {
  showModal(renderObraArteEstendida(obraId), 'arte-estendida-sheet arte-estendida-obra-sheet');
}

/* ---------- Vincular à mão ----------
   Mesma ideia da busca do scanner: digita nome ou número, escolhe da lista.
   A escolha fica salva (arteEstendidaSalvarVinculo) — não precisa repetir. */
let arteEstendidaVinculoAlvo = null; // {obraId, ordem}

function abrirVinculoArteEstendida(obraId, ordem) {
  const obra = (window.__ARTE_ESTENDIDA__ || []).find(o => o.id === obraId);
  const slot = obra?.cartas.find(s => s[0] === ordem);
  if (!obra || !slot) return;
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
  if (sheet) { sheet.innerHTML = html; sheet.className = 'modal-sheet arte-estendida-sheet arte-estendida-obra-sheet'; }
  else showModal(html, 'arte-estendida-sheet arte-estendida-obra-sheet');
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
