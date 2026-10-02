/* Fichário virtual — a coleção aberta pelo Explorar como um fichário de
   verdade: páginas de 9 bolsos (3×3), só a foto da carta em cada bolso, sem
   nome, preço, borda ou filtro, e a página virando com o dedo.

   Cada VERSÃO ocupa um bolso, lado a lado com as outras da mesma carta
   (Comum, Holo, Reverse...), mesmo que a foto se repita. A lista é a mesma do
   Master Set — linhasDeCadastro, como em versoesDaColecao: as versões que a
   fonte conhece mais o que você cadastrou. Carimbo e foil especial que você
   não tem ficam de fora, como no Master (são coisa de Grand Master).

   O bolso da carta que falta tem o aspecto das que faltam na grade da
   Coleção: arte em cinza, apagada, com contorno tracejado.

   A página 0 é a capa; ela abre sozinha ao entrar. Tocar num bolso tira a
   carta do plástico e mostra grande. */

const FICHARIO_BOLSOS = 9;
// Margens da folha (px): à esquerda fica a faixa dos furos das argolas.
const FICHARIO_FOLGA = { esquerda: 26, direita: 9, topo: 9, base: 9, vao: 6 };
const ficharioEstado = {
  el: null, set: null, bolsos: [], atual: 0, total: 2,
  paginas: new Map(), giro: null, quadro: 0, toque: null, zoom: null,
  medidas: null, partes: null, abertura: 0, arrastouEm: 0, quadroDoDedo: 0, vizinhas: 0,
};
// Página em que você parou em cada coleção, enquanto o app está aberto.
const ficharioPaginaDaSessao = new Map();
// Arte que já carregou para cada carta: as outras versões dela usam direto.
const ficharioArteBoa = new Map();

const FICHARIO_SETA_ESQ = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M14.5 5.5 8 12l6.5 6.5" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"/></svg>';
const FICHARIO_SETA_DIR = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M9.5 5.5 16 12l-6.5 6.5" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"/></svg>';

function ficharioAberto() {
  return Boolean(ficharioEstado.el);
}

/* ---------- Bolsos ---------- */

// Ordem dentro da carta, como se guarda num fichário: Comum, Holo, Reverse,
// os reverses especiais (Poké Ball, Master Ball...), 1ª edição e, por fim,
// carimbos e foils especiais que você tem.
function ordemDoBolso(linha) {
  if (linha.extra) return 9;
  const valor = String(linha.identidade?.pricingVariant || '').toLowerCase();
  if (!valor || valor === 'normal' || valor === 'unlimited') return 0;
  if (/1st/.test(valor)) return /holo/.test(valor) ? 5 : 4;
  if (/reverse/.test(valor)) return /^reverse(-holofoil)?$/.test(valor) ? 2 : 3;
  if (/holo/.test(valor)) return 1;
  return 6;
}

function bolsosDoFichario(setId) {
  const cartas = (cardsBySet.get(setId) || []).slice().sort(cardSorter('number'));
  const bolsos = [];
  for (const card of cartas) {
    const linhas = linhasDeCadastro(card, false).filter(linha => !linha.extra || linha.quantidade > 0);
    linhas.sort((a, b) => ordemDoBolso(a) - ordemDoBolso(b));
    for (const linha of linhas) bolsos.push({ card, linha, tem: linha.quantidade > 0 });
  }
  return bolsos;
}

function arteDoBolso(bolso) {
  // A foto que você escolheu para esta versão vale mais que a do catálogo.
  const comFoto = bolso.linha.donos.find(variant => variant.imageUrl);
  if (comFoto) return upgradeCardImageUrl(comFoto.imageUrl) || '';
  const boa = ficharioArteBoa.get(bolso.card.id);
  if (boa) return boa;
  // Resolução cheia: o bolso é bem maior que a miniatura da grade.
  return upgradeCardImageUrl(cardGridImage(bolso.card, null)) || '';
}

function bolsoDoFicharioHtml(bolso, indice) {
  const url = arteDoBolso(bolso);
  const foil = bolso.tem ? brilhoDaVariante(bolso.linha.identidade) : '';
  const rotulo = `${bolso.card.name}, ${bolso.linha.titulo}${bolso.tem ? '' : ', falta'}`;
  return `<button type="button" class="bolso ${bolso.tem ? 'tem' : 'falta'}${foil ? ` foil-${foil}` : ''}" data-i="${indice}" aria-label="${esc(rotulo)}">
    <img alt="" draggable="false" decoding="async" data-carta="${esc(bolso.card.id)}"${url ? ` src="${esc(url)}"` : ''}>
    <i class="bolso-plastico" aria-hidden="true"></i>
  </button>`;
}

/* ---------- Páginas ---------- */

