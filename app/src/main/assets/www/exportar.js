/* Exportar cartas — PDF ou Excel, com a foto de cada uma.

   A tela abre pela aba Coleção: escolhe a lista (a coleção inteira, a lista
   que está na tela, Quero, Duplicadas), marca as cartas e o formato. Os dois
   arquivos são escritos aqui mesmo, sem biblioteca: o PDF e a planilha .xlsx
   são montados byte a byte, com as fotos convertidas em JPEG pequeno. Uma
   biblioteca de PDF e outra de Excel pesariam mais que o aplicativo inteiro.

   No celular, o arquivo pronto vai para o "Salvar como" do Android ou para o
   compartilhamento (WhatsApp, e-mail, Drive). No navegador, é baixado. */

const EXPORTACAO_MAXIMO = 1000;       // cartas por arquivo: cada uma leva uma foto
const EXPORTACAO_PASSO_LISTA = 120;   // linhas desenhadas de cada vez na tela

const exportacao = {
  fonte: 'colecao',
  busca: '',
  selecionadas: new Set(),
  formato: 'pdf',
  limite: EXPORTACAO_PASSO_LISTA,
  dados: new Map(),        // cardId → versões, quantidade e valor (vale enquanto a tela está aberta)
  cache: null,             // a lista da fonte escolhida, já ordenada
  trabalhando: false,
  arquivo: null,
};
// Foto já convertida, por endereço: exportar de novo não baixa tudo outra vez.
const fotosDaExportacao = new Map();

const FONTES_DE_EXPORTACAO = [
  ['colecao', 'Minha coleção'],
  ['lista', 'Lista da tela'],
  ['quero', 'Quero'],
  ['repetidas', 'Duplicadas'],
];

const ROTULO_DO_FILTRO_DA_COLECAO = {
  all: 'Todas as cartas', owned: 'Minha coleção', missing: 'Faltantes', wishlist: 'Desejo',
  repeated: 'Duplicadas', trade: 'Para trocar ou vender', 'price-review': 'Preços pendentes',
};

/* ---------- Quais cartas ---------- */

// A lista da tela só vira opção quando é diferente da coleção inteira: há
// busca, coleção, artista, ou um filtro que não é "Tenho".
function listaDaTelaEhDiferente() {
  return ui.cardFilter !== 'owned' || Boolean(normalize(ui.cardQuery))
    || ui.cardSet !== 'all' || ui.cardArtist !== 'all';
}

// Como no fichário: coleção mais nova primeiro, e dentro dela pelo número.
function ordemDoFichario(setsPorId) {
  return (a, b) => {
    const setA = setsPorId.get(a.setId);
    const setB = setsPorId.get(b.setId);
    const porColecao = setA && setB ? compareSetsByTimeline(setA, setB)
      : String(a.setName || '').localeCompare(String(b.setName || ''), 'pt-BR');
    return porColecao || numericLocal(a) - numericLocal(b) || a.name.localeCompare(b.name, 'pt-BR');
  };
}

function cartasDaFonte(fonte) {
  if (fonte === 'lista') {
    // "Trocar/Vender" desenha por versão; aqui interessa a carta.
    if (ui.cardFilter === 'trade') return [...new Map(sobrasParaTrocar().map(item => [item.card.id, item.card])).values()];
    return filteredCardsForUi().result;
  }
  const lista = [];
  for (const [cardId, entry] of Object.entries(state.entries || {})) {
    const card = cardMap.get(cardId);
    if (!card) continue;
    if (fonte === 'colecao' ? quantityFor(cardId) > 0
      : fonte === 'quero' ? Boolean(entry.wishlist)
        : temVersaoRepetida(cardId)) lista.push(card);
  }
  return lista.sort(ordemDoFichario(new Map((catalog.sets || []).map(set => [set.id, set]))));
}

// A coleção não muda com a tela aberta: a lista da fonte é montada (e
// ordenada) uma vez, não a cada carta marcada.
function cartasDaFonteAtual() {
  if (exportacao.cache?.fonte !== exportacao.fonte) {
    exportacao.cache = { fonte: exportacao.fonte, cartas: cartasDaFonte(exportacao.fonte) };
  }
  return exportacao.cache.cartas;
}

function fontesDisponiveis() {
  return FONTES_DE_EXPORTACAO
    .filter(([valor]) => valor !== 'lista' || listaDaTelaEhDiferente())
    .map(([valor, rotulo]) => [valor, rotulo, valor === exportacao.fonte ? cartasDaFonteAtual().length : cartasDaFonte(valor).length]);
}

/* ---------- O que vai em cada linha ---------- */

function numeroParaExportar(card) {
  return String(card.number || '').includes('/') ? card.number : formatCardNumber(card.localId || card.number, card.setTotal);
}

// "Reverse Holo · EN · LP": a versão e só o que foge do comum.
function rotuloParaExportar(variante) {
  const partes = [rotuloDaVariante(variante) || 'Comum'];
  if (variante.language && variante.language !== 'pt-br') partes.push(languageCode(variante.language));
  if (variante.condition && variante.condition !== 'Near Mint') {
    partes.push(String(CONDICAO_CURTA[variante.condition] || variante.condition).split(' — ')[0]);
  }
  const graduacao = String(variante.gradingCompany || '').trim();
  if (graduacao && !/^n[aã]o\s+gradua/i.test(graduacao)) partes.push(`${graduacao} ${variante.grade || ''}`.trim());
  return partes.join(' · ');
}

/* Versões que você tem, quantas e quanto valem — com o MESMO preço do
   Portfólio (effectiveVariantPrice), para o arquivo bater com o app. A carta
   que você ainda não tem (Quero) leva o preço de mercado de uma cópia comum. */
function dadosDaCartaExportada(card) {
  if (exportacao.dados.has(card.id)) return exportacao.dados.get(card.id);
  const grupos = new Map();
  for (const variante of variantsFor(card.id)) {
    const quantidade = Math.max(0, Math.trunc(Number(variante.quantity) || 0));
    if (!quantidade) continue;
    const rotulo = rotuloParaExportar(variante);
    const cotacao = effectiveVariantPrice(card.id, variante);
    const preco = cotacao?.brl != null && Number.isFinite(Number(cotacao.brl)) ? Number(cotacao.brl) : null;
    const chave = `${rotulo}|${preco}`;
    const grupo = grupos.get(chave) || { rotulo, quantidade: 0, preco };
    grupo.quantidade += quantidade;
    grupos.set(chave, grupo);
  }
  const versoes = [...grupos.values()];
  const quantidade = versoes.reduce((soma, versao) => soma + versao.quantidade, 0);
  const comPreco = versoes.filter(versao => versao.preco != null);
  let valor = comPreco.length ? comPreco.reduce((soma, versao) => soma + versao.preco * versao.quantidade, 0) : null;
  let deMercado = false;
  if (!quantidade) {
    const cotacao = automaticPriceQuote(card.id, 'normal');
    valor = cotacao?.brl != null && Number(cotacao.brl) > 0 ? Number(cotacao.brl) : null;
    deMercado = valor != null;
  }
  const dados = { card, versoes, quantidade, valor, deMercado, naWishlist: Boolean(state.entries?.[card.id]?.wishlist) };
  exportacao.dados.set(card.id, dados);
  return dados;
}

function resumoDasVersoes(dados) {
  if (dados.quantidade) return dados.versoes.map(versao => `${versao.quantidade}× ${versao.rotulo}`).join(' · ');
  return dados.naWishlist ? 'No Quero · você ainda não tem' : 'Você ainda não tem';
}

/* ---------- A tela ---------- */

function abrirExportacao() {
  exportacao.cache = null;
  exportacao.fonte = listaDaTelaEhDiferente() && cartasDaFonte('lista').length ? 'lista' : 'colecao';
  exportacao.busca = '';
  exportacao.dados = new Map();
  exportacao.arquivo = null;
  marcarPadraoDaFonte();
  desenharExportacao();
}

// Lista que cabe num arquivo começa toda marcada; a maior, desmarcada.
function marcarPadraoDaFonte() {
  const cartas = cartasDaFonteAtual();
  exportacao.selecionadas = new Set(cartas.length <= EXPORTACAO_MAXIMO ? cartas.map(card => card.id) : []);
  exportacao.limite = EXPORTACAO_PASSO_LISTA;
}

function cartasVisiveisNaExportacao() {
  const todas = cartasDaFonteAtual();
  const busca = normalize(exportacao.busca);
  if (!busca) return todas;
  return todas.filter(card => (cardSearchIndex.get(card.id) || normalize(`${card.name} ${card.setName} ${card.number}`)).includes(busca));
}

