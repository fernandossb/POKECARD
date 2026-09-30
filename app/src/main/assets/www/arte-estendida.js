/* Arte Estendida — obras que se completam entre duas ou mais cartas.

   Duas origens, mostradas juntas:

   · Obras do estudo (data/arte-estendida-data.js): só entram as que o app
     consegue montar COM CERTEZA — toda carta com número impresso conhecido,
     encontrada no catálogo deste aparelho por esse número, com imagem, e do
     Pokémon que o estudo diz. Qualquer dúvida exclui a obra inteira: obra
     montada pela metade, ou com a carta comum no lugar da ilustração
     especial, era pior do que não mostrar. Quando o catálogo crescer
     (Buscar cartas e coleções novas), mais obras passam nesse filtro sozinhas.

   · Obras criadas por você: nome, encaixe e as cartas escolhidas na ordem.
     Ficam no estado da coleção (state.artesEstendidasProprias) — gravadas no
     aparelho e incluídas no backup, como decks e produtos.

   O "tenho" de cada carta vem direto da coleção (quantityFor): não existe
   uma lista paralela de posse só para esta tela. */

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

// Encaixes oferecidos na criação — o mesmo formato "largura × altura" do estudo.
const ARTE_ESTENDIDA_ENCAIXES = [
  ['Horizontal', 'Lado a lado'],
  ['Vertical', 'Uma sobre a outra'],
  ['2×2', 'Grade 2×2'],
  ['3×2', 'Grade 3×2'],
  ['3×3', 'Grade 3×3'],
];
const ARTE_ESTENDIDA_MAX_CARTAS = 12;

/* Como as cartas se encaixam. "Horizontal" é uma fileira com todas, na
   ordem; "Vertical", uma coluna, a primeira em cima; "3×2", "3×3" etc. são
   largura × altura — N por fileira, da esquerda para a direita, de cima para
   baixo. `razao` é largura/altura da obra inteira (cada carta tem 63 × 88
   mm): é ela que deixa a arte do tamanho certo antes de as imagens chegarem,
   sem a lista "pular" enquanto carrega. */
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
   neste aparelho (`cardsBySet`, o mesmo índice do resto do app). */
function arteEstendidaSetIdsCandidatos(colecaoTexto) {
  const partes = String(colecaoTexto || '').split(' / ');
  const ids = new Set();
  for (const parte of partes) {
    const id = ARTE_ESTENDIDA_NOME_PARA_SET[arteEstendidaNormalizarNomeSet(parte)];
    if (id && cardsBySet.get(id)?.length) ids.add(id);
  }
  return [...ids];
}

/* Acha a carta SÓ pelo número impresso (fração "165/162" ou código de promo
   "SWSH061"). Sem palpite por nome: era assim que a obra ganhava a versão
   comum no lugar da ilustração especial. E o número tem de levar ao Pokémon
   certo — conferido: "SWSH015" apontava para Cinderace V, não Scorbunny. */
function arteEstendidaResolverPorNumero(setIds, nomeIngles, numeroBruto) {
  const numero = String(numeroBruto || '').trim();
  if (!setIds.length || !numero) return null;
  const pool = setIds.flatMap(id => cardsBySet.get(id) || []);
  let candidatos;
  const fracao = numero.match(/^0*(\d+)\/\d+$/);
  if (fracao) {
    candidatos = pool.filter(c => String(Number(String(c.localId || '').match(/\d+/)?.[0] ?? -1)) === fracao[1]);
    if (candidatos.length > 1) candidatos = candidatos.filter(c => normalize(c.name) === normalize(nomeIngles));
  } else {
    const alvo = normalize(numero).replace(/\s+/g, '');
    candidatos = pool.filter(c => normalize(String(c.localId || '')).replace(/\s+/g, '') === alvo);
  }
  if (candidatos.length !== 1) return null;
  const card = candidatos[0];
  const esperado = inferPokemonIds(nomeIngles);
  const achado = pokemonIdsForCard(card);
  if (esperado.length && achado.length && !esperado.some(id => achado.includes(id))) return null;
  return card;
}

/* As obras do estudo que passam no filtro, já com a carta de cada posição.
   Só muda com catálogo novo — fica guardado até lá. */