function capaDoFicharioHtml() {
  const { set, bolsos } = ficharioEstado;
  const tem = bolsos.filter(bolso => bolso.tem).length;
  const pct = bolsos.length ? Math.round((tem / bolsos.length) * 100) : 0;
  const logos = setImageCandidates(set);
  return `<div class="capa-costura" aria-hidden="true"></div>
    <div class="capa-conteudo">
      <span class="capa-rotulo">Fichário</span>
      <span class="capa-placa">${logos[0]
        ? `<img class="capa-logo" src="${esc(logos[0])}" alt="" draggable="false" data-fallbacks="${esc(logos.slice(1).join('|'))}" onerror="loadNextSetImage(this)"><span class="capa-logo-falha" hidden>${icone('pastas')}</span>`
        : `<span class="capa-logo-falha">${icone('pastas')}</span>`}</span>
      <strong class="capa-nome">${esc(set.name)}</strong>
      <span class="capa-conta">${tem} de ${bolsos.length} ${bolsos.length === 1 ? 'versão' : 'versões'}</span>
      <span class="capa-barra"><i style="width:${pct}%"></i></span>
    </div>`;
}

function ficharioVazioHtml() {
  const atualizado = Boolean(catalogUpdateMeta?.updatedAt);
  return `<div class="fichario-vazio">
    <strong>${atualizado ? 'Esta coleção não tem cartas publicadas na fonte' : 'As cartas desta coleção ainda não foram baixadas'}</strong>
    <span>${atualizado
      ? 'O TCGdex, de onde o app tira o catálogo, não disponibiliza as cartas dela.'
      : 'Nenhuma carta dela está instalada no aplicativo ainda.'}</span>
    ${atualizado ? '' : `<button type="button" class="fichario-buscar" onclick="startCatalogUpdate()">Buscar as cartas agora</button>`}
  </div>`;
}

/* ---------- Páginas ----------

   Cada folha é uma camada própria dentro do livro, presa às argolas
   (transform-origin na borda esquerda). Virar a página só GIRA uma camada já
   desenhada — trabalho da placa de vídeo, sem redesenhar carta nenhuma.

   Antes (5.86–5.91) a virada tirava as folhas de um lugar e punha em outro
   no começo e no fim — o celular redesenhava 18 cartas em alta resolução
   nesses quadros —, e a luz e a sombra mudavam por variável de CSS, o que
   redesenhava as duas folhas a cada quadro. Era isso que travava.

   Ficam montadas a folha da vez e as duas vizinhas: a anterior já virada (de
   costas, invisível), a próxima embaixo da da vez — prontas antes de o dedo
   chegar. A luz da folha que gira e a sombra que ela joga na de baixo mudam
   só por opacidade e deslocamento, que também não pedem redesenho. */

// Folha de número menor fica por cima, como num fichário fechado.
const zDaPagina = indice => 2000 - 2 * indice;

function montarPaginaDoFichario(indice) {
  const E = ficharioEstado;
  const pagina = document.createElement('div');
  if (indice === 0) {
    pagina.className = 'fichario-pagina fichario-capa';
    pagina.innerHTML = capaDoFicharioHtml();
  } else {
    pagina.className = 'fichario-pagina fichario-folha';
    let bolsos = '';
    if (!E.bolsos.length) {
      bolsos = ficharioVazioHtml();
    } else {
      const inicio = (indice - 1) * FICHARIO_BOLSOS;
      for (let i = inicio; i < inicio + FICHARIO_BOLSOS; i++) {
        bolsos += E.bolsos[i] ? bolsoDoFicharioHtml(E.bolsos[i], i) : '<span class="bolso vazio"><i class="bolso-plastico"></i></span>';
      }
    }
    pagina.innerHTML = `<span class="folha-furos" aria-hidden="true"><i></i><i></i><i></i></span><div class="bolsos">${bolsos}</div>`;
    // Carta sem endereço de arte no catálogo: vai direto para a cascata.
    pagina.querySelectorAll('img[data-carta]:not([src])').forEach(trocarArteDoFichario);
  }
  const luz = document.createElement('i');
  luz.className = 'pagina-luz';
  luz.setAttribute('aria-hidden', 'true');
  pagina.appendChild(luz);
  pagina.dataset.indice = String(indice);
  // Imagem decodificada antes de aparecer: a folha de baixo não "pisca"
  // carregando quando a de cima sai da frente.
  pagina.querySelectorAll('img[src]').forEach(img => { if (img.decode) img.decode().catch(() => {}); });
  return pagina;
}