function linhaDaExportacao(card) {
  const marcada = exportacao.selecionadas.has(card.id);
  const dados = dadosDaCartaExportada(card);
  const arte = cardGridImage(card, null);
  return `<button type="button" class="exportar-item${marcada ? ' marcada' : ''}" data-card-id="${esc(card.id)}" aria-pressed="${marcada}"
      onclick="alternarCartaNaExportacao(this.dataset.cardId)">
    <span class="exportar-caixa" aria-hidden="true"></span>
    ${arte ? `<img src="${esc(arte)}" alt="" loading="lazy" decoding="async" onerror="this.outerHTML='<span class=&quot;exportar-sem-foto&quot;>TCG</span>'">` : '<span class="exportar-sem-foto">TCG</span>'}
    <span class="exportar-texto">
      <strong>${esc(card.name)}</strong>
      <small>${esc(card.setName)} · ${esc(numeroParaExportar(card))}</small>
      <small>${esc(resumoDasVersoes(dados))}</small>
    </span>
    <span class="exportar-valor">${dados.valor != null ? esc(money(dados.valor)) : '—'}</span>
  </button>`;
}

function htmlDaListaDaExportacao() {
  const visiveis = cartasVisiveisNaExportacao();
  if (!visiveis.length) {
    return `<div class="empty"><strong>Nenhuma carta aqui</strong>${exportacao.busca ? 'Nada com esse nome nesta lista.' : 'Esta lista está vazia.'}</div>`;
  }
  const mostradas = visiveis.slice(0, exportacao.limite);
  return mostradas.map(linhaDaExportacao).join('')
    + (mostradas.length < visiveis.length
      ? `<button type="button" class="load-more" onclick="mostrarMaisNaExportacao()">Mostrar mais ${Math.min(EXPORTACAO_PASSO_LISTA, visiveis.length - mostradas.length)}</button>`
      : '');
}

function desenharExportacao() {
  const fontes = fontesDisponiveis();
  const total = cartasDaFonteAtual().length;
  showModal(`
    <div class="exportar-tela">
      <div class="exportar-topo">
        <button type="button" class="gaveta-voltar" onclick="closeModal()" aria-label="Voltar">←</button>
        <div><h2>Exportar cartas</h2><small>Marque as cartas e escolha PDF ou Excel</small></div>
      </div>

      <div class="exportar-fontes" role="group" aria-label="Quais cartas">
        ${fontes.map(([valor, rotulo, quantas]) => `<button type="button" class="chip${exportacao.fonte === valor ? ' active' : ''}"
          aria-pressed="${exportacao.fonte === valor}" onclick="mudarFonteDaExportacao('${valor}')">${esc(rotulo)} <b>${quantas.toLocaleString('pt-BR')}</b></button>`).join('')}
      </div>
      ${exportacao.fonte === 'lista' ? `<p class="exportar-nota">${esc(descricaoDaListaDaTela())}</p>` : ''}
      ${total > EXPORTACAO_MAXIMO ? `<p class="exportar-nota">Esta lista tem ${total.toLocaleString('pt-BR')} cartas. Marque até ${EXPORTACAO_MAXIMO.toLocaleString('pt-BR')} por arquivo.</p>` : ''}

      <label class="vision-search exportar-busca"><span>${tabIcon('pokedex')}</span>
        <input id="exportarBusca" value="${esc(exportacao.busca)}" placeholder="Buscar nesta lista" autocomplete="off"
          oninput="buscarNaExportacao(this.value)"></label>

      <div class="exportar-marcar">
        <span id="exportarContagem"></span>
        <button type="button" onclick="marcarVisiveisNaExportacao(true)">Marcar todas</button>
        <button type="button" onclick="marcarVisiveisNaExportacao(false)">Desmarcar</button>
      </div>

      <div class="exportar-lista" id="exportarLista">${htmlDaListaDaExportacao()}</div>

      <div class="exportar-rodape">
        <div class="exportar-formatos" role="radiogroup" aria-label="Formato do arquivo">
          ${[['pdf', 'PDF', 'Lista com foto, para imprimir ou mandar'], ['xlsx', 'Excel', 'Planilha com foto, versões e valor']]
            .map(([valor, nome, descricao]) => `<button type="button" role="radio" aria-checked="${exportacao.formato === valor}" data-formato="${valor}"
              class="exportar-formato${exportacao.formato === valor ? ' ativo' : ''}" onclick="escolherFormatoDaExportacao('${valor}')">
              <strong>${nome}</strong><small>${descricao}</small></button>`).join('')}
        </div>
        <button type="button" class="primary-btn exportar-gerar" id="exportarGerar" onclick="gerarExportacao()"></button>
        <small class="exportar-resumo" id="exportarResumo"></small>
      </div>
    </div>`, 'exportar-sheet');
  atualizarRodapeDaExportacao();
  adiantarPrecosDaLista();
}

// Quantas marcadas, quantas cópias e quanto valem — sem redesenhar a lista.
function atualizarRodapeDaExportacao() {
  const cartas = cartasDaFonteAtual().filter(card => exportacao.selecionadas.has(card.id));
  let copias = 0;
  let valor = 0;
  for (const card of cartas) {
    const dados = dadosDaCartaExportada(card);
    copias += dados.quantidade;
    if (dados.valor != null) valor += dados.valor;
  }
  const total = cartasDaFonteAtual().length;
  const contagem = document.getElementById('exportarContagem');
  if (contagem) contagem.textContent = `${cartas.length.toLocaleString('pt-BR')} de ${total.toLocaleString('pt-BR')} marcadas`;
  const botao = document.getElementById('exportarGerar');
  if (botao) {
    botao.disabled = !cartas.length;
    botao.textContent = cartas.length
      ? `Exportar ${cartas.length.toLocaleString('pt-BR')} ${cartas.length === 1 ? 'carta' : 'cartas'} em ${exportacao.formato === 'pdf' ? 'PDF' : 'Excel'}`
      : 'Marque ao menos uma carta';
  }
  const resumo = document.getElementById('exportarResumo');
  if (resumo) {
    resumo.textContent = cartas.length
      ? [copias ? `${copias.toLocaleString('pt-BR')} ${copias === 1 ? 'cópia' : 'cópias'}` : '', valor ? `valor estimado ${money(valor)}` : ''].filter(Boolean).join(' · ')
      : '';
  }
}

function atualizarListaDaExportacao() {
  const lista = document.getElementById('exportarLista');
  if (lista) lista.innerHTML = htmlDaListaDaExportacao();
  atualizarRodapeDaExportacao();
  adiantarPrecosDaLista();
}

function mudarFonteDaExportacao(fonte) {
  if (exportacao.fonte === fonte) return;
  exportacao.fonte = fonte;
  exportacao.busca = '';
  marcarPadraoDaFonte();
  desenharExportacao();
}

function buscarNaExportacao(valor) {
  exportacao.busca = valor;
  exportacao.limite = EXPORTACAO_PASSO_LISTA;
  atualizarListaDaExportacao();
}

function mostrarMaisNaExportacao() {
  exportacao.limite += EXPORTACAO_PASSO_LISTA;
  atualizarListaDaExportacao();
}

function alternarCartaNaExportacao(cardId) {
  if (exportacao.selecionadas.has(cardId)) exportacao.selecionadas.delete(cardId);
  else if (exportacao.selecionadas.size >= EXPORTACAO_MAXIMO) {
    notify(`Até ${EXPORTACAO_MAXIMO.toLocaleString('pt-BR')} cartas por arquivo.`);
    return;
  } else exportacao.selecionadas.add(cardId);
  const marcada = exportacao.selecionadas.has(cardId);
  const botao = document.querySelector(`.exportar-item[data-card-id="${CSS.escape(cardId)}"]`);
  if (botao) {
    botao.classList.toggle('marcada', marcada);
    botao.setAttribute('aria-pressed', String(marcada));
  }
  atualizarRodapeDaExportacao();
}

// Vale para o que a busca está mostrando — "marcar todas" com uma busca
// ligada marca só as encontradas.
function marcarVisiveisNaExportacao(marcar) {
  for (const card of cartasVisiveisNaExportacao()) {
    if (!marcar) exportacao.selecionadas.delete(card.id);
    else if (exportacao.selecionadas.size < EXPORTACAO_MAXIMO) exportacao.selecionadas.add(card.id);
  }
  atualizarListaDaExportacao();
}