let arteEstendidaEstudoCache = { carimbo: '', obras: [] };
function arteEstendidaObrasDoEstudo() {
  const carimbo = `${cards.length}|${cardMap.size}`;
  if (arteEstendidaEstudoCache.carimbo === carimbo) return arteEstendidaEstudoCache.obras;
  const obras = [];
  for (const obra of window.__ARTE_ESTENDIDA__ || []) {
    const setIds = arteEstendidaSetIdsCandidatos(obra.colecao);
    const cardIds = [];
    for (const [, nomeIngles, numero] of obra.cartas) {
      const card = arteEstendidaResolverPorNumero(setIds, nomeIngles, numero);
      if (!card || !cardGridImage(card, null)) break;
      cardIds.push(card.id);
    }
    if (cardIds.length === obra.cartas.length) obras.push({ ...obra, id: `e${obra.id}`, cardIds, propria: false });
  }
  arteEstendidaEstudoCache = { carimbo, obras };
  return obras;
}

/* ---------- Obras criadas por você ---------- */

function arteEstendidaListaPropria() {
  if (!Array.isArray(state.artesEstendidasProprias)) state.artesEstendidasProprias = [];
  return state.artesEstendidasProprias;
}

// A obra salva, no mesmo formato das do estudo (as telas não distinguem).
function arteEstendidaObraPropriaParaExibir(salva) {
  const cardIds = (Array.isArray(salva.cardIds) ? salva.cardIds : []).filter(Boolean);
  const colecoes = [...new Set(cardIds.map(id => cardMap.get(id)?.setName).filter(Boolean))];
  return {
    id: salva.id, nome: salva.nome, encaixe: salva.encaixe || 'Horizontal', propria: true,
    colecao: colecoes.join(' / ') || 'Cartas escolhidas por você',
    cardIds,
    cartas: cardIds.map((id, i) => [i + 1, cardMap.get(id)?.name || 'Carta fora do catálogo', cardMap.get(id)?.number || '']),
  };
}

// As suas primeiro, das mais novas para as mais antigas; depois as do estudo.
function arteEstendidaTodasAsObras() {
  const proprias = arteEstendidaListaPropria().map(arteEstendidaObraPropriaParaExibir).reverse();
  return [...proprias, ...arteEstendidaObrasDoEstudo()];
}

function arteEstendidaObraPorId(obraId) {
  return arteEstendidaTodasAsObras().find(obra => String(obra.id) === String(obraId)) || null;
}

function arteEstendidaProgressoDaObra(obra) {
  const ligadas = obra.cardIds.filter(id => cardMap.has(id));
  const tenho = ligadas.filter(id => quantityFor(id) > 0).length;
  const total = obra.cartas.length;
  return { tenho, ligadas: ligadas.length, total, completa: total > 0 && ligadas.length === total && tenho === total };
}

// Teaser para o menu "Mais": "4 de 25 cartas já na coleção · 11 obras".
function resumoArteEstendida() {
  const obras = arteEstendidaTodasAsObras();
  if (!obras.length) return null;
  let tenho = 0, ligadas = 0, completas = 0;
  for (const obra of obras) {
    const p = arteEstendidaProgressoDaObra(obra);
    tenho += p.tenho; ligadas += p.ligadas;
    if (p.completa) completas++;
  }
  return { totalObras: obras.length, tenho, ligadas, completas };
}

/* ---------- A arte montada: as cartas no encaixe certo ----------

   Uma grade só serve para todos os encaixes: `colunas` diz quantas por
   fileira, e a proporção da obra inteira fica declarada no CSS — nada muda
   de tamanho quando a imagem chega, e é isso que permite a lista voltar
   exatamente para onde estava. Na lista a arte é só imagem (o cartão inteiro
   abre a obra); dentro da obra cada carta é um botão que abre a carta. */
function arteEstendidaImagemDaCarta(card, grande) {
  const url = grande && card.imageUrl ? upgradeCardImageUrl(card.imageUrl) : cardGridImage(card, null);
  return url
    ? `<img src="${esc(url)}" alt="${esc(card.name)}" loading="lazy" decoding="async" onerror="this.outerHTML='<span class=&quot;card-placeholder&quot;>TCG</span>'">`
    : '<span class="card-placeholder">TCG</span>';
}