// A folha deste número, montada e no livro (sem mudar se está virada ou não).
function paginaDoFichario(indice) {
  const E = ficharioEstado;
  let pagina = E.paginas.get(indice);
  if (!pagina) {
    pagina = montarPaginaDoFichario(indice);
    E.paginas.set(indice, pagina);
  }
  const partes = partesDoFichario();
  if (pagina.parentNode !== partes.livro) {
    pagina.classList.toggle('virada', indice < E.atual);
    pagina.style.zIndex = String(zDaPagina(indice));
    partes.livro.insertBefore(pagina, partes.argolas);
  }
  return pagina;
}

function luzDaPagina(pagina) {
  return pagina.querySelector(':scope > .pagina-luz');
}

// Lidas uma vez por abertura: a virada mexe nelas a cada quadro.
function partesDoFichario() {
  const E = ficharioEstado;
  if (!E.partes || E.partes.el !== E.el) {
    const el = E.el;
    E.partes = {
      el,
      livro: el.querySelector('.fichario-livro'),
      argolas: el.querySelector('.fichario-argolas'),
      sombraCaixa: el.querySelector('.fichario-sombra-caixa'),
      sombra: el.querySelector('.fichario-sombra'),
    };
  }
  return E.partes;
}

function mostrarPaginaDoFichario(indice) {
  const E = ficharioEstado;
  E.atual = Math.max(0, Math.min(E.total - 1, indice));
  // Fora as folhas que não são vizinhas (libera a memória das imagens).
  for (const [numero, pagina] of [...E.paginas]) {
    if (Math.abs(numero - E.atual) > 1) { pagina.remove(); E.paginas.delete(numero); }
  }
  for (const [numero, pagina] of E.paginas) {
    pagina.classList.toggle('virada', numero < E.atual);
    pagina.style.transform = '';
    pagina.style.zIndex = String(zDaPagina(numero));
  }
  paginaDoFichario(E.atual);
  partesDoFichario().sombra.style.opacity = '0';
  if (E.atual > 0) ficharioPaginaDaSessao.set(E.set.id, E.atual);
  atualizarRodapeDoFichario(E.atual);
  agendarVizinhasDoFichario();
}

/* As vizinhas entram logo depois, não no mesmo quadro em que a virada
   termina: assim o último quadro da animação sai liso. Se o dedo já voltar a
   virar antes disso, comecarGiroDoFichario monta a que precisar na hora. */
function agendarVizinhasDoFichario() {
  const E = ficharioEstado;
  clearTimeout(E.vizinhas);
  E.vizinhas = setTimeout(() => {
    if (!E.el || E.giro) return;
    [E.atual + 1, E.atual - 1].forEach(numero => {
      if (numero >= 0 && numero < E.total) paginaDoFichario(numero);
    });
  }, 90);
}

function atualizarRodapeDoFichario(indice) {
  const E = ficharioEstado;
  const el = E.el;
  const ultima = E.total - 1;
  el.querySelector('.fichario-pagina-texto').textContent = indice === 0 ? 'Capa' : `Página ${indice} de ${ultima}`;
  const regua = el.querySelector('.fichario-regua');
  regua.max = String(ultima);
  regua.value = String(indice);
  regua.style.setProperty('--pct', `${ultima ? (indice / ultima) * 100 : 0}%`);
  el.querySelector('.fichario-seta[data-passo="-1"]').disabled = indice <= 0;
  el.querySelector('.fichario-seta[data-passo="1"]').disabled = indice >= ultima;
}

function atualizarTituloDoFichario() {
  const { el, bolsos } = ficharioEstado;
  const tem = bolsos.filter(bolso => bolso.tem).length;
  el.querySelector('.fichario-conta').textContent = bolsos.length
    ? `${tem} de ${bolsos.length} ${bolsos.length === 1 ? 'versão' : 'versões'}`
    : 'sem cartas instaladas';
}

/* ---------- Virar a página ----------

   A folha gira em torno das argolas (borda esquerda), como num fichário de
   verdade. `p` vai de 0 a 1: 0 é a folha deitada à direita, 1 é a virada
   completa. Para frente, a folha da vez gira e mostra a de baixo; para trás,
   a anterior (já virada, de costas) volta por cima da da vez. */

function comecarGiroDoFichario(para) {
  const E = ficharioEstado;
  const de = E.atual;
  clearTimeout(E.vizinhas);
  let pagina;
  let dir = 1;
  if (para === null) {
    // Última página: a folha só levanta um pouco e volta — embaixo dela, só
    // a contracapa (que fica sempre no fundo do livro).
    pagina = paginaDoFichario(de);
  } else {
    dir = para > de ? 1 : -1;
    // Salto (régua, capa abrindo na página em que você parou): as folhas do
    // meio saem, para a de destino ser a que aparece.
    if (Math.abs(para - de) > 1) {
      for (const [numero, folha] of [...E.paginas]) {
        if ((numero - de) * (numero - para) < 0) { folha.remove(); E.paginas.delete(numero); }
      }
    }
    const destino = paginaDoFichario(para);
    destino.classList.toggle('virada', dir === -1);
    pagina = dir === 1 ? paginaDoFichario(de) : destino;
  }
  E.giro = { de, para, dir, p: 0, elastico: para === null, pagina, luz: luzDaPagina(pagina) };
  // A sombra fica logo abaixo da folha que gira, em cima da de baixo.
  partesDoFichario().sombraCaixa.style.zIndex = String(zDaPagina(Number(pagina.dataset.indice)) - 1);
  aplicarGiroDoFichario(0);
}