function escolherFormatoDaExportacao(formato) {
  exportacao.formato = formato;
  document.querySelectorAll('.exportar-formato').forEach(botao => {
    const ativo = botao.dataset.formato === formato;
    botao.classList.toggle('ativo', ativo);
    botao.setAttribute('aria-checked', String(ativo));
  });
  atualizarRodapeDaExportacao();
}

function descricaoDaListaDaTela() {
  const partes = [ROTULO_DO_FILTRO_DA_COLECAO[ui.cardFilter] || 'Cartas'];
  if (ui.cardSet !== 'all') partes.push(catalog.sets.find(set => set.id === ui.cardSet)?.name || ui.cardSet);
  if (ui.cardArtist !== 'all') partes.push(`Artista: ${artistIndex.get(ui.cardArtist)?.nome || ui.cardArtist}`);
  if (normalize(ui.cardQuery)) partes.push(`"${String(ui.cardQuery).trim()}"`);
  return partes.join(' · ');
}

function tituloDaExportacao() {
  if (exportacao.fonte === 'lista') return descricaoDaListaDaTela();
  if (exportacao.fonte === 'quero') return 'Quero — lista de desejos';
  if (exportacao.fonte === 'repetidas') return 'Duplicadas';
  return 'Minha coleção';
}

function nomeDoArquivoExportado() {
  const hoje = new Date();
  const data = `${hoje.getFullYear()}-${String(hoje.getMonth() + 1).padStart(2, '0')}-${String(hoje.getDate()).padStart(2, '0')}`;
  const nome = { colecao: 'colecao', lista: 'lista', quero: 'quero', repetidas: 'duplicadas' }[exportacao.fonte] || 'cartas';
  return `pokecard-${nome}-${data}.${exportacao.formato}`;
}

/* ---------- Preços que ainda não estão no aparelho ----------
   O preço de mercado mora em lotes do banco de preços, baixados sob demanda.
   Carta do Quero (sem cópia com preço guardado) ficava "—" no arquivo só
   porque o lote dela ainda não tinha vindo. */
const lotesEmBusca = new Set();

// Um cardId por lote que falta — um basta para baixar o lote inteiro.
function lotesQueFaltam(cartas) {
  const lotes = new Map();
  for (const card of cartas) {
    const indice = Number(centralPriceIndex?.cards?.[card.id]);
    if (Number.isInteger(indice) && indice >= 0 && !centralPriceLoadedShards.has(indice)
      && !lotesEmBusca.has(indice) && !lotes.has(indice)) lotes.set(indice, card.id);
  }
  return [...lotes.entries()];
}

async function baixarLote([indice, cardId]) {
  lotesEmBusca.add(indice);
  try { return await ensureCentralPriceShard(cardId); }
  catch (_) { return false; }   // sem internet: a carta fica sem preço
  finally { lotesEmBusca.delete(indice); }
}

async function carregarPrecosDaExportacao(cartas, aoAvancar) {
  try { await syncCentralPrices(false, true); } catch (_) { /* segue com o que tem */ }
  const lotes = lotesQueFaltam(cartas);
  let proximo = 0;
  let feitos = 0;
  const trabalhador = async () => {
    while (proximo < lotes.length && exportacao.trabalhando && exportacaoAberta()) {
      await baixarLote(lotes[proximo++]);
      feitos += 1;
      aoAvancar(feitos, lotes.length);
    }
  };
  await Promise.all(Array.from({ length: 3 }, trabalhador));
  return lotes.length;
}

// Na lista da tela, em segundo plano: poucos lotes por vez, e a lista se
// redesenha quando os preços chegam.
function adiantarPrecosDaLista() {
  const faltam = lotesQueFaltam(cartasVisiveisNaExportacao().slice(0, exportacao.limite)).slice(0, 4);
  if (!faltam.length) return;
  Promise.all(faltam.map(baixarLote)).then(resultados => {
    const lista = document.getElementById('exportarLista');
    if (!resultados.some(Boolean) || !lista || !exportacaoAberta() || exportacao.trabalhando) return;
    exportacao.dados = new Map();
    const rolagem = lista.scrollTop;
    atualizarListaDaExportacao();
    lista.scrollTop = rolagem;
  });
}

/* ---------- Gerar ---------- */

function exportacaoAberta() {
  const modal = document.getElementById('modal');
  return Boolean(modal && !modal.classList.contains('hidden')
    && document.getElementById('modal-content')?.classList.contains('exportar-sheet'));
}

function mostrarEstadoDaExportacao(html) {
  const sheet = document.getElementById('modal-content');
  if (sheet && exportacaoAberta()) sheet.innerHTML = `<div class="exportar-tela exportar-estado">${html}</div>`;
}

function mostrarProgressoDaExportacao(titulo, feitas, total) {
  const texto = document.getElementById('exportarProgressoTexto');
  const barra = document.getElementById('exportarProgressoBarra');
  const cabecalho = document.getElementById('exportarProgressoTitulo');
  if (texto && barra && cabecalho) {
    cabecalho.textContent = titulo;
    texto.textContent = total ? `${feitas.toLocaleString('pt-BR')} de ${total.toLocaleString('pt-BR')}` : '';
    barra.style.width = `${total ? Math.round(feitas / total * 100) : 100}%`;
    return;
  }
  mostrarEstadoDaExportacao(`
    <div class="pokeball-loader" aria-hidden="true"></div>
    <strong id="exportarProgressoTitulo">${esc(titulo)}</strong>
    <span id="exportarProgressoTexto">${total ? `${feitas} de ${total}` : ''}</span>
    <div class="exportar-barra" aria-hidden="true"><span id="exportarProgressoBarra" style="width:0%"></span></div>
    <button type="button" class="secondary-btn" onclick="cancelarExportacao()">Cancelar</button>`);
}

function cancelarExportacao() {
  exportacao.trabalhando = false;
  desenharExportacao();
}

async function gerarExportacao() {
  const cartas = cartasDaFonteAtual().filter(card => exportacao.selecionadas.has(card.id));
  if (!cartas.length) return notify('Marque ao menos uma carta.');
  exportacao.trabalhando = true;
  mostrarProgressoDaExportacao('Conferindo os preços…', 0, 0);
  const lotes = await carregarPrecosDaExportacao(cartas, (feitos, total) => mostrarProgressoDaExportacao('Conferindo os preços…', feitos, total));
  if (!exportacao.trabalhando || !exportacaoAberta()) return;
  if (lotes) exportacao.dados = new Map();   // recalcula com os preços que chegaram
  const dados = cartas.map(dadosDaCartaExportada);
  mostrarProgressoDaExportacao('Preparando as fotos…', 0, dados.length);

  const fotos = await carregarFotosDaExportacao(cartas, feitas => mostrarProgressoDaExportacao('Preparando as fotos…', feitas, cartas.length));
  // Cancelou ou fechou a tela no meio: o que já baixou fica guardado para a próxima.
  if (!exportacao.trabalhando || !exportacaoAberta()) return;

  mostrarProgressoDaExportacao('Montando o arquivo…', 0, 0);
  await new Promise(resolve => setTimeout(resolve, 40));   // deixa a mensagem aparecer
  const titulo = tituloDaExportacao();
  let bytes;
  try {
    bytes = exportacao.formato === 'pdf' ? montarPdfDaExportacao(dados, fotos, titulo) : montarXlsxDaExportacao(dados, fotos, titulo);
  } catch (erro) {
    console.warn('POKECARD: exportação falhou —', erro);
    exportacao.trabalhando = false;
    notify('Não foi possível montar o arquivo.');
    return desenharExportacao();
  }
  exportacao.trabalhando = false;
  if (!exportacaoAberta()) return;
  exportacao.arquivo = {
    bytes,
    nome: nomeDoArquivoExportado(),
    mime: exportacao.formato === 'pdf' ? 'application/pdf' : 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    cartas: dados.length,
    semFoto: fotos.filter(foto => !foto).length,
    salvo: false,
  };
  mostrarArquivoPronto();
}

function tamanhoLegivel(bytes) {
  if (bytes < 1024 * 1024) return `${Math.max(1, Math.round(bytes / 1024))} KB`;
  return `${(bytes / 1024 / 1024).toFixed(1).replace('.', ',')} MB`;
}