function arteEstendidaCelula(obra, indice, interativa, grande) {
  const cardId = obra.cardIds[indice];
  const card = cardId ? cardMap.get(cardId) : null;
  if (!card) {
    // Só acontece com obra sua cuja carta saiu do catálogo deste aparelho.
    return `<span class="arte-celula vazio"><span class="arte-estendida-slot-nome">${esc(obra.cartas[indice]?.[1] || 'Carta')}</span></span>`;
  }
  const tem = quantityFor(card.id) > 0;
  // Na lista a arte fica limpa: o que falta já aparece em cinza, como na
  // Coleção. Dentro da obra entram os selos ✓/✕.
  const miolo = `${arteEstendidaImagemDaCarta(card, grande)}${interativa
    ? `<span class="arte-estendida-selo ${tem ? 'ok' : ''}" aria-hidden="true">${tem ? '✓' : '✕'}</span>` : ''}`;
  return interativa
    ? `<button type="button" class="arte-celula ${tem ? 'tem' : 'falta'}" onclick="abrirCartaDaObraArteEstendida('${esc(obra.id)}','${esc(card.id)}')" title="${esc(card.name)} · ${esc(card.setName)}" aria-label="${esc(card.name)}${tem ? ' (você tem)' : ' (falta)'}">${miolo}</button>`
    : `<span class="arte-celula ${tem ? 'tem' : 'falta'}">${miolo}</span>`;
}

function arteEstendidaComposicao(obra, { interativa = false, grande = false } = {}) {
  const g = arteEstendidaGeometria(obra);
  const celulas = obra.cartas.map((_, i) => arteEstendidaCelula(obra, i, interativa, grande)).join('');
  return `<div class="arte-composicao ${g.tipo}" style="--arte-colunas:${g.colunas};--arte-linhas:${g.linhas};--arte-razao:${g.razao.toFixed(4)}">${celulas}</div>`;
}

/* ---------- Tela: lista das obras ----------

   Cartões no estilo da Coleção: a arte completa em cima, na largura toda, e
   uma descrição curta embaixo. A lista lembra a busca e o ponto da rolagem:
   entrar numa obra (ou numa carta aberta a partir dela) e voltar devolve
   você exatamente onde estava. */
let arteEstendidaBusca = '';
let arteEstendidaRolagemLista = 0;
let arteEstendidaRolagemObra = { obraId: null, topo: 0 };

function arteEstendidaFolha() {
  return document.getElementById('modal-content');
}

function arteEstendidaListaFiltrada() {
  const obras = arteEstendidaTodasAsObras();
  const termo = normalize(arteEstendidaBusca);
  if (!termo) return obras;
  return obras.filter(obra => normalize(`${obra.nome} ${obra.colecao}`).includes(termo));
}

function arteEstendidaCartaoDaObra(obra) {
  const p = arteEstendidaProgressoDaObra(obra);
  const pct = p.total ? Math.round((p.tenho / p.total) * 100) : 0;
  const g = arteEstendidaGeometria(obra);
  return `<button type="button" class="arte-obra-cartao${p.completa ? ' completa' : ''}" data-obra-id="${esc(obra.id)}" onclick="abrirObraArteEstendida('${esc(obra.id)}')">
    <span class="arte-obra-cartao-arte">${arteEstendidaComposicao(obra)}</span>
    <span class="arte-obra-cartao-texto">
      <strong>${esc(obra.nome)}${obra.propria ? ' <span class="arte-obra-tag">Sua obra</span>' : ''}</strong>
      <small>${esc(obra.colecao)}</small>
      <span class="arte-obra-cartao-progresso">
        <span class="arte-estendida-barra"><i style="width:${pct}%"></i></span>
        <b>${p.completa ? '✓ completa' : `${p.tenho}/${p.total}`}</b>
      </span>
      <small class="arte-obra-cartao-encaixe">${esc(g.rotulo)} · ${p.total} cartas</small>
    </span>
  </button>`;
}

function arteEstendidaListaHtml() {
  const obras = arteEstendidaListaFiltrada();
  if (obras.length) return obras.map(arteEstendidaCartaoDaObra).join('');
  return arteEstendidaBusca
    ? '<div class="empty">Nenhuma obra encontrada com esse nome.</div>'
    : '<div class="empty">Nenhuma obra ainda. Toque em "Criar obra" para montar a primeira.</div>';
}