function aplicarGiroDoFichario(p) {
  const E = ficharioEstado;
  const giro = E.giro;
  if (!giro) return;
  giro.p = p;
  // "lado": 0 = folha deitada à direita, 1 = deitada à esquerda (virada).
  const lado = giro.dir === 1 ? p : 1 - p;
  giro.pagina.style.transform = `rotateY(${(-180 * lado).toFixed(2)}deg)`;
  if (giro.luz) giro.luz.style.opacity = (lado * 0.62).toFixed(3);
  // A sombra da folha cai sobre a de baixo, logo depois da borda que gira.
  const sombra = partesDoFichario().sombra;
  const borda = Math.max(0, Math.cos(lado * Math.PI)) * (E.medidas?.livroL || 320);
  sombra.style.transform = `translate3d(${borda.toFixed(1)}px, 0, 0)`;
  sombra.style.opacity = (Math.sin(lado * Math.PI) * 0.85).toFixed(3);
}

const ficharioCurvas = {
  suave: t => 1 - Math.pow(1 - t, 3),
  vaiVem: t => (t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2),
};

function animarGiroDoFichario(alvo, duracao, aoTerminar, curva = ficharioCurvas.suave) {
  const E = ficharioEstado;
  cancelAnimationFrame(E.quadro);
  const giro = E.giro;
  if (!giro) return;
  giro.alvo = alvo;
  const inicio = giro.p;
  const t0 = performance.now();
  const passo = agora => {
    if (E.giro !== giro) return;
    const t = Math.min(1, (agora - t0) / Math.max(1, duracao));
    aplicarGiroDoFichario(inicio + (alvo - inicio) * curva(t));
    if (t < 1) E.quadro = requestAnimationFrame(passo);
    else { E.quadro = 0; aoTerminar(); }
  };
  E.quadro = requestAnimationFrame(passo);
}

function terminarGiroDoFichario(completou) {
  const E = ficharioEstado;
  const giro = E.giro;
  if (!giro) return;
  cancelAnimationFrame(E.quadro);
  cancelAnimationFrame(E.quadroDoDedo);
  E.quadro = 0;
  E.quadroDoDedo = 0;
  E.giro = null;
  if (giro.luz) giro.luz.style.opacity = '0';
  mostrarPaginaDoFichario(completou && giro.para !== null ? giro.para : giro.de);
}

// Uma virada que ainda está andando termina na hora, no lado para onde ia.
function concluirGiroEmAndamento() {
  const giro = ficharioEstado.giro;
  if (!giro) return;
  terminarGiroDoFichario(giro.alvo !== undefined ? giro.alvo === 1 : giro.p >= 0.5);
}

function virarFichario(para, duracao = 560) {
  const E = ficharioEstado;
  if (!E.el) return;
  clearTimeout(E.abertura);
  concluirGiroEmAndamento();
  para = Math.max(0, Math.min(E.total - 1, Math.round(para)));
  if (para === E.atual) { atualizarRodapeDoFichario(E.atual); return; }
  comecarGiroDoFichario(para);
  animarGiroDoFichario(1, duracao, () => terminarGiroDoFichario(true), ficharioCurvas.vaiVem);
}

function abrirCapaDoFichario() {
  const E = ficharioEstado;
  const destino = Math.min(ficharioPaginaDaSessao.get(E.set.id) || 1, E.total - 1);
  virarFichario(destino, 760);
}

/* ---------- Dedo ---------- */

function ficharioDedoDesce(evento) {
  const E = ficharioEstado;
  if (evento.pointerType === 'mouse' && evento.button !== 0) return;
  concluirGiroEmAndamento();
  E.toque = {
    id: evento.pointerId, x0: evento.clientX, y0: evento.clientY, t0: performance.now(),
    arrastando: false, ignorar: false, dir: 0, trilha: [[performance.now(), evento.clientX]],
  };
}