function mostrarArquivoPronto() {
  const arquivo = exportacao.arquivo;
  if (!arquivo) return;
  const noCelular = Boolean(window.Android?.arquivoSalvar);
  mostrarEstadoDaExportacao(`
    <span class="exportar-pronto-icone" aria-hidden="true">${exportacao.formato === 'pdf' ? 'PDF' : 'XLS'}</span>
    <strong>${arquivo.salvo ? '✓ Arquivo salvo' : 'Arquivo pronto'}</strong>
    <span>${esc(arquivo.nome)}</span>
    <small>${arquivo.cartas.toLocaleString('pt-BR')} ${arquivo.cartas === 1 ? 'carta' : 'cartas'} · ${tamanhoLegivel(arquivo.bytes.length)}</small>
    ${arquivo.semFoto ? `<small class="exportar-aviso">${arquivo.semFoto} ${arquivo.semFoto === 1 ? 'carta saiu' : 'cartas saíram'} sem foto: a imagem não pôde ser baixada agora (sem internet?).</small>` : ''}
    <div class="exportar-pronto-acoes">
      ${noCelular
        ? `<button type="button" class="primary-btn" onclick="salvarArquivoExportado()">Salvar no celular</button>
           <button type="button" class="secondary-btn" onclick="compartilharArquivoExportado()">Compartilhar</button>`
        : '<button type="button" class="primary-btn" onclick="salvarArquivoExportado()">Baixar arquivo</button>'}
      <button type="button" class="secondary-btn" onclick="desenharExportacao()">Voltar à seleção</button>
    </div>`);
}

/* ---------- Fotos ---------- */

async function carregarFotosDaExportacao(cartas, aoAvancar) {
  const fotos = new Array(cartas.length).fill(null);
  let proxima = 0;
  let feitas = 0;
  // Seis de cada vez: rápido sem entupir a conexão do celular.
  const trabalhador = async () => {
    while (proxima < cartas.length && exportacao.trabalhando && exportacaoAberta()) {
      const indice = proxima++;
      fotos[indice] = await fotoParaExportar(cartas[indice]);
      feitas += 1;
      aoAvancar(feitas);
    }
  };
  await Promise.all(Array.from({ length: 6 }, trabalhador));
  return fotos;
}

/* A foto que a grade mostraria, na mesma ordem de preferência: a foto da SUA
   cópia, a imagem que você adicionou para a carta, a arte do catálogo e, se
   ela falhar, as outras fontes que o app conhece (espelho em inglês, CDN,
   outras bases) — é assim que aparecem as Energias básicas, que não têm arte
   no catálogo. */
async function fotoParaExportar(card) {
  const tentados = new Set();
  const tentar = async endereco => {
    const limpo = String(endereco || '').trim();
    if (!limpo || tentados.has(limpo)) return null;
    tentados.add(limpo);
    if (fotosDaExportacao.has(limpo)) return fotosDaExportacao.get(limpo);
    try {
      const foto = await jpegDaImagem(limpo);
      fotosDaExportacao.set(limpo, foto);
      return foto;
    } catch (_) {
      return null;   // sem guardar a falha: com internet de volta, a próxima tenta de novo
    }
  };
  const variante = variantsFor(card.id).find(item => Number(item.quantity) > 0 && String(item.imageUrl || '').trim());
  let foto = await tentar(variante?.imageUrl);
  if (foto) return foto;
  try {
    const adicionada = await window.FicharioLocalImages?.get?.(card.id);
    foto = await tentar(adicionada?.dataUrl);
    if (foto) return foto;
  } catch (_) { /* sem imagem adicionada */ }
  for (const endereco of [cardGridImage(card, null), upgradeCardImageUrl(card.imageUrl)]) {
    foto = await tentar(endereco);
    if (foto) return foto;
  }
  let alternativas = [];
  try { alternativas = await window.FicharioImageFallback?.candidates?.(card.id) || []; } catch (_) { alternativas = []; }
  // Endereço que não existe falha rápido (404); a fonte boa costuma ser a
  // última da cascata — a das Energias básicas é a 18ª.
  for (const endereco of alternativas.slice(0, 24)) {
    foto = await tentar(endereco);
    if (foto) return foto;
  }
  return null;
}

const FOTO_LARGURA = 150;
const FOTO_ALTURA = 210;   // 5:7, a proporção da carta

async function jpegDaImagem(endereco) {
  const imagem = await decodificarImagem(await baixarImagem(endereco));
  const canvas = document.createElement('canvas');
  canvas.width = FOTO_LARGURA;
  canvas.height = FOTO_ALTURA;
  const contexto = canvas.getContext('2d');
  contexto.fillStyle = '#ffffff';
  contexto.fillRect(0, 0, FOTO_LARGURA, FOTO_ALTURA);
  const escala = Math.min(FOTO_LARGURA / imagem.width, FOTO_ALTURA / imagem.height);
  const largura = imagem.width * escala;
  const altura = imagem.height * escala;
  contexto.imageSmoothingQuality = 'high';
  contexto.drawImage(imagem, (FOTO_LARGURA - largura) / 2, (FOTO_ALTURA - altura) / 2, largura, altura);
  if (typeof imagem.close === 'function') imagem.close();
  const jpeg = await new Promise((resolve, rejeita) => canvas.toBlob(blob => blob ? resolve(blob) : rejeita(new Error('jpeg')), 'image/jpeg', 0.82));
  return { bytes: await bytesDoBlob(jpeg), largura: FOTO_LARGURA, altura: FOTO_ALTURA };
}

/* O endereço pode ser da internet (catálogo), de dentro do app (file://) ou
   uma foto guardada no aparelho (data:). O fetch do WebView recusa file:,
   então o que ele não consegue vai por XMLHttpRequest. */
async function baixarImagem(endereco) {
  if (/^(https?|data|blob):/i.test(endereco)) {
    try {
      const resposta = await fetch(endereco, { cache: 'force-cache' });
      if (resposta.ok) return await resposta.blob();
    } catch (_) { /* tenta pelo outro caminho */ }
  }
  return new Promise((resolve, rejeita) => {
    const pedido = new XMLHttpRequest();
    pedido.open('GET', endereco);
    pedido.responseType = 'blob';
    pedido.timeout = 20000;
    pedido.onload = () => ((pedido.status === 200 || pedido.status === 0) && pedido.response?.size
      ? resolve(pedido.response) : rejeita(new Error(`HTTP ${pedido.status}`)));
    pedido.onerror = () => rejeita(new Error('rede'));
    pedido.ontimeout = () => rejeita(new Error('tempo'));
    pedido.send();
  });
}

async function decodificarImagem(blob) {
  if (typeof createImageBitmap === 'function') {
    try { return await createImageBitmap(blob); } catch (_) { /* cai para o <img> */ }
  }
  return new Promise((resolve, rejeita) => {
    const imagem = new Image();
    const endereco = URL.createObjectURL(blob);
    imagem.onload = () => { URL.revokeObjectURL(endereco); resolve(imagem); };
    imagem.onerror = () => { URL.revokeObjectURL(endereco); rejeita(new Error('imagem')); };
    imagem.src = endereco;
  });
}

async function bytesDoBlob(blob) {
  if (typeof blob.arrayBuffer === 'function') return new Uint8Array(await blob.arrayBuffer());
  return new Promise((resolve, rejeita) => {
    const leitor = new FileReader();
    leitor.onload = () => resolve(new Uint8Array(leitor.result));
    leitor.onerror = () => rejeita(leitor.error);
    leitor.readAsArrayBuffer(blob);
  });
}

function juntarBytes(partes) {
  const total = partes.reduce((soma, parte) => soma + parte.length, 0);
  const saida = new Uint8Array(total);
  let posicao = 0;
  for (const parte of partes) { saida.set(parte, posicao); posicao += parte.length; }
  return saida;
}

/* ---------- PDF ----------
   PDF 1.4 com as fontes padrão (Helvetica), que todo leitor tem: não é
   preciso embutir fonte. O texto vai em WinAnsi, que cobre o português
   inteiro (ã, ç, é...). Cada foto entra como JPEG, do jeito que foi gerada. */

