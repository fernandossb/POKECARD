/* Energias básicas — achar e escolher a tiragem certa.

   Energia básica é a carta mais difícil de cadastrar: as atuais não têm
   número impresso, e a mesma "Energia de Água" existe em várias coleções e
   reimpressões quase idênticas — com preços diferentes. Buscar pelo número
   não acha (o número do catálogo não está na carta) e buscar pelo nome traz
   uma lista de iguais sem dizer qual é qual.

   Aqui a pessoa escolhe o tipo e vê cada tiragem com o que realmente a
   diferencia: coleção, ano, raridade, se tem número impresso, e o preço de
   cada versão (Comum, Reverse...) — com − / + ali mesmo. O resto (reverse
   especial, carimbo, condição) fica no cadastro completo da carta, a um
   toque, com volta para cá. */

const ENERGIAS_TIPOS = [
  ['grass', 'Grama'], ['fire', 'Fogo'], ['water', 'Água'], ['lightning', 'Elétrica'],
  ['psychic', 'Psíquica'], ['fighting', 'Luta'], ['darkness', 'Escuridão'],
  ['metal', 'Metal'], ['fairy', 'Fada'],
];

// Coleções de energia sem número impresso na carta (o número é só do catálogo).
const ENERGIAS_SEM_NUMERO = new Set(['sve', 'mee']);

const ENERGIAS_RARIDADE = {
  'common': 'Comum', 'uncommon': 'Incomum', 'rare': 'Rara', 'secret rare': 'Rara secreta',
  'hyper rare': 'Hiper rara', 'ultra rare': 'Ultra rara', 'illustration rare': 'Ilustração rara',
  'special illustration rare': 'Ilustração especial rara',
};

let energiasTipoAberto = null;
let energiasRolagem = { tipo: null, topo: 0 };
let energiasCartaAberta = null; // { tipo, cardId }

function energiasFolha() {
  return document.getElementById('modal-content');
}

/* Ano da tiragem. As energias de Escarlate e Violeta foram reimpressas em
   levas de 8 (uma por tipo), e o catálogo dá a todas a data da primeira.
   Conferido na Água: SVE 003, 011 e 019 saíram em 31/03/2023, 13/09/2024 e
   18/07/2025. Nas outras coleções vale a data de lançamento. */
function energiaAnoDaTiragem(card, set) {
  if (card.setId === 'sve') {
    const numero = Number(String(card.localId || '').match(/\d+/)?.[0] || 0);
    return numero <= 8 ? 2023 : numero <= 16 ? 2024 : 2025;
  }
  return Number(String(set?.releaseDate || '').slice(0, 4)) || '';
}

function energiasDoTipo(tipo) {
  const sets = new Map((catalog.sets || []).map(set => [set.id, set]));
  return cards
    .filter(card => ehEnergiaBasica(card) && tipoDaEnergiaBasica(card) === tipo)
    .map(card => ({ card, set: sets.get(card.setId), ano: energiaAnoDaTiragem(card, sets.get(card.setId)) }))
    .sort((a, b) => (Number(b.ano) || 0) - (Number(a.ano) || 0)
      || String(b.set?.releaseDate || '').localeCompare(String(a.set?.releaseDate || ''))
      || numericLocal(a.card) - numericLocal(b.card));
}

function energiasQuantasTenho(lista) {
  return lista.reduce((soma, item) => soma + quantityFor(item.card.id), 0);
}

/* ---------- Tela 1: os tipos ---------- */

function renderEnergiasTipos() {
  const botoes = ENERGIAS_TIPOS.map(([tipo, rotulo]) => {
    const lista = energiasDoTipo(tipo);
    if (!lista.length) return '';
    const tenho = energiasQuantasTenho(lista);
    return `<button type="button" class="energia-tipo" onclick="abrirEnergiasDoTipo('${tipo}')">
      <span class="energia-bolinha energia-${tipo}" aria-hidden="true"></span>
      <strong>${esc(rotulo)}</strong>
      <small>${lista.length} ${lista.length === 1 ? 'tiragem' : 'tiragens'}${tenho ? ` · você tem ${tenho}` : ''}</small>
    </button>`;
  }).join('');
  return `
    <button class="modal-close" onclick="closeModal()" aria-label="Fechar">×</button>
    <h2>Energias básicas</h2>
    <p class="screen-subtitle">Escolha o tipo e depois a tiragem. As energias atuais não têm número impresso: o que diferencia uma da outra é a coleção e o ano — e o preço muda com isso.</p>
    <div class="energias-tipos">${botoes || '<div class="empty">Nenhuma Energia básica no catálogo deste aparelho.</div>'}</div>`;
}