function ficharioDedoMove(evento) {
  const E = ficharioEstado;
  const toque = E.toque;
  if (!toque || evento.pointerId !== toque.id) return;
  const dx = evento.clientX - toque.x0;
  const dy = evento.clientY - toque.y0;
  toque.trilha.push([performance.now(), evento.clientX]);
  if (toque.trilha.length > 6) toque.trilha.shift();
  if (!toque.arrastando) {
    if (Math.abs(dx) < 9 || Math.abs(dx) < Math.abs(dy) * 1.1) return;
    toque.arrastando = true;
    clearTimeout(E.abertura);
    toque.dir = dx < 0 ? 1 : -1;
    const para = E.atual + toque.dir;
    try { evento.currentTarget.setPointerCapture(evento.pointerId); } catch (_) {}
    if (para < 0) { toque.ignorar = true; return; }
    comecarGiroDoFichario(para >= E.total ? null : para);
  }
  if (toque.ignorar || !E.giro) return;
  const largura = E.medidas?.livroL || 320;
  let p = Math.max(0, Math.min(1, ((toque.dir === 1 ? -dx : dx)) / (largura * 0.9)));
  if (E.giro.elastico) p = 0.12 * (1 - Math.exp(-p * 4));
  /* O celular manda até 120 toques por segundo; a tela desenha 60. A folha
     muda uma vez por quadro, com a última posição do dedo. */
  toque.p = p;
  if (!E.quadroDoDedo) {
    E.quadroDoDedo = requestAnimationFrame(() => {
      E.quadroDoDedo = 0;
      if (E.toque && E.giro && E.toque.p != null) aplicarGiroDoFichario(E.toque.p);
    });
  }
}

function ficharioDedoSobe(evento) {
  const E = ficharioEstado;
  const toque = E.toque;
  if (!toque || evento.pointerId !== toque.id) return;
  E.toque = null;
  try {
    if (evento.currentTarget.hasPointerCapture?.(evento.pointerId)) evento.currentTarget.releasePointerCapture(evento.pointerId);
  } catch (_) {}
  if (!toque.arrastando) return;
  E.arrastouEm = performance.now();
  const giro = E.giro;
  if (toque.ignorar || !giro) return;
  // O quadro que ainda ia desenhar a posição do dedo: aplica já, para a
  // animação partir exatamente dali.
  cancelAnimationFrame(E.quadroDoDedo);
  E.quadroDoDedo = 0;
  if (toque.p != null) aplicarGiroDoFichario(toque.p);
  if (giro.elastico) {
    animarGiroDoFichario(0, 240, () => terminarGiroDoFichario(false));
    return;
  }
  const [ta, xa] = toque.trilha[0];
  const [tb, xb] = toque.trilha[toque.trilha.length - 1];
  const velocidade = tb > ta ? (xb - xa) / (tb - ta) : 0;
  const aFavor = toque.dir === 1 ? -velocidade : velocidade;
  const completa = evento.type !== 'pointercancel'
    && aFavor > -0.3
    && (giro.p > 0.42 || (aFavor > 0.35 && giro.p > 0.03));
  const resta = completa ? 1 - giro.p : giro.p;
  animarGiroDoFichario(completa ? 1 : 0, 150 + 380 * resta, () => terminarGiroDoFichario(completa));
}

function ficharioToque(evento) {
  const E = ficharioEstado;
  // O clique que chega no fim de um arrasto não é um toque.
  if (performance.now() - E.arrastouEm < 400) return;
  if (E.giro) return;
  if (E.atual === 0) { abrirCapaDoFichario(); return; }
  // Com o dedo "capturado" pelo palco, o alvo do clique é o próprio palco:
  // o bolso tocado se acha pela posição.
  const bolso = evento.target.closest('.bolso[data-i]')
    || document.elementFromPoint(evento.clientX, evento.clientY)?.closest('.bolso[data-i]');
  if (bolso) abrirZoomDoFichario(bolso);
}

function ficharioTecla(evento) {
  const E = ficharioEstado;
  if (!E.el) return;
  if (!document.getElementById('modal')?.classList.contains('hidden')) return;
  if (evento.key === 'ArrowRight') { evento.preventDefault(); if (!E.zoom) virarFichario(E.atual + 1); }
  else if (evento.key === 'ArrowLeft') { evento.preventDefault(); if (!E.zoom) virarFichario(E.atual - 1); }
  else if (evento.key === 'Escape') { evento.preventDefault(); ficharioVoltar(); }
}

/* ---------- Carta fora do bolso ---------- */