// Larguras da Helvetica (a cada 1000 unidades), caracteres 32 a 126.
const LARGURA_HELVETICA = [
  278, 278, 355, 556, 556, 889, 667, 191, 333, 333, 389, 584, 278, 333, 278, 278,
  556, 556, 556, 556, 556, 556, 556, 556, 556, 556, 278, 278, 584, 584, 584, 556, 1015,
  667, 667, 722, 722, 667, 611, 778, 722, 278, 500, 667, 556, 833, 722, 778, 667, 778, 722, 667, 611, 722, 667, 944, 667, 667, 611,
  278, 278, 278, 469, 556, 333,
  556, 556, 500, 556, 556, 278, 556, 556, 222, 222, 500, 222, 833, 556, 556, 556, 556, 333, 500, 278, 556, 500, 722, 500, 500, 500,
  334, 260, 334, 584,
];
const LARGURA_HELVETICA_NEGRITO = [
  278, 333, 474, 556, 556, 889, 722, 238, 333, 333, 389, 584, 278, 333, 278, 278,
  556, 556, 556, 556, 556, 556, 556, 556, 556, 556, 333, 333, 584, 584, 584, 611, 975,
  722, 722, 722, 722, 667, 611, 778, 722, 278, 556, 722, 611, 833, 722, 778, 667, 778, 722, 667, 611, 722, 667, 944, 667, 667, 611,
  333, 278, 333, 584, 556, 333,
  556, 611, 556, 611, 556, 333, 611, 611, 278, 278, 556, 278, 889, 611, 611, 611, 611, 389, 556, 333, 611, 556, 778, 556, 556, 500,
  389, 280, 389, 584,
];
// Os símbolos fora do ASCII que aparecem no arquivo: [normal, negrito].
const LARGURA_EXTRA = {
  ' ': [278, 278], '·': [278, 278], '×': [584, 584], '—': [1000, 1000], '–': [556, 556], '…': [1000, 1000],
  '’': [222, 278], '‘': [222, 278], '“': [333, 500], '”': [333, 500], '•': [350, 350], '€': [556, 556],
  '°': [400, 400], 'ª': [370, 370], 'º': [365, 365],
};
// WinAnsi entre 0x80 e 0x9F (o resto do Latin-1 é igual ao Unicode).
const WIN_ANSI = {
  '€': 0x80, '‚': 0x82, 'ƒ': 0x83, '„': 0x84, '…': 0x85, '†': 0x86, '‡': 0x87, 'ˆ': 0x88, '‰': 0x89, 'Š': 0x8A,
  '‹': 0x8B, 'Œ': 0x8C, 'Ž': 0x8E, '‘': 0x91, '’': 0x92, '“': 0x93, '”': 0x94, '•': 0x95, '–': 0x96, '—': 0x97,
  '˜': 0x98, '™': 0x99, 'š': 0x9A, '›': 0x9B, 'œ': 0x9C, 'ž': 0x9E, 'Ÿ': 0x9F,
};
// Símbolos de nome de carta que a Helvetica não tem.
const TROCAS_PARA_PDF = { '♀': ' (F)', '♂': ' (M)', '★': '*', '☆': '*', '◇': '', 'δ': 'delta', ' ': ' ', ' ': ' ' };

function letraBase(caractere) {
  return caractere.normalize('NFD').replace(/[̀-ͯ]/g, '');
}

function textoParaPdf(valor) {
  let saida = '';
  for (const caractere of String(valor ?? '')) {
    const troca = TROCAS_PARA_PDF[caractere];
    if (troca !== undefined) { saida += troca; continue; }
    const codigo = caractere.codePointAt(0);
    if ((codigo >= 0x20 && codigo <= 0x7E) || (codigo >= 0xA0 && codigo <= 0xFF) || WIN_ANSI[caractere]) saida += caractere;
    else {
      const base = letraBase(caractere);
      saida += /^[\x20-\x7e]+$/.test(base) ? base : '?';
    }
  }
  return saida;
}

function larguraNoPdf(texto, negrito, tamanho) {
  const tabela = negrito ? LARGURA_HELVETICA_NEGRITO : LARGURA_HELVETICA;
  let soma = 0;
  for (const caractere of texto) {
    const codigo = caractere.charCodeAt(0);
    if (codigo >= 32 && codigo <= 126) { soma += tabela[codigo - 32]; continue; }
    const extra = LARGURA_EXTRA[caractere];
    if (extra) { soma += extra[negrito ? 1 : 0]; continue; }
    const base = letraBase(caractere).charCodeAt(0);
    soma += base >= 32 && base <= 126 ? tabela[base - 32] : 556;
  }
  return soma * tamanho / 1000;
}

function cortarParaCaber(texto, largura, negrito, tamanho) {
  let corte = textoParaPdf(texto);
  if (larguraNoPdf(corte, negrito, tamanho) <= largura) return corte;
  while (corte.length > 1 && larguraNoPdf(`${corte}…`, negrito, tamanho) > largura) corte = corte.slice(0, -1);
  return `${corte.trimEnd()}…`;
}

// Texto já em WinAnsi → bytes do literal PDF, com \ ( ) escapados.
function literalPdf(texto) {
  let saida = '';
  for (const caractere of textoParaPdf(texto)) {
    const codigo = WIN_ANSI[caractere] || caractere.charCodeAt(0);
    const letra = String.fromCharCode(codigo);
    saida += letra === '\\' || letra === '(' || letra === ')' ? `\\${letra}` : letra;
  }
  return saida;
}

function bytesLatin1(texto) {
  const bytes = new Uint8Array(texto.length);
  for (let i = 0; i < texto.length; i += 1) bytes[i] = texto.charCodeAt(i) & 0xFF;
  return bytes;
}

const numeroPdf = valor => String(Math.round(valor * 100) / 100);

const PDF_A4 = { largura: 595.28, altura: 841.89 };
const PDF_MARGEM = 36;
const PDF_BASE = 46;          // abaixo disso fica só o rodapé
const PDF_LINHA = 74;         // altura de cada carta
const COR_TEXTO = '0.11 0.1 0.14';
const COR_SUAVE = '0.42 0.4 0.47';
const COR_FAIXA = '0.94 0.92 0.97';
const COR_SEPARADOR = '0.86 0.85 0.89';
const COR_DESTAQUE = '0.3 0.17 0.47';