function renderArteEstendidaLista() {
  const resumo = resumoArteEstendida();
  return `
    <button class="modal-close" onclick="closeModal()" aria-label="Fechar">×</button>
    <h2>🖼 Arte Estendida</h2>
    <p class="screen-subtitle">Obras que só aparecem inteiras quando duas ou mais cartas ficam lado a lado.${resumo ? ` ${resumo.completas} de ${resumo.totalObras} obras completas na sua coleção.` : ''}</p>
    <button type="button" class="primary-btn arte-estendida-criar" onclick="criarObraArteEstendida()">＋ Criar obra</button>
    <label class="vision-search arte-estendida-busca"><span>${tabIcon('pokedex')}</span>
      <input value="${esc(arteEstendidaBusca)}" placeholder="Buscar obra ou coleção..." autocomplete="off"
        oninput="arteEstendidaBusca=this.value;atualizarArteEstendidaLista()"></label>
    <div class="arte-estendida-lista">${arteEstendidaListaHtml()}</div>
    <p class="arte-estendida-fonte">Além das suas, aparecem as obras do estudo "Artes conectadas Pokémon TCG" (levantamento Deck Certo) que o app consegue montar com certeza: toda carta com número confirmado, presente no catálogo deste aparelho, com imagem e do Pokémon certo. As demais não entram — crie a sua quando conhecer as cartas.</p>`;
}