function abrirZoomDoFichario(botao) {
  const E = ficharioEstado;
  const bolso = E.bolsos[Number(botao.dataset.i)];
  if (!bolso || E.zoom) return;
  const img = botao.querySelector('img');
  const url = img && img.getAttribute('src') ? (img.currentSrc || img.src) : '';
  const origem = (img || botao).getBoundingClientRect();
  const foil = bolso.tem ? brilhoDaVariante(bolso.linha.identidade) : '';
  const quantas = bolso.linha.quantidade;
  const zoom = E.el.querySelector('.fichario-zoom');
  zoom.innerHTML = `<div class="zoom-fundo"></div>
    <div class="zoom-palco">
      <div class="zoom-carta${foil ? ` foil-${foil}` : ''}">${url
        ? `<img src="${esc(url)}" alt="${esc(bolso.card.name)}" draggable="false">`
        : `<span class="bolso-sem-arte">${icone('pokebola')}</span>`}<i class="zoom-reflexo" aria-hidden="true"></i></div>
    </div>
    <div class="zoom-legenda">
      <strong>${esc(bolso.card.name)}</strong>
      <span>${esc(bolso.card.number || bolso.card.localId || '')} · ${esc(bolso.linha.titulo)}</span>
      <span class="zoom-posse ${bolso.tem ? 'tem' : 'falta'}">${bolso.tem
        ? `Você tem ${quantas} ${quantas === 1 ? 'cópia' : 'cópias'}`
        : 'Ainda não está na sua coleção'}</span>
      <button type="button" class="zoom-abrir">Abrir carta</button>
    </div>`;
  zoom.hidden = false;
  E.zoom = { botao, bolso };
  const carta = zoom.querySelector('.zoom-carta');
  const destino = carta.getBoundingClientRect();
  carta.style.transformOrigin = '0 0';
  carta.style.transform = `translate(${origem.left - destino.left}px, ${origem.top - destino.top}px) scale(${origem.width / destino.width})`;
  botao.classList.add('fora-do-bolso');
  carta.getBoundingClientRect();
  carta.style.transition = 'transform .36s cubic-bezier(.2,.8,.2,1)';
  zoom.classList.add('aberto');
  carta.style.transform = 'none';
}

function fecharZoomDoFichario(imediato = false) {
  const E = ficharioEstado;
  const aberto = E.zoom;
  if (!aberto || !E.el) return false;
  E.zoom = null;
  const zoom = E.el.querySelector('.fichario-zoom');
  const carta = zoom.querySelector('.zoom-carta');
  const terminar = () => {
    zoom.hidden = true;
    zoom.classList.remove('aberto', 'fechando');
    zoom.innerHTML = '';
    aberto.botao.classList.remove('fora-do-bolso');
  };
  if (imediato || !carta || !aberto.botao.isConnected) { terminar(); return true; }
  // A carta volta para o bolso de onde saiu.
  const destino = (aberto.botao.querySelector('img') || aberto.botao).getBoundingClientRect();
  const agora = carta.getBoundingClientRect();
  carta.style.transform = `translate(${destino.left - agora.left}px, ${destino.top - agora.top}px) scale(${destino.width / agora.width})`;
  zoom.classList.remove('aberto');
  zoom.classList.add('fechando');
  setTimeout(terminar, 330);
  return true;
}

function ficharioToqueNoZoom(evento) {
  const E = ficharioEstado;
  if (!E.zoom) return;
  if (evento.target.closest('.zoom-abrir')) {
    const { bolso } = E.zoom;
    fecharZoomDoFichario(true);
    openCard(bolso.card.id, bolso.linha.donos[0]?.id);
    return;
  }
  fecharZoomDoFichario();
}

/* ---------- Imagens ---------- */

function ficharioArteCarregou(evento) {
  const img = evento.target;
  if (!img || img.tagName !== 'IMG' || !img.dataset.carta) return;
  ficharioArteBoa.set(img.dataset.carta, img.currentSrc || img.src);
}

function ficharioArteFalhou(evento) {
  const img = evento.target;
  if (!img || img.tagName !== 'IMG' || !img.dataset.carta) return;
  trocarArteDoFichario(img);
}

/* A mesma cascata de fontes da Coleção (catálogo, espelho em inglês, CDN,
   APIs), na resolução cheia quando a fonte tem. Sem nenhuma, fica um verso
   de carta no bolso. */
async function trocarArteDoFichario(img) {
  const cardId = img.dataset.carta;
  const tentadas = new Set(String(img.dataset.tentadas || '').split('\n').filter(Boolean));
  const falhou = img.getAttribute('src') || '';
  if (falhou) tentadas.add(falhou);
  if (ficharioArteBoa.get(cardId) === falhou) ficharioArteBoa.delete(cardId);
  let candidatas = [];
  try { candidatas = (await window.FicharioImageFallback?.candidates?.(cardId)) || []; } catch (_) {}
  if (!ficharioEstado.el) return;
  const lista = [];
  for (const url of candidatas) {
    const alta = upgradeCardImageUrl(url);
    if (alta) lista.push(alta);
    if (url) lista.push(url);
  }
  const proxima = lista.find(url => !tentadas.has(url));
  if (proxima) {
    tentadas.add(proxima);
    img.dataset.tentadas = [...tentadas].join('\n');
    img.src = proxima;
    return;
  }
  const verso = document.createElement('span');
  verso.className = 'bolso-sem-arte';
  verso.innerHTML = icone('pokebola');
  img.replaceWith(verso);
}