function montarPdfDaExportacao(dados, fotos, titulo) {
  const hoje = new Date();
  const dataCurta = hoje.toLocaleDateString('pt-BR');
  const copias = dados.reduce((soma, item) => soma + item.quantidade, 0);
  const total = dados.reduce((soma, item) => soma + (item.valor || 0), 0);
  const subtitulo = [
    `Exportado em ${dataCurta}`,
    `${dados.length} ${dados.length === 1 ? 'carta' : 'cartas'}`,
    copias ? `${copias} ${copias === 1 ? 'cópia' : 'cópias'}` : '',
    total ? `valor estimado ${money(total)}` : '',
  ].filter(Boolean).join(' · ');

  const imagens = [];              // fotos únicas, na ordem em que entram
  const indiceDaImagem = new Map();
  const paginas = [];
  let pagina = null;
  let y = 0;

  const texto = (x, yTexto, conteudo, negrito, tamanho, cor = COR_TEXTO) => {
    pagina.conteudo.push(`BT /${negrito ? 'F2' : 'F1'} ${numeroPdf(tamanho)} Tf ${cor} rg ${numeroPdf(x)} ${numeroPdf(yTexto)} Td (${literalPdf(conteudo)}) Tj ET`);
  };
  const textoADireita = (direita, yTexto, conteudo, negrito, tamanho, cor) => {
    const limpo = textoParaPdf(conteudo);
    texto(direita - larguraNoPdf(limpo, negrito, tamanho), yTexto, limpo, negrito, tamanho, cor);
  };
  const retangulo = (x, yRet, largura, altura, cor) => pagina.conteudo.push(`${cor} rg ${numeroPdf(x)} ${numeroPdf(yRet)} ${numeroPdf(largura)} ${numeroPdf(altura)} re f`);
  const contorno = (x, yRet, largura, altura, cor) => pagina.conteudo.push(`${cor} RG 0.6 w ${numeroPdf(x)} ${numeroPdf(yRet)} ${numeroPdf(largura)} ${numeroPdf(altura)} re S`);
  const traco = (x1, y1, x2, y2, cor) => pagina.conteudo.push(`${cor} RG 0.6 w ${numeroPdf(x1)} ${numeroPdf(y1)} m ${numeroPdf(x2)} ${numeroPdf(y2)} l S`);

  const direita = PDF_A4.largura - PDF_MARGEM;
  // O valor precisa de ~65 pt ("R$ 12.345,67" em negrito): a quantidade para
  // em 488 e o valor começa depois, sem encostar.
  const colunas = { foto: PDF_MARGEM + 4, carta: 92, versoes: 306, qtd: 488, valor: direita - 2 };

  const novaPagina = () => {
    pagina = { conteudo: [], imagens: new Set() };
    paginas.push(pagina);
    y = PDF_A4.altura - PDF_MARGEM;
    if (paginas.length === 1) {
      texto(PDF_MARGEM, y - 18, cortarParaCaber(titulo, direita - PDF_MARGEM, true, 18), true, 18, COR_DESTAQUE);
      texto(PDF_MARGEM, y - 34, cortarParaCaber(subtitulo, direita - PDF_MARGEM, false, 9), false, 9, COR_SUAVE);
      y -= 48;
    }
    retangulo(PDF_MARGEM, y - 18, direita - PDF_MARGEM, 18, COR_FAIXA);
    texto(colunas.foto, y - 12, 'FOTO', true, 7.5, COR_SUAVE);
    texto(colunas.carta, y - 12, 'CARTA', true, 7.5, COR_SUAVE);
    texto(colunas.versoes, y - 12, 'VERSÕES', true, 7.5, COR_SUAVE);
    textoADireita(colunas.qtd, y - 12, 'QTD', true, 7.5, COR_SUAVE);
    textoADireita(colunas.valor, y - 12, 'VALOR', true, 7.5, COR_SUAVE);
    y -= 18;
  };

  dados.forEach((item, indice) => {
    if (!pagina || y - PDF_LINHA < PDF_BASE) novaPagina();
    const topo = y;
    const { card } = item;
    const foto = fotos[indice];
    const yFoto = topo - 6 - 63;
    if (foto) {
      if (!indiceDaImagem.has(foto)) { indiceDaImagem.set(foto, imagens.length); imagens.push(foto); }
      const numero = indiceDaImagem.get(foto);
      pagina.imagens.add(numero);
      pagina.conteudo.push(`q 45 0 0 63 ${numeroPdf(colunas.foto)} ${numeroPdf(yFoto)} cm /Im${numero} Do Q`);
      contorno(colunas.foto, yFoto, 45, 63, COR_SEPARADOR);
    } else {
      retangulo(colunas.foto, yFoto, 45, 63, COR_FAIXA);
      texto(colunas.foto + 7, yFoto + 29, 'sem foto', false, 7, COR_SUAVE);
    }

    const larguraCarta = colunas.versoes - colunas.carta - 8;
    texto(colunas.carta, topo - 18, cortarParaCaber(card.name, larguraCarta, true, 10.5), true, 10.5);
    texto(colunas.carta, topo - 31, cortarParaCaber(`${card.setName} · ${numeroParaExportar(card)}`, larguraCarta, false, 8.5), false, 8.5, COR_SUAVE);
    const extra = [card.rarity, card.illustrator ? `Ilus. ${card.illustrator}` : ''].filter(Boolean).join(' · ');
    if (extra) texto(colunas.carta, topo - 43, cortarParaCaber(extra, larguraCarta, false, 8), false, 8, COR_SUAVE);

    const larguraVersoes = colunas.qtd - colunas.versoes - 26;
    const linhasVersao = item.quantidade
      ? item.versoes.map(versao => `${versao.quantidade}× ${versao.rotulo}${versao.preco != null ? ` — ${money(versao.preco)}` : ''}`)
      : [item.naWishlist ? 'No Quero' : 'Você não tem'];
    const cabem = linhasVersao.length > 4 ? 3 : 4;
    linhasVersao.slice(0, cabem).forEach((linha, posicao) => {
      texto(colunas.versoes, topo - 18 - posicao * 11.5, cortarParaCaber(linha, larguraVersoes, false, 8.5), false, 8.5);
    });
    if (linhasVersao.length > cabem) {
      texto(colunas.versoes, topo - 18 - cabem * 11.5, `+${linhasVersao.length - cabem} ${linhasVersao.length - cabem === 1 ? 'versão' : 'versões'}`, false, 8, COR_SUAVE);
    }

    textoADireita(colunas.qtd, topo - 18, String(item.quantidade), true, 10, COR_TEXTO);
    textoADireita(colunas.valor, topo - 18, item.valor != null ? money(item.valor) : '—', true, 10, COR_TEXTO);
    if (item.deMercado) textoADireita(colunas.valor, topo - 30, 'preço de mercado', false, 7, COR_SUAVE);

    traco(PDF_MARGEM, topo - PDF_LINHA, direita, topo - PDF_LINHA, COR_SEPARADOR);
    y = topo - PDF_LINHA;
  });

  if (y - 30 < PDF_BASE) novaPagina();
  textoADireita(colunas.valor, y - 20, `Total: ${copias} ${copias === 1 ? 'cópia' : 'cópias'} · ${money(total)}`, true, 11, COR_TEXTO);

  paginas.forEach((folha, numero) => {
    pagina = folha;
    texto(PDF_MARGEM, 24, `POKECARD Brasil · ${dataCurta}`, false, 7.5, COR_SUAVE);
    textoADireita(direita, 24, `Página ${numero + 1} de ${paginas.length}`, false, 7.5, COR_SUAVE);
  });

  return escreverPdf(paginas, imagens, titulo, hoje);
}

function escreverPdf(paginas, imagens, titulo, data) {
  const partes = [];
  let tamanho = 0;
  const deslocamentos = [];
  const escrever = conteudo => {
    const bytes = typeof conteudo === 'string' ? bytesLatin1(conteudo) : conteudo;
    partes.push(bytes);
    tamanho += bytes.length;
  };
  const objeto = (numero, corpo) => {
    deslocamentos[numero] = tamanho;
    escrever(`${numero} 0 obj\n`);
    corpo();
    escrever('\nendobj\n');
  };
  const primeiraImagem = 6;
  const primeiraPagina = primeiraImagem + imagens.length;
  const numeroDaPagina = indice => primeiraPagina + indice * 2;
  const ultimo = primeiraPagina + paginas.length * 2 - 1;
  const doisDigitos = valor => String(valor).padStart(2, '0');
  const dataPdf = `D:${data.getFullYear()}${doisDigitos(data.getMonth() + 1)}${doisDigitos(data.getDate())}${doisDigitos(data.getHours())}${doisDigitos(data.getMinutes())}${doisDigitos(data.getSeconds())}`;

  escrever('%PDF-1.4\n%âãÏÓ\n');
  objeto(1, () => escrever('<< /Type /Catalog /Pages 2 0 R >>'));
  objeto(2, () => escrever(`<< /Type /Pages /Kids [${paginas.map((_, indice) => `${numeroDaPagina(indice)} 0 R`).join(' ')}] /Count ${paginas.length} >>`));
  objeto(3, () => escrever('<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>'));
  objeto(4, () => escrever('<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>'));
  objeto(5, () => escrever(`<< /Title (${literalPdf(titulo)}) /Creator (POKECARD Brasil) /Producer (POKECARD Brasil) /CreationDate (${dataPdf}) >>`));
  imagens.forEach((foto, indice) => objeto(primeiraImagem + indice, () => {
    escrever(`<< /Type /XObject /Subtype /Image /Width ${foto.largura} /Height ${foto.altura} /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length ${foto.bytes.length} >>\nstream\n`);
    escrever(foto.bytes);
    escrever('\nendstream');
  }));
  paginas.forEach((pagina, indice) => {
    const conteudo = bytesLatin1(pagina.conteudo.join('\n'));
    const fotosDaPagina = [...pagina.imagens];
    const recursos = `/Font << /F1 3 0 R /F2 4 0 R >>${fotosDaPagina.length
      ? ` /XObject << ${fotosDaPagina.map(numero => `/Im${numero} ${primeiraImagem + numero} 0 R`).join(' ')} >>` : ''}`;
    objeto(numeroDaPagina(indice), () => escrever(`<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${PDF_A4.largura} ${PDF_A4.altura}] /Resources << ${recursos} >> /Contents ${numeroDaPagina(indice) + 1} 0 R >>`));
    objeto(numeroDaPagina(indice) + 1, () => {
      escrever(`<< /Length ${conteudo.length} >>\nstream\n`);
      escrever(conteudo);
      escrever('\nendstream');
    });
  });

  const inicioDaTabela = tamanho;
  let tabela = `xref\n0 ${ultimo + 1}\n0000000000 65535 f \n`;
  for (let numero = 1; numero <= ultimo; numero += 1) tabela += `${String(deslocamentos[numero]).padStart(10, '0')} 00000 n \n`;
  escrever(tabela);
  escrever(`trailer\n<< /Size ${ultimo + 1} /Root 1 0 R /Info 5 0 R >>\nstartxref\n${inicioDaTabela}\n%%EOF\n`);
  return juntarBytes(partes);
}