function abrirEnergiasBasicas() {
  energiasTipoAberto = null;
  energiasCartaAberta = null;
  showModal(renderEnergiasTipos(), 'energias-sheet energias-tipos-sheet');
}

/* ---------- Tela 2: as tiragens de um tipo ---------- */

function energiaVersaoHtml(card, linha) {
  const dono = linha.donos.find(v => Number(v.quantity) > 0);
  const preco = dono ? effectiveVariantPrice(card.id, dono) : automaticPriceQuote(card.id, linha.identidade);
  const precoTexto = preco?.brl != null ? money(preco.brl) : 'sem preço';
  const cardId = esc(card.id);
  const linhaId = esc(linha.id);
  return `<div class="energia-versao${linha.quantidade > 0 ? ' tenho' : ''}">
    <span class="energia-versao-nome">${esc(linha.titulo)}</span>
    <b class="energia-versao-preco${preco?.manual ? ' manual' : ''}">${esc(precoTexto)}</b>
    <span class="variant-quick-stepper">
      <button type="button" class="quantity-step-btn" ${linha.quantidade <= 0 ? 'disabled' : ''} onclick="ajustarEnergia('${cardId}','${linhaId}',-1)" aria-label="Diminuir ${esc(linha.titulo)} de ${esc(card.setName)}">−</button>
      <b>${linha.quantidade}</b>
      <button type="button" class="quantity-step-btn" onclick="ajustarEnergia('${cardId}','${linhaId}',1)" aria-label="Aumentar ${esc(linha.titulo)} de ${esc(card.setName)}">+</button>
    </span>
  </div>`;
}

function energiaTiragemHtml({ card, ano }) {
  const semNumero = ENERGIAS_SEM_NUMERO.has(card.setId);
  const raridade = ENERGIAS_RARIDADE[normalize(card.rarity || '')] || card.rarity || '';
  const imagem = card.imageUrl ? rebaixarParaMiniatura(upgradeCardImageUrl(card.imageUrl)) : '';
  const versoes = linhasDeCadastro(card).filter(linha => !linha.extra);
  const tem = quantityFor(card.id) > 0;
  // A fonte não tem imagem das energias mais novas (e as de reserva trazem a
  // arte de outra tiragem): no lugar, a cor do tipo — melhor que uma errada.
  const semImagem = `<span class="energia-sem-imagem"><span class="energia-bolinha energia-${esc(tipoDaEnergiaBasica(card))}"></span>sem imagem na fonte</span>`;
  return `<article class="energia-tiragem${tem ? ' tenho' : ''}" data-energia-card="${esc(card.id)}">
    <button type="button" class="energia-tiragem-arte" onclick="abrirCartaDaEnergia('${esc(card.id)}')" aria-label="Abrir ${esc(card.name)} de ${esc(card.setName)}">
      ${imagem
        ? `<img src="${esc(imagem)}" alt="" loading="lazy" decoding="async" onerror="this.outerHTML=this.dataset.semImagem" data-sem-imagem="${esc(semImagem)}">`
        : semImagem}
    </button>
    <div class="energia-tiragem-info">
      <strong>${esc(card.setName)}</strong>
      <span class="energia-tiragem-ano">${ano ? `Tiragem de ${ano}` : 'Ano não informado'}${raridade ? ` · ${esc(raridade)}` : ''}</span>
      <small>${semNumero ? 'Sem número impresso — confira o ano no rodapé da carta' : `Nº ${esc(card.number || card.localId)} impresso na carta`}</small>
      <div class="energia-versoes">${versoes.map(linha => energiaVersaoHtml(card, linha)).join('')}</div>
      <button type="button" class="energia-mais-versoes" onclick="abrirCartaDaEnergia('${esc(card.id)}')">Reverse especial, carimbo e condição ›</button>
    </div>
  </article>`;
}

function renderEnergiasDoTipo(tipo) {
  const rotulo = ENERGIAS_TIPOS.find(([valor]) => valor === tipo)?.[1] || tipo;
  const lista = energiasDoTipo(tipo);
  const tenho = energiasQuantasTenho(lista);
  return `
    <button type="button" class="gaveta-voltar energias-voltar" onclick="abrirEnergiasBasicas()" aria-label="Voltar para os tipos">‹</button>
    <button class="modal-close" onclick="closeModal()" aria-label="Fechar">×</button>
    <h2><span class="energia-bolinha energia-${tipo}" aria-hidden="true"></span> Energia de ${esc(rotulo)}</h2>
    <p class="screen-subtitle" id="energiasResumoTipo">${lista.length} ${lista.length === 1 ? 'tiragem' : 'tiragens'}, da mais nova para a mais antiga${tenho ? ` · você tem ${tenho}` : ''}.</p>
    <div class="energias-tiragens">${lista.map(energiaTiragemHtml).join('')}</div>`;
}