// Guarda a rolagem da tela que está saindo: a lista ou uma obra.
function arteEstendidaGuardarRolagem() {
  const folha = arteEstendidaFolha();
  if (!folha || document.getElementById('modal')?.classList.contains('hidden')) return;
  if (folha.classList.contains('arte-estendida-lista-sheet')) arteEstendidaRolagemLista = folha.scrollTop;
  else if (folha.classList.contains('arte-estendida-detalhe-sheet') && folha.dataset.obraId) {
    arteEstendidaRolagemObra = { obraId: folha.dataset.obraId, topo: folha.scrollTop };
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
  if (lista) lista.innerHTML = arteEstendidaListaHtml();
}

function voltarParaListaArteEstendida() {
  arteEstendidaRolagemObra = { obraId: null, topo: 0 };
  arteEstendidaRascunho = null;
  showModal(renderArteEstendidaLista(), 'arte-estendida-sheet arte-estendida-lista-sheet');
  arteEstendidaRolarPara(arteEstendidaRolagemLista);
}

/* ---------- Tela: uma obra ---------- */

function renderObraArteEstendida(obraId) {
  const obra = arteEstendidaObraPorId(obraId);
  if (!obra) return '<button class="modal-close" onclick="closeModal()">×</button><h2>Obra não encontrada</h2>';
  const p = arteEstendidaProgressoDaObra(obra);
  const g = arteEstendidaGeometria(obra);
  return `
    <button type="button" class="gaveta-voltar arte-estendida-voltar" onclick="voltarParaListaArteEstendida()" aria-label="Voltar para a lista">‹</button>
    <button class="modal-close" onclick="closeModal()" aria-label="Fechar">×</button>
    <h2>${esc(obra.nome)}</h2>
    <p class="screen-subtitle">${obra.propria ? 'Sua obra · ' : ''}${esc(obra.colecao)} · ${esc(g.rotulo)} · ${p.tenho} de ${p.total} cartas já na sua coleção</p>
    <div class="arte-obra-detalhe">${arteEstendidaComposicao(obra, { interativa: true, grande: true })}</div>
    <p class="arte-estendida-dica">✓ verde = já está na sua coleção · ✕ = ainda falta · toque numa carta para abri-la.</p>
    ${obra.propria ? `<div class="modal-actions"><button type="button" class="secondary-btn" onclick="editarObraArteEstendida('${esc(obra.id)}')">✎ Editar obra</button></div>` : ''}`;
}

function abrirObraArteEstendida(obraId) {
  const id = String(obraId);
  arteEstendidaGuardarRolagem();
  arteEstendidaRascunho = null;
  showModal(renderObraArteEstendida(id), 'arte-estendida-sheet arte-estendida-obra-sheet arte-estendida-detalhe-sheet');
  const folha = arteEstendidaFolha();
  if (folha) folha.dataset.obraId = id;
  // Voltando de uma carta ou do editor desta mesma obra: onde estava. Obra
  // aberta a partir da lista: do começo.
  arteEstendidaRolarPara(arteEstendidaRolagemObra.obraId === id ? arteEstendidaRolagemObra.topo : 0);
  arteEstendidaRolagemObra = { obraId: null, topo: 0 };
}

/* ---------- Carta aberta a partir de uma obra ----------

   O cadastro da carta é o mesmo de sempre (openCard). O que muda é a volta:
   um ‹ no canto leva de novo à obra, na mesma posição — e o Voltar do
   celular faz o mesmo. O × continua fechando tudo, como em qualquer painel. */
let arteEstendidaCartaAberta = null; // { obraId, cardId }

function abrirCartaDaObraArteEstendida(obraId, cardId) {
  arteEstendidaGuardarRolagem();
  arteEstendidaCartaAberta = { obraId: String(obraId), cardId };
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

/* ---------- Criar e editar uma obra ----------

   Nome, encaixe e as cartas na ordem em que se encaixam (da esquerda para a
   direita, de cima para baixo). A prévia mostra a obra montada enquanto você
   escolhe. O rascunho só vira obra ao tocar em "Salvar". */
let arteEstendidaRascunho = null; // { id|null, nome, encaixe, cardIds }

function criarObraArteEstendida() {
  arteEstendidaGuardarRolagem();
  arteEstendidaRascunho = { id: null, nome: '', encaixe: 'Horizontal', cardIds: [] };
  abrirEditorArteEstendida();
}

function editarObraArteEstendida(obraId) {
  const salva = arteEstendidaListaPropria().find(item => String(item.id) === String(obraId));
  if (!salva) return;
  arteEstendidaGuardarRolagem();
  arteEstendidaRascunho = { id: salva.id, nome: salva.nome || '', encaixe: salva.encaixe || 'Horizontal', cardIds: [...(salva.cardIds || [])] };
  abrirEditorArteEstendida();
}

function arteEstendidaVoltarDoEditor() {
  const id = arteEstendidaRascunho?.id;
  arteEstendidaRascunho = null;
  if (id) abrirObraArteEstendida(id);
  else voltarParaListaArteEstendida();
}

function abrirEditorArteEstendida() {
  const r = arteEstendidaRascunho;
  if (!r) return;
  showModal(`
    <button type="button" class="gaveta-voltar arte-estendida-voltar" onclick="arteEstendidaVoltarDoEditor()" aria-label="Voltar">‹</button>
    <button class="modal-close" onclick="closeModal()" aria-label="Fechar">×</button>
    <h2>${r.id ? 'Editar obra' : 'Nova obra'}</h2>
    <p class="screen-subtitle">Escolha as cartas na ordem em que elas se encaixam: da esquerda para a direita e de cima para baixo.</p>
    <label class="registration-field arte-editor-campo"><span>Nome da obra</span>
      <input id="arteEditorNome" class="field" value="${esc(r.nome)}" placeholder="Ex.: Kyogre e Groudon" autocomplete="off" maxlength="80"
        oninput="arteEstendidaRascunho.nome=this.value"></label>
    <div class="registration-field arte-editor-campo"><span>Como as cartas se encaixam</span>
      <div id="arteEditorEncaixes" class="chips arte-editor-encaixes"></div></div>
    <div id="arteEditorPrevia" class="arte-editor-previa"></div>
    <div id="arteEditorCartas" class="arte-editor-cartas"></div>
    <label class="registration-field arte-editor-campo"><span>Acrescentar carta</span>
      <input id="arteEditorBusca" class="field" placeholder="Nome, coleção, raridade ou nº (ex.: 165/162)" autocomplete="off"
        oninput="arteEstendidaBuscarParaEditor(this.value)"></label>
    <div id="arteEditorResultados" class="busca-manual-lista"></div>
    <div class="modal-actions">
      <button id="arteEditorSalvar" type="button" class="primary-btn" onclick="salvarObraArteEstendida()">Salvar obra</button>
      ${r.id ? `<button type="button" class="danger-btn" onclick="apagarObraArteEstendida('${esc(r.id)}')">Apagar obra</button>` : ''}
    </div>`, 'arte-estendida-sheet arte-estendida-obra-sheet arte-estendida-editor-sheet');
  arteEstendidaAtualizarEditor();
}

function arteEstendidaObraDoRascunho() {
  const r = arteEstendidaRascunho;
  return {
    id: 'rascunho', nome: r.nome, encaixe: r.encaixe, propria: true, cardIds: r.cardIds,
    cartas: r.cardIds.map((id, i) => [i + 1, cardMap.get(id)?.name || 'Carta', cardMap.get(id)?.number || '']),
  };
}

// Redesenha prévia, lista e encaixes — nunca os campos de texto (o teclado
// não se perde no meio da digitação).
function arteEstendidaAtualizarEditor() {
  const r = arteEstendidaRascunho;
  if (!r) return;
  const encaixes = document.getElementById('arteEditorEncaixes');
  if (encaixes) {
    encaixes.innerHTML = ARTE_ESTENDIDA_ENCAIXES.map(([valor, rotulo]) =>
      `<button type="button" class="chip${r.encaixe === valor ? ' active' : ''}" onclick="arteEstendidaEscolherEncaixe('${valor}')">${esc(rotulo)}</button>`).join('');
  }
  const previa = document.getElementById('arteEditorPrevia');
  if (previa) {
    previa.innerHTML = r.cardIds.length
      ? arteEstendidaComposicao(arteEstendidaObraDoRascunho(), { grande: r.cardIds.length <= 2 })
      : '<p class="arte-editor-vazio">As cartas escolhidas aparecem aqui, já montadas no encaixe.</p>';
  }
  const lista = document.getElementById('arteEditorCartas');
  if (lista) {
    lista.innerHTML = r.cardIds.map((id, i) => {
      const card = cardMap.get(id);
      const imagem = card ? cardGridImage(card, null) : '';
      return `<div class="arte-editor-carta">
        <b class="arte-editor-ordem">${i + 1}</b>
        ${imagem ? `<img src="${esc(imagem)}" alt="" loading="lazy">` : '<span class="card-placeholder">TCG</span>'}
        <span class="arte-editor-carta-texto"><strong>${esc(card?.name || 'Carta fora do catálogo')}</strong><small>${card ? `${esc(formatCardNumber(card.localId || card.number, card.setTotal))} · ${esc(card.setName)}` : ''}</small></span>
        <span class="arte-editor-acoes">
          <button type="button" ${i === 0 ? 'disabled' : ''} onclick="arteEstendidaMoverNoEditor(${i},-1)" aria-label="Mover para antes">↑</button>
          <button type="button" ${i === r.cardIds.length - 1 ? 'disabled' : ''} onclick="arteEstendidaMoverNoEditor(${i},1)" aria-label="Mover para depois">↓</button>
          <button type="button" onclick="arteEstendidaTirarDoEditor(${i})" aria-label="Tirar da obra">✕</button>
        </span>
      </div>`;
    }).join('');
  }
  const salvar = document.getElementById('arteEditorSalvar');
  if (salvar) {
    salvar.disabled = r.cardIds.length < 2;
    salvar.textContent = r.cardIds.length < 2 ? 'Escolha pelo menos 2 cartas' : 'Salvar obra';
  }
}

function arteEstendidaEscolherEncaixe(valor) {
  if (!arteEstendidaRascunho) return;
  arteEstendidaRascunho.encaixe = valor;
  arteEstendidaAtualizarEditor();
}

/* Busca por palavras (nome, coleção, raridade, artista — todas precisam
   bater) e, se houver, por número impresso completo ("165/162"). */
function arteEstendidaBuscarCartas(termo, limite = 20) {
  const texto = String(termo || '').trim();
  if (texto.length < 2) return [];
  const fracao = texto.match(/(\d{1,4})\s*\/\s*(\d{1,4})/);
  const palavras = normalize(fracao ? texto.replace(fracao[0], ' ') : texto).split(/\s+/).filter(Boolean);
  const achados = [];
  for (const card of cards) {
    if (fracao) {
      const local = String(Number(String(card.localId || '').match(/\d+/)?.[0] ?? -1));
      const total = String(Number(card.setTotal || String(card.number || '').split('/')[1] || -1));
      if (local !== String(Number(fracao[1])) || total !== String(Number(fracao[2]))) continue;
    }
    const indice = cardSearchIndex.get(card.id) || '';
    if (!palavras.every(p => indice.includes(p))) continue;
    achados.push(card);
    if (achados.length >= limite) break;
  }
  return achados;
}

function arteEstendidaBuscarParaEditor(termo) {
  const lista = document.getElementById('arteEditorResultados');
  if (!lista || !arteEstendidaRascunho) return;
  if (String(termo || '').trim().length < 2) { lista.innerHTML = ''; return; }
  const achados = arteEstendidaBuscarCartas(termo);
  const escolhidas = new Set(arteEstendidaRascunho.cardIds);
  lista.innerHTML = achados.length
    ? achados.map(card => `<button type="button" class="busca-manual-item${escolhidas.has(card.id) ? ' tenho' : ''}" onclick="arteEstendidaAcrescentarNoEditor('${esc(card.id)}')">
        ${cardGridImage(card, null) ? `<img src="${esc(cardGridImage(card, null))}" alt="" loading="lazy">` : '<span class="card-placeholder">TCG</span>'}
        <span><strong>${esc(card.name)}</strong><small>${esc(formatCardNumber(card.localId || card.number, card.setTotal))} · ${esc(card.setName)}</small></span>
        ${escolhidas.has(card.id) ? '<b class="produto-tenho">✓ na obra</b>' : ''}
      </button>`).join('')
    : '<p class="busca-manual-dica">Nenhuma carta encontrada.</p>';
}

function arteEstendidaAcrescentarNoEditor(cardId) {
  const r = arteEstendidaRascunho;
  if (!r || !cardMap.has(cardId)) return;
  if (r.cardIds.includes(cardId)) return notify('Esta carta já está na obra.');
  if (r.cardIds.length >= ARTE_ESTENDIDA_MAX_CARTAS) return notify(`Uma obra pode ter até ${ARTE_ESTENDIDA_MAX_CARTAS} cartas.`);
  r.cardIds.push(cardId);
  vibrar();
  arteEstendidaAtualizarEditor();
  // A lista de resultados fica: dá para escolher a próxima da mesma coleção.
  arteEstendidaBuscarParaEditor(document.getElementById('arteEditorBusca')?.value || '');
}

function arteEstendidaMoverNoEditor(indice, direcao) {
  const lista = arteEstendidaRascunho?.cardIds;
  const destino = indice + direcao;
  if (!lista || destino < 0 || destino >= lista.length) return;
  [lista[indice], lista[destino]] = [lista[destino], lista[indice]];
  arteEstendidaAtualizarEditor();
}

function arteEstendidaTirarDoEditor(indice) {
  const r = arteEstendidaRascunho;
  if (!r) return;
  r.cardIds.splice(indice, 1);
  arteEstendidaAtualizarEditor();
  arteEstendidaBuscarParaEditor(document.getElementById('arteEditorBusca')?.value || '');
}

// "Kyogre e Groudon", "Mew, Pidgeot e Onix" — quando o nome fica em branco.
function arteEstendidaNomeAutomatico(cardIds) {
  const nomes = cardIds.map(id => cardMap.get(id)?.name).filter(Boolean);
  return nomes.length > 1 ? `${nomes.slice(0, -1).join(', ')} e ${nomes[nomes.length - 1]}` : (nomes[0] || 'Minha obra');
}

function salvarObraArteEstendida() {
  const r = arteEstendidaRascunho;
  if (!r) return;
  if (r.cardIds.length < 2) return notify('Escolha pelo menos duas cartas.');
  const nome = String(r.nome || '').trim() || arteEstendidaNomeAutomatico(r.cardIds);
  const lista = arteEstendidaListaPropria();
  let id = r.id;
  const existente = id ? lista.find(item => String(item.id) === String(id)) : null;
  if (existente) {
    Object.assign(existente, { nome, encaixe: r.encaixe, cardIds: [...r.cardIds], atualizadaEm: new Date().toISOString() });
  } else {
    id = `m-${Date.now().toString(36)}`;
    lista.push({ id, nome, encaixe: r.encaixe, cardIds: [...r.cardIds], criadaEm: new Date().toISOString() });
  }
  saveState();
  arteEstendidaRascunho = null;
  vibrar();
  abrirObraArteEstendida(id);
  notify(existente ? 'Obra atualizada.' : 'Obra criada.');
}

function apagarObraArteEstendida(obraId) {
  const lista = arteEstendidaListaPropria();
  const salva = lista.find(item => String(item.id) === String(obraId));
  if (!salva || !window.confirm(`Apagar a obra "${salva.nome}"? As cartas continuam na sua coleção.`)) return;
  state.artesEstendidasProprias = lista.filter(item => item !== salva);
  saveState();
  voltarParaListaArteEstendida();
  notify('Obra apagada.');
}