/* ---------- Excel (.xlsx) ----------
   Uma planilha é um .zip de arquivos XML. As fotos ficam numa "camada de
   desenho" ancorada na coluna A de cada linha; o resto são células comuns,
   com filtro no cabeçalho e o valor em formato de moeda. */

function xmlSeguro(valor) {
  return String(valor ?? '')
    .replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F￾￿]/g, '')
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}

const celulaTexto = (referencia, valor, estilo) => `<c r="${referencia}" t="inlineStr"${estilo ? ` s="${estilo}"` : ''}><is><t xml:space="preserve">${xmlSeguro(valor)}</t></is></c>`;
const celulaNumero = (referencia, valor, estilo) => `<c r="${referencia}"${estilo ? ` s="${estilo}"` : ''}><v>${Math.round(Number(valor) * 100) / 100}</v></c>`;

const XLSX_COLUNAS = [
  ['Foto', 11], ['Carta', 30], ['Coleção', 26], ['Número', 11], ['Raridade', 16],
  ['Versões', 38], ['Quantidade', 12], ['Valor (R$)', 14], ['Artista', 22],
];
const XML_CABECALHO = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n';
const NS_PLANILHA = 'http://schemas.openxmlformats.org/spreadsheetml/2006/main';
const NS_RELACOES = 'http://schemas.openxmlformats.org/officeDocument/2006/relationships';
const NS_PACOTE = 'http://schemas.openxmlformats.org/package/2006/relationships';