function abrirEnergiasDoTipo(tipo) {
  energiasTipoAberto = tipo;
  showModal(renderEnergiasDoTipo(tipo), 'energias-sheet energias-tipo-sheet');
  const folha = energiasFolha();
  if (folha) folha.scrollTop = energiasRolagem.tipo === tipo ? energiasRolagem.topo : 0;
  energiasRolagem = { tipo: null, topo: 0 };
  energiasCarregarPrecos(tipo);
}

// Os preços vêm em lotes do banco; o lote de cada tiragem é buscado aqui,
// e a tela se redesenha (no mesmo ponto) quando eles chegam.
function energiasCarregarPrecos(tipo) {
  if (!centralPriceIndex?.cards) return;
  const porLote = new Map();
  for (const { card } of energiasDoTipo(tipo)) {
    const lote = Number(centralPriceIndex.cards[card.id]);
    if (Number.isInteger(lote) && lote >= 0 && !centralPriceLoadedShards.has(lote) && !porLote.has(lote)) porLote.set(lote, card.id);
  }
  if (!porLote.size) return;
  Promise.allSettled([...porLote.values()].map(cardId => ensureCentralPriceShard(cardId))).then(() => {
    const folha = energiasFolha();
    if (energiasTipoAberto !== tipo || !folha?.classList.contains('energias-tipo-sheet')) return;
    const topo = folha.scrollTop;
    folha.innerHTML = renderEnergiasDoTipo(tipo);
    folha.scrollTop = topo;
  });
}

function ajustarEnergia(cardId, linhaId, delta) {
  if (!aplicarAjusteDeLinha(cardId, linhaId, delta)) return;
  if (delta > 0) vibrar();
  // Só a tiragem mexida é redesenhada — o resto da tela fica parado.
  const tile = document.querySelector(`[data-energia-card="${CSS.escape(cardId)}"]`);
  const card = cardMap.get(cardId);
  if (tile && card && energiasTipoAberto) {
    const item = energiasDoTipo(energiasTipoAberto).find(entrada => entrada.card.id === cardId);
    if (item) tile.outerHTML = energiaTiragemHtml(item);
    const resumo = document.getElementById('energiasResumoTipo');
    if (resumo) {
      const lista = energiasDoTipo(energiasTipoAberto);
      const tenho = energiasQuantasTenho(lista);
      resumo.textContent = `${lista.length} ${lista.length === 1 ? 'tiragem' : 'tiragens'}, da mais nova para a mais antiga${tenho ? ` · você tem ${tenho}` : ''}.`;
    }
  }
  // A grade da Coleção atrás do painel acompanha.
  refreshAfterEntryChange(cardId);
}

/* ---------- Cadastro completo, com volta para a tiragem ---------- */

function abrirCartaDaEnergia(cardId) {
  const folha = energiasFolha();
  if (folha?.classList.contains('energias-tipo-sheet')) energiasRolagem = { tipo: energiasTipoAberto, topo: folha.scrollTop };
  energiasCartaAberta = { tipo: energiasTipoAberto, cardId };
  openCard(cardId);
}

function voltarDaCartaParaEnergias() {
  const alvo = energiasCartaAberta;
  energiasCartaAberta = null;
  if (alvo?.tipo) abrirEnergiasDoTipo(alvo.tipo);
  else abrirEnergiasBasicas();
}

function energiasPorBotaoVoltarNaCarta() {
  const folha = energiasFolha();
  if (!folha || folha.querySelector('.energias-voltar-carta')) return;
  const botao = document.createElement('button');
  botao.type = 'button';
  botao.className = 'gaveta-voltar energias-voltar-carta';
  botao.setAttribute('aria-label', 'Voltar para as energias');
  botao.textContent = '‹';
  botao.addEventListener('click', voltarDaCartaParaEnergias);
  folha.prepend(botao);
}

/* Mesmo esquema da Arte Estendida: o cadastro se redesenha sozinho a cada
   ação, então o ‹ é recolocado depois de cada abertura; abrir com o painel
   fechado, ou outra carta, encerra o caminho de volta. */
if (typeof openCard === 'function') {
  const abrirCartaAntesDasEnergias = openCard;
  openCard = function (cardId, ...resto) {
    const painelAberto = !document.getElementById('modal')?.classList.contains('hidden');
    if (energiasCartaAberta && (!painelAberto || energiasCartaAberta.cardId !== cardId)) energiasCartaAberta = null;
    const resultado = abrirCartaAntesDasEnergias.call(this, cardId, ...resto);
    if (energiasCartaAberta) energiasPorBotaoVoltarNaCarta();
    return resultado;
  };
}