/* ---------- Abrir, medir, fechar ---------- */

function coresDoFichario(set) {
  const cor = corDaColecao(set);
  const h = cor.h;
  const s = Math.max(30, Math.min(58, cor.s));
  return {
    '--fc-fundo-1': `hsl(${h}, ${Math.round(s * 0.55)}%, 17%)`,
    '--fc-fundo-2': `hsl(${h}, ${Math.round(s * 0.35)}%, 6%)`,
    '--fc-capa': `hsl(${h}, ${s}%, 34%)`,
    '--fc-capa-escura': `hsl(${h}, ${s}%, 17%)`,
    '--fc-detalhe': `hsl(${h}, ${Math.min(80, s + 18)}%, 66%)`,
  };
}

function medirFichario() {
  const E = ficharioEstado;
  if (!E.el) return;
  const palco = E.el.querySelector('.fichario-palco');
  const F = FICHARIO_FOLGA;
  const largura = palco.clientWidth - 18;
  const altura = palco.clientHeight - 14;
  const bolsoL = Math.max(44, Math.floor(Math.min(
    (largura - F.esquerda - F.direita - 2 * F.vao) / 3,
    ((altura - F.topo - F.base - 2 * F.vao) / 3) * (63 / 88),
  )));
  const bolsoA = Math.round(bolsoL * (88 / 63));
  const livroL = F.esquerda + F.direita + 3 * bolsoL + 2 * F.vao;
  const livroA = F.topo + F.base + 3 * bolsoA + 2 * F.vao;
  E.medidas = { bolsoL, bolsoA, livroL, livroA };
  const estilo = E.el.style;
  estilo.setProperty('--bolso-l', `${bolsoL}px`);
  estilo.setProperty('--bolso-a', `${bolsoA}px`);
  estilo.setProperty('--livro-l', `${livroL}px`);
  estilo.setProperty('--livro-a', `${livroA}px`);
}

let ficharioMedidaAgendada = 0;
function ficharioRedimensionar() {
  clearTimeout(ficharioMedidaAgendada);
  ficharioMedidaAgendada = setTimeout(medirFichario, 120);
}

function abrirFichario(setId) {
  const set = (catalog?.sets || []).find(item => item.id === setId);
  if (!set) return;
  if (ficharioEstado.el) fecharFichario(true);
  const E = ficharioEstado;
  E.set = set;
  E.bolsos = bolsosDoFichario(setId);
  E.total = 1 + Math.max(1, Math.ceil(E.bolsos.length / FICHARIO_BOLSOS));
  E.atual = 0;
  E.paginas = new Map();
  E.giro = null;
  E.toque = null;
  E.zoom = null;

  const el = document.createElement('div');
  el.id = 'fichario';
  el.className = 'fichario';
  el.setAttribute('role', 'dialog');
  el.setAttribute('aria-modal', 'true');
  el.setAttribute('aria-label', `Fichário ${set.name}`);
  const cores = coresDoFichario(set);
  Object.keys(cores).forEach(nome => el.style.setProperty(nome, cores[nome]));
  el.innerHTML = `
    <header class="fichario-topo">
      <button type="button" class="fichario-fechar" aria-label="Fechar o fichário">${icone('fechar')}</button>
      <div class="fichario-titulo"><strong>${esc(set.name)}</strong><small class="fichario-conta"></small></div>
      <span class="fichario-carregando" hidden title="Carregando as versões das cartas">${icone('ampulheta')}</span>
    </header>
    <div class="fichario-palco">
      <div class="fichario-livro">
        <div class="fichario-pagina fichario-contracapa" aria-hidden="true"></div>
        <div class="fichario-sombra-caixa" aria-hidden="true"><div class="fichario-sombra"></div></div>
        <span class="fichario-argolas" aria-hidden="true"><i></i><i></i><i></i></span>
      </div>
    </div>
    <footer class="fichario-rodape">
      <button type="button" class="fichario-seta" data-passo="-1" aria-label="Página anterior">${FICHARIO_SETA_ESQ}</button>
      <label class="fichario-paginacao">
        <span class="fichario-pagina-texto"></span>
        <input type="range" class="fichario-regua" min="0" max="1" step="1" value="0" aria-label="Ir para a página">
      </label>
      <button type="button" class="fichario-seta" data-passo="1" aria-label="Próxima página">${FICHARIO_SETA_DIR}</button>
    </footer>
    <div class="fichario-zoom" hidden></div>`;
  document.body.appendChild(el);
  document.documentElement.classList.add('fichario-aberto');
  E.el = el;

  el.querySelector('.fichario-fechar').addEventListener('click', () => fecharFichario());
  el.querySelectorAll('.fichario-seta').forEach(botao => {
    botao.addEventListener('click', () => virarFichario(E.atual + Number(botao.dataset.passo)));
  });
  const regua = el.querySelector('.fichario-regua');
  regua.addEventListener('input', () => {
    const valor = Number(regua.value);
    const ultima = E.total - 1;
    el.querySelector('.fichario-pagina-texto').textContent = valor === 0 ? 'Capa' : `Página ${valor} de ${ultima}`;
    regua.style.setProperty('--pct', `${ultima ? (valor / ultima) * 100 : 0}%`);
  });
  regua.addEventListener('change', () => virarFichario(Number(regua.value), 640));
  const palco = el.querySelector('.fichario-palco');
  palco.addEventListener('pointerdown', ficharioDedoDesce);
  palco.addEventListener('pointermove', ficharioDedoMove);
  palco.addEventListener('pointerup', ficharioDedoSobe);
  palco.addEventListener('pointercancel', ficharioDedoSobe);
  palco.addEventListener('click', ficharioToque);
  el.querySelector('.fichario-zoom').addEventListener('click', ficharioToqueNoZoom);
  // load/error não sobem pela árvore: capturados na descida, valem para
  // todas as páginas, inclusive as que ainda nem estão na tela.
  el.addEventListener('load', ficharioArteCarregou, true);
  el.addEventListener('error', ficharioArteFalhou, true);
  document.addEventListener('keydown', ficharioTecla);
  window.addEventListener('resize', ficharioRedimensionar);

  medirFichario();
  atualizarTituloDoFichario();
  mostrarPaginaDoFichario(0);
  requestAnimationFrame(() => el.classList.add('visivel'));
  // A capa abre sozinha, na página em que você parou (nesta sessão).
  E.abertura = setTimeout(abrirCapaDoFichario, 450);
  carregarVersoesDoFichario();
}