function montarXlsxDaExportacao(dados, fotos, titulo) {
  const letras = 'ABCDEFGHI';
  const ultimaLinha = dados.length + 1;
  const linhas = [`<row r="1" ht="24" customHeight="1">${XLSX_COLUNAS.map(([nome], indice) => celulaTexto(`${letras[indice]}1`, nome, 1)).join('')}</row>`];
  dados.forEach((item, indice) => {
    const r = indice + 2;
    const versoes = item.quantidade
      ? item.versoes.map(versao => `${versao.quantidade}× ${versao.rotulo}${versao.preco != null ? ` (${money(versao.preco)})` : ''}`).join('\n')
      : (item.naWishlist ? 'No Quero' : 'Você não tem');
    linhas.push(`<row r="${r}" ht="66" customHeight="1">`
      + celulaTexto(`B${r}`, item.card.name, 3)
      + celulaTexto(`C${r}`, item.card.setName || '', 3)
      + celulaTexto(`D${r}`, numeroParaExportar(item.card), 4)
      + celulaTexto(`E${r}`, item.card.rarity || '', 3)
      + celulaTexto(`F${r}`, versoes, 3)
      + celulaNumero(`G${r}`, item.quantidade, 4)
      + (item.valor != null ? celulaNumero(`H${r}`, item.valor, 2) : '')
      + celulaTexto(`I${r}`, item.card.illustrator || '', 3)
      + '</row>');
  });
  // Total separado por uma linha vazia, fora do filtro: ordenar a tabela não o arrasta junto.
  const linhaTotal = ultimaLinha + 2;
  const copias = dados.reduce((soma, item) => soma + item.quantidade, 0);
  const total = dados.reduce((soma, item) => soma + (item.valor || 0), 0);
  linhas.push(`<row r="${linhaTotal}" ht="22" customHeight="1">${celulaTexto(`B${linhaTotal}`, 'Total', 5)}${celulaNumero(`G${linhaTotal}`, copias, 6)}${celulaNumero(`H${linhaTotal}`, total, 7)}</row>`);

  const comFoto = [];
  fotos.forEach((foto, indice) => { if (foto) comFoto.push({ foto, linha: indice + 1, nome: dados[indice].card.name }); });

  const planilha = `${XML_CABECALHO}<worksheet xmlns="${NS_PLANILHA}" xmlns:r="${NS_RELACOES}">`
    + `<dimension ref="A1:I${linhaTotal}"/>`
    + '<sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>'
    + '<sheetFormatPr defaultRowHeight="15"/>'
    + `<cols>${XLSX_COLUNAS.map(([, largura], indice) => `<col min="${indice + 1}" max="${indice + 1}" width="${largura}" customWidth="1"/>`).join('')}</cols>`
    + `<sheetData>${linhas.join('')}</sheetData>`
    + `<autoFilter ref="A1:I${ultimaLinha}"/>`
    + '<pageMargins left="0.4" right="0.4" top="0.5" bottom="0.5" header="0.3" footer="0.3"/>'
    + '<pageSetup paperSize="9" orientation="landscape"/>'
    + (comFoto.length ? '<drawing r:id="rId1"/>' : '')
    + '</worksheet>';

  const EMU_POR_PONTO = 12700;
  const larguraFoto = 45 * EMU_POR_PONTO;
  const alturaFoto = 63 * EMU_POR_PONTO;
  const desenho = `${XML_CABECALHO}<xdr:wsDr xmlns:xdr="http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="${NS_RELACOES}">`
    + comFoto.map((item, indice) => `<xdr:oneCellAnchor>`
      + `<xdr:from><xdr:col>0</xdr:col><xdr:colOff>${3 * EMU_POR_PONTO}</xdr:colOff><xdr:row>${item.linha}</xdr:row><xdr:rowOff>${Math.round(1.5 * EMU_POR_PONTO)}</xdr:rowOff></xdr:from>`
      + `<xdr:ext cx="${larguraFoto}" cy="${alturaFoto}"/>`
      + `<xdr:pic><xdr:nvPicPr><xdr:cNvPr id="${indice + 2}" name="Foto ${indice + 1}" descr="${xmlSeguro(item.nome)}"/><xdr:cNvPicPr><a:picLocks noChangeAspect="1"/></xdr:cNvPicPr></xdr:nvPicPr>`
      + `<xdr:blipFill><a:blip r:embed="rId${indice + 1}"/><a:stretch><a:fillRect/></a:stretch></xdr:blipFill>`
      + `<xdr:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="${larguraFoto}" cy="${alturaFoto}"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></xdr:spPr></xdr:pic>`
      + '<xdr:clientData/></xdr:oneCellAnchor>').join('')
    + '</xdr:wsDr>';

  const estilos = `${XML_CABECALHO}<styleSheet xmlns="${NS_PLANILHA}">`
    + '<numFmts count="1"><numFmt numFmtId="164" formatCode="&quot;R$&quot;\\ #,##0.00"/></numFmts>'
    + '<fonts count="3">'
    + '<font><sz val="11"/><name val="Calibri"/><family val="2"/></font>'
    + '<font><b/><sz val="11"/><color rgb="FFFFFFFF"/><name val="Calibri"/><family val="2"/></font>'
    + '<font><b/><sz val="11"/><name val="Calibri"/><family val="2"/></font>'
    + '</fonts>'
    + '<fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill>'
    + '<fill><patternFill patternType="solid"><fgColor rgb="FF4B2B78"/><bgColor indexed="64"/></patternFill></fill></fills>'
    + '<borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>'
    + '<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>'
    + '<cellXfs count="8">'
    + '<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>'
    + '<xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0" applyFont="1" applyFill="1" applyAlignment="1"><alignment vertical="center"/></xf>'
    + '<xf numFmtId="164" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1" applyAlignment="1"><alignment vertical="center"/></xf>'
    + '<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0" applyAlignment="1"><alignment vertical="center" wrapText="1"/></xf>'
    + '<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0" applyAlignment="1"><alignment horizontal="center" vertical="center"/></xf>'
    + '<xf numFmtId="0" fontId="2" fillId="0" borderId="0" xfId="0" applyFont="1" applyAlignment="1"><alignment vertical="center"/></xf>'
    + '<xf numFmtId="0" fontId="2" fillId="0" borderId="0" xfId="0" applyFont="1" applyAlignment="1"><alignment horizontal="center" vertical="center"/></xf>'
    + '<xf numFmtId="164" fontId="2" fillId="0" borderId="0" xfId="0" applyNumberFormat="1" applyFont="1" applyAlignment="1"><alignment vertical="center"/></xf>'
    + '</cellXfs>'
    + '<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>'
    + '</styleSheet>';

  const agora = new Date().toISOString().replace(/\.\d+Z$/, 'Z');
  const texto = conteudo => new TextEncoder().encode(conteudo);
  const arquivos = [
    { nome: '[Content_Types].xml', dados: texto(`${XML_CABECALHO}<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">`
      + '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
      + '<Default Extension="xml" ContentType="application/xml"/>'
      + '<Default Extension="jpeg" ContentType="image/jpeg"/>'
      + '<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>'
      + '<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>'
      + '<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>'
      + (comFoto.length ? '<Override PartName="/xl/drawings/drawing1.xml" ContentType="application/vnd.openxmlformats-officedocument.drawing+xml"/>' : '')
      + '<Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>'
      + '<Override PartName="/docProps/app.xml" ContentType="application/vnd.openxmlformats-officedocument.extended-properties+xml"/>'
      + '</Types>') },
    { nome: '_rels/.rels', dados: texto(`${XML_CABECALHO}<Relationships xmlns="${NS_PACOTE}">`
      + '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>'
      + '<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/>'
      + '<Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties" Target="docProps/app.xml"/>'
      + '</Relationships>') },
    { nome: 'docProps/core.xml', dados: texto(`${XML_CABECALHO}<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:dcterms="http://purl.org/dc/terms/" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">`
      + `<dc:title>${xmlSeguro(titulo)}</dc:title><dc:creator>POKECARD Brasil</dc:creator>`
      + `<dcterms:created xsi:type="dcterms:W3CDTF">${agora}</dcterms:created><dcterms:modified xsi:type="dcterms:W3CDTF">${agora}</dcterms:modified>`
      + '</cp:coreProperties>') },
    { nome: 'docProps/app.xml', dados: texto(`${XML_CABECALHO}<Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/extended-properties"><Application>POKECARD Brasil</Application></Properties>`) },
    { nome: 'xl/workbook.xml', dados: texto(`${XML_CABECALHO}<workbook xmlns="${NS_PLANILHA}" xmlns:r="${NS_RELACOES}">`
      + '<bookViews><workbookView/></bookViews>'
      + '<sheets><sheet name="Cartas" sheetId="1" r:id="rId1"/></sheets>'
      + `<definedNames><definedName name="_xlnm._FilterDatabase" localSheetId="0" hidden="1">Cartas!$A$1:$I$${ultimaLinha}</definedName></definedNames>`
      + '</workbook>') },
    { nome: 'xl/_rels/workbook.xml.rels', dados: texto(`${XML_CABECALHO}<Relationships xmlns="${NS_PACOTE}">`
      + '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>'
      + '<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>'
      + '</Relationships>') },
    { nome: 'xl/styles.xml', dados: texto(estilos) },
    { nome: 'xl/worksheets/sheet1.xml', dados: texto(planilha) },
  ];
  if (comFoto.length) {
    arquivos.push(
      { nome: 'xl/worksheets/_rels/sheet1.xml.rels', dados: texto(`${XML_CABECALHO}<Relationships xmlns="${NS_PACOTE}"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/drawing" Target="../drawings/drawing1.xml"/></Relationships>`) },
      { nome: 'xl/drawings/drawing1.xml', dados: texto(desenho) },
      { nome: 'xl/drawings/_rels/drawing1.xml.rels', dados: texto(`${XML_CABECALHO}<Relationships xmlns="${NS_PACOTE}">${comFoto.map((_, indice) => `<Relationship Id="rId${indice + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="../media/image${indice + 1}.jpeg"/>`).join('')}</Relationships>`) },
      ...comFoto.map((item, indice) => ({ nome: `xl/media/image${indice + 1}.jpeg`, dados: item.foto.bytes })),
    );
  }
  return montarZip(arquivos);
}

/* ZIP sem compressão ("stored"): o Excel aceita, as fotos já são JPEG
   comprimido e o XML é pequeno — comprimir não valeria o código. */
const TABELA_CRC32 = (() => {
  const tabela = new Uint32Array(256);
  for (let n = 0; n < 256; n += 1) {
    let c = n;
    for (let k = 0; k < 8; k += 1) c = c & 1 ? 0xEDB88320 ^ (c >>> 1) : c >>> 1;
    tabela[n] = c >>> 0;
  }
  return tabela;
})();

function crc32(bytes) {
  let c = 0xFFFFFFFF;
  for (let i = 0; i < bytes.length; i += 1) c = TABELA_CRC32[(c ^ bytes[i]) & 0xFF] ^ (c >>> 8);
  return (c ^ 0xFFFFFFFF) >>> 0;
}

function montarZip(arquivos) {
  const agora = new Date();
  const hora = (agora.getHours() << 11) | (agora.getMinutes() << 5) | Math.floor(agora.getSeconds() / 2);
  const dia = ((agora.getFullYear() - 1980) << 9) | ((agora.getMonth() + 1) << 5) | agora.getDate();
  const partes = [];
  const central = [];
  let deslocamento = 0;
  for (const arquivo of arquivos) {
    const nome = new TextEncoder().encode(arquivo.nome);
    const dados = arquivo.dados;
    const crc = crc32(dados);
    const local = new DataView(new ArrayBuffer(30));
    local.setUint32(0, 0x04034b50, true);
    local.setUint16(4, 20, true);
    local.setUint16(8, 0, true);
    local.setUint16(10, hora, true);
    local.setUint16(12, dia, true);
    local.setUint32(14, crc, true);
    local.setUint32(18, dados.length, true);
    local.setUint32(22, dados.length, true);
    local.setUint16(26, nome.length, true);
    partes.push(new Uint8Array(local.buffer), nome, dados);

    const entrada = new DataView(new ArrayBuffer(46));
    entrada.setUint32(0, 0x02014b50, true);
    entrada.setUint16(4, 20, true);
    entrada.setUint16(6, 20, true);
    entrada.setUint16(12, hora, true);
    entrada.setUint16(14, dia, true);
    entrada.setUint32(16, crc, true);
    entrada.setUint32(20, dados.length, true);
    entrada.setUint32(24, dados.length, true);
    entrada.setUint16(28, nome.length, true);
    entrada.setUint32(42, deslocamento, true);
    central.push(new Uint8Array(entrada.buffer), nome);
    deslocamento += 30 + nome.length + dados.length;
  }
  const tamanhoCentral = central.reduce((soma, parte) => soma + parte.length, 0);
  const fim = new DataView(new ArrayBuffer(22));
  fim.setUint32(0, 0x06054b50, true);
  fim.setUint16(8, arquivos.length, true);
  fim.setUint16(10, arquivos.length, true);
  fim.setUint32(12, tamanhoCentral, true);
  fim.setUint32(16, deslocamento, true);
  return juntarBytes([...partes, ...central, new Uint8Array(fim.buffer)]);
}

/* ---------- Entregar o arquivo ---------- */

function paraBase64(bytes) {
  let binario = '';
  for (let i = 0; i < bytes.length; i += 0x8000) binario += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
  return btoa(binario);
}

/* O arquivo passa para o Android em pedaços: uma string de 10 MB de uma vez
   pela ponte do WebView é pedir para travar. Cada pedaço tem tamanho
   múltiplo de 3, e por isso vira base64 sem "=" no meio — do outro lado,
   cada um é decodificado sozinho e emendado no anterior. */
function mandarArquivoParaOAndroid(bytes) {
  const PEDACO = 3 * 65536;
  window.Android.arquivoComecar();
  for (let i = 0; i < bytes.length; i += PEDACO) window.Android.arquivoPedaco(paraBase64(bytes.subarray(i, i + PEDACO)));
}

function salvarArquivoExportado() {
  const arquivo = exportacao.arquivo;
  if (!arquivo) return;
  if (window.Android?.arquivoSalvar) {
    mandarArquivoParaOAndroid(arquivo.bytes);
    window.Android.arquivoSalvar(arquivo.nome, arquivo.mime);
    return;
  }
  baixarArquivoNoNavegador(arquivo);
}

function compartilharArquivoExportado() {
  const arquivo = exportacao.arquivo;
  if (!arquivo) return;
  if (window.Android?.arquivoCompartilhar) {
    mandarArquivoParaOAndroid(arquivo.bytes);
    window.Android.arquivoCompartilhar(arquivo.nome, arquivo.mime);
    return;
  }
  baixarArquivoNoNavegador(arquivo);
}

function baixarArquivoNoNavegador(arquivo) {
  const link = document.createElement('a');
  link.href = URL.createObjectURL(new Blob([arquivo.bytes], { type: arquivo.mime }));
  link.download = arquivo.nome;
  document.body.appendChild(link);
  link.click();
  link.remove();
  setTimeout(() => URL.revokeObjectURL(link.href), 60000);
}

// O Android avisa quando o "Salvar como" terminou.
window.receberArquivoExportado = function (situacao) {
  if (situacao === 'salvo' && exportacao.arquivo) {
    exportacao.arquivo.salvo = true;
    if (exportacaoAberta()) mostrarArquivoPronto();
  }
};