/* Coleção cujo lote de preços ainda não veio: o app só conhece as versões
   que você cadastrou. Busca o lote e remonta o fichário quando ele chega. */
function carregarVersoesDoFichario() {
  const E = ficharioEstado;
  const set = E.set;
  let versoes;
  try { versoes = versoesDaColecao(set); } catch (_) { return; }
  if (!versoes.pendentes.length) return;
  const aviso = E.el.querySelector('.fichario-carregando');
  aviso.hidden = false;
  Promise.resolve(buscarVersoesDaColecao(set.id, versoes.pendentes)).then(() => {
    if (!E.el || E.set !== set) return;
    aviso.hidden = true;
    atualizarFicharioAberto();
  });
}

/* Remonta os bolsos depois de mudar a coleção (carta aberta pelo zoom e
   fechada, versões que chegaram), sem sair da página. */
function atualizarFicharioAberto() {
  const E = ficharioEstado;
  if (!E.el || !E.set) return;
  concluirGiroEmAndamento();
  fecharZoomDoFichario(true);
  E.bolsos = bolsosDoFichario(E.set.id);
  E.total = 1 + Math.max(1, Math.ceil(E.bolsos.length / FICHARIO_BOLSOS));
  // As folhas são remontadas com os bolsos novos.
  for (const pagina of E.paginas.values()) pagina.remove();
  E.paginas = new Map();
  atualizarTituloDoFichario();
  mostrarPaginaDoFichario(Math.min(E.atual, E.total - 1));
}

function fecharFichario(imediato = false) {
  const E = ficharioEstado;
  const el = E.el;
  if (!el) return;
  clearTimeout(E.abertura);
  clearTimeout(E.vizinhas);
  cancelAnimationFrame(E.quadro);
  cancelAnimationFrame(E.quadroDoDedo);
  E.quadroDoDedo = 0;
  document.removeEventListener('keydown', ficharioTecla);
  window.removeEventListener('resize', ficharioRedimensionar);
  document.documentElement.classList.remove('fichario-aberto');
  E.el = null;
  E.partes = null;
  E.giro = null;
  E.toque = null;
  E.zoom = null;
  E.quadro = 0;
  E.paginas = new Map();
  if (imediato) { el.remove(); return; }
  el.classList.remove('visivel');
  setTimeout(() => el.remove(), 240);
}

/** Botão Voltar do Android: primeiro guarda a carta no bolso, depois fecha. */
function ficharioVoltar() {
  if (!ficharioAberto()) return false;
  if (fecharZoomDoFichario()) return true;
  fecharFichario();
  return true;
}
