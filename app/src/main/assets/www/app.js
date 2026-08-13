'use strict';

try {
  if (window.Android?.getTopInsetCss) {
    document.documentElement.style.setProperty('--safe-top', `${window.Android.getTopInsetCss()}px`);
    document.documentElement.style.setProperty('--safe-bottom', `${window.Android.getBottomInsetCss()}px`);
  }
} catch (_) {}

const STORAGE_KEY = 'fichario-pokemon-br-plus-state-v1';
const CATALOG_DB_NAME = 'fichario-pokemon-catalog-v1';
const CATALOG_DB_STORE = 'catalog';
const CATALOG_DB_KEY = 'current';
const CATALOG_META_KEY = 'fichario-pokemon-catalog-meta-v1';
const LIGA_SET_CACHE_KEY = 'fichario-pokemon-liga-set-cache-v1';
const VARIANT_IMAGE_CACHE_KEY = 'fichario-pokemon-variant-image-cache-v1';
const SCANNER_PREFS_KEY = 'pokecard-brasil-scanner-preferences-v1';
const PRICE_LOGIC_VERSION_KEY = 'fichario-pokemon-price-logic-version';
const PRICE_LOGIC_VERSION = 25;
const TCGDEX_API_BASE = 'https://api.tcgdex.net/v2/pt-br';
const TCGDEX_API_FALLBACK = 'https://api.tcgdex.net/v2/en';
const TCGDEX_API_JAPANESE = 'https://api.tcgdex.net/v2/ja';
const CENTRAL_PRICE_BASE = 'https://raw.githubusercontent.com/fernandossb/pokemon-price-database/main/output';
const CENTRAL_PRICE_STATUS_URL = `${CENTRAL_PRICE_BASE}/status.json`;
const CENTRAL_PRICE_INDEX_URL = `${CENTRAL_PRICE_BASE}/card-shard-index.json`;
const CENTRAL_PRICE_SHARD_BASE = `${CENTRAL_PRICE_BASE}/shards`;
const CENTRAL_PRICE_DB_NAME = 'fichario-pokemon-central-prices-v1';
const CENTRAL_PRICE_DB_STORE = 'data';
const CENTRAL_PRICE_DB_KEY = 'current';
const CENTRAL_PRICE_SYNC_TTL = 6 * 60 * 60 * 1000;
const TAB_ITEMS = [
  ['dashboard', 'Início', 'home'],
  ['sets', 'Explorar', 'pokedex'],
  ['cards', 'Coleção', 'collections'],
  ['pokedex', 'Pokédex', 'pokedex'],
  ['decks', 'Decks', 'decks'],
  ['wishlist', 'Wishlist', 'wishlist'],
  ['repeated', 'Repetidas', 'repeated'],
];
const REGION_ORDER = ['Kanto','Johto','Hoenn','Sinnoh','Unova','Kalos','Alola','Galar','Paldea','Outros'];

let catalog = null;
let pokedex = [];
let seed = null;
let state = null;
let cards = [];
let cardMap = new Map();
let pokemonMap = new Map();
let pokemonCards = new Map();
let pokemonNameIndex = [];
let catalogUpdateMeta = {};
let catalogUpdating = false;
let catalogUpdateMessage = '';
let catalogUpdateCurrent = 0;
let catalogUpdateTotal = 1;
let ligaSetCache = {};
let variantImageCache = {};
let priceRequests = new Map();
let priceUpdating = false;
let priceUpdateMessage = '';
let priceUpdateCurrent = 0;
let priceUpdateTotal = 1;
let priceUpdateFailures = 0;
let lastPriceDiagnostic = '';
let selectedDeckId = null;
let latestUpdateInfo = null;
let updateCheckInProgress = false;
let centralPriceData = { meta: {}, prices: {}, variantCatalog: {} };
let centralPriceStatus = {};
let centralPriceIndex = { meta: {}, cards: {} };
let centralPriceLoadedShards = new Set();
let centralPriceSyncing = false;
let centralPriceLastCheck = 0;
let cardSearchIndex = new Map();
let cardsBySet = new Map();
let staticCardSortCache = new Map();
let pokemonInferenceCache = new Map();
let scannerSession = { active: false, pricingVariant: 'normal', finish: 'normal', language: 'pt-br', condition: 'Near Mint', edition: 'unlimited', distribution: 'unstamped', artVariant: 'standard', region: 'Brasil', gradingCompany: 'Não graduada', grade: '', tags: [], setId: 'all', count: 0, lastIds: [], live: false };
let scannerDraftFinish = '';
let scannerDraftLanguage = '';
let scannerDraftCondition = '';
let scannerDraftMetadata = {};
let scannerCandidateBuffer = [];
let scannerLastOcrText = '';
const scannerVariantAvailability = new Map();

// Performance v2.1.1: cache das consultas visíveis, pré-carregamento moderado
// e pausa temporária das animações durante a rolagem.
let cardResultCache = { key: '', revision: -1, value: null };
let imagePreloadQueue = [];
let imagePreloadActive = 0;
const IMAGE_PRELOAD_MAX = 1;
const IMAGE_PRELOAD_AHEAD = 4;
let scrollIdleTimer = null;

// Performance v2.1: gravação agrupada e caches derivados.
let stateSaveTimer = null;
let stateSaveDirty = false;
let stateRevision = 0;
let collectionSummaryCache = { revision: -1, value: null };
let pokemonStatsCache = { revision: -1, value: null };


// Modo Laboratório v2.2.1 — diagnóstico local de performance.
// Não envia dados para servidores e mantém somente as últimas amostras no aparelho.
const LAB_STORAGE_KEY = 'fichario-pokemon-lab-report-v1';
const lab = {
  enabled: false,
  startedAt: Date.now(),
  sessionId: `lab-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`,
  events: [],
  counters: {},
  fps: 0,
  fpsMin: 60,
  longTasks: 0,
  lastFrameAt: performance.now(),
  frames: 0,
  lastFpsAt: performance.now(),
};

function labNowIso() { return new Date().toISOString(); }
function labRound(value) { return Math.round(Number(value || 0) * 10) / 10; }
function labCount(name, amount = 1) { lab.counters[name] = (lab.counters[name] || 0) + amount; }
function labRecord(type, durationMs = null, detail = {}) {
  if (!lab.enabled && type !== 'startup') return;
  const event = { at: labNowIso(), type, ...detail };
  if (durationMs != null) event.durationMs = labRound(durationMs);
  lab.events.push(event);
  if (lab.events.length > 500) lab.events.splice(0, lab.events.length - 500);
  labCount(type);
}
function labMeasure(type, fn, detail = {}) {
  const start = performance.now();
  try { return fn(); }
  finally { labRecord(type, performance.now() - start, detail); }
}
async function labMeasureAsync(type, fn, detail = {}) {
  const start = performance.now();
  try { return await fn(); }
  finally { labRecord(type, performance.now() - start, detail); }
}
function labMemorySnapshot() {
  const memory = performance.memory;
  return memory ? {
    usedMb: labRound(memory.usedJSHeapSize / 1048576),
    totalMb: labRound(memory.totalJSHeapSize / 1048576),
    limitMb: labRound(memory.jsHeapSizeLimit / 1048576),
  } : null;
}
function labSnapshot() {
  const durations = lab.events.filter(item => Number.isFinite(item.durationMs));
  const byType = {};
  for (const item of durations) {
    const bucket = byType[item.type] ||= [];
    bucket.push(item.durationMs);
  }
  const timings = Object.fromEntries(Object.entries(byType).map(([type, values]) => {
    const sorted = values.slice().sort((a,b)=>a-b);
    const avg = values.reduce((sum, value)=>sum+value, 0) / values.length;
    return [type, {
      count: values.length,
      averageMs: labRound(avg),
      maximumMs: labRound(Math.max(...values)),
      p95Ms: labRound(sorted[Math.min(sorted.length - 1, Math.floor(sorted.length * .95))]),
    }];
  }));
  return {
    format: 'fichario-pokemon-performance-report',
    reportVersion: 1,
    generatedAt: labNowIso(),
    sessionId: lab.sessionId,
    activeSeconds: Math.round((Date.now() - lab.startedAt) / 1000),
    appVersion: '2.2.2-performance',
    userAgent: navigator.userAgent,
    viewport: { width: innerWidth, height: innerHeight, pixelRatio: devicePixelRatio },
    connection: navigator.connection ? {
      effectiveType: navigator.connection.effectiveType,
      downlink: navigator.connection.downlink,
      saveData: navigator.connection.saveData,
    } : null,
    catalogCards: cards?.length || 0,
    ownedEntries: state?.entries ? Object.keys(state.entries).length : 0,
    currentTab: ui?.tab || '',
    visibleCardLimit: ui?.cardLimit || 0,
    fpsCurrent: lab.fps,
    fpsMinimum: lab.fpsMin === 60 && !lab.frames ? null : lab.fpsMin,
    longTasks: lab.longTasks,
    memory: labMemorySnapshot(),
    counters: { ...lab.counters },
    timings,
    recentEvents: lab.events.slice(-150),
  };
}
function labSaveReport() {
  const report = labSnapshot();
  try { localStorage.setItem(LAB_STORAGE_KEY, JSON.stringify(report)); } catch (_) {}
  return report;
}
function labDownloadReport() {
  const report = labSaveReport();
  const text = JSON.stringify(report, null, 2);
  const name = `fichario-performance-${new Date().toISOString().replace(/[:.]/g,'-')}.json`;
  try {
    const link = document.createElement('a');
    link.href = URL.createObjectURL(new Blob([text], { type: 'application/json' }));
    link.download = name;
    document.body.appendChild(link);
    link.click();
    link.remove();
    setTimeout(() => URL.revokeObjectURL(link.href), 1000);
    notify('Relatório de performance gerado.');
  } catch (_) {
    if (navigator.clipboard) navigator.clipboard.writeText(text).then(()=>notify('Relatório copiado.'));
    else showModal(`<button class="modal-close" onclick="closeModal()">×</button><h2>Relatório</h2><pre class="lab-report-text">${esc(text)}</pre>`);
  }
}
function labClear() {
  lab.events = [];
  lab.counters = {};
  lab.longTasks = 0;
  lab.fpsMin = 60;
  lab.startedAt = Date.now();
  try { localStorage.removeItem(LAB_STORAGE_KEY); } catch (_) {}
  renderLabPanel();
}
function labToggle() {
  lab.enabled = !lab.enabled;
  if (lab.enabled) {
    lab.startedAt = Date.now();
    labRecord('laboratorio_iniciado', 0);
  } else labSaveReport();
  renderLabPanel();
}
function labMetricRows() {
  const report = labSnapshot();
  const memory = report.memory;
  const last = report.recentEvents.slice().reverse().find(item => item.durationMs != null);
  return `
    <div class="lab-grid">
      <div class="lab-metric"><span>Estado</span><strong>${lab.enabled ? 'MEDINDO' : 'PAUSADO'}</strong></div>
      <div class="lab-metric"><span>FPS atual</span><strong>${report.fpsCurrent || '—'}</strong></div>
      <div class="lab-metric"><span>FPS mínimo</span><strong>${report.fpsMinimum || '—'}</strong></div>
      <div class="lab-metric"><span>RAM JavaScript</span><strong>${memory ? `${memory.usedMb} MB` : 'Indisponível'}</strong></div>
      <div class="lab-metric"><span>Tarefas longas</span><strong>${report.longTasks}</strong></div>
      <div class="lab-metric"><span>Última operação</span><strong>${last ? `${last.type}: ${last.durationMs} ms` : '—'}</strong></div>
      <div class="lab-metric"><span>Catálogo</span><strong>${report.catalogCards.toLocaleString('pt-BR')}</strong></div>
      <div class="lab-metric"><span>Eventos medidos</span><strong>${lab.events.length}</strong></div>
    </div>`;
}
function renderLabPanel() {
  const target = document.getElementById('lab-live-metrics');
  if (target) target.innerHTML = labMetricRows();
}
function openLaboratoryPanel() {
  showModal(`
    <button class="modal-close" onclick="closeModal()">×</button>
    <h2>Modo Laboratório</h2>
    <p class="screen-subtitle">Mede desempenho somente neste aparelho. Nenhum dado é enviado automaticamente.</p>
    <div id="lab-live-metrics">${labMetricRows()}</div>
    <div class="notice"><strong>Teste recomendado</strong><br>Ative a medição, use todas as abas, pesquise cartas, abra cadastros, altere quantidades e consulte preços. Depois gere o relatório.</div>
    <div class="backup-actions">
      <button class="primary-btn" onclick="labToggle()">${lab.enabled ? 'Pausar medição' : 'Iniciar medição'}</button>
      <button class="secondary-btn" onclick="labDownloadReport()">Gerar relatório</button>
      <button class="secondary-btn" onclick="labClear()">Limpar medições</button>
    </div>`);
}

(function startLabObservers(){
  function frame(now) {
    lab.frames++;
    if (now - lab.lastFpsAt >= 1000) {
      lab.fps = Math.round((lab.frames * 1000) / (now - lab.lastFpsAt));
      lab.fpsMin = Math.min(lab.fpsMin, lab.fps);
      lab.frames = 0;
      lab.lastFpsAt = now;
      if (lab.enabled && document.getElementById('lab-live-metrics')) renderLabPanel();
    }
    requestAnimationFrame(frame);
  }
  requestAnimationFrame(frame);
  try {
    new PerformanceObserver(list => {
      for (const entry of list.getEntries()) {
        lab.longTasks++;
        labRecord('tarefa_longa', entry.duration, { startMs: labRound(entry.startTime) });
      }
    }).observe({ entryTypes: ['longtask'] });
  } catch (_) {}
})();

function invalidateDerivedState() {
  stateRevision++;
  collectionSummaryCache.revision = -1;
  pokemonStatsCache.revision = -1;
  cardResultCache.revision = -1;
  cardResultCache.key = '';
  cardResultCache.value = null;
}

const ui = {
  tab: 'dashboard',
  cardQuery: '',
  cardFilter: 'owned',
  cardSort: 'number',
  cardSet: 'all',
  cardLimit: 40,
  setQuery: '',
  setStatus: 'all',   // all | comecei | completas | faltando | vazias
  setSort: 'recentes', // recentes | antigas | nome | completas | tamanho
  dexQuery: '',
  dexRegion: 'all',
  dexType: 'all',
  dexStatus: 'all',
  dexSort: 'number',
  dexLimit: 180,
  selectedPokemon: null,
};

const esc = value => String(value ?? '')
  .replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;')
  .replaceAll('"', '&quot;').replaceAll("'", '&#039;');

const normalize = value => String(value ?? '')
  .normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase()
  .replace(/[^a-z0-9]+/g, ' ').trim();

const hasFiniteNumber = value => value !== null && value !== undefined && value !== '' && Number.isFinite(Number(value));

const money = value => hasFiniteNumber(value)
  ? Number(value).toLocaleString('pt-BR', { style: 'currency', currency: 'BRL' })
  : 'Sem preço';


function loadStoredObject(key) {
  try {
    const parsed = JSON.parse(localStorage.getItem(key) || '{}');
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed : {};
  } catch (_) {
    return {};
  }
}

function saveStoredObject(key, value) {
  try { localStorage.setItem(key, JSON.stringify(value || {})); } catch (_) {}
}

function loadPricingState() {
  ligaSetCache = loadStoredObject(LIGA_SET_CACHE_KEY);
  variantImageCache = loadStoredObject(VARIANT_IMAGE_CACHE_KEY);
}

function saveLigaSetCache() {
  saveStoredObject(LIGA_SET_CACHE_KEY, ligaSetCache);
}

function saveVariantImageCache() {
  saveStoredObject(VARIANT_IMAGE_CACHE_KEY, variantImageCache);
}

const PRICE_FINISHES = ['normal', 'holo', 'reverse'];
/* Os três idiomas ficam sempre disponíveis em qualquer carta. Antes a lista
   era restringida pelo que a fonte publicava para aquela carta — e como o
   idioma não entra mais no cálculo do valor (o preço cai para o mercado que
   tiver dados), essa restrição só atrapalhava quem tem a carta em português. */
const PRICE_LANGUAGES = ['pt-br', 'en', 'ja'];
const PRICE_LANGUAGE_LABELS = { 'pt-br': 'Português', en: 'Inglês', ja: 'Japonês' };
// Cardmarket e TCGplayer só precificam as tiragens internacional (inglês) e
// japonesa. O Price Database, portanto, não publica chaves `pt-br`. Para que a
// carta brasileira tenha referência de valor, a consulta cai para o mercado que
// realmente possui dados, sempre identificando de onde veio o preço.
const PRICE_LANGUAGE_FALLBACK = ['en', 'ja'];
const PRICE_MARKET_LABELS = { 'pt-br': 'mercado brasileiro', en: 'mercado internacional (inglês)', ja: 'mercado japonês' };

// Cardmarket e TCGplayer publicam um único preço por variante, sem separar
// condição. Estes percentuais são ESTIMATIVA de mercado aplicada sobre esse
// preço-base — não são dado de fonte. São editáveis pelo usuário e o app
// sempre mostra o preço-base ao lado do valor ajustado.
const CONDITION_MULTIPLIER_KEY = 'fichario-pokemon-condition-multipliers-v1';
const DEFAULT_CONDITION_MULTIPLIERS = { nm: 1, sp: 0.85, mp: 0.7, hp: 0.5, dmg: 0.35 };
const CONDITION_LABELS = {
  nm: 'Nova / Quase nova (NM)',
  sp: 'Usada levemente (SP/LP)',
  mp: 'Usada moderadamente (MP)',
  hp: 'Muito usada (HP)',
  dmg: 'Danificada (D)',
};
let conditionMultipliers = { ...DEFAULT_CONDITION_MULTIPLIERS };

function loadConditionMultipliers() {
  try {
    const raw = JSON.parse(localStorage.getItem(CONDITION_MULTIPLIER_KEY) || 'null');
    if (raw && typeof raw === 'object') {
      const next = { ...DEFAULT_CONDITION_MULTIPLIERS };
      for (const key of Object.keys(DEFAULT_CONDITION_MULTIPLIERS)) {
        const value = Number(raw[key]);
        if (Number.isFinite(value) && value > 0 && value <= 10) next[key] = value;
      }
      conditionMultipliers = next;
    }
  } catch (_) { /* mantém os padrões */ }
  return conditionMultipliers;
}

function saveConditionMultipliers() {
  try { localStorage.setItem(CONDITION_MULTIPLIER_KEY, JSON.stringify(conditionMultipliers)); } catch (_) {}
}

function conditionMultiplier(condition) {
  const value = Number(conditionMultipliers[marketConditionKey(condition)]);
  return Number.isFinite(value) && value > 0 ? value : 1;
}

function setConditionMultiplierPercent(condition, percent) {
  const key = marketConditionKey(condition);
  const value = Number(percent) / 100;
  if (!Number.isFinite(value) || value <= 0 || value > 10) return notify('Informe um percentual entre 1 e 1000.');
  conditionMultipliers[key] = value;
  saveConditionMultipliers();
  for (const cardId of Object.keys(state?.entries || {})) persistAutomaticPricesForCard(cardId, false);
  saveState();
  renderKeepingScroll();
  notify(`${CONDITION_LABELS[key] || key}: ${Math.round(value * 100)}% do preço-base.`);
}

function resetConditionMultipliers() {
  conditionMultipliers = { ...DEFAULT_CONDITION_MULTIPLIERS };
  saveConditionMultipliers();
  for (const cardId of Object.keys(state?.entries || {})) persistAutomaticPricesForCard(cardId, false);
  saveState();
  renderKeepingScroll();
  notify('Percentuais de condição restaurados para o padrão de mercado.');
}
const PRICE_PRINT_VARIATIONS = ['unlimited', 'firstEdition'];
const PRICE_STAMPS = ['unstamped', 'stamped'];
const SOURCE_VARIANT_META_KEYS = new Set(['updated', 'unit']);
const SOURCE_PRICE_FIELDS = ['marketPrice', 'midPrice', 'lowPrice', 'highPrice', 'directLowPrice'];

// Ordem de preferência dos mercados. O preço exibido vem de UM mercado só — o
// primeiro desta lista que tiver valor para a versão escolhida —, não de uma
// mistura dos três. TCGdex não publica preço próprio: ele republica TCGplayer
// e Cardmarket, então na prática a disputa é entre o primeiro e o terceiro.
const PRICE_SOURCE_PRIORITY = ['tcgplayer', 'tcgdex', 'cardmarket'];
const PRICE_SOURCE_LABELS = { tcgplayer: 'TCGplayer', tcgdex: 'TCGdex', cardmarket: 'Cardmarket' };

function providerFromSourceId(id) {
  return String(id || '').split(':')[0];
}

function priorityMarketValues(sources) {
  const byProvider = new Map();
  for (const item of sources || []) {
    const value = Number(item?.valueBrl);
    if (!Number.isFinite(value) || value <= 0) continue;
    const provider = providerFromSourceId(item.source);
    if (!byProvider.has(provider)) byProvider.set(provider, []);
    byProvider.get(provider).push(value);
  }
  for (const provider of PRICE_SOURCE_PRIORITY) {
    const values = byProvider.get(provider);
    if (values?.length) return { provider, values };
  }
  // Fonte nova que ainda não está na lista: melhor usar do que descartar.
  for (const [provider, values] of byProvider) {
    if (values.length) return { provider, values };
  }
  return { provider: '', values: [] };
}

function exactSourceEnum(value) {
  return String(value ?? '').trim();
}

function finishKind(finish) {
  const value = exactSourceEnum(finish);
  if (!value) return 'normal';
  return PRICE_FINISHES.includes(value) ? value : value;
}

function marketBaseFinishKind(kind) {
  return finishKind(kind);
}

function isSpecialPriceFinish() {
  return false;
}

function finishPriceLabel(kind) {
  return exactSourceEnum(kind) || 'normal';
}

const CARD_FINISH_DEFINITIONS = [
  ['normal', 'normal', 'normal', 'Standard non-foil'],
  ['holo', 'holo', 'holo', 'Holofoil'],
  ['reverse', 'reverse', 'reverse', 'Reverse holofoil'],
];

function finishValueFromKind(kind) {
  return finishKind(kind);
}

function inferCardArtVariant() {
  return 'standard';
}

function uniqueValues(values, fallback = []) {
  return [...new Set([...(values || []), ...fallback].map(exactSourceEnum).filter(Boolean))];
}

function uniqueFinishValues(values, fallback = []) {
  return uniqueValues([...(values || []), ...fallback].map(finishKind));
}

function hasSourcePriceObject(value) {
  return Boolean(value && typeof value === 'object' && SOURCE_PRICE_FIELDS.some(field => Number(value[field]) > 0));
}

function mergeSourceVariantDetails(...groups) {
  const merged = new Map();
  for (const group of groups) {
    for (const raw of group || []) {
      const item = typeof raw === 'string' ? { value: raw } : (raw || {});
      const value = exactSourceEnum(item.value);
      if (!value) continue;
      const current = merged.get(value) || { value, sources: [], kinds: [], priced: false, languages: [] };
      current.sources = uniqueValues([...(current.sources || []), ...(item.sources || []), item.source]);
      current.kinds = uniqueValues([...(current.kinds || []), ...(item.kinds || []), item.kind]);
      current.languages = uniqueValues([...(current.languages || []), ...(item.languages || []), item.language]);
      current.priced = Boolean(current.priced || item.priced);
      merged.set(value, current);
    }
  }
  return [...merged.values()].sort((a, b) => {
    if (a.priced !== b.priced) return a.priced ? -1 : 1;
    return a.value.localeCompare(b.value, 'en');
  });
}

function sourceVariantEnumsFromTcgDexDetail(detail, language = '') {
  const found = [];
  const variants = detail?.variants && typeof detail.variants === 'object' ? detail.variants : {};
  for (const [key, value] of Object.entries(variants)) {
    if (value === true || (value != null && value !== false && value !== '')) {
      found.push({ value: key, sources: ['tcgdex'], kinds: ['tcgdex-flag'], priced: false, language });
    }
  }
  const tcgplayer = detail?.pricing?.tcgplayer && typeof detail.pricing.tcgplayer === 'object' ? detail.pricing.tcgplayer : {};
  for (const [key, value] of Object.entries(tcgplayer)) {
    if (SOURCE_VARIANT_META_KEYS.has(key) || !value || typeof value !== 'object') continue;
    found.push({ value: key, sources: ['tcgplayer'], kinds: ['market-variant'], priced: false, language });
  }
  const cardmarket = detail?.pricing?.cardmarket && typeof detail.pricing.cardmarket === 'object' ? detail.pricing.cardmarket : {};
  const entries = Object.entries(cardmarket);
  if (entries.some(([key]) => !SOURCE_VARIANT_META_KEYS.has(key) && !key.endsWith('-holo'))) {
    found.push({ value: 'normal', sources: ['cardmarket'], kinds: ['market-variant'], priced: false, language });
  }
  if (entries.some(([key]) => key.endsWith('-holo'))) {
    found.push({ value: 'holo', sources: ['cardmarket'], kinds: ['market-variant'], priced: false, language });
  }
  for (const [key, value] of entries) {
    if (SOURCE_VARIANT_META_KEYS.has(key) || !value || typeof value !== 'object') continue;
    found.push({ value: key, sources: ['cardmarket'], kinds: ['market-variant'], priced: false, language });
  }
  return mergeSourceVariantDetails(found);
}

function centralVariantEntries(cardId, language = '') {
  const list = Array.isArray(centralPriceData?.variantCatalog?.[cardId]) ? centralPriceData.variantCatalog[cardId] : [];
  if (!language) return list;
  const own = list.filter(item => !item?.language || item.language === language);
  // O catálogo pt-br do TCGdex só marca "normal". Os acabamentos realmente
  // precificados (holo, reverse-holofoil, etc.) vivem nas tiragens que
  // Cardmarket/TCGplayer cobrem, então também são oferecidos como referência.
  const fallback = list.filter(item => item?.language && item.language !== language && PRICE_LANGUAGE_FALLBACK.includes(item.language));
  return [...own, ...fallback];
}

function pricingVariantDetailsForCard(card, language = '') {
  const loaded = scannerVariantAvailability.get(card?.id || '');
  const saved = variantsFor(card?.id || '').map(item => ({
    value: item.pricingVariant,
    sources: ['fichario'],
    kinds: ['saved'],
    priced: Boolean(centralPriceResolveKey(card?.id || '', item.language || language || 'pt-br', item.pricingVariant)),
    language: item.language || '',
  }));
  const localFlags = Object.entries(card?.variants || {})
    .filter(([, value]) => value === true || (value != null && value !== false && value !== ''))
    .map(([value]) => ({ value, sources: ['tcgdex'], kinds: ['tcgdex-flag'], priced: false, language }));
  return mergeSourceVariantDetails(
    centralVariantEntries(card?.id || '', language),
    (loaded?.pricingVariantDetails || []).filter(item => !language || item.language === language || (item.languages || []).includes(language)),
    saved.filter(item => !language || !item.language || item.language === language),
    localFlags,
  );
}

function localCardVariationProfile(card) {
  const remote = card?.variants && typeof card.variants === 'object' ? card.variants : {};
  const saved = variantsFor(card?.id || '');
  const finishes = [];
  if (remote.normal !== false) finishes.push('normal');
  if (remote.holo === true) finishes.push('holo');
  if (remote.reverse === true) finishes.push('reverse');
  saved.forEach(item => finishes.push(item.finish));
  const pricingDetails = pricingVariantDetailsForCard(card);
  return {
    loading: false,
    source: Object.keys(remote).length ? 'Catálogo TCGdex + Price Database' : 'Price Database',
    finishes: uniqueFinishValues(finishes, finishes.length ? [] : ['normal']),
    languages: uniqueValues(saved.map(item => item.language), PRICE_LANGUAGES),
    editions: uniqueValues(saved.map(item => item.edition), remote.firstEdition === true ? ['unlimited', 'firstEdition'] : ['unlimited']),
    distributions: uniqueValues(saved.map(item => item.distribution), remote.wPromo === true ? ['unstamped', 'stamped'] : ['unstamped']),
    artVariants: ['standard'],
    pricingVariantDetails: pricingDetails,
    pricingVariants: pricingDetails.map(item => item.value),
  };
}

function cardVariationProfile(card) {
  const local = localCardVariationProfile(card);
  const loaded = scannerVariantAvailability.get(card?.id);
  if (!loaded) return local;
  const pricingDetails = mergeSourceVariantDetails(local.pricingVariantDetails, loaded.pricingVariantDetails);
  return {
    ...local,
    ...loaded,
    finishes: loaded.finishes?.length ? uniqueFinishValues(loaded.finishes) : local.finishes,
    languages: loaded.languages?.length ? uniqueValues(loaded.languages) : local.languages,
    editions: loaded.editions?.length ? uniqueValues(loaded.editions) : local.editions,
    distributions: loaded.distributions?.length ? uniqueValues(loaded.distributions) : local.distributions,
    artVariants: ['standard'],
    pricingVariantDetails: pricingDetails,
    pricingVariants: pricingDetails.map(item => item.value),
  };
}

// Nomes em português para os códigos que as fontes publicam. O usuário nunca
// deve ver "reverse-holofoil"; o código continua sendo o mesmo por baixo.
const VARIANT_FRIENDLY_LABELS = {
  'normal': 'Comum',
  'holo': 'Holográfica',
  'holofoil': 'Holográfica',
  'reverse': 'Reverse Holo',
  'reverse-holofoil': 'Reverse Holo',
  '1st-edition': '1ª Edição',
  '1st-edition-holofoil': '1ª Edição holográfica',
  'unlimited': 'Tiragem normal',
  'unlimited-holofoil': 'Tiragem normal holográfica',
  'firstEdition': '1ª Edição',
  'unstamped': 'Sem carimbo',
  'stamped': 'Com carimbo',
};

function friendlyVariantLabel(value) {
  const exact = exactSourceEnum(value);
  return VARIANT_FRIENDLY_LABELS[exact] || exact;
}

// Cada acabamento tem sua própria cara, como as etiquetas da carta física.
const VARIANT_ESTILO = {
  'normal': { classe: 'v-comum', icone: '◻' },
  'holo': { classe: 'v-holo', icone: '✦' },
  'holofoil': { classe: 'v-holo', icone: '✦' },
  'reverse': { classe: 'v-reverse', icone: '◧' },
  'reverse-holofoil': { classe: 'v-reverse', icone: '◧' },
  '1st-edition': { classe: 'v-primeira', icone: '❶' },
  '1st-edition-holofoil': { classe: 'v-primeira', icone: '❶' },
  'unlimited': { classe: 'v-comum', icone: '◻' },
  'unlimited-holofoil': { classe: 'v-holo', icone: '✦' },
};

function variantEstilo(value) {
  return VARIANT_ESTILO[exactSourceEnum(value)] || { classe: 'v-outra', icone: '◈' };
}

/**
 * Versões que valem a pena mostrar como botão.
 *
 * 1. Só entram as que têm preço de verdade no banco. O idioma não conta aqui:
 *    se a versão só tem valor no mercado inglês, ela continua sendo uma versão
 *    válida da carta — é a mesma regra que o preço já usa.
 * 2. Nomes repetidos viram um botão só. "reverse" e "reverse-holofoil" são a
 *    mesma coisa para quem olha a carta; mostrar os dois só confunde.
 * 3. Se nenhuma tiver preço, mostramos todas — melhor uma escolha imperfeita
 *    do que um bloco vazio.
 */
function variantesVisiveis(cardId, valores, selecionada, language = '') {
  const idioma = language || document.getElementById('regLanguage')?.value || 'pt-br';
  const lista = (valores || []).filter(Boolean);
  const comPreco = lista.filter(value => Boolean(centralPriceResolveKey(cardId, idioma, value)));
  let base = comPreco.length ? comPreco : lista;
  if (selecionada && !base.includes(selecionada) && lista.includes(selecionada)) base = [...base, selecionada];

  const porRotulo = new Map();
  for (const value of base) {
    const rotulo = friendlyVariantLabel(value);
    // A versão escolhida sempre ganha a vaga do nome dela.
    if (!porRotulo.has(rotulo) || value === selecionada) porRotulo.set(rotulo, value);
  }
  return [...porRotulo.values()];
}

function pillHtml(cardId, value, ativo, semPreco) {
  const estilo = variantEstilo(value);
  const marca = ativo ? ' ativo' : '';
  const aviso = semPreco ? ' sem-preco' : '';
  const titulo = semPreco ? 'Sem preço publicado para esta versão' : '';
  return `<button type="button" class="variante-pill ${estilo.classe}${marca}${aviso}"
    data-pill="${esc(value)}"${titulo ? ` title="${esc(titulo)}"` : ''}
    onclick="escolherVariante('${esc(cardId)}','${esc(value)}')">
    <span class="variante-icone" aria-hidden="true">${estilo.icone}</span>${esc(friendlyVariantLabel(value))}
  </button>`;
}

function pillsHtml(cardId, valores, valorAtual, idioma) {
  return valores
    .map(value => pillHtml(cardId, value, value === valorAtual, !centralPriceResolveKey(cardId, idioma, value)))
    .join('');
}

function variantPillsHtml(card, draft) {
  const idioma = draft.language || document.getElementById('regLanguage')?.value || scannerSession.language || 'pt-br';
  const valores = pricingVariantDetailsForCard(card, idioma).map(item => item.value);
  // A versão já salva nesta carta entra na lista mesmo que a fonte não a
  // ofereça mais, senão o botão dela sumiria da tela.
  if (draft.pricingVariant && !valores.includes(draft.pricingVariant)) valores.push(draft.pricingVariant);
  const visiveis = variantesVisiveis(card.id, valores, draft.pricingVariant, idioma);
  if (!visiveis.length) return '';
  const atual = visiveis.includes(draft.pricingVariant) ? draft.pricingVariant : visiveis[0];
  return `<div class="variante-pills destaque" id="regVariantePills">${pillsHtml(card.id, visiveis, atual, idioma)}</div>`;
}

// Redesenha os botões depois que a lista escondida foi recalculada (troca de
// idioma, carta recém-consultada na fonte, etc.).
function reconstruirPills(card) {
  const grupo = document.getElementById('regVariantePills');
  const select = document.getElementById('regPricingVariant');
  if (!grupo || !select) return;
  const idioma = document.getElementById('regLanguage')?.value || 'pt-br';
  const visiveis = variantesVisiveis(card.id, [...select.options].map(opcao => opcao.value), select.value, idioma);
  if (!visiveis.length) return;
  // A lista escondida acompanha os botões: ela é quem o formulário lê ao salvar.
  if (!visiveis.includes(select.value)) {
    select.value = visiveis[0];
    sincronizarAcabamentoComVariante(select.value);
  }
  grupo.innerHTML = pillsHtml(card.id, visiveis, select.value, idioma);
}

// Deixa aceso o botão escolhido, venha o clique do usuário ou a escolha
// automática do app.
function marcarPillAtiva(valor) {
  const grupo = document.getElementById('regVariantePills');
  if (!grupo) return;
  grupo.querySelectorAll('.variante-pill').forEach(botao => {
    botao.classList.toggle('ativo', botao.dataset.pill === valor);
  });
}

// O acabamento não tem mais botão próprio: ele é consequência da versão
// escolhida, e continua existindo escondido porque o cálculo do preço o lê.
function sincronizarAcabamentoComVariante(valor) {
  const finish = document.getElementById('regFinish');
  if (!finish) return;
  const alvo = /reverse/i.test(valor) ? 'reverse' : /holo/i.test(valor) ? 'holo' : 'normal';
  if (![...finish.options].some(opcao => opcao.value === alvo)) finish.add(new Option(alvo, alvo));
  finish.value = alvo;
}

function escolherVariante(cardId, valor) {
  const campo = document.getElementById('regPricingVariant');
  if (campo) {
    if (![...campo.options].some(opcao => opcao.value === valor)) campo.add(new Option(valor, valor));
    campo.value = valor;
  }
  marcarPillAtiva(valor);
  sincronizarAcabamentoComVariante(valor);
  handleRegistrationVariantChange(cardId, 'variante');
}

/**
 * Escolhe sozinho a variante exata a partir do acabamento, da edição e do
 * idioma marcados. A lista de opções muda de carta para carta — quem manda é
 * o que as fontes publicam para aquela carta — por isso não dá para deduzir
 * por regra fixa: aqui pontuamos as opções reais e ficamos com a melhor.
 */
function derivePricingVariant(card, { finish, edition, language, current } = {}) {
  const todas = pricingVariantDetailsForCard(card, language).map(item => item.value).filter(Boolean);
  // Só concorrem as versões que o usuário consegue ver na tela; escolher
  // sozinho uma versão sem botão deixaria a seleção invisível.
  const visiveis = variantesVisiveis(card?.id || '', todas, exactSourceEnum(current), language);
  const options = visiveis.length ? visiveis : todas;
  if (!options.length) return exactSourceEnum(current) || 'normal';
  if (options.length === 1) return options[0];

  const wantedFinish = normalize(finish || 'normal');
  const firstEdition = normalize(edition) === normalize('firstEdition');
  let best = null;
  let bestScore = -Infinity;

  for (const value of options) {
    const v = normalize(value);
    const isReverse = v.includes('reverse');
    const isHolo = v.includes('holo') && !isReverse;
    let score = 0;

    if (wantedFinish === 'reverse') score += isReverse ? 12 : -6;
    else if (wantedFinish === 'holo') score += isHolo ? 12 : -6;
    else score += (!isReverse && !isHolo) ? 12 : -6;

    if (firstEdition) score += v.includes('1st') ? 8 : -4;
    else score += v.includes('1st') ? -4 : 2;

    // Entre empates, vale mais a opção que realmente tem preço publicado.
    if (centralPriceResolveKey(card?.id || '', language || 'pt-br', value)) score += 3;
    // Mantém a escolha atual quando ela continua sendo válida.
    if (exactSourceEnum(current) === value) score += 1;

    if (score > bestScore) { bestScore = score; best = value; }
  }
  return best || options[0];
}

function priceDimensionLabel(field, value) {
  if (field === 'finishes') return finishPriceLabel(value);
  return friendlyVariantLabel(value);
}

function optionListForCard(card, field, selected, preserveSelected = true, language = '') {
  const profile = cardVariationProfile(card);
  let values;
  if (field === 'pricingVariants') {
    const effectiveLanguage = language || document.getElementById('regLanguage')?.value || scannerSession.language || 'pt-br';
    const details = pricingVariantDetailsForCard(card, effectiveLanguage);
    const byValue = new Map(details.map(item => [item.value, item]));
    values = uniqueValues(details.map(item => item.value), preserveSelected && selected ? [selected] : []);
    return values.map(value => {
      const item = byValue.get(value);
      const source = item?.sources?.length ? ` · ${item.sources.join('+')}` : '';
      const availability = item ? (item.priced ? ' · com preço' : ' · sem preço exato') : '';
      return option(value, `${value}${source}${availability}`, selected);
    }).join('');
  }
  values = uniqueValues(profile[field] || [], preserveSelected && selected ? [selected] : []);
  return values.map(value => option(value, priceDimensionLabel(field, value), selected)).join('');
}

function marketLanguageKey(value) {
  return exactSourceEnum(value) || 'pt-br';
}

function marketConditionKey(value) {
  const normalized = normalize(value);
  if (/praticamente nova|near mint|\bnm\b|\bmint\b/.test(normalized)) return 'nm';
  if (/usada levemente|slightly played|lightly played|\bsp\b|\blp\b|excelente/.test(normalized)) return 'sp';
  if (/usada moderadamente|moderately played|\bmp\b|\bplayed\b|\bbom\b/.test(normalized)) return 'mp';
  if (/muito usada|heavily played|\bhp\b|\bregular\b/.test(normalized)) return 'hp';
  if (/danificada|damaged|\bdmg\b/.test(normalized)) return 'dmg';
  return 'nm';
}

function marketIdentityToken(value, fallback) {
  const normalized = normalize(value || fallback || '');
  return normalized.replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '') || fallback;
}

function marketTagToken(value) {
  return marketIdentityToken(value, '');
}

function marketVariantIdentity(value = {}) {
  const source = typeof value === 'string' ? { pricingVariant: value } : (value || {});
  const language = exactSourceEnum(source.language) || 'pt-br';
  const pricingVariant = exactSourceEnum(source.pricingVariant ?? source.variantEnum) || 'normal';
  const finish = exactSourceEnum(source.finish) || 'normal';
  const edition = exactSourceEnum(source.edition) || 'unlimited';
  const distribution = exactSourceEnum(source.distribution) || 'unstamped';
  const tags = (Array.isArray(source.variantTags) ? source.variantTags : (Array.isArray(source.tags) ? source.tags : []))
    .map(marketTagToken).filter(Boolean).sort();
  return {
    pricingVariant,
    finish,
    language,
    condition: marketConditionKey(source.condition || 'Near Mint'),
    edition,
    distribution,
    artVariant: exactSourceEnum(source.artVariant) || 'standard',
    region: exactSourceEnum(source.region) || (language === 'pt-br' ? 'Brasil' : 'Internacional'),
    gradingCompany: marketIdentityToken(source.gradingCompany, 'nao-graduada'),
    grade: marketIdentityToken(source.grade, 'sem-nota'),
    tags,
  };
}

function marketVariantKey(value = {}) {
  const identity = marketVariantIdentity(value);
  return [identity.pricingVariant, identity.language, identity.condition, identity.edition, identity.distribution, identity.artVariant, identity.region, identity.gradingCompany, identity.grade, identity.tags.join('+') || 'sem-tags'].join('|');
}

function marketVariantDescription(value = {}) {
  const identity = marketVariantIdentity(value);
  const conditions = { nm: 'NM', sp: 'SP/LP', mp: 'MP', hp: 'HP', dmg: 'Damaged' };
  const extras = [identity.language, conditions[identity.condition] || identity.condition];
  if (identity.gradingCompany !== 'nao-graduada') extras.push(`${identity.gradingCompany}${identity.grade !== 'sem-nota' ? ` ${identity.grade}` : ''}`);
  if (identity.tags.length) extras.push(identity.tags.join(', '));
  return `${identity.pricingVariant} · ${extras.join(' · ')}`;
}

function ligaNumberPart(value, width = 3) {
  const raw = String(value || '').trim();
  const match = raw.match(/^([A-Za-z]*)(\d+)$/);
  if (!match) return raw;
  const prefix = match[1].toUpperCase();
  const digits = match[2];
  return `${prefix}${digits.padStart(prefix ? digits.length : width, '0')}`;
}

function ligaCardNumber(card) {
  const rawNumber = String(card?.number || '');
  const [rawNumerator, rawDenominator = ''] = rawNumber.split('/');
  const rawLocal = String(card?.localId || rawNumerator || '').trim();
  const prefixMatch = rawLocal.match(/^([A-Za-z]+)(\d+)$/);
  let numerator = ligaNumberPart(rawLocal, 3);
  let denominator = String(rawDenominator || '').trim();
  if (prefixMatch && /^\d+$/.test(denominator) && /^(TG|GG|SV)$/i.test(prefixMatch[1])) {
    denominator = `${prefixMatch[1].toUpperCase()}${denominator.padStart(prefixMatch[2].length, '0')}`;
  } else if (/^\d+$/.test(denominator)) {
    denominator = denominator.padStart(3, '0');
  }
  return { numerator, full: denominator ? `${numerator}/${denominator}` : numerator };
}

async function ligaSetCode(setId) {
  const localSet = (catalog?.sets || []).find(item => item.id === setId);
  const localCode = String(localSet?.tcgOnline || '').trim();
  if (localCode) return localCode.toUpperCase();
  const cached = ligaSetCache[setId];
  if (cached && String(cached.code || '').trim()) return String(cached.code).trim().toUpperCase();
  const detail = await fetchJsonWithTimeout(`${TCGDEX_API_BASE}/sets/${encodeURIComponent(setId)}`, 30000);
  const code = String(detail?.tcgOnline || '').trim().toUpperCase();
  ligaSetCache[setId] = { code: code || null, fetchedAt: Date.now() };
  saveLigaSetCache();
  if (code && localSet) localSet.tcgOnline = code;
  return code || null;
}



function openCentralPriceDatabase() {
  return new Promise((resolve, reject) => {
    if (!window.indexedDB) return reject(new Error('IndexedDB indisponível'));
    const request = indexedDB.open(CENTRAL_PRICE_DB_NAME, 1);
    request.onupgradeneeded = () => {
      const db = request.result;
      if (!db.objectStoreNames.contains(CENTRAL_PRICE_DB_STORE)) db.createObjectStore(CENTRAL_PRICE_DB_STORE);
    };
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error || new Error('Falha ao abrir banco de preços'));
  });
}

function centralCachePayload() {
  return {
    meta: centralPriceStatus || centralPriceData.meta || {},
    prices: centralPriceData.prices || {},
    variantCatalog: centralPriceData.variantCatalog || {},
    index: centralPriceIndex,
    loadedShards: [...centralPriceLoadedShards],
  };
}

async function loadCentralPriceCache() {
  try {
    const db = await openCentralPriceDatabase();
    const cached = await new Promise((resolve, reject) => {
      const tx = db.transaction(CENTRAL_PRICE_DB_STORE, 'readonly');
      const req = tx.objectStore(CENTRAL_PRICE_DB_STORE).get(CENTRAL_PRICE_DB_KEY);
      req.onsuccess = () => resolve(req.result || null);
      req.onerror = () => reject(req.error);
    });
    db.close();
    if (Number(cached?.meta?.schemaVersion) === 4 && cached?.prices && cached?.index?.cards) {
      centralPriceData = {
        meta: cached.meta || {},
        prices: cached.prices || {},
        variantCatalog: cached.variantCatalog || {},
      };
      centralPriceStatus = cached.meta || {};
      centralPriceIndex = cached.index;
      centralPriceLoadedShards = new Set(Array.isArray(cached.loadedShards) ? cached.loadedShards.map(Number) : []);
    }
  } catch (_) {}
  return centralPriceData;
}

async function saveCentralPriceCache(payload = centralCachePayload()) {
  const db = await openCentralPriceDatabase();
  await new Promise((resolve, reject) => {
    const tx = db.transaction(CENTRAL_PRICE_DB_STORE, 'readwrite');
    tx.objectStore(CENTRAL_PRICE_DB_STORE).put(payload, CENTRAL_PRICE_DB_KEY);
    tx.oncomplete = () => resolve();
    tx.onerror = () => reject(tx.error);
  });
  db.close();
}

function centralPriceGeneratedAt() {
  return centralPriceData?.meta?.generatedAt || centralPriceStatus?.generatedAt || null;
}

// Reproduz o número como vem impresso na carta: "015/094", e não "015/94".
// O total é preenchido com zeros até a largura do número local quando ambos
// são numéricos; prefixos alfanuméricos (SM108, TG12) ficam intactos.
function formatCardNumber(number, setTotal) {
  const local = String(number ?? '').trim();
  if (!local) return '';
  const total = String(setTotal ?? '').trim();
  if (!total) return local;
  if (/^\d+$/.test(local) && /^\d+$/.test(total)) {
    return `${local}/${total.padStart(local.length, '0')}`;
  }
  return `${local}/${total}`;
}

// Nenhuma fonte publica preço separado para carta carimbada (staff, prerelease,
// league, championship). Quando a versão carimbada existe como CARTA PRÓPRIA no
// catálogo — o caso mais comum em sets promocionais — ela tem preço real e o app
// sugere trocar para ela em vez de estimar ágio, que varia demais para ser
// calculado por multiplicador.
function stampedCounterpartsFor(cardId) {
  const card = cardMap.get(cardId);
  if (!card) return [];
  const target = normalize(card.name);
  if (!target) return [];
  const found = [];
  for (const [id, other] of cardMap) {
    if (id === cardId || normalize(other.name) !== target) continue;
    const isPromo = Boolean(other.promotional)
      || /promo|prerelease|pré-lançamento|staff|league|championship|campeonato/i.test(`${other.setName || ''} ${other.setId || ''}`);
    if (!isPromo) continue;
    found.push({ id, name: other.name, setName: other.setName || '', number: other.localId || other.number || '' });
    if (found.length >= 6) break;
  }
  return found;
}

function centralPriceKeyExists(key) {
  const match = centralPriceData?.prices?.[key];
  return Boolean(match && hasFiniteNumber(match.priceBrl) && Number(match.priceBrl) > 0);
}

// Resolve a chave `cardId::idioma::variantEnum` que realmente existe no banco,
// tentando primeiro o idioma da variante e depois os mercados que publicam preço.
function centralPriceResolveKey(cardId, language, variantEnum) {
  for (const candidate of uniqueValues([language, ...PRICE_LANGUAGE_FALLBACK])) {
    const key = `${cardId}::${candidate}::${variantEnum}`;
    if (centralPriceKeyExists(key)) return { key, language: candidate };
  }
  return null;
}

function centralPriceCompatibility(cardId, value = {}) {
  const identity = marketVariantIdentity(value);
  if (!cardId || !identity.language || !identity.pricingVariant) return null;
  if (identity.gradingCompany !== 'nao-graduada' || identity.tags.length) return null;
  const resolved = centralPriceResolveKey(cardId, identity.language, identity.pricingVariant);
  if (!resolved) return null;

  const fallbackLanguage = resolved.language !== identity.language;
  const multiplier = conditionMultiplier(identity.condition);
  const conditionEstimated = multiplier !== 1;
  // Carimbo não é precificado por nenhuma fonte: o valor do banco é sempre o da
  // versão sem carimbo. Isso não pode entrar sozinho no total da coleção.
  const stamped = identity.distribution === 'stamped';

  const reasons = [];
  if (conditionEstimated) {
    reasons.push(`Nenhuma fonte separa condição: valor estimado em ${Math.round(multiplier * 100)}% do preço-base NM para ${CONDITION_LABELS[identity.condition] || identity.condition}.`);
  }
  if (fallbackLanguage) {
    reasons.push(`Cardmarket e TCGplayer não publicam preço da tiragem ${identity.language}; o valor exibido é a referência do ${PRICE_MARKET_LABELS[resolved.language] || resolved.language}.`);
  }
  if (stamped) {
    reasons.push('O valor exibido é o da versão SEM carimbo — nenhuma fonte precifica carimbo. Informe o valor manual ou use a carta carimbada correspondente.');
  }
  return {
    key: resolved.key,
    identity,
    language: resolved.language,
    requestedLanguage: identity.language,
    fallbackLanguage,
    variantEnum: identity.pricingVariant,
    conditionMultiplier: multiplier,
    conditionEstimated,
    stamped,
    // A identidade é exata quando não há carimbo sem preço próprio. A condição
    // é tratada por multiplicador declarado, não invalida a correspondência.
    exact: !stamped,
    reasons,
  };
}

function centralPriceQuote(cardId, variant = 'normal') {
  const prices = centralPriceData?.prices || {};
  const compatibility = centralPriceCompatibility(cardId, variant);
  if (!compatibility) return null;
  const match = prices[compatibility.key];
  if (!match || !hasFiniteNumber(match.priceBrl) || Number(match.priceBrl) <= 0) return null;

  const sources = Array.isArray(match.sources) ? match.sources : [];
  const confidenceNumber = Math.max(0, Math.min(100, Number(match.confidence) || 0));
  const reasons = [...compatibility.reasons];
  const verified = compatibility.exact && match.matchLevel === 'exact';
  const identity = compatibility.identity;
  // Um mercado só, na ordem de preferência: TCGplayer, TCGdex, Cardmarket.
  // Misturar os três numa média única esconde de onde veio o número e mistura
  // mercados com liquidez muito diferente.
  const providers = new Set(sources.map(item => providerFromSourceId(item.source)).filter(Boolean));
  // Banco novo já publica o mercado que usou; banco antigo não tem esse campo.
  const publishedMarket = String(match.priceMarket || '').trim();
  const usedMarket = publishedMarket || (providers.size > 1 ? priorityMarketValues(sources).provider : [...providers][0] || '');
  const sourceValues = (usedMarket ? sources.filter(item => providerFromSourceId(item.source) === usedMarket) : sources)
    .map(item => Number(item?.valueBrl))
    .filter(value => Number.isFinite(value) && value > 0);

  // Quem calcula o preço é o banco: `priceBrl` é a resposta oficial. O app só
  // refaz a conta no caso em que o banco publicado ainda é antigo E a lista
  // mistura mais de um mercado — aí a prioridade muda o número e vale a pena
  // aplicá-la já, sem esperar a próxima reconstrução. Com um mercado só, a
  // prioridade não altera nada e o valor publicado fica como está.
  const recalcular = !publishedMarket && providers.size > 1 && sourceValues.length > 0;
  const basePriceBrl = recalcular
    ? Math.round((sourceValues.reduce((sum, value) => sum + value, 0) / sourceValues.length) * 100) / 100
    : Number(match.priceBrl);
  if (usedMarket) {
    reasons.push(`Valor do ${PRICE_SOURCE_LABELS[usedMarket] || usedMarket} (${sourceValues.length} referência(s)) — mercado de maior prioridade com preço para esta versão.`);
  }
  const adjustedBrl = Math.round(basePriceBrl * compatibility.conditionMultiplier * 100) / 100;
  return {
    brl: adjustedBrl,
    basePriceBrl,
    conditionMultiplier: compatibility.conditionMultiplier,
    conditionEstimated: compatibility.conditionEstimated,
    stamped: compatibility.stamped,
    stampedCounterparts: compatibility.stamped ? stampedCounterpartsFor(cardId) : [],
    value: null,
    currency: 'BRL',
    label: 'Price Database',
    source: 'preco-brasil',
    provider: 'Pokémon Price Database Brasil',
    fetchedAt: new Date(match.updatedAt || centralPriceGeneratedAt() || Date.now()).getTime(),
    confidence: verified ? 'verified' : 'review',
    confidencePercent: confidenceNumber,
    verified,
    usable: verified,
    // O multiplicador entra na impressão digital: mudar a tabela de condição
    // invalida as confirmações manuais feitas sobre o valor anterior.
    fingerprint: ['preco-brasil', compatibility.key, basePriceBrl, usedMarket, compatibility.conditionMultiplier, match.updatedAt || centralPriceGeneratedAt() || ''].join('|'),
    priceLanguage: compatibility.language,
    requestedLanguage: compatibility.requestedLanguage,
    fallbackLanguage: compatibility.fallbackLanguage,
    validation: {
      reasons,
      checks: [
        `cardId ${cardId}`,
        // Identidade exata publicada pelo banco: coleção + número local +
        // total impresso ("015/094"), para conferir que é mesmo esta carta.
        match.setId ? `set ${match.setId}${match.setName ? ` (${match.setName})` : ''}` : '',
        match.number ? `nº ${formatCardNumber(match.number, match.setTotal)}` : '',
        match.rarity ? `raridade ${match.rarity}` : '',
        compatibility.fallbackLanguage
          ? `language ${compatibility.requestedLanguage} → ${compatibility.language} (${PRICE_MARKET_LABELS[compatibility.language] || compatibility.language})`
          : `language ${compatibility.language}`,
        `variantEnum ${compatibility.variantEnum}`,
        usedMarket
          ? `mercado ${PRICE_SOURCE_LABELS[usedMarket] || usedMarket} · ${sourceValues.length} de ${sources.length} valor(es)`
          : `${sources.length} valor(es) de fonte`,
      ].filter(Boolean),
    },
    priceMarket: usedMarket,
    priceMarketLabel: PRICE_SOURCE_LABELS[usedMarket] || usedMarket,
    sources,
    // As referências acompanham o mesmo ajuste de condição do preço exibido.
    low: Math.round((sourceValues.length ? Math.min(...sourceValues) : basePriceBrl) * compatibility.conditionMultiplier * 100) / 100,
    high: Math.round((sourceValues.length ? Math.max(...sourceValues) : basePriceBrl) * compatibility.conditionMultiplier * 100) / 100,
    acceptedCount: sourceValues.length || 1,
    estimatedDimensions: [],
    marketVariantKey: marketVariantKey(identity),
    marketIdentity: identity,
    databaseKey: compatibility.key,
    central: true,
  };
}

function centralShardFileName(index) {
  return `shard-${String(index).padStart(2, '0')}.json`;
}

/* =====================================================================
   Adiantar os lotes das cartas que estão na tela

   As etiquetas de versão e o dourado da carta completa dependem de saber
   quais versões a carta tem — e isso vem do Price Database, em lotes.
   Antes o lote só era baixado quando a carta era ABERTA, então a lista
   ficava um tempo mostrando só as versões que o usuário já cadastrou, e as
   demais apareciam aos poucos.

   Aqui os lotes das cartas visíveis são buscados juntos, em segundo plano.
   Cada lote cobre centenas de cartas, então normalmente são dois ou três
   pedidos para a lista inteira — não um por carta.
   ===================================================================== */
let lotesEmAndamento = new Set();

function adiantarLotesDaLista(listaDeCartas) {
  if (!centralPriceIndex?.cards) return;
  const lotes = new Set();
  for (const card of listaDeCartas.slice(0, 60)) {
    const indice = Number(centralPriceIndex.cards[card.id]);
    if (Number.isInteger(indice) && indice >= 0
      && !centralPriceLoadedShards.has(indice) && !lotesEmAndamento.has(indice)) {
      lotes.add(indice);
    }
  }
  if (!lotes.size) return;

  // Poucos por vez: a lista precisa continuar rolando sem travar.
  for (const indice of [...lotes].slice(0, 3)) {
    lotesEmAndamento.add(indice);
    const carta = listaDeCartas.find(item => Number(centralPriceIndex.cards[item.id]) === indice);
    if (!carta) { lotesEmAndamento.delete(indice); continue; }
    ensureCentralPriceShard(carta.id)
      .then(carregou => { if (carregou) renderKeepingScroll(); })
      .catch(() => {})
      .then(() => { lotesEmAndamento.delete(indice); });
  }
}

async function ensureCentralPriceShard(cardId, force = false) {
  const shardIndex = Number(centralPriceIndex?.cards?.[cardId]);
  if (!Number.isInteger(shardIndex) || shardIndex < 0) throw new Error(`Carta ${cardId} não encontrada no índice do Price Database.`);
  if (!force && centralPriceLoadedShards.has(shardIndex)) return false;

  const payload = await fetchJsonWithTimeout(`${CENTRAL_PRICE_SHARD_BASE}/${centralShardFileName(shardIndex)}?t=${Date.now()}`, 60000);
  if (!payload?.prices || !payload?.variantCatalog || Number(payload?.meta?.schemaVersion) !== 4 || payload.meta?.format !== 'price-shard-v2') {
    throw new Error('Shard de preços dinâmicos inválido.');
  }
  if (centralPriceStatus?.catalogHash && payload.meta?.catalogHash !== centralPriceStatus.catalogHash) throw new Error('Shard pertence a outra versão do catálogo.');

  centralPriceData.prices = centralPriceData.prices || {};
  centralPriceData.variantCatalog = centralPriceData.variantCatalog || {};
  if (force && centralPriceLoadedShards.has(shardIndex)) {
    const shardCardIds = new Set(Object.entries(centralPriceIndex.cards || {}).filter(([, value]) => Number(value) === shardIndex).map(([id]) => id));
    for (const key of Object.keys(centralPriceData.prices)) {
      if (shardCardIds.has(key.split('::', 1)[0])) delete centralPriceData.prices[key];
    }
    for (const id of shardCardIds) delete centralPriceData.variantCatalog[id];
  }
  Object.assign(centralPriceData.prices, payload.prices);
  Object.assign(centralPriceData.variantCatalog, payload.variantCatalog);
  centralPriceLoadedShards.add(shardIndex);
  await saveCentralPriceCache();
  return true;
}

async function syncCentralPrices(force = false, silent = false) {
  if (centralPriceSyncing) return false;
  if (!force && Date.now() - centralPriceLastCheck < CENTRAL_PRICE_SYNC_TTL && centralPriceIndex?.cards && Object.keys(centralPriceIndex.cards).length) return false;
  centralPriceSyncing = true;
  centralPriceLastCheck = Date.now();
  try {
    const status = await fetchJsonWithTimeout(`${CENTRAL_PRICE_STATUS_URL}?t=${Date.now()}`, 30000);
    if (!status || status.status !== 'complete' || Number(status.schemaVersion) !== 4 || status.format !== 'sharded-v2') {
      throw new Error('Price Database v4 com enums dinâmicos ainda não está publicado ou está incompleto.');
    }

    const changedCatalog = centralPriceStatus?.catalogHash && centralPriceStatus.catalogHash !== status.catalogHash;
    const missingIndex = !centralPriceIndex?.cards || !Object.keys(centralPriceIndex.cards).length;
    const newer = new Date(status.generatedAt || 0).getTime() > new Date(centralPriceStatus?.generatedAt || 0).getTime();
    if (force || changedCatalog || missingIndex || newer) {
      const indexPayload = await fetchJsonWithTimeout(`${CENTRAL_PRICE_INDEX_URL}?t=${Date.now()}`, 60000);
      if (!indexPayload?.cards || Number(indexPayload?.meta?.schemaVersion) !== 4 || indexPayload.meta?.format !== 'card-shard-index-v2') {
        throw new Error('Índice dinâmico do Price Database inválido.');
      }
      if (indexPayload.meta?.catalogHash !== status.catalogHash) throw new Error('Índice e status do Price Database estão divergentes.');
      centralPriceIndex = indexPayload;
      if (changedCatalog || newer) {
        centralPriceData = { meta: status, prices: {}, variantCatalog: {} };
        centralPriceLoadedShards = new Set();
      }
    }
    centralPriceStatus = status;
    centralPriceData.meta = status;
    await saveCentralPriceCache();
    if (force && !silent) notify(`Price Database atualizado · ${Number(status.variantsPriced || 0).toLocaleString('pt-BR')} enums com preço.`);
    return true;
  } catch (error) {
    lastPriceDiagnostic = String(error?.message || 'Falha ao sincronizar o Price Database.');
    if (force && !silent) notify(`Banco de preços: ${lastPriceDiagnostic}`);
    if (!silent) throw error;
    return false;
  } finally {
    centralPriceSyncing = false;
  }
}

function centralPriceStatusPanel() {
  const meta = centralPriceStatus || centralPriceData?.meta || {};
  const variants = Number(meta.variantsPriced) || 0;
  const discovered = Number(meta.variantsDiscovered) || variants;
  const cards = Number(meta.cardsInCatalog) || Object.keys(centralPriceIndex?.cards || {}).length;
  const unmatched = Number(meta.unmatched) || 0;
  const date = meta.generatedAt ? formatPriceDate(meta.generatedAt) : 'ainda não sincronizado';
  return `<div class="catalog-last-result"><strong>Pokémon Price Database Brasil</strong><br>${variants.toLocaleString('pt-BR')} enums com preço de ${discovered.toLocaleString('pt-BR')} encontrados${cards ? ` · ${cards.toLocaleString('pt-BR')} cartas no índice` : ''}${unmatched ? ` · ${unmatched.toLocaleString('pt-BR')} pendências` : ''}<br>${centralPriceLoadedShards.size} lote(s) carregado(s) no aparelho · Atualizado: ${esc(date)}</div>`;
}

function automaticPriceQuote(cardId, variant = 'normal') {
  return centralPriceQuote(cardId, marketVariantIdentity(variant));
}

function legacyPriceQuote(cardId) {
  const value = entryFor(cardId).priceBrl;
  return hasFiniteNumber(value) ? { brl: Number(value), label: 'Preço antigo importado', legacy: true } : null;
}

function storedAutomaticPriceQuote(variant) {
  if (!hasFiniteNumber(variant?.automaticEstimatedValue)) return null;
  const source = String(variant?.automaticPriceSource || '').trim().toLowerCase();
  const provider = String(variant?.automaticPriceProvider || '').trim().toLowerCase();
  const isPriceDatabase = source === 'preco-brasil' || provider.includes('price database');
  if (!isPriceDatabase) return null;
  const confidence = variant.automaticPriceConfidence || 'review';
  const userValidated = Boolean(variant.automaticPriceUserValidated)
    && Boolean(variant.automaticPriceFingerprint)
    && variant.automaticPriceFingerprint === variant.automaticPriceAcceptedFingerprint;
  return {
    brl: Number(variant.automaticEstimatedValue),
    label: variant.automaticPriceLabel || 'Preço Brasil',
    source: 'preco-brasil',
    provider: variant.automaticPriceProvider || 'Pokémon Price Database Brasil',
    value: nullableNumber(variant.automaticPriceOriginalValue),
    currency: variant.automaticPriceCurrency || 'BRL',
    fetchedAt: variant.automaticPriceUpdatedAt || null,
    stored: true,
    confidence,
    verified: confidence === 'verified',
    userValidated,
    usable: confidence === 'verified' || userValidated,
    fingerprint: variant.automaticPriceFingerprint || '',
    listingsCount: null,
    acceptedCount: Number(variant.automaticPriceAcceptedCount) || null,
    excludedCount: 0,
    storesCount: null,
    low: nullableNumber(variant.automaticPriceLow),
    high: nullableNumber(variant.automaticPriceHigh),
    priceMarket: variant.automaticPriceMarket || '',
    priceMarketLabel: PRICE_SOURCE_LABELS[variant.automaticPriceMarket] || '',
    marketVariantKey: variant.automaticPriceMarketKey || '',
    marketIdentity: variant.automaticPriceMarketIdentity || marketVariantIdentity(variant),
    validation: {
      reasons: Array.isArray(variant.automaticPriceValidationReasons) ? variant.automaticPriceValidationReasons : [],
      checks: Array.isArray(variant.automaticPriceValidationChecks) ? variant.automaticPriceValidationChecks : [],
    },
    central: true,
  };
}

function clearNonDatabaseAutomaticPrices() {
  let changed = false;
  for (const entry of Object.values(state?.entries || {})) {
    for (const variant of entry.variants || []) {
      const source = String(variant.automaticPriceSource || '').trim().toLowerCase();
      const provider = String(variant.automaticPriceProvider || '').trim().toLowerCase();
      const isDatabase = source === 'preco-brasil' || provider.includes('price database');
      if (isDatabase || (variant.automaticEstimatedValue == null && !variant.automaticPriceSource)) continue;
      variant.automaticEstimatedValue = null;
      variant.automaticPriceSource = '';
      variant.automaticPriceLabel = '';
      variant.automaticPriceProvider = '';
      variant.automaticPriceOriginalValue = null;
      variant.automaticPriceCurrency = '';
      variant.automaticPriceUpdatedAt = null;
      variant.automaticPriceConfidence = '';
      variant.automaticPriceFingerprint = '';
      variant.automaticPriceValidationReasons = [];
      variant.automaticPriceValidationChecks = [];
      variant.automaticPriceUserValidated = false;
      variant.automaticPriceAcceptedFingerprint = '';
      variant.automaticPriceUserValidatedAt = null;
      variant.automaticPriceListingsCount = 0;
      variant.automaticPriceAcceptedCount = 0;
      variant.automaticPriceExcludedCount = 0;
      variant.automaticPriceStoresCount = 0;
      variant.automaticPriceLow = null;
      variant.automaticPriceHigh = null;
      variant.automaticPriceMarketKey = '';
      variant.automaticPriceMarketIdentity = null;
      changed = true;
    }
  }
  return changed;
}

function applyAutomaticPriceToVariant(cardId, variant) {
  if (!variant) return false;
  const quote = automaticPriceQuote(cardId, variant);
  if (!quote || !hasFiniteNumber(quote.brl)) return false;
  const nextValue = Math.round(Number(quote.brl) * 100) / 100;
  const nextUpdated = quote.fetchedAt ? new Date(Number(quote.fetchedAt)).toISOString() : new Date().toISOString();
  const previouslyAccepted = Boolean(variant.automaticPriceUserValidated)
    && variant.automaticPriceAcceptedFingerprint === quote.fingerprint;
  const nextUserValidated = quote.confidence === 'review' ? previouslyAccepted : false;
  const nextReasons = quote.validation?.reasons || [];
  const nextChecks = quote.validation?.checks || [];
  const changed = Number(variant.automaticEstimatedValue) !== nextValue
    || variant.automaticPriceSource !== quote.source
    || variant.automaticPriceLabel !== quote.label
    || variant.automaticPriceCurrency !== quote.currency
    || Number(variant.automaticPriceOriginalValue) !== Number(quote.value)
    || variant.automaticPriceUpdatedAt !== nextUpdated
    || variant.automaticPriceConfidence !== quote.confidence
    || variant.automaticPriceFingerprint !== quote.fingerprint
    || Boolean(variant.automaticPriceUserValidated) !== nextUserValidated
    || Number(variant.automaticPriceListingsCount || 0) !== Number(quote.listingsCount || 0)
    || Number(variant.automaticPriceAcceptedCount || 0) !== Number(quote.acceptedCount || 0)
    || Number(variant.automaticPriceExcludedCount || 0) !== Number(quote.excludedCount || 0)
    || Number(variant.automaticPriceStoresCount || 0) !== Number(quote.storesCount || 0)
    || nullableNumber(variant.automaticPriceLow) !== nullableNumber(quote.low)
    || nullableNumber(variant.automaticPriceHigh) !== nullableNumber(quote.high)
    || variant.automaticPriceMarketKey !== (quote.marketVariantKey || marketVariantKey(variant))
    || (variant.automaticPriceMarket || '') !== (quote.priceMarket || '')
    || JSON.stringify(variant.automaticPriceValidationReasons || []) !== JSON.stringify(nextReasons)
    || JSON.stringify(variant.automaticPriceValidationChecks || []) !== JSON.stringify(nextChecks);
  variant.automaticEstimatedValue = nextValue;
  variant.automaticPriceSource = 'preco-brasil';
  variant.automaticPriceLabel = quote.label || 'Preço Brasil';
  variant.automaticPriceProvider = quote.provider || 'Pokémon Price Database Brasil';
  variant.automaticPriceOriginalValue = nullableNumber(quote.value);
  variant.automaticPriceCurrency = quote.currency || 'BRL';
  variant.automaticPriceUpdatedAt = nextUpdated;
  variant.automaticPriceConfidence = quote.confidence;
  variant.automaticPriceFingerprint = quote.fingerprint;
  variant.automaticPriceValidationReasons = nextReasons;
  variant.automaticPriceValidationChecks = nextChecks;
  variant.automaticPriceUserValidated = nextUserValidated;
  variant.automaticPriceListingsCount = Math.max(0, Number(quote.listingsCount) || 0);
  variant.automaticPriceAcceptedCount = Math.max(0, Number(quote.acceptedCount) || 0);
  variant.automaticPriceExcludedCount = Math.max(0, Number(quote.excludedCount) || 0);
  variant.automaticPriceStoresCount = Math.max(0, Number(quote.storesCount) || 0);
  variant.automaticPriceLow = nullableNumber(quote.low);
  variant.automaticPriceHigh = nullableNumber(quote.high);
  variant.automaticPriceMarketKey = quote.marketVariantKey || marketVariantKey(variant);
  variant.automaticPriceMarket = quote.priceMarket || '';
  variant.automaticPriceMarketIdentity = quote.marketIdentity || marketVariantIdentity(variant);
  if (quote.imageUrl) {
    variant.imageUrl = quote.imageUrl;
    variant.imageSource = quote.imageSource || 'Pokémon Price Database Brasil';
  }
  if (!nextUserValidated) variant.automaticPriceAcceptedFingerprint = '';
  return changed;
}

function persistAutomaticPricesForCard(cardId, save = true) {
  const entry = state?.entries?.[cardId];
  if (!entry || !Array.isArray(entry.variants) || !entry.variants.length) return false;
  let changed = false;
  for (const variant of entry.variants) changed = applyAutomaticPriceToVariant(cardId, variant) || changed;
  if (changed) {
    syncEntry(cardId);
    if (save) saveState();
  }
  return changed;
}

function effectiveVariantPrice(cardId, variant) {
  if (hasFiniteNumber(variant?.manualEstimatedValue)) {
    return { brl: Number(variant.manualEstimatedValue), label: 'Valor manual', manual: true, usable: true };
  }
  const stored = storedAutomaticPriceQuote(variant);
  if (stored?.usable) return stored;
  const live = automaticPriceQuote(cardId, variant || 'normal');
  if (live?.confidence === 'verified') return { ...live, usable: true };
  if (live && variant?.automaticPriceUserValidated && variant?.automaticPriceAcceptedFingerprint === live.fingerprint) {
    return { ...live, userValidated: true, usable: true };
  }
  return null;
}

function priceBadgeForCard(cardId) {
  const variants = variantsFor(cardId);
  const ownedVariant = variants.find(item => Number(item.quantity) > 0 && effectiveVariantPrice(cardId, item)?.brl != null);
  const fallbackVariant = variants.find(item => effectiveVariantPrice(cardId, item)?.brl != null);
  const quote = ownedVariant ? effectiveVariantPrice(cardId, ownedVariant)
    : fallbackVariant ? effectiveVariantPrice(cardId, fallbackVariant)
    : null;
  return quote?.brl != null ? money(quote.brl) : null;
}

function formatPriceDate(value) {
  const date = new Date(value);
  return Number.isFinite(date.getTime()) ? date.toLocaleString('pt-BR', { dateStyle: 'short', timeStyle: 'short' }) : 'data indisponível';
}

async function fetchCardPricing(cardId, force = false, variant = 'normal') {
  const identity = marketVariantIdentity(variant);
  const variantKey = marketVariantKey(identity);
  const requestKey = `${cardId}|${variantKey}`;
  if (priceRequests.has(requestKey)) return priceRequests.get(requestKey);
  const request = (async () => {
    const card = cardMap.get(cardId);
    if (!card) throw new Error('Carta não encontrada no catálogo local.');
    await syncCentralPrices(force, true);
    await ensureCentralPriceShard(cardId, force);
    const quote = centralPriceQuote(cardId, identity);
    if (!quote) {
      throw new Error(`O Pokémon Price Database ainda não possui preço para ${marketVariantDescription(identity)}.`);
    }
    return quote;
  })().finally(() => priceRequests.delete(requestKey));
  priceRequests.set(requestKey, request);
  return request;
}

function notify(message) {
  if (window.Android?.toast) window.Android.toast(message);
}

async function loadJson(path) {
  if (path.endsWith('catalog.json') && window.__CATALOG__) return window.__CATALOG__;
  if (path.endsWith('pokedex.json') && window.__POKEDEX__) return window.__POKEDEX__;
  if (path.endsWith('collection-seed.json') && window.__COLLECTION_SEED__) return window.__COLLECTION_SEED__;
  const response = await fetch(path);
  if (!response.ok) throw new Error(`Falha ao carregar ${path}`);
  return response.json();
}

function openCatalogDatabase() {
  return new Promise((resolve, reject) => {
    if (!window.indexedDB) {
      reject(new Error('O armazenamento de atualizações não está disponível neste aparelho.'));
      return;
    }
    const request = indexedDB.open(CATALOG_DB_NAME, 1);
    request.onupgradeneeded = () => {
      const database = request.result;
      if (!database.objectStoreNames.contains(CATALOG_DB_STORE)) {
        database.createObjectStore(CATALOG_DB_STORE);
      }
    };
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error || new Error('Falha ao abrir o catálogo local.'));
  });
}

async function readUpdatedCatalog() {
  let database;
  try {
    database = await openCatalogDatabase();
    return await new Promise((resolve, reject) => {
      const transaction = database.transaction(CATALOG_DB_STORE, 'readonly');
      const request = transaction.objectStore(CATALOG_DB_STORE).get(CATALOG_DB_KEY);
      request.onsuccess = () => resolve(request.result || null);
      request.onerror = () => reject(request.error || new Error('Falha ao ler a atualização.'));
    });
  } catch (_) {
    return null;
  } finally {
    try { database?.close(); } catch (_) {}
  }
}

async function saveUpdatedCatalog(value) {
  const database = await openCatalogDatabase();
  try {
    await new Promise((resolve, reject) => {
      const transaction = database.transaction(CATALOG_DB_STORE, 'readwrite');
      transaction.oncomplete = () => resolve();
      transaction.onerror = () => reject(transaction.error || new Error('Falha ao salvar a atualização.'));
      transaction.onabort = () => reject(transaction.error || new Error('Atualização cancelada ao salvar.'));
      transaction.objectStore(CATALOG_DB_STORE).put(value, CATALOG_DB_KEY);
    });
  } finally {
    database.close();
  }
}

function loadCatalogUpdateMeta() {
  try {
    return JSON.parse(localStorage.getItem(CATALOG_META_KEY) || '{}') || {};
  } catch (_) {
    return {};
  }
}

function saveCatalogUpdateMeta(value) {
  catalogUpdateMeta = value || {};
  try { localStorage.setItem(CATALOG_META_KEY, JSON.stringify(catalogUpdateMeta)); } catch (_) {}
}

async function loadCatalogData() {
  const bundled = await loadJson('data/catalog.json');
  const updated = await readUpdatedCatalog();
  const collectionMetadata = window.__COLLECTION_METADATA__ || {};
  const enrichSet = item => {
    const installed = collectionMetadata[String(item?.id || '')] || {};
    return {
      ...item,
      releaseDate: installed.releaseDate || item.releaseDate || null,
      // Sem o papel de parede aqui: ele já é o último recurso da lista de
      // candidatos. Preenchendo neste ponto, o campo nunca ficava vazio e
      // atropelava o logo verdadeiro da coleção.
      collectionImage: installed.image || item.collectionImage || '',
    };
  };
  if (!updated?.cards?.length || !updated?.sets?.length) {
    return { ...bundled, sets: (bundled.sets || []).map(enrichSet) };
  }

  // Atualizações antigas ficam salvas entre versões do APK. Enriquece essas
  // coleções com os metadados instalados sem substituir cartas nem dados locais.
  const bundledSets = new Map((bundled.sets || []).map(item => [String(item.id), item]));
  const mergedSets = updated.sets.map(item => {
    const installed = bundledSets.get(String(item.id)) || {};
    return enrichSet({
      ...installed,
      ...item,
      logoUrl: item.logoUrl || installed.logoUrl || null,
      symbolUrl: item.symbolUrl || installed.symbolUrl || null,
      releaseDate: item.releaseDate || installed.releaseDate || null,
      seriesName: item.seriesName || installed.seriesName || null,
    });
  });
  const knownIds = new Set(mergedSets.map(item => String(item.id)));
  for (const item of bundled.sets || []) {
    if (!knownIds.has(String(item.id))) mergedSets.push(enrichSet(item));
  }
  return { ...updated, sets: mergedSets };
}

function inferPokemonIds(cardName) {
  const normalized = normalize(cardName);
  if (!normalized) return [];
  if (pokemonInferenceCache.has(normalized)) return pokemonInferenceCache.get(normalized).slice();
  const found = [];
  for (const item of pokemonNameIndex) {
    const pattern = item.normalized;
    if (normalized === pattern || normalized.startsWith(`${pattern} `) || normalized.includes(` ${pattern} `)) {
      found.push(item.id);
    }
  }
  const result = [...new Set(found)];
  pokemonInferenceCache.set(normalized, result);
  return result.slice();
}

function rebuildPerformanceIndexes() {
  cardSearchIndex = new Map();
  cardsBySet = new Map();
  staticCardSortCache = new Map();
  for (const card of cards) {
    cardSearchIndex.set(card.id, normalize(`${card.name} ${card.number} ${card.localId} ${card.setName} ${card.rarity || ''} ${card.illustrator || ''}`));
    if (!cardsBySet.has(card.setId)) cardsBySet.set(card.setId, []);
    cardsBySet.get(card.setId).push(card);
  }
}

function rebuildCatalogIndexes() {
  cards = Array.isArray(catalog?.cards) ? catalog.cards : [];
  catalog.sets = Array.isArray(catalog?.sets) ? catalog.sets : [];
  pokemonMap = new Map(pokedex.map(item => [item.id, item]));
  pokemonNameIndex = pokedex
    .map(item => ({ id: item.id, normalized: normalize(item.name) }))
    .filter(item => item.normalized)
    .sort((a, b) => b.normalized.length - a.normalized.length);
  for (const card of cards) {
    card.imageUrl = upgradeCardImageUrl(card.imageUrl);
    // Catálogos antigos podem não ter este índice. Arrays vazios são válidos
    // (Treinadores, Energias etc.) e não devem ser recalculados a cada abertura.
    if (!Array.isArray(card.pokemonIds)) card.pokemonIds = inferPokemonIds(card.name);
  }
  cardMap = new Map(cards.map(card => [card.id, card]));
  pokemonCards = new Map(pokedex.map(item => [item.id, []]));
  rebuildPerformanceIndexes();
  for (const card of cards) {
    for (const pokemonId of pokemonIdsForCard(card)) {
      if (pokemonCards.has(pokemonId)) pokemonCards.get(pokemonId).push(card.id);
    }
  }
}

async function init() {
  const labInitStart = performance.now();
  try {
    [catalog, pokedex, seed] = await Promise.all([
      loadCatalogData(),
      loadJson('data/pokedex.json'),
      loadJson('data/collection-seed.json'),
    ]);
    rebuildCatalogIndexes();
    catalogUpdateMeta = loadCatalogUpdateMeta();
    catalogUpdating = false;
    loadPricingState();
    loadConditionMultipliers();
    await loadCentralPriceCache();
    state = loadState();
    if (clearNonDatabaseAutomaticPrices()) saveState();
    // Ao mudar para a média brasileira, removemos uma vez os valores internacionais antigos.
    const storedLogicVersion = Number(localStorage.getItem(PRICE_LOGIC_VERSION_KEY) || 0);
    if (storedLogicVersion < PRICE_LOGIC_VERSION) {
      let cleared = false;
      for (const entry of Object.values(state.entries || {})) {
        for (const variant of entry.variants || []) {
          if (variant.manualEstimatedValue != null) continue;
          if (variant.automaticEstimatedValue != null || variant.automaticPriceSource) {
            variant.automaticEstimatedValue = null;
            variant.automaticPriceSource = '';
            variant.automaticPriceLabel = '';
            variant.automaticPriceProvider = '';
            variant.automaticPriceOriginalValue = null;
            variant.automaticPriceCurrency = '';
            variant.automaticPriceUpdatedAt = null;
            variant.automaticPriceConfidence = '';
            variant.automaticPriceFingerprint = '';
            variant.automaticPriceValidationReasons = [];
            variant.automaticPriceValidationChecks = [];
            variant.automaticPriceUserValidated = false;
            variant.automaticPriceAcceptedFingerprint = '';
            variant.automaticPriceUserValidatedAt = null;
            variant.automaticPriceListingsCount = 0;
            variant.automaticPriceAcceptedCount = 0;
            variant.automaticPriceExcludedCount = 0;
            variant.automaticPriceStoresCount = 0;
            variant.automaticPriceLow = null;
            variant.automaticPriceHigh = null;
            variant.automaticPriceMarketKey = '';
            variant.automaticPriceMarketIdentity = null;
            cleared = true;
          }
        }
      }
      if (cleared) saveState();
      localStorage.setItem(PRICE_LOGIC_VERSION_KEY, String(PRICE_LOGIC_VERSION));
    }
    // Apenas caches produzidos pela lógica atual podem ser persistidos.
    let migratedCachedPrices = false;
    for (const cardId of Object.keys(state.entries)) {
      if (variantsFor(cardId).some(variant => centralPriceQuote(cardId, variant))) migratedCachedPrices = persistAutomaticPricesForCard(cardId, false) || migratedCachedPrices;
    }
    if (migratedCachedPrices) saveState();
    renderTabs();
    render();
    document.getElementById('loading').classList.add('hidden');
    document.getElementById('app').classList.remove('hidden');
    const scheduleCentralSync = () => syncCentralPrices(false, true).catch(() => false);
    if (typeof window.requestIdleCallback === 'function') window.requestIdleCallback(scheduleCentralSync, { timeout: 8000 });
    else setTimeout(scheduleCentralSync, 3500);
    setTimeout(() => checkForAppUpdate(false), 1800);
    labRecord('startup', performance.now() - labInitStart, { cards: cards.length });
  } catch (error) {
    document.getElementById('loading').innerHTML = `
      <strong>Não consegui abrir o fichário</strong>
      <span>${esc(error.message)}</span>`;
  }
}

function defaultVariant(quantity = 0, overrides = {}) {
  return {
    id: overrides.id || `v-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
    quantity: Math.max(0, Math.trunc(Number(quantity) || 0)),
    language: overrides.language || 'pt-br',
    pricingVariant: exactSourceEnum(overrides.pricingVariant ?? overrides.variantEnum) || 'normal',
    finish: overrides.finish || 'normal',
    condition: overrides.condition || 'Near Mint',
    edition: overrides.edition || 'unlimited',
    distribution: overrides.distribution || 'unstamped',
    artVariant: overrides.artVariant || 'standard',
    region: overrides.region || 'Brasil',
    gradingCompany: overrides.gradingCompany || 'Não graduada',
    grade: String(overrides.grade || ''),
    variantTags: Array.isArray(overrides.variantTags) ? overrides.variantTags.map(String) : [],
    storageLocation: overrides.storageLocation || 'fichario',
    isWishlist: Boolean(overrides.isWishlist),
    isForTrade: Boolean(overrides.isForTrade),
    isForSale: Boolean(overrides.isForSale),
    paidPrice: nullableNumber(overrides.paidPrice),
    manualEstimatedValue: nullableNumber(overrides.manualEstimatedValue),
    automaticEstimatedValue: nullableNumber(overrides.automaticEstimatedValue),
    automaticPriceSource: String(overrides.automaticPriceSource || ''),
    automaticPriceLabel: String(overrides.automaticPriceLabel || ''),
    automaticPriceProvider: String(overrides.automaticPriceProvider || ''),
    automaticPriceOriginalValue: nullableNumber(overrides.automaticPriceOriginalValue),
    automaticPriceCurrency: String(overrides.automaticPriceCurrency || ''),
    automaticPriceUpdatedAt: overrides.automaticPriceUpdatedAt || null,
    automaticPriceConfidence: String(overrides.automaticPriceConfidence || ''),
    automaticPriceFingerprint: String(overrides.automaticPriceFingerprint || ''),
    automaticPriceValidationReasons: Array.isArray(overrides.automaticPriceValidationReasons) ? overrides.automaticPriceValidationReasons : [],
    automaticPriceValidationChecks: Array.isArray(overrides.automaticPriceValidationChecks) ? overrides.automaticPriceValidationChecks : [],
    automaticPriceUserValidated: Boolean(overrides.automaticPriceUserValidated),
    automaticPriceAcceptedFingerprint: String(overrides.automaticPriceAcceptedFingerprint || ''),
    automaticPriceUserValidatedAt: overrides.automaticPriceUserValidatedAt || null,
    automaticPriceListingsCount: Math.max(0, Number(overrides.automaticPriceListingsCount) || 0),
    automaticPriceAcceptedCount: Math.max(0, Number(overrides.automaticPriceAcceptedCount) || 0),
    automaticPriceExcludedCount: Math.max(0, Number(overrides.automaticPriceExcludedCount) || 0),
    automaticPriceStoresCount: Math.max(0, Number(overrides.automaticPriceStoresCount) || 0),
    automaticPriceLow: nullableNumber(overrides.automaticPriceLow),
    automaticPriceHigh: nullableNumber(overrides.automaticPriceHigh),
    automaticPriceMarketKey: String(overrides.automaticPriceMarketKey || ''),
    automaticPriceMarketIdentity: overrides.automaticPriceMarketIdentity && typeof overrides.automaticPriceMarketIdentity === 'object' ? overrides.automaticPriceMarketIdentity : null,
    imageUrl: String(overrides.imageUrl || ''),
    imageSource: String(overrides.imageSource || ''),
    artConfirmed: Boolean(overrides.artConfirmed),
    notes: String(overrides.notes || ''),
    manualVariationOverride: Boolean(overrides.manualVariationOverride),
    updatedAt: overrides.updatedAt || new Date().toISOString(),
  };
}

function nullableNumber(value) {
  if (value === '' || value == null) return null;
  const parsed = Number(String(value).replace(',', '.'));
  return Number.isFinite(parsed) ? parsed : null;
}

function syncEntry(cardId) {
  const entry = state.entries[cardId];
  if (!entry) return;
  entry.variants = Array.isArray(entry.variants) ? entry.variants : [];
  entry.quantity = entry.variants.reduce((sum, item) => sum + Math.max(0, Math.trunc(Number(item.quantity) || 0)), 0);
  entry.wishlist = Boolean(entry.wishlist) || entry.variants.some(item => item.isWishlist);
  entry.forTrade = entry.variants.some(item => item.isForTrade);
  entry.forSale = entry.variants.some(item => item.isForSale);
  const hasMetadata = Number(entry.manualPokemonId) > 0
    || entry.variants.some(item => item.isWishlist || item.isForTrade || item.isForSale || item.artConfirmed || item.notes || item.paidPrice != null || item.manualEstimatedValue != null);
  if (entry.quantity === 0 && !entry.wishlist && !hasMetadata) delete state.entries[cardId];
}

function migrateState(saved) {
  if (!saved?.entries) return null;
  const migrated = { version: 2, entries: {}, decks: Array.isArray(saved.decks) ? saved.decks : [], importedAt: saved.importedAt || null };
  for (const [cardId, raw] of Object.entries(saved.entries)) {
    const entry = {
      priceBrl: nullableNumber(raw.priceBrl),
      wishlist: Boolean(raw.wishlist),
      manualPokemonId: Number(raw.manualPokemonId) > 0 ? Number(raw.manualPokemonId) : null,
      variants: [],
    };
    if (Array.isArray(raw.variants) && raw.variants.length) {
      entry.variants = raw.variants.map(item => defaultVariant(item.quantity, item));
    } else if ((Number(raw.quantity) || 0) > 0 || raw.wishlist) {
      entry.variants = [defaultVariant(raw.quantity, {
        id: `imported-${cardId}`,
        isWishlist: Boolean(raw.wishlist),
        notes: 'Cadastro importado da versão anterior.',
      })];
    }
    migrated.entries[cardId] = entry;
  }
  state = migrated;
  for (const cardId of Object.keys(migrated.entries)) syncEntry(cardId);
  return migrated;
}

function loadState() {
  try {
    const saved = JSON.parse(localStorage.getItem(STORAGE_KEY));
    const migrated = migrateState(saved);
    if (migrated) {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(migrated));
      return migrated;
    }
  } catch (_) {}
  // Instalações novas começam vazias. Dados pessoais nunca são distribuídos no APK.
  const initial = { version: 2, entries: {}, decks: [], importedAt: null };
  localStorage.setItem(STORAGE_KEY, JSON.stringify(initial));
  return initial;
}

function saveStateNow() {
  if (!state || !stateSaveDirty) return;
  state.version = 2;
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(state));
    stateSaveDirty = false;
  } catch (_) {}
}

function saveState() {
  if (!state) return;
  state.version = 2;
  stateSaveDirty = true;
  invalidateDerivedState();
  clearTimeout(stateSaveTimer);
  stateSaveTimer = setTimeout(saveStateNow, 180);
}

// Garante persistência ao sair, sem bloquear cada toque em +/−.
window.addEventListener('pagehide', saveStateNow);
document.addEventListener('visibilitychange', () => {
  if (document.visibilityState === 'hidden') saveStateNow();
});

function entryFor(cardId) {
  return state.entries[cardId] || { quantity: 0, priceBrl: null, wishlist: false, variants: [] };
}

function variantsFor(cardId) {
  const entry = entryFor(cardId);
  return Array.isArray(entry.variants) ? entry.variants : [];
}

function quantityFor(cardId) {
  return variantsFor(cardId).reduce((sum, item) => sum + Math.max(0, Math.trunc(Number(item.quantity) || 0)), 0);
}

/* =====================================================================
   Cartas repetidas

   Repetida é a MESMA carta em tudo: mesma versão, mesmo idioma, mesma
   condição, mesma edição, mesmo carimbo, mesma graduação, mesmas marcações.
   Um Bulbasaur normal e um Bulbasaur holo são duas cartas diferentes para
   quem coleciona — nenhuma delas sobra.

   Antes o app somava as quantidades de todas as variantes e chamava de
   repetida qualquer carta com total acima de 1. Quem tivesse o normal e o
   reverse via a carta na aba Duplicadas sem ter nenhuma sobrando.
   ===================================================================== */
function assinaturaVariante(variant) {
  return [
    exactSourceEnum(variant?.pricingVariant),
    finishKind(variant?.finish),
    variant?.language || '',
    variant?.condition || '',
    variant?.edition || '',
    variant?.distribution || '',
    variant?.artVariant || '',
    variant?.region || '',
    variant?.gradingCompany || '',
    variant?.grade || '',
    (variant?.variantTags || []).slice().sort().join(','),
  ].join('|');
}

/**
 * Quantas cópias existem de cada versão idêntica desta carta.
 * Agrupa por identidade completa: duas linhas separadas com exatamente os
 * mesmos campos contam como cópias da mesma carta, não como versões distintas.
 */
function copiasPorVersaoIdentica(cardId) {
  const grupos = new Map();
  for (const variant of variantsFor(cardId)) {
    if (variant?.isWishlist) continue;
    const quantidade = Math.max(0, Math.trunc(Number(variant.quantity) || 0));
    if (!quantidade) continue;
    const chave = assinaturaVariante(variant);
    grupos.set(chave, (grupos.get(chave) || 0) + quantidade);
  }
  return [...grupos.values()];
}

/** Quantas cópias sobram, somando todas as versões desta carta. */
function copiasRepetidas(cardId) {
  return copiasPorVersaoIdentica(cardId).reduce((soma, q) => soma + Math.max(0, q - 1), 0);
}

/** A carta tem alguma versão com duas ou mais cópias iguais? */
function temVersaoRepetida(cardId) {
  return copiasPorVersaoIdentica(cardId).some(q => q > 1);
}

function pokemonIdsForCard(cardOrId) {
  const card = typeof cardOrId === 'string' ? cardMap.get(cardOrId) : cardOrId;
  if (!card) return [];
  const manualId = Number(state?.entries?.[card.id]?.manualPokemonId);
  if (manualId === 1026) return [];
  if (manualId > 0 && pokemonMap.has(manualId)) return [manualId];
  return Array.isArray(card.pokemonIds)
    ? card.pokemonIds.map(Number).filter(id => id > 0 && pokemonMap.has(id))
    : [];
}

function pokemonLinkLabel(card) {
  const manualId = Number(state?.entries?.[card?.id]?.manualPokemonId);
  if (manualId === 1026) return 'Energia / Ferramenta';
  return pokemonIdsForCard(card).map(id => pokemonMap.get(id)?.name).filter(Boolean).join(' + ');
}

function primaryVariant(cardId, create = false) {
  let entry = state.entries[cardId];
  if (!entry && create) {
    entry = { quantity: 0, priceBrl: null, wishlist: false, variants: [] };
    state.entries[cardId] = entry;
  }
  if (!entry) return null;
  entry.variants = Array.isArray(entry.variants) ? entry.variants : [];
  if (!entry.variants.length && create) entry.variants.push(defaultVariant(0));
  return entry.variants[0] || null;
}

function currentCardFilter() {
  return ui.tab === 'wishlist' ? 'wishlist' : ui.tab === 'repeated' ? 'repeated' : ui.cardFilter;
}

function findRenderedCardRow(cardId) {
  return [...document.querySelectorAll('.card-row[data-card-id]')]
    .find(node => node.dataset.cardId === String(cardId)) || null;
}

function updateCardRowInPlace(cardId) {
  if (!['cards', 'wishlist', 'repeated'].includes(ui.tab)) return false;
  const filter = currentCardFilter();
  // Nestes filtros a mudança de quantidade pode incluir/remover o item da lista.
  if (['owned', 'missing', 'repeated'].includes(filter) || ui.cardSort === 'quantity') {
    refreshSearchResults('cardQuery', true);
    return true;
  }
  const card = cardMap.get(cardId);
  const current = findRenderedCardRow(cardId);
  if (!card || !current) return false;
  const holder = document.createElement('div');
  holder.innerHTML = renderCardRow(card).trim();
  const replacement = holder.firstElementChild;
  if (!replacement) return false;
  current.replaceWith(replacement);
  return true;
}

function refreshAfterEntryChange(cardId) {
  updateHeader();
  if (!updateCardRowInPlace(cardId) && ['cards', 'wishlist', 'repeated'].includes(ui.tab)) {
    refreshSearchResults('cardQuery', true);
  }
}

function setQuantity(cardId, nextQuantity) {
  const target = Math.max(0, Math.trunc(Number(nextQuantity) || 0));
  const current = quantityFor(cardId);
  const linkId = Number(state?.entries?.[cardId]?.manualPokemonId);
  if (target > current && !pokemonIdsForCard(cardId).length && linkId !== 1026) {
    openCard(cardId);
    notify('Escolha qual Pokémon esta carta representa antes de cadastrá-la.');
    return;
  }
  let difference = target - current;
  if (difference > 0) {
    const variant = primaryVariant(cardId, true);
    variant.quantity += difference;
    variant.updatedAt = new Date().toISOString();
  } else if (difference < 0) {
    let remaining = Math.abs(difference);
    const variants = variantsFor(cardId);
    for (const variant of variants) {
      if (!remaining) break;
      const removable = Math.min(remaining, Math.max(0, Number(variant.quantity) || 0));
      variant.quantity -= removable;
      remaining -= removable;
      variant.updatedAt = new Date().toISOString();
    }
  }
  syncEntry(cardId);
  saveState();
  refreshAfterEntryChange(cardId);
}

function changeQuantity(event, cardId, delta) {
  event?.stopPropagation();
  setQuantity(cardId, quantityFor(cardId) + delta);
}

function toggleWishlist(cardId) {
  const entry = state.entries[cardId] || { quantity: 0, priceBrl: null, wishlist: false, variants: [] };
  state.entries[cardId] = entry;
  const next = !Boolean(entry.wishlist);
  entry.wishlist = next;
  const variant = primaryVariant(cardId, next);
  if (variant) variant.isWishlist = next;
  syncEntry(cardId);
  saveState();
  closeModal();
  refreshAfterEntryChange(cardId);
  notify(next ? 'Carta adicionada à wishlist' : 'Carta removida da wishlist');
}

function parseCurrencyInput(value) {
  let clean = String(value || '').trim().replace(/\s/g, '').replace('R$', '');
  if (!clean) return null;
  if (clean.includes(',')) clean = clean.replaceAll('.', '').replace(',', '.');
  const parsed = Number(clean);
  return Number.isFinite(parsed) && parsed >= 0 ? parsed : null;
}

function formatInputNumber(value) {
  return value == null || !Number.isFinite(Number(value)) ? '' : String(Number(value)).replace('.', ',');
}

function variantLabel(variant) {
  const details = [variant.pricingVariant, variant.finish, variant.edition, variant.distribution, variant.artVariant, variant.language, variant.condition]
    .filter(value => value && !['unlimited', 'unstamped', 'standard'].includes(value));
  return `${details.join(' · ') || variant.finish} · x${Math.max(0, Number(variant.quantity) || 0)}`;
}

function variantNeedsExactPricing(variant) {
  if (!variant) return false;
  return (variant.edition && variant.edition !== 'unlimited')
    || (variant.distribution && variant.distribution !== 'unstamped')
    || (variant.artVariant && variant.artVariant !== 'standard')
    || (variant.region && variant.region !== 'Brasil')
    || (variant.gradingCompany && variant.gradingCompany !== 'Não graduada')
    || (Array.isArray(variant.variantTags) && variant.variantTags.length > 0);
}

function saveCardVariant(cardId, variantId) {
  const card = cardMap.get(cardId);
  if (!card) return;
  const entry = state.entries[cardId] || { quantity: 0, priceBrl: null, wishlist: false, variants: [] };
  state.entries[cardId] = entry;
  const automaticPokemonIds = Array.isArray(card.pokemonIds) ? card.pokemonIds.map(Number).filter(id => pokemonMap.has(id)) : [];
  const selectedPokemonId = Number(document.getElementById('regPokemonId')?.value || 0);
  /* Carta que a fonte já classificou como Treinador ou Energia não representa
     Pokémon nenhum — exigir o vínculo aqui obrigava o usuário a inventar um.
     Só continua obrigatório quando o app realmente não sabe o que é a carta. */
  const categoriaConhecida = categoriaDaCarta(card);
  const precisaDeVinculo = !categoriaConhecida || categoriaConhecida.classe === 'pokemon';
  if (!automaticPokemonIds.length && !selectedPokemonId && precisaDeVinculo) {
    const field = document.getElementById('regPokemonSearch') || document.getElementById('regPokemonId');
    field?.classList.add('field-error');
    field?.focus();
    document.getElementById('regPokemonError')?.classList.remove('hidden');
    notify('Selecione o Pokémon representado por esta carta.');
    return;
  }
  if ((selectedPokemonId > 0 && selectedPokemonId <= 1025) || selectedPokemonId === 1026) entry.manualPokemonId = selectedPokemonId;
  else delete entry.manualPokemonId;
  entry.variants = Array.isArray(entry.variants) ? entry.variants : [];
  const index = variantId ? entry.variants.findIndex(item => item.id === variantId) : -1;
  const previous = index >= 0 ? entry.variants[index] : null;
  const quantity = Math.max(0, Math.trunc(Number(document.getElementById('regQuantity')?.value) || 0));
  const nextPricingVariant = exactSourceEnum(document.getElementById('regPricingVariant')?.value) || 'normal';
  const nextFinish = document.getElementById('regFinish')?.value || 'normal';
  const nextEdition = document.getElementById('regEdition')?.value || 'unlimited';
  const nextDistribution = document.getElementById('regDistribution')?.value || 'unstamped';
  const nextArtVariant = document.getElementById('regArtVariant')?.value || 'standard';
  const nextRegion = document.getElementById('regRegion')?.value || 'Brasil';
  const nextGradingCompany = document.getElementById('regGradingCompany')?.value || 'Não graduada';
  const keepAutomatic = previous && exactSourceEnum(previous.pricingVariant) === nextPricingVariant
    && finishKind(previous.finish) === finishKind(nextFinish)
    && previous.edition === nextEdition && previous.distribution === nextDistribution
    && previous.artVariant === nextArtVariant && previous.region === nextRegion
    && previous.gradingCompany === nextGradingCompany
    && String(previous.grade || '') === String(document.getElementById('regGrade')?.value || '')
    && JSON.stringify((previous.variantTags || []).map(marketTagToken).filter(Boolean).sort()) === JSON.stringify(String(document.getElementById('regVariantTags')?.value || '').split(',').map(marketTagToken).filter(Boolean).sort())
    && marketLanguageKey(previous.language) === marketLanguageKey(document.getElementById('regLanguage')?.value)
    && marketConditionKey(previous.condition) === marketConditionKey(document.getElementById('regCondition')?.value);
  const draft = defaultVariant(quantity, {
    id: variantId || undefined,
    condition: document.getElementById('regCondition')?.value,
    pricingVariant: nextPricingVariant,
    finish: nextFinish,
    language: document.getElementById('regLanguage')?.value,
    edition: nextEdition,
    distribution: nextDistribution,
    artVariant: nextArtVariant,
    region: nextRegion,
    gradingCompany: nextGradingCompany,
    grade: document.getElementById('regGrade')?.value,
    variantTags: String(document.getElementById('regVariantTags')?.value || '').split(',').map(value => value.trim()).filter(Boolean),
    storageLocation: document.getElementById('regStorage')?.value,
    isWishlist: document.getElementById('regWishlist')?.checked,
    isForTrade: document.getElementById('regTrade')?.checked,
    isForSale: document.getElementById('regSale')?.checked,
    artConfirmed: document.getElementById('regArt')?.checked,
    paidPrice: parseCurrencyInput(document.getElementById('regPaidPrice')?.value),
    manualEstimatedValue: parseCurrencyInput(document.getElementById('regManualValue')?.value),
    automaticEstimatedValue: keepAutomatic ? previous.automaticEstimatedValue : null,
    automaticPriceSource: keepAutomatic ? previous.automaticPriceSource : '',
    automaticPriceLabel: keepAutomatic ? previous.automaticPriceLabel : '',
    automaticPriceProvider: keepAutomatic ? previous.automaticPriceProvider : '',
    automaticPriceOriginalValue: keepAutomatic ? previous.automaticPriceOriginalValue : null,
    automaticPriceCurrency: keepAutomatic ? previous.automaticPriceCurrency : '',
    automaticPriceUpdatedAt: keepAutomatic ? previous.automaticPriceUpdatedAt : null,
    automaticPriceConfidence: keepAutomatic ? previous.automaticPriceConfidence : '',
    automaticPriceFingerprint: keepAutomatic ? previous.automaticPriceFingerprint : '',
    automaticPriceValidationReasons: keepAutomatic ? previous.automaticPriceValidationReasons : [],
    automaticPriceValidationChecks: keepAutomatic ? previous.automaticPriceValidationChecks : [],
    automaticPriceUserValidated: keepAutomatic ? previous.automaticPriceUserValidated : false,
    automaticPriceAcceptedFingerprint: keepAutomatic ? previous.automaticPriceAcceptedFingerprint : '',
    automaticPriceUserValidatedAt: keepAutomatic ? previous.automaticPriceUserValidatedAt : null,
    automaticPriceListingsCount: keepAutomatic ? previous.automaticPriceListingsCount : 0,
    automaticPriceAcceptedCount: keepAutomatic ? previous.automaticPriceAcceptedCount : 0,
    automaticPriceExcludedCount: keepAutomatic ? previous.automaticPriceExcludedCount : 0,
    automaticPriceStoresCount: keepAutomatic ? previous.automaticPriceStoresCount : 0,
    automaticPriceLow: keepAutomatic ? previous.automaticPriceLow : null,
    automaticPriceHigh: keepAutomatic ? previous.automaticPriceHigh : null,
    automaticPriceMarketKey: keepAutomatic ? previous.automaticPriceMarketKey : '',
    automaticPriceMarketIdentity: keepAutomatic ? previous.automaticPriceMarketIdentity : null,
    imageUrl: document.getElementById('regVariantImageUrl')?.value || previous?.imageUrl || '',
    imageSource: document.getElementById('regVariantImageSource')?.value || previous?.imageSource || '',
    manualVariationOverride: document.getElementById('regManualVariationOverride')?.value === '1',
    notes: document.getElementById('regNotes')?.value,
  });
  // Se já há preço consultado para este acabamento, ele é gravado junto do cadastro.
  applyAutomaticPriceToVariant(cardId, draft);
  if (index >= 0) entry.variants[index] = draft;
  else entry.variants.push(draft);
  entry.wishlist = entry.variants.some(item => item.isWishlist);
  syncEntry(cardId);
  saveState();
  conferirColecaoCompleta();
  if (scannerDraftFinish && scannerSession.active) {
    const scannedFinish = scannerDraftFinish;
    scannerDraftFinish = '';
    scannerDraftLanguage = '';
    scannerDraftCondition = '';
    scannerDraftMetadata = {};
    scannerSession.count++;
    scannerSession.lastIds.unshift(cardId);
    scannerSession.lastIds = scannerSession.lastIds.slice(0, 12);
    render();
    showScannerMessage(`${card.name} cadastrada como ${scannerFinishLabel(scannedFinish)}.`);
    return;
  }
  render();
  openCard(cardId, draft.id);
  notify(index >= 0 ? 'Cadastro atualizado' : 'Nova variante cadastrada');
}

function deleteCardVariant(cardId, variantId) {
  const entry = state.entries[cardId];
  if (!entry) return;
  if (!window.confirm('Excluir esta variante da carta?')) return;
  entry.variants = variantsFor(cardId).filter(item => item.id !== variantId);
  entry.wishlist = entry.variants.some(item => item.isWishlist);
  syncEntry(cardId);
  saveState();
  render();
  const next = variantsFor(cardId)[0];
  openCard(cardId, next?.id || null);
  notify('Variante excluída');
}

function renderKeepingScroll() {
  const y = window.scrollY;
  render();
  requestAnimationFrame(() => window.scrollTo(0, y));
}

function tabIcon(name) {
  const icons = {
    home: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M3 11.2 12 3l9 8.2v9.3a1.5 1.5 0 0 1-1.5 1.5H15v-6H9v6H4.5A1.5 1.5 0 0 1 3 20.5z"/></svg>',
    collections: '<svg viewBox="0 0 24 24" aria-hidden="true"><rect x="3" y="5" width="7" height="14" rx="1.5"/><rect x="10" y="3" width="7" height="16" rx="1.5"/><path d="m17 6 4 1v12l-4-1z"/></svg>',
    cards: '<svg viewBox="0 0 24 24" aria-hidden="true"><rect x="5" y="3" width="13" height="18" rx="2"/><circle cx="11.5" cy="10" r="3"/><path d="M8 16h7"/></svg>',
    pokedex: '<svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="9"/><path d="M3 12h18"/><circle cx="12" cy="12" r="3"/></svg>',
    decks: '<svg viewBox="0 0 24 24" aria-hidden="true"><rect x="6" y="4" width="12" height="16" rx="2"/><path d="M3 7v11a2 2 0 0 0 2 2M9 1h8a2 2 0 0 1 2 2v14"/></svg>',
    wishlist: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 21S3 16 3 9.5A4.5 4.5 0 0 1 11 6.7 4.5 4.5 0 0 1 21 9.5C21 16 12 21 12 21z"/></svg>',
    repeated: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M7 7h11v11H7z"/><path d="M4 4h11v3M4 4v11h3"/><path d="M11 12h3M12.5 10.5v3"/></svg>'
  };
  return icons[name] || icons.cards;
}

function renderTabs() {
  const root = document.getElementById('tabs');
  const primary = TAB_ITEMS.filter(([value]) => ['dashboard', 'sets', 'cards', 'decks'].includes(value));
  root.innerHTML = primary.slice(0, 2).map(([value, label, icon]) => `
    <button class="tab ${ui.tab === value ? 'active' : ''}" onclick="setTab('${value}')" aria-label="${label}">
      <span class="tab-icon">${tabIcon(icon)}</span><span class="tab-label">${label}</span>
    </button>
  `).join('') + `
    <button class="tab scanner-tab" onclick="openScannerSetup()" aria-label="Escanear carta">
      <span class="tab-icon">${tabIcon('cards')}</span><span class="tab-label">Scanner</span>
    </button>` + primary.slice(2).map(([value, label, icon]) => `
    <button class="tab ${ui.tab === value ? 'active' : ''}" onclick="setTab('${value}')" aria-label="${label}">
      <span class="tab-icon">${tabIcon(icon)}</span><span class="tab-label">${label}</span>
    </button>`).join('') + `
    <button class="tab ${['pokedex','wishlist','repeated','produtos'].includes(ui.tab) ? 'active' : ''}" onclick="openMoreNavigation()" aria-label="Mais opções">
      <span class="tab-icon">${tabIcon('decks')}</span><span class="tab-label">Mais</span>
    </button>`;
}

function openMoreNavigation() {
  showModal(`<button class="modal-close" onclick="closeModal()" aria-label="Fechar">×</button>
    <h2>Mais opções</h2><p class="screen-subtitle">Acesse todas as áreas do seu fichário.</p>
    <div class="quick-action-list">
      <button onclick="closeModal();setTab('pokedex')"><span class="quick-action-icon">${tabIcon('pokedex')}</span><span><strong>Pokédex</strong><small>Acompanhe os Pokémon encontrados</small></span></button>
      <button onclick="closeModal();setTab('decks')"><span class="quick-action-icon">${tabIcon('decks')}</span><span><strong>Decks</strong><small>Monte e organize seus decks</small></span></button>
      <button onclick="closeModal();setTab('wishlist')"><span class="quick-action-icon">${tabIcon('wishlist')}</span><span><strong>Wishlist</strong><small>Cartas que você procura</small></span></button>
      <button onclick="closeModal();setTab('repeated')"><span class="quick-action-icon">${tabIcon('repeated')}</span><span><strong>Repetidas</strong><small>Estoque para troca ou venda</small></span></button>
      <button onclick="closeModal();setTab('produtos')"><span class="quick-action-icon">${tabIcon('collections')}</span><span><strong>Produtos prontos</strong><small>Baralhos de batalha, kits e caixas</small></span></button>
      <button onclick="closeModal();abrirTrofeus()"><span class="quick-action-icon">🏆</span><span><strong>Troféus</strong><small>${(() => { const r = typeof resumoTrofeus === 'function' ? resumoTrofeus() : null; return r ? `${r.conquistadas} de ${r.total} medalhas conquistadas` : 'Medalhas por coleção, tipo e região'; })()}</small></span></button>
      <button onclick="closeModal();abrirTemaPokemon()"><span class="quick-action-icon">${tabIcon('pokedex')}</span><span><strong>Tema do aplicativo</strong><small>${esc(window.__TEMA_ATUAL__?.nome || 'Gengar')} · deixe o app com a cara do seu favorito</small></span></button>
      <button onclick="closeModal();openBackupPanel()"><span class="quick-action-icon">${tabIcon('collections')}</span><span><strong>Backup e ajustes</strong><small>Proteja e configure seus dados</small></span></button>
    </div>`);
}

function setTab(tab) {
  const labStart = performance.now();
  const previousTab = ui.tab;
  ui.tab = tab;
  ui.selectedPokemon = null;
  ui.cardLimit = 80;
  renderTabs();
  render();
  window.scrollTo(0, 0);
  labRecord('troca_aba', performance.now() - labStart, { from: previousTab, to: tab });
}

function render() {
  const labStart = performance.now();
  labCount('render_chamadas');
  updateHeader();
  const content = document.getElementById('content');
  if (!content) return;
  if (ui.tab === 'dashboard') content.innerHTML = renderDashboard();
  else if (ui.tab === 'sets') content.innerHTML = renderSets();
  else if (ui.tab === 'pokedex') content.innerHTML = renderPokedex();
  else if (ui.tab === 'decks') content.innerHTML = renderDecks();
  else if (ui.tab === 'produtos') content.innerHTML = renderProdutos();
  else content.innerHTML = renderCards();
  labRecord('render_completo', performance.now() - labStart, { tab: ui.tab, htmlLength: content.innerHTML.length });
}

function updateHeader() {
  const target = document.getElementById('header-status');
  if (!target || !state) return;
  const summary = collectionSummary();
  target.textContent = `${summary.uniqueOwned} cartas únicas · ${summary.totalCopies} cartas no total`;
}

function collectionSummary() {
  if (collectionSummaryCache.revision === stateRevision && collectionSummaryCache.value) return collectionSummaryCache.value;
  let totalCopies = 0;
  let uniqueOwned = 0;
  let repeated = 0;
  let wishlist = 0;
  let estimatedValue = 0;
  for (const [cardId, entry] of Object.entries(state.entries)) {
    const quantity = quantityFor(cardId);
    totalCopies += quantity;
    if (quantity > 0) uniqueOwned++;
    repeated += copiasRepetidas(cardId);
    if (entry.wishlist) wishlist++;
    if (quantity > 0) {
      const variants = variantsFor(cardId);
      if (variants.length) {
        for (const variant of variants) {
          const variantQuantity = Math.max(0, Math.trunc(Number(variant.quantity) || 0));
          const quote = effectiveVariantPrice(cardId, variant);
          if (variantQuantity && quote?.brl != null) estimatedValue += Number(quote.brl) * variantQuantity;
        }
      } else if (hasFiniteNumber(entry.priceBrl)) {
        estimatedValue += Number(entry.priceBrl) * quantity;
      }
    }
  }
  const pokemonStats = buildPokemonStats();
  const pokemonOwned = [...pokemonStats.values()].filter(item => item.copies > 0).length;
  const value = { totalCopies, uniqueOwned, repeated, wishlist, estimatedValue, pokemonOwned };
  collectionSummaryCache = { revision: stateRevision, value };
  return value;
}

function buildPokemonStats() {
  if (pokemonStatsCache.revision === stateRevision && pokemonStatsCache.value) return pokemonStatsCache.value;
  const stats = new Map(pokedex.map(item => [item.id, { copies: 0, cardIds: new Set() }]));
  for (const [cardId, entry] of Object.entries(state.entries)) {
    const quantity = quantityFor(cardId);
    if (!quantity) continue;
    const card = cardMap.get(cardId);
    if (!card) continue;
    for (const pokemonId of pokemonIdsForCard(card)) {
      const item = stats.get(pokemonId);
      if (!item) continue;
      item.copies += quantity;
      item.cardIds.add(cardId);
    }
  }
  pokemonStatsCache = { revision: stateRevision, value: stats };
  return stats;
}


function pricedOwnedCount() {
  let priced = 0;
  let owned = 0;
  let pending = 0;
  for (const cardId of Object.keys(state.entries)) {
    if (quantityFor(cardId) <= 0) continue;
    owned++;
    const variants = variantsFor(cardId);
    const hasPrice = variants.length
      ? variants.some(variant => effectiveVariantPrice(cardId, variant)?.brl != null)
      : automaticPriceQuote(cardId, defaultVariant(0))?.confidence === 'verified';
    if (hasPrice) priced++;
    if (variants.some(variant => {
      const stored = storedAutomaticPriceQuote(variant);
      return stored && stored.confidence === 'review' && !stored.userValidated;
    })) pending++;
  }
  return { priced, owned, pending };
}

function latestPriceFetch() {
  const centralLatest = new Date(centralPriceGeneratedAt() || 0).getTime();
  return Number.isFinite(centralLatest) ? centralLatest : 0;
}

function pricingPanel() {
  const counts = pricedOwnedCount();
  const progress = priceUpdateTotal > 0 ? Math.max(0, Math.min(100, Math.round((priceUpdateCurrent / priceUpdateTotal) * 100))) : 0;
  const latest = latestPriceFetch();
  return `<section class="price-update-card">
    <div class="catalog-update-heading">
      <div><strong>Preços da coleção</strong><span>${counts.priced} de ${counts.owned} cartas próprias com valor aceito${counts.pending ? ` · ${counts.pending} aguardando validação` : ''}${latest ? ` · última atualização ${esc(formatPriceDate(latest))}` : ''}</span></div>
      <span class="online-badge">Price Database</span>
    </div>
    <p>Fonte automática exclusiva: Pokémon Price Database Brasil. O banco separa ID, idioma, edição, carimbo e acabamento. Quando não houver correspondência, o app não consulta marketplaces e mantém a variação sem preço automático.</p>
    ${centralPriceStatusPanel()}
    ${priceUpdating ? `<div class="catalog-progress"><div class="progress"><span style="width:${progress}%"></span></div><small>${esc(priceUpdateMessage || 'Atualizando o banco de preços...')}</small></div>` : ''}
    <button class="primary-btn" ${priceUpdating ? 'disabled' : ''} onclick="startOwnedPriceUpdate()">${priceUpdating ? 'Atualizando...' : 'Atualizar Price Database'}</button>
    ${priceUpdateFailures ? `<div class="catalog-last-result">${priceUpdateFailures} variação(ões) continuam sem preço no banco.</div>` : ''}
    ${conditionMultiplierEditor()}
  </section>`;
}

function conditionMultiplierEditor() {
  const rows = Object.keys(DEFAULT_CONDITION_MULTIPLIERS).map(key => {
    const percent = Math.round(conditionMultiplier(key) * 100);
    const isDefault = conditionMultiplier(key) === DEFAULT_CONDITION_MULTIPLIERS[key];
    return `<label class="condition-multiplier-row">
      <span>${esc(CONDITION_LABELS[key])}</span>
      <input type="number" min="1" max="1000" step="1" value="${percent}"
        ${key === 'nm' ? 'disabled title="A condição NM é a própria base do preço."' : ''}
        onchange="setConditionMultiplierPercent('${esc(key)}', this.value)">
      <small>%${isDefault ? '' : ' · alterado'}</small>
    </label>`;
  }).join('');
  return `<details class="condition-multiplier-box">
    <summary>Percentuais por condição da carta</summary>
    <p>Cardmarket e TCGplayer publicam um preço único por variante, sem separar condição. Estes percentuais são <strong>estimativa de mercado</strong> aplicada sobre o preço-base NM — não são dado de fonte. O cadastro sempre mostra o preço-base ao lado do valor ajustado.</p>
    ${rows}
    <button type="button" class="price-refresh-btn" onclick="resetConditionMultipliers()">Restaurar padrão de mercado</button>
  </details>`;
}

function setPriceUpdateProgress(message, current, total) {
  priceUpdateMessage = String(message || 'Consultando preços...');
  priceUpdateCurrent = Number(current) || 0;
  priceUpdateTotal = Math.max(1, Number(total) || 1);
  if (ui.tab === 'dashboard' && (current === 0 || current === total || current % 8 === 0)) renderKeepingScroll();
}

async function startOwnedPriceUpdate() {
  if (priceUpdating) return;
  const targets = [];
  const seen = new Set();
  for (const cardId of Object.keys(state.entries)) {
    if (quantityFor(cardId) <= 0 || !cardMap.has(cardId)) continue;
    const variants = variantsFor(cardId).length ? variantsFor(cardId) : [defaultVariant(0)];
    for (const variant of variants) {
      const identity = marketVariantIdentity(variant);
      const key = `${cardId}|${marketVariantKey(identity)}`;
      if (!seen.has(key)) { seen.add(key); targets.push({ cardId, variant, identity }); }
    }
  }
  if (!targets.length) return notify('Nenhuma carta cadastrada para atualizar.');
  priceUpdating = true;
  priceUpdateFailures = 0;
  setPriceUpdateProgress('Sincronizando o Pokémon Price Database...', 0, Math.max(1, targets.length));

  const synced = await syncCentralPrices(true, true);
  let verified = 0;
  let review = 0;
  let missing = 0;
  const touchedCards = new Set();
  for (let index = 0; index < targets.length; index++) {
    const item = targets[index];
    const quote = centralPriceQuote(item.cardId, item.identity);
    if (quote?.confidence === 'verified') verified++;
    else if (quote) review++;
    else missing++;
    touchedCards.add(item.cardId);
    setPriceUpdateProgress(`Aplicando preços do banco: ${index + 1} de ${targets.length}`, index + 1, targets.length);
  }
  for (const cardId of touchedCards) persistAutomaticPricesForCard(cardId, false);
  saveState();
  priceUpdateFailures = missing;
  priceUpdating = false;
  priceUpdateMessage = '';
  renderKeepingScroll();
  notify(`${synced ? 'Price Database sincronizado' : 'Price Database já estava atualizado'}: ${verified} exata(s) · ${review} para revisão · ${missing} sem preço.`);
}

function renderDashboard() {
  const summary = collectionSummary();
  const pokemonStats = buildPokemonStats();
  const speciesFound = [...pokemonStats.values()].filter(item => item.copies > 0).length;
  const speciesTotal = Math.max(1, pokemonStats.size);
  const speciesProgress = Math.round((speciesFound / speciesTotal) * 100);
  const ownedVariants = Object.values(state.entries || {}).reduce((sum, item) => sum + (item.variants || []).filter(variant => Number(variant.quantity) > 0).length, 0);
  const specialCopies = Object.values(state.entries || {}).reduce((sum, item) => sum + (item.variants || []).filter(variant => Number(variant.quantity) > 0 && finishKind(variant.finish) !== 'normal').reduce((count, variant) => count + Number(variant.quantity || 0), 0), 0);
  const forSale = Object.values(state.entries || {}).reduce((sum, item) => sum + (item.variants || []).filter(variant => variant.isForSale).reduce((count, variant) => count + Number(variant.quantity || 0), 0), 0);
  const languages = new Map();
  Object.values(state.entries || {}).forEach(item => (item.variants || []).forEach(variant => {
    if (Number(variant.quantity) <= 0) return;
    const key = PRICE_LANGUAGES.includes(variant.language) ? variant.language : 'pt-br';
    languages.set(key, (languages.get(key) || 0) + Number(variant.quantity || 0));
  }));
  return `
    <section class="screen vision-home-screen">
      <div class="vision-home-head">
        <div><span>${dashboardGreeting()}</span><h2>POKECARD Brasil</h2></div>
        <button class="vision-profile-button" onclick="openBackupPanel()" aria-label="Abrir perfil e backup">PB</button>
      </div>

      <section class="portfolio-card">
        <div class="portfolio-title"><span>${tabIcon('cards')}</span><strong>PORTFÓLIO</strong><button onclick="updateAllCollectionPrices()">Atualizar</button></div>
        <div class="portfolio-value"><small>Valor estimado de mercado</small><strong>${money(summary.estimatedValue)}</strong></div>
        <div class="portfolio-sale"><span>${forSale}</span> ${forSale === 1 ? 'carta disponível' : 'cartas disponíveis'} para venda</div>
        <div class="portfolio-languages">${[...languages.entries()].slice(0,4).map(([language,count]) => `<span><b>${esc(languageCode(language))}</b> ${count}</span>`).join('') || '<span><b>PT</b> coleção local</span>'}</div>
        <small class="portfolio-foot">Preços por acabamento, idioma, condição e demais variações cadastradas</small>
      </section>

      <button class="pokedex-progress-card" onclick="setTab('pokedex')">
        <span class="pokedex-ring" style="--progress:${speciesProgress * 3.6}deg"><b>${speciesProgress}%</b></span>
        <span><small>POKÉDEX</small><strong>${speciesFound} <em>/ ${speciesTotal} espécies</em></strong><i><span style="width:${speciesProgress}%"></span></i><small>espécies registradas na sua coleção</small></span>
      </button>

      <div class="section-heading"><h3 class="section-title">Sua coleção</h3><button onclick="setTab('cards')">Ver todos</button></div>
      <div class="collection-summary-grid">
        <button onclick="ui.cardFilter='owned';setTab('cards')"><span>${tabIcon('cards')}</span><strong>${summary.uniqueOwned}</strong><small>Cartas</small></button>
        <button onclick="ui.cardFilter='owned';setTab('cards')"><span>${tabIcon('collections')}</span><strong>${ownedVariants}</strong><small>Versões</small></button>
        <button onclick="ui.cardFilter='owned';setTab('cards')"><span>${tabIcon('pokedex')}</span><strong>${specialCopies}</strong><small>Especiais</small></button>
      </div>
      ${topColecoesPanel()}
      ${pricingPanel()}
    </section>`;
}

/**
 * As 10 coleções em que você está mais perto de completar, da mais adiantada
 * para a menos. Só entram coleções com pelo menos uma carta cadastrada.
 */
function topColecoesPanel() {
  const lista = buildSetStats()
    .filter(set => set.ownedUnique > 0)
    .map(set => {
      const total = set.officialCardCount || set.totalCardCount || 0;
      return { ...set, total, pct: total ? Math.min(100, (set.ownedUnique / total) * 100) : 0 };
    })
    .sort((a, b) => b.pct - a.pct || b.ownedUnique - a.ownedUnique)
    .slice(0, 10);

  if (!lista.length) {
    return `<section class="top-sets">
      <div class="section-heading"><h3 class="section-title">Coleções mais completas</h3></div>
      <div class="empty">Cadastre cartas para acompanhar o progresso das suas coleções.</div>
    </section>`;
  }

  return `<section class="top-sets">
    <div class="section-heading"><h3 class="section-title">Coleções mais completas</h3><button onclick="setTab('sets')">Ver todas</button></div>
    <ol class="top-sets-lista">
      ${lista.map((set, i) => `
        <li class="top-set" onclick="ui.cardSet='${esc(set.id)}';ui.cardFilter='all';setTab('cards')">
          <span class="top-set-pos">${i + 1}</span>
          <span class="top-set-info">
            <strong>${esc(set.name)}</strong>
            <small>${set.ownedUnique}${set.total ? ` de ${set.total}` : ''} cartas</small>
            <i class="top-set-barra"><span style="width:${set.pct.toFixed(1)}%"></span></i>
          </span>
          <b class="top-set-pct">${Math.round(set.pct)}%</b>
        </li>`).join('')}
    </ol>
  </section>`;
}

function languageCode(language) {
  return ({ 'pt-br': 'PT-BR', en: 'EN', ja: 'JA' })[language] || String(language || '—');
}

function dashboardGreeting() {
  const hour = new Date().getHours();
  return hour < 12 ? 'Bom dia' : hour < 18 ? 'Boa tarde' : 'Boa noite';
}

function statCard(value, label, wide = false) {
  return `<div class="stat-card ${wide ? 'wide' : ''}"><span class="stat-value">${esc(value)}</span><span class="stat-label">${esc(label)}</span></div>`;
}

function buildSetStats() {
  const stats = new Map(catalog.sets.map(set => [set.id, {
    ...set, ownedUnique: 0, ownedCopies: 0, progress: 0,
  }]));
  for (const [cardId, entry] of Object.entries(state.entries)) {
    const quantity = quantityFor(cardId);
    if (!quantity) continue;
    const card = cardMap.get(cardId);
    if (!card) continue;
    if (!stats.has(card.setId)) {
      stats.set(card.setId, { id: card.setId, name: card.setName, officialCardCount: 0, totalCardCount: 0, ownedUnique: 0, ownedCopies: 0, progress: 0 });
    }
    const item = stats.get(card.setId);
    item.ownedUnique++;
    item.ownedCopies += quantity;
  }
  for (const item of stats.values()) {
    const total = item.officialCardCount || item.totalCardCount || 1;
    item.progress = Math.min(100, Math.round((item.ownedUnique / total) * 100));
  }
  return [...stats.values()];
}

function formatCatalogUpdateDate(value) {
  const date = new Date(Number(value));
  return Number.isFinite(date.getTime())
    ? date.toLocaleString('pt-BR', { dateStyle: 'short', timeStyle: 'short' })
    : 'Ainda não atualizado online';
}

function catalogUpdatePanel() {
  const progress = catalogUpdateTotal > 0
    ? Math.max(0, Math.min(100, Math.round((catalogUpdateCurrent / catalogUpdateTotal) * 100)))
    : 0;
  const lastResult = catalogUpdateMeta.updatedAt
    ? `${formatCatalogUpdateDate(catalogUpdateMeta.updatedAt)} · ${catalogUpdateMeta.setsTotal || catalog.sets.length} coleções · ${catalogUpdateMeta.cardsTotal || cards.length} cartas`
    : `${catalog.sets.length} coleções e ${cards.length} cartas disponíveis no catálogo instalado.`;
  return `<section class="catalog-update-card">
    <div class="catalog-update-heading">
      <div><strong>Atualização das coleções</strong><span>${esc(lastResult)}</span></div>
      <span class="online-badge">TCGdex completo JA + EN + PT-BR</span>
    </div>
    <p>Verifica novas expansões e cartas sem apagar quantidades, variantes, observações ou outros dados da sua coleção.</p>
    ${catalogUpdating ? `<div class="catalog-progress"><div class="progress"><span style="width:${progress}%"></span></div><small>${esc(catalogUpdateMessage || 'Preparando atualização...')}</small></div>` : ''}
    <button class="primary-btn" ${catalogUpdating ? 'disabled' : ''} onclick="startCatalogUpdate()">${catalogUpdating ? 'Atualizando...' : 'Verificar novas coleções'}</button>
    ${catalogUpdateMeta.cardsAdded || catalogUpdateMeta.setsAdded ? `<div class="catalog-last-result">Último resultado: +${catalogUpdateMeta.setsAdded || 0} coleções · +${catalogUpdateMeta.cardsAdded || 0} cartas</div>` : ''}
  </section>`;
}

function setCatalogUpdateProgress(message, current, total, forceRender = false) {
  catalogUpdateMessage = String(message || 'Atualizando...');
  catalogUpdateCurrent = Number(current) || 0;
  catalogUpdateTotal = Math.max(1, Number(total) || 1);
  if (ui.tab === 'sets' && (forceRender || current === 0 || current === total || current % 3 === 0)) {
    renderKeepingScroll();
  }
}

function fetchJsonWithTimeout(url, timeoutMs = 30000) {
  const controller = typeof AbortController !== 'undefined' ? new AbortController() : null;
  const timeout = setTimeout(() => controller?.abort(), timeoutMs);
  return fetch(url, {
    method: 'GET',
    headers: { Accept: 'application/json' },
    cache: 'no-store',
    signal: controller?.signal,
  }).then(response => {
    if (!response.ok) throw new Error(`Servidor respondeu ${response.status}`);
    return response.json();
  }).finally(() => clearTimeout(timeout));
}

function remoteSetCounts(remote) {
  const counts = remote?.cardCount || {};
  return {
    total: Number(counts.total) || Number(remote?.totalCardCount) || 0,
    official: Number(counts.official) || Number(remote?.officialCardCount) || 0,
  };
}

function setNeedsCatalogRefresh(local, remote) {
  if (!local) return true;
  const counts = remoteSetCounts(remote);
  return counts.total !== Number(local.totalCardCount || 0)
    || counts.official !== Number(local.officialCardCount || 0)
    || (remote.name != null && String(remote.name) !== String(local.name || ''))
    || (remote.logo != null && String(remote.logo) !== String(local.logoUrl || ''))
    || (remote.symbol != null && String(remote.symbol) !== String(local.symbolUrl || ''));
}

function cardImageUrl(value) {
  const source = String(value || '').trim();
  if (!source) return null;
  if (/assets\.tcgdex\.net/i.test(source)) {
    const root = source.replace(/\/(?:low|high)\.(?:webp|png|jpe?g)(\?.*)?$/i, '').replace(/\/$/, '');
    return `${root}/high.webp`;
  }
  if (/\.(webp|png|jpe?g)(\?.*)?$/i.test(source)) return source;
  return `${source.replace(/\/$/, '')}/high.webp`;
}

function upgradeCardImageUrl(value) {
  const source = String(value || '').trim();
  if (!source) return null;
  if (!/assets\.tcgdex\.net/i.test(source)) return source;
  return source
    .replace(/\/low\.webp(\?.*)?$/i, '/high.webp$1')
    .replace(/\/low\.png(\?.*)?$/i, '/high.png$1');
}

function variantDisplayImage(card, variant) {
  return upgradeCardImageUrl(variant?.imageUrl || card?.imageUrl || '');
}

function tcgDexBasesForLanguage(language) {
  const key = marketLanguageKey(language);
  return ({
    'pt-br': [TCGDEX_API_BASE],
    en: [TCGDEX_API_FALLBACK],
    ja: [TCGDEX_API_JAPANESE],
  })[key] || [];
}

async function fetchVariantImage(card, language) {
  if (!card) return null;
  const languageKey = marketLanguageKey(language);
  const cacheKey = `${card.id}|${languageKey}`;
  const cached = variantImageCache[cacheKey];
  if (cached?.url) return cached;
  const bases = tcgDexBasesForLanguage(language);
  for (const base of bases) {
    try {
      const detail = await fetchJsonWithTimeout(`${base}/cards/${encodeURIComponent(card.id)}`, 25000);
      const url = cardImageUrl(detail?.image);
      if (url) {
        const locale = base.split('/').pop().toUpperCase();
        const result = { url, source: `TCGdex ${locale}`, fetchedAt: Date.now() };
        variantImageCache[cacheKey] = result;
        saveVariantImageCache();
        return result;
      }
    } catch (_) {}
  }
  if (card.imageUrl) return { url: upgradeCardImageUrl(card.imageUrl), source: 'Catálogo TCGdex', fallback: true };
  return null;
}

function registrationVariantFromForm(finish = 'normal') {
  const variantId = document.getElementById('regVariantId')?.value || '';
  return {
    id: variantId,
    pricingVariant: exactSourceEnum(document.getElementById('regPricingVariant')?.value) || 'normal',
    finish: document.getElementById('regFinish')?.value || finish,
    language: document.getElementById('regLanguage')?.value || 'pt-br',
    condition: document.getElementById('regCondition')?.value || 'Near Mint',
    edition: document.getElementById('regEdition')?.value || 'unlimited',
    distribution: document.getElementById('regDistribution')?.value || 'unstamped',
    artVariant: document.getElementById('regArtVariant')?.value || 'standard',
    region: document.getElementById('regRegion')?.value || 'Brasil',
    gradingCompany: document.getElementById('regGradingCompany')?.value || 'Não graduada',
    grade: document.getElementById('regGrade')?.value || '',
    variantTags: String(document.getElementById('regVariantTags')?.value || '').split(',').map(value => value.trim()).filter(Boolean),
  };
}

async function refreshRegistrationVariantImage(cardId) {
  const card = cardMap.get(cardId);
  const variant = registrationVariantFromForm();
  const image = await fetchVariantImage(card, variant.language);
  const frame = document.querySelector('[data-registration-variant-image]');
  const urlField = document.getElementById('regVariantImageUrl');
  const sourceField = document.getElementById('regVariantImageSource');
  const sourceLabel = document.getElementById('regVariantImageLabel');
  if (!frame || !image?.url) return;
  frame.innerHTML = `<img class="registration-card-image" src="${esc(upgradeCardImageUrl(image.url))}" alt="Arte de ${esc(card?.name || '')}"><span class="variant-image-badge">${esc(finishPriceLabel(finishKind(variant.finish)))}</span>`;
  if (urlField) urlField.value = image.url;
  if (sourceField) sourceField.value = image.source || '';
  if (sourceLabel) sourceLabel.textContent = `Imagem: ${image.source || 'catálogo público'}${image.fallback ? ' (imagem-base)' : ''}`;
}

function handleRegistrationVariantChange(cardId, origem = '') {
  const card = cardMap.get(cardId);
  if (card) refreshCardSpecificVariationFields(card);
  // A variante exata acompanha sozinha o acabamento, a edição e o idioma.
  // Só não mexemos nela quando foi o próprio usuário quem a escolheu.
  if (card && origem !== 'variante') sincronizarVarianteExata(card);
  const variant = registrationVariantFromForm();
  refreshAutomaticPriceField(cardId, variant.finish);
  refreshRegistrationVariantImage(cardId);
}

function sincronizarVarianteExata(card) {
  const campo = document.getElementById('regPricingVariant');
  if (!campo) return;
  const escolhido = derivePricingVariant(card, {
    finish: document.getElementById('regFinish')?.value,
    edition: document.getElementById('regEdition')?.value,
    language: document.getElementById('regLanguage')?.value,
    current: campo.value,
  });
  if (escolhido && campo.value !== escolhido) {
    const existe = [...campo.options].some(opcao => opcao.value === escolhido);
    if (existe) campo.value = escolhido;
  }
  atualizarResumoVariante(card);
}

// Mostra os botões apenas quando a carta tem mais de uma versão de verdade.
function atualizarResumoVariante(card) {
  const bloco = document.getElementById('regVarianteBloco');
  const resumo = document.getElementById('regVarianteResumo');
  const campo = document.getElementById('regPricingVariant');
  if (!bloco || !campo) return;
  // Carta que só existe de um jeito não precisa de botão de escolha.
  const idioma = document.getElementById('regLanguage')?.value || 'pt-br';
  const visiveis = variantesVisiveis(card?.id || '', [...campo.options].map(opcao => opcao.value), campo.value, idioma);
  bloco.classList.toggle('hidden', visiveis.length <= 1);
  marcarPillAtiva(campo.value);
  if (resumo) resumo.textContent = friendlyVariantLabel(campo.value);
}

function showQuoteImageInRegistration(cardId, identity) {
  const quote = automaticPriceQuote(cardId, identity);
  if (!quote?.imageUrl) return false;
  const card = cardMap.get(cardId);
  const frame = document.querySelector('[data-registration-variant-image]');
  if (!frame) return false;
  frame.innerHTML = `<img class="registration-card-image" src="${esc(upgradeCardImageUrl(quote.imageUrl))}" alt="Arte de ${esc(card?.name || '')}"><span class="variant-image-badge">${esc(finishPriceLabel(finishKind(identity.finish)))}</span>`;
  const urlField = document.getElementById('regVariantImageUrl');
  const sourceField = document.getElementById('regVariantImageSource');
  const sourceLabel = document.getElementById('regVariantImageLabel');
  if (urlField) urlField.value = quote.imageUrl;
  if (sourceField) sourceField.value = quote.imageSource || 'Pokémon Price Database Brasil';
  if (sourceLabel) sourceLabel.textContent = `Imagem: ${quote.imageSource || 'Pokémon Price Database Brasil'} · correspondência do banco`;
  return true;
}

function normalizeRemoteSet(detail, fallback) {
  const counts = remoteSetCounts(detail?.cardCount ? detail : fallback);
  const cardsInSet = Array.isArray(detail?.cards) ? detail.cards : [];
  return {
    id: String(detail?.id || fallback?.id || ''),
    name: String(detail?.name || fallback?.name || detail?.id || fallback?.id || 'Coleção'),
    officialCardCount: counts.official || cardsInSet.length,
    totalCardCount: counts.total || cardsInSet.length,
    logoUrl: detail?.logo || fallback?.logo || null,
    symbolUrl: detail?.symbol || fallback?.symbol || null,
    releaseDate: detail?.releaseDate || null,
    seriesName: detail?.serie?.name || detail?.serie?.id || null,
    tcgOnline: detail?.tcgOnline || fallback?.tcgOnline || null,
  };
}

function normalizeRemoteCard(remote, set) {
  const localId = String(remote?.localId || '').trim();
  const official = Number(set.officialCardCount || 0);
  return {
    id: String(remote?.id || `${set.id}-${localId}`),
    localId,
    name: String(remote?.name || 'Carta sem nome'),
    setId: set.id,
    setName: set.name,
    number: official ? `${localId}/${official}` : localId,
    rarity: remote?.rarity || null,
    imageUrl: cardImageUrl(remote?.image),
    pokemonIds: Array.isArray(remote?.dexId) && remote.dexId.length ? remote.dexId : inferPokemonIds(remote?.name),
    illustrator: remote?.illustrator || null,
    category: remote?.category || null,
    variants: remote?.variants && typeof remote.variants === 'object' ? remote.variants : null,
    promotional: Boolean(remote?.variants?.wPromo)
      || /promo|black star/i.test(String(set.name || ''))
      || /(?:^|[-_.])p(?:$|[-_.])/i.test(String(set.id || '')),
  };
}

function inferSetIdFromRemoteCard(remote) {
  const explicit = String(remote?.set?.id || remote?.setId || '').trim();
  if (explicit) return explicit;
  const id = String(remote?.id || '').trim();
  const localId = String(remote?.localId || '').trim();
  if (id && localId && id.endsWith(`-${localId}`)) return id.slice(0, -(localId.length + 1));
  const split = id.lastIndexOf('-');
  return split > 0 ? id.slice(0, split) : '';
}

async function fetchCatalogLocale(base, localeLabel) {
  // A listagem direta /cards evita perder cartas quando uma consulta individual
  // de coleção falha. É também muito mais rápida do que abrir todos os sets.
  const [remoteSets, remoteCards] = await Promise.all([
    fetchJsonWithTimeout(`${base}/sets`, 60000),
    fetchJsonWithTimeout(`${base}/cards`, 90000),
  ]);
  if (!Array.isArray(remoteSets)) throw new Error(`${localeLabel}: lista de coleções inválida.`);
  if (!Array.isArray(remoteCards)) throw new Error(`${localeLabel}: lista de cartas inválida.`);

  const sets = new Map();
  for (const remote of remoteSets) {
    if (!remote?.id) continue;
    const set = normalizeRemoteSet(remote, remote);
    if (set.id) sets.set(set.id, set);
  }

  const cards = new Map();
  for (let index = 0; index < remoteCards.length; index++) {
    const remote = remoteCards[index];
    if (!remote?.id) continue;
    if (index % 500 === 0) {
      setCatalogUpdateProgress(`${localeLabel}: importando ${index.toLocaleString('pt-BR')} de ${remoteCards.length.toLocaleString('pt-BR')} cartas...`, index, remoteCards.length);
    }
    const setId = inferSetIdFromRemoteCard(remote);
    let set = sets.get(setId);
    if (!set) {
      const brief = remote?.set || {};
      set = {
        id: setId || String(brief.id || 'sem-colecao'),
        name: String(brief.name || setId || 'Coleção não identificada'),
        officialCardCount: Number(brief?.cardCount?.official) || 0,
        totalCardCount: Number(brief?.cardCount?.total) || 0,
        logoUrl: brief.logo || null,
        symbolUrl: brief.symbol || null,
      };
      sets.set(set.id, set);
    }
    const normalized = normalizeRemoteCard(remote, set);
    if (normalized.id) cards.set(normalized.id, normalized);
  }
  setCatalogUpdateProgress(`${localeLabel}: ${cards.size.toLocaleString('pt-BR')} cartas encontradas.`, remoteCards.length, remoteCards.length, true);
  return { sets, cards, failures: 0, listedCards: remoteCards.length };
}

/* =====================================================================
   Categoria da carta (Pokémon / Treinador / Energia)

   A listagem de cartas do TCGdex traz só id, número e nome — não diz se a
   carta é um Pokémon, um Item, um Apoiador, um Estádio ou uma Energia. Essa
   informação existe na consulta GraphQL, que aceita 2.500 cartas por página:
   o catálogo inteiro sai em cerca de quinze pedidos.

   Sem isso o app não tem como classificar uma carta que não representa
   nenhum Pokémon, e ela ficava sem identidade nenhuma na tela.
   ===================================================================== */
const CATEGORIA_ROTULOS = { Pokemon: 'Pokémon', Trainer: 'Treinador', Energy: 'Energia' };
const TREINADOR_ROTULOS = {
  Item: 'Item',
  Supporter: 'Apoiador',
  Stadium: 'Estádio',
  Tool: 'Ferramenta',
  'Pokemon Tool': 'Ferramenta',
  'Technical Machine': 'Máquina Técnica',
};
const ENERGIA_ROTULOS = { Normal: 'Energia básica', Special: 'Energia especial' };

async function fetchCategoriasGraphQL(totalEstimado) {
  const porPagina = 2500;
  const mapa = new Map();
  for (let pagina = 1; pagina <= 40; pagina += 1) {
    setCatalogUpdateProgress(`Classificando cartas (Item, Apoiador, Energia...): página ${pagina}`,
      mapa.size, Math.max(totalEstimado, mapa.size + 1), true);
    const corpo = JSON.stringify({
      query: `{ cards(filters: {}, pagination: {page: ${pagina}, itemsPerPage: ${porPagina}}) { id category trainerType energyType rarity } }`,
    });
    let lote;
    try {
      const resposta = await fetch('https://api.tcgdex.net/v2/graphql', {
        method: 'POST', headers: { 'content-type': 'application/json' }, body: corpo,
      });
      if (!resposta.ok) break;
      const json = await resposta.json();
      lote = json?.data?.cards;
    } catch (_) { break; }
    if (!Array.isArray(lote) || !lote.length) break;
    for (const item of lote) {
      if (item?.id) mapa.set(item.id, { category: item.category || '', trainerType: item.trainerType || '', energyType: item.energyType || '', rarity: item.rarity || '' });
    }
    if (lote.length < porPagina) break;
  }
  return mapa;
}

/* =====================================================================
   Data de lançamento das coleções

   A listagem de coleções do TCGdex devolve só id, nome e contagem de cartas —
   nenhuma data. Por isso quase todas as coleções ficavam sem mês, e a ordem
   cronológica caía no ano aproximado de uma tabela interna: dentro do mesmo
   ano, as coleções apareciam fora de ordem.

   A consulta GraphQL traz `releaseDate` completo (ano-mês-dia) de todas elas,
   num único pedido.
   ===================================================================== */
async function fetchDatasDeLancamento() {
  const mapa = new Map();
  try {
    const resposta = await fetch('https://api.tcgdex.net/v2/graphql', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ query: '{ sets(filters: {}, pagination: {page: 1, itemsPerPage: 1000}) { id releaseDate } }' }),
    });
    if (!resposta.ok) return mapa;
    const lista = (await resposta.json())?.data?.sets;
    if (!Array.isArray(lista)) return mapa;
    for (const item of lista) {
      if (item?.id && item?.releaseDate) mapa.set(item.id, item.releaseDate);
    }
  } catch (_) { /* sem internet: fica a ordem antiga, por ano */ }
  return mapa;
}

/* Subconjuntos como "swsh9.5tg" (galeria de treinadores) saem junto com a
   coleção principal e não têm data própria na fonte. Herdam a do pai. */
function dataHerdadaDoConjuntoPai(setId, mapa) {
  const id = String(setId || '');
  const pai = id.replace(/(?:tg|gg)$/i, '');
  return pai !== id ? mapa.get(pai) : null;
}

/* A consulta GraphQL cobre só o catálogo internacional. As coleções japonesas
   têm data, mas apenas quando pedidas uma a uma. São poucas para justificar
   deixá-las de fora da ordem cronológica — e em lotes paralelos a busca leva
   segundos, não minutos. */
async function completarDatasFaltantes(ids, aoProgredir) {
  const encontradas = new Map();
  const lote = 6;
  for (let i = 0; i < ids.length; i += lote) {
    const fatia = ids.slice(i, i + lote);
    aoProgredir?.(i, ids.length);
    await Promise.all(fatia.map(async id => {
      for (const base of [TCGDEX_API_JAPANESE, TCGDEX_API_FALLBACK]) {
        try {
          const resposta = await fetch(`${base}/sets/${encodeURIComponent(id)}`);
          if (!resposta.ok) continue;
          const detalhe = await resposta.json();
          if (detalhe?.releaseDate) { encontradas.set(id, detalhe.releaseDate); return; }
        } catch (_) { /* tenta o próximo idioma */ }
      }
    }));
  }
  return encontradas;
}

async function startCatalogUpdate() {
  if (catalogUpdating) return;
  catalogUpdating = true;
  setCatalogUpdateProgress('Baixando o catálogo completo TCGdex EN, PT-BR e japonês...', 0, 1, true);

  try {
    const localSets = new Map((catalog.sets || []).map(item => [item.id, { ...item }]));
    const localCards = new Map((catalog.cards || []).map(item => [item.id, { ...item }]));
    const oldCardCount = localCards.size;
    const oldSetCount = localSets.size;

    let ptResult = { sets: new Map(), cards: new Map(), failures: 0 };
    let enResult = { sets: new Map(), cards: new Map(), failures: 0 };
    let jaResult = { sets: new Map(), cards: new Map(), failures: 0 };
    try { jaResult = await fetchCatalogLocale(TCGDEX_API_JAPANESE, 'Japonês'); } catch (_) {}
    try { enResult = await fetchCatalogLocale(TCGDEX_API_FALLBACK, 'Inglês'); } catch (_) {}
    try { ptResult = await fetchCatalogLocale(TCGDEX_API_BASE, 'Português'); } catch (_) {}
    if (!ptResult.cards.size && !enResult.cards.size && !jaResult.cards.size) throw new Error('Nenhum dos catálogos online respondeu.');

    // Japonês inclui impressões regionais; inglês amplia a cobertura internacional;
    // português sobrescreve nomes e imagens quando disponível.
    for (const [id, set] of jaResult.sets) localSets.set(id, { ...(localSets.get(id) || {}), ...set });
    for (const [id, card] of jaResult.cards) localCards.set(id, { ...(localCards.get(id) || {}), ...card, catalogLocale: 'ja' });
    for (const [id, set] of enResult.sets) localSets.set(id, { ...(localSets.get(id) || {}), ...set });
    for (const [id, card] of enResult.cards) localCards.set(id, { ...(localCards.get(id) || {}), ...card, catalogLocale: 'en' });
    for (const [id, set] of ptResult.sets) localSets.set(id, { ...(localSets.get(id) || {}), ...set });
    for (const [id, card] of ptResult.cards) {
      const existing = localCards.get(id) || {};
      localCards.set(id, {
        ...existing,
        ...card,
        imageUrl: card.imageUrl || existing.imageUrl || null,
        illustrator: card.illustrator || existing.illustrator || null,
        variants: card.variants || existing.variants || null,
        pokemonIds: card.pokemonIds?.length ? card.pokemonIds : (existing.pokemonIds || []),
        catalogLocale: 'pt-br',
      });
    }

    // Data de lançamento completa, para a ordem cronológica ficar certa.
    setCatalogUpdateProgress('Buscando as datas de lançamento das coleções...', 0, 1, true);
    const datas = await fetchDatasDeLancamento();
    for (const [id] of localSets) {
      const herdada = datas.has(id) ? null : dataHerdadaDoConjuntoPai(id, datas);
      if (herdada) datas.set(id, herdada);
    }

    // As que a consulta em lote não cobriu são buscadas uma a uma.
    const faltantes = [...localSets.keys()].filter(id => !datas.get(id));
    if (faltantes.length) {
      const extras = await completarDatasFaltantes(faltantes, (feitos, total) => {
        setCatalogUpdateProgress(`Datas de lançamento: ${feitos} de ${total} coleções restantes...`, feitos, total, true);
      });
      for (const [id, data] of extras) datas.set(id, data);
    }

    let comData = 0;
    for (const [id, set] of localSets) {
      const data = datas.get(id);
      if (!data) continue;
      localSets.set(id, { ...set, releaseDate: data });
      comData += 1;
    }

    // Classificação: Pokémon, Item, Apoiador, Estádio, Ferramenta, Energia.
    const categorias = await fetchCategoriasGraphQL(localCards.size);
    let classificadas = 0;
    for (const [id, dados] of categorias) {
      const card = localCards.get(id);
      if (!card || !dados.category) continue;
      // "None" é como a fonte diz "esta carta não tem raridade" — não é uma
      // raridade chamada None, e guardá-la assim confundiria a tela.
      const raridade = dados.rarity && dados.rarity !== 'None' ? dados.rarity : (card.rarity || null);
      localCards.set(id, { ...card, category: dados.category, trainerType: dados.trainerType, energyType: dados.energyType, rarity: raridade });
      classificadas += 1;
    }

    const updatedCatalog = {
      version: new Date().toISOString(),
      source: 'TCGdex completo JA + EN + PT-BR + catálogo local',
      sets: [...localSets.values()],
      cards: [...localCards.values()],
    };
    setCatalogUpdateProgress('Salvando catálogo ampliado para uso offline...', 1, 1, true);
    await saveUpdatedCatalog(updatedCatalog);
    catalog = updatedCatalog;
    rebuildCatalogIndexes();

    const result = {
      updatedAt: Date.now(),
      setsAdded: Math.max(0, localSets.size - oldSetCount),
      setsUpdated: localSets.size,
      cardsAdded: Math.max(0, localCards.size - oldCardCount),
      setsTotal: localSets.size,
      cardsTotal: localCards.size,
      classificadas,
      comData,
      failures: ptResult.failures + enResult.failures + jaResult.failures,
      enListedCards: enResult.listedCards || enResult.cards.size,
      ptListedCards: ptResult.listedCards || ptResult.cards.size,
      jaListedCards: jaResult.listedCards || jaResult.cards.size,
    };
    saveCatalogUpdateMeta(result);
    ui.cardLimit = 80;
    catalogUpdating = false;
    catalogUpdateMessage = '';
    render();
    notify(`Catálogo completo: ${result.cardsTotal.toLocaleString('pt-BR')} impressões únicas (+${result.cardsAdded.toLocaleString('pt-BR')}).`);
  } catch (error) {
    catalogUpdating = false;
    catalogUpdateMessage = '';
    renderKeepingScroll();
    notify(`Não foi possível atualizar: ${error?.message || 'erro de conexão'}`);
  }
}

function filteredSetRows() {
  const query = normalize(ui.setQuery);
  const comCartas = new Set(cards.map(card => card.setId));

  const filtradas = buildSetStats().filter(item => {
    if (query
      && !normalize(item.name).includes(query)
      && !normalize(item.id).includes(query)
      && !String(setReleaseYear(item) || '').includes(query)) return false;

    const total = item.officialCardCount || item.totalCardCount || 0;
    switch (ui.setStatus) {
      case 'comecei': return item.ownedUnique > 0 && (!total || item.ownedUnique < total);
      case 'completas': return total > 0 && item.ownedUnique >= total;
      case 'faltando': return item.ownedUnique === 0;
      // Coleções que aparecem na lista mas não têm carta instalada: útil para
      // enxergar o que a atualização do catálogo ainda não trouxe.
      case 'vazias': return !comCartas.has(item.id);
      default: return true;
    }
  });

  const porNome = (a, b) => a.name.localeCompare(b.name, 'pt-BR');
  const tamanho = item => item.officialCardCount || item.totalCardCount || 0;
  switch (ui.setSort) {
    case 'antigas': return filtradas.sort((a, b) => compareSetsByTimeline(b, a));
    case 'nome': return filtradas.sort(porNome);
    case 'completas': return filtradas.sort((a, b) => (b.progress - a.progress) || (b.ownedUnique - a.ownedUnique) || porNome(a, b));
    case 'tamanho': return filtradas.sort((a, b) => (tamanho(b) - tamanho(a)) || porNome(a, b));
    default: return filtradas.sort(compareSetsByTimeline);
  }
}

function mudarFiltroSets(campo, valor) {
  ui[campo] = valor;
  render();
}

const SET_RELEASE_YEAR_BY_ID = {
  base1: 1999, base2: 1999, base3: 1999, base4: 2000, base5: 2000, base6: 2000,
  gym1: 2000, gym2: 2000, neo1: 2000, neo2: 2001, neo3: 2001, neo4: 2002,
  ecard1: 2002, ecard2: 2003, ecard3: 2003, ex1: 2003, ex2: 2003, ex3: 2003,
  ex4: 2004, ex5: 2004, ex6: 2004, ex7: 2004, ex8: 2005, ex9: 2005,
  ex10: 2005, ex11: 2005, ex12: 2006, ex13: 2006, ex14: 2006, ex15: 2006,
  ex16: 2007, dp1: 2007, dp2: 2007, dp3: 2007, dp4: 2008, dp5: 2008,
  dp6: 2008, dp7: 2008, pl1: 2009, pl2: 2009, pl3: 2009, pl4: 2009,
  hgss1: 2010, hgss2: 2010, hgss3: 2010, hgss4: 2010, col1: 2011,
  bw1: 2011, bw2: 2011, bw3: 2011, bw4: 2012, bw5: 2012, bw6: 2012,
  bw7: 2012, bw8: 2013, bw9: 2013, bw10: 2013, bw11: 2013,
  xy0: 2013, xy1: 2014, xy2: 2014, xy3: 2014, xy4: 2014, xy5: 2015,
  xy6: 2015, xy7: 2015, xy8: 2015, xy9: 2016, xy10: 2016, xy11: 2016,
  xy12: 2016, g1: 2016, sm1: 2017, sm2: 2017, sm3: 2017, sm4: 2017,
  sm5: 2018, sm6: 2018, sm7: 2018, sm8: 2018, sm9: 2019, sm10: 2019,
  sm11: 2019, sm12: 2019, swsh1: 2020, swsh2: 2020, swsh3: 2020,
  'swsh3.5': 2020, swsh4: 2020, 'swsh4.5': 2021, swsh5: 2021,
  swsh6: 2021, swsh7: 2021, swsh8: 2021, swsh9: 2022, swsh10: 2022,
  'swsh10.5': 2022, swsh11: 2022, swsh12: 2022, 'swsh12.5': 2023,
  cel25: 2021, cel25cc: 2021, sv01: 2023, sv02: 2023, sv03: 2023,
  'sv03.5': 2023, sv04: 2023, 'sv04.5': 2024, sv05: 2024, sv06: 2024, 'sv06.5': 2024,
  sv07: 2024, sv08: 2024, 'sv08.5': 2025, sv09: 2025, sv10: 2025,
  'sv10.5b': 2025, 'sv10.5w': 2025, me01: 2025, me02: 2025,
  'me02.5': 2026, me03: 2026, me04: 2026, me05: 2026,
  dv1: 2012, dc1: 2015, sm115: 2019, sma: 2019, det1: 2019,
  'sm3.5': 2017, 'sm7.5': 2018, smp: 2017, swshp: 2020, svp: 2023,
  sve: 2023, mee: 2025, mep: 2025, 'p-a': 2024,
  a1: 2024, a1a: 2024, a2: 2025, a2a: 2025, a2b: 2025,
  a3: 2025, a4a: 2025, b1a: 2026, b2: 2026, b2a: 2026,
  'swsh9.5tg': 2022, 'swsh11.5tg': 2022,
};

function setReleaseYear(item) {
  const date = String(item?.releaseDate || '').trim();
  const match = date.match(/\b(19|20)\d{2}\b/);
  if (match) return Number(match[0]);
  const id = String(item?.id || '').trim().toLowerCase();
  if (SET_RELEASE_YEAR_BY_ID[id]) return SET_RELEASE_YEAR_BY_ID[id];
  const parentId = id.replace(/(?:tg|gg|sv)$/i, '');
  if (SET_RELEASE_YEAR_BY_ID[parentId]) return SET_RELEASE_YEAR_BY_ID[parentId];
  return 1999;
}

const SET_ERA_ORDER = {
  me: 1000, sv: 900, swsh: 800, sm: 700, xy: 600, bw: 500,
  hgss: 400, pl: 350, dp: 300, ex: 200, base: 100, gym: 90,
  neo: 80, ecard: 70, pop: 60, col: 50, tk: 40, other: 0,
};

function setEraKey(item) {
  const id = String(item?.id || '').toLowerCase();
  const series = normalize(item?.seriesName || '');
  if (/^me/.test(id) || /mega/.test(series)) return 'me';
  if (/^sv/.test(id) || /scarlet|violet|escarlate|purpura/.test(series)) return 'sv';
  if (/^swsh/.test(id) || /sword|shield|espada|escudo/.test(series)) return 'swsh';
  if (/^sm/.test(id) || /sun|moon|sol|lua/.test(series)) return 'sm';
  if (/^xy/.test(id)) return 'xy';
  if (/^bw/.test(id) || /black|white/.test(series)) return 'bw';
  if (/^hgss/.test(id)) return 'hgss';
  if (/^pl/.test(id)) return 'pl';
  if (/^dp/.test(id)) return 'dp';
  if (/^ex/.test(id)) return 'ex';
  if (/^neo/.test(id)) return 'neo';
  if (/^ecard|^ecard/.test(id)) return 'ecard';
  if (/^base/.test(id)) return 'base';
  if (/^gym/.test(id)) return 'gym';
  if (/^pop/.test(id)) return 'pop';
  if (/^col/.test(id)) return 'col';
  if (/^tk/.test(id)) return 'tk';
  return 'other';
}

function setEraLabel(item) {
  if (item?.seriesName) return String(item.seriesName);
  return ({
    me: 'Mega Evolução', sv: 'Escarlate e Violeta', swsh: 'Espada e Escudo',
    sm: 'Sol e Lua', xy: 'XY', bw: 'Black & White',
    hgss: 'HeartGold & SoulSilver', pl: 'Platina', dp: 'Diamond & Pearl',
    ex: 'EX', neo: 'Neo', ecard: 'e-Card', base: 'Clássicas',
    gym: 'Gym', pop: 'POP', col: 'Call of Legends', tk: 'Trainer Kits',
    other: 'Outras coleções',
  })[setEraKey(item)];
}

function setSequence(item) {
  const values = String(item?.id || '').match(/\d+(?:\.\d+)?/g) || [];
  return values.reduce((score, value, index) => score + Number(value) * Math.pow(1000, Math.max(0, 2 - index)), 0);
}

function setReleaseTime(item) {
  const value = Date.parse(String(item?.releaseDate || ''));
  return Number.isFinite(value) ? value : 0;
}

const MESES_CURTOS = ['jan', 'fev', 'mar', 'abr', 'mai', 'jun', 'jul', 'ago', 'set', 'out', 'nov', 'dez'];

/**
 * "mar/2023" a partir da data de lançamento.
 *
 * A data vem como texto puro ("2023-03-31"). Passar isso por `new Date` e
 * formatar interpretaria como meia-noite em Londres e converteria para o fuso
 * do Brasil, três horas atrás — datas de dia 1º voltavam para o mês anterior,
 * e as de janeiro voltavam um ano inteiro. Aqui os pedaços são lidos direto do
 * texto, sem passar por fuso nenhum.
 */
function mesAnoDoLancamento(item) {
  const partes = String(item?.releaseDate || '').match(/^(\d{4})-(\d{2})/);
  if (!partes) return '';
  const mes = MESES_CURTOS[Number(partes[2]) - 1];
  return mes ? `${mes}/${partes[1]}` : partes[1];
}

function compareSetsByTimeline(a, b) {
  const dated = setReleaseTime(b) - setReleaseTime(a);
  if (dated) return dated;
  const years = setReleaseYear(b) - setReleaseYear(a);
  if (years) return years;
  const era = (SET_ERA_ORDER[setEraKey(b)] || 0) - (SET_ERA_ORDER[setEraKey(a)] || 0);
  if (era) return era;
  const sequence = setSequence(b) - setSequence(a);
  return sequence || a.name.localeCompare(b.name, 'pt-BR');
}

function setAssetSeries(item) {
  const id = String(item?.id || '').toLowerCase();
  if (/^(cel25)/.test(id)) return 'swsh';
  if (/^(dv1)/.test(id)) return 'bw';
  if (/^(dc1|g1)/.test(id)) return 'xy';
  if (/^(det1|sma)/.test(id)) return 'sm';
  return setEraKey(item);
}

function setLogoUrl(item) {
  const series = setAssetSeries(item);
  if (item.logoUrl) return item.logoUrl;
  return series && series !== 'other'
    ? `https://assets.tcgdex.net/en/${encodeURIComponent(series)}/${encodeURIComponent(item.id)}/logo`
    : '';
}

function explicitSetAssetUrl(url) {
  const value = String(url || '');
  return value && !/\.(?:webp|png|jpe?g)(?:[?#].*)?$/i.test(value)
    ? `${value}.webp`
    : value;
}

function setImageCandidates(item) {
  const series = setAssetSeries(item);
  const id = encodeURIComponent(item.id);
  // A ordem importa: a primeira que carregar é a que fica. O papel de parede
  // genérico estava em segundo lugar e, como ele sempre existe, o logo real
  // nunca chegava a ser tentado — todas as coleções ficavam com a mesma arte.
  // Agora ele é o último recurso.
  const candidates = [
    setLogoLocal(item.id),
    explicitSetAssetUrl(item.logoUrl),
    series && series !== 'other' ? `https://assets.tcgdex.net/pt/${encodeURIComponent(series)}/${id}/logo.webp` : '',
    series && series !== 'other' ? `https://assets.tcgdex.net/en/${encodeURIComponent(series)}/${id}/logo.webp` : '',
    // A imagem dos metadados costuma ser a arte de uma carta, não o logo da
    // coleção. Serve como reserva, nunca antes do logo de verdade.
    item.collectionImage,
    explicitSetAssetUrl(item.symbolUrl),
    series && series !== 'other' ? `https://assets.tcgdex.net/univ/${encodeURIComponent(series)}/${id}/symbol.webp` : '',
  ];
  return [...new Set(candidates.filter(Boolean))];
}

/**
 * Logo que veio junto no aplicativo, baixado por
 * scripts/baixar-logos-colecoes.mjs. Funciona sem internet e não quebra se
 * algum endereço mudar lá fora.
 */
function setLogoLocal(setId) {
  const id = String(setId || '').trim();
  if (!id) return '';
  const indice = window.__SET_LOGOS__;
  // Com o índice presente, só devolve o que realmente foi baixado. Sem ele,
  // tenta o caminho previsto — se não existir, o app passa para o próximo.
  if (indice && typeof indice === 'object') return indice[id] || '';
  return `set-logos/${encodeURIComponent(id)}.webp`;
}

function loadNextSetImage(image) {
  const fallbacks = String(image.dataset.fallbacks || '').split('|').filter(Boolean);
  if (fallbacks.length) {
    image.dataset.fallbacks = fallbacks.slice(1).join('|');
    image.src = fallbacks[0];
    return;
  }
  image.hidden = true;
  if (image.nextElementSibling) image.nextElementSibling.hidden = false;
}

function renderSetTimeline(rows) {
  return `<div class="set-timeline">${rows.map(renderSetCard).join('')}</div>`;
}

function renderSetSearchResults() {
  const rows = filteredSetRows();
  return `<div style="margin-top:12px">${rows.length ? renderSetTimeline(rows) : '<div class="empty"><strong>Nenhuma coleção encontrada</strong>Tente outro termo.</div>'}</div>`;
}

function renderSets() {
  const totalSets = catalog.sets.length;
  const vazias = colecoesSemCartas().length;
  const mostradas = filteredSetRows().length;
  return `
    <section class="screen vision-explore-screen">
      <div class="vision-screen-head"><h2>Explorar</h2><button onclick="ui.setQuery='';render()">Mais recentes</button></div>
      <div class="explore-search-panel">
        <label class="vision-search"><span>${tabIcon('pokedex')}</span><input id="setSearchInput" value="${esc(ui.setQuery)}" placeholder="Buscar set..."
          oncompositionstart="this.dataset.composing='1'"
          oncompositionend="this.dataset.composing='';searchAndRender('setQuery', this.value, 'setSearchInput')"
          oninput="searchAndRender('setQuery', this.value, 'setSearchInput')"></label>
        <!-- Antes havia dois botões, "Séries" e "Todos", que não faziam nada.
             No lugar deles, filtro e ordenação que respondem de verdade. -->
        <div class="explore-filter-row">
          <select class="explore-filtro" onchange="mudarFiltroSets('setStatus', this.value)">
            ${[['all','Todas as coleções'],['comecei','Comecei'],['completas','Completas'],['faltando','Ainda não tenho'],['vazias','Sem cartas baixadas']]
              .map(([valor, rotulo]) => option(valor, rotulo, ui.setStatus)).join('')}
          </select>
          <select class="explore-filtro" onchange="mudarFiltroSets('setSort', this.value)">
            ${[['recentes','Mais recentes'],['antigas','Mais antigas'],['nome','Nome (A–Z)'],['completas','Mais completas'],['tamanho','Mais cartas']]
              .map(([valor, rotulo]) => option(valor, rotulo, ui.setSort)).join('')}
          </select>
          <span class="explore-contagem">${mostradas.toLocaleString('pt-BR')} de ${totalSets.toLocaleString('pt-BR')}</span>
        </div>
      </div>

      <!-- Antes isto ficava no fim da tela, depois de mais de cem coleções e
           dentro de um bloco fechado: na prática, invisível. -->
      <button class="atualizar-catalogo-btn" ${catalogUpdating ? 'disabled' : ''} onclick="startCatalogUpdate()">
        <span>${catalogUpdating ? '⟳ Atualizando…' : '⟳ Buscar cartas e coleções novas'}</span>
        <small>${esc(resumoCatalogo())}</small>
      </button>
      ${vazias && !catalogUpdateMeta.updatedAt ? `<div class="catalogo-aviso">
        <strong>${vazias} coleção(ões) ainda sem cartas baixadas</strong>
        <span>Elas aparecem na lista mas abrem vazias. O botão acima busca as cartas que faltam.</span>
      </div>` : ''}
      ${catalogUpdating ? `<div class="catalog-progress"><div class="progress"><span style="width:${catalogUpdateTotal > 0 ? Math.max(0, Math.min(100, Math.round((catalogUpdateCurrent / catalogUpdateTotal) * 100))) : 0}%"></span></div><small>${esc(catalogUpdateMessage || 'Preparando atualização...')}</small></div>` : ''}

      <div id="setSearchResults">${renderSetSearchResults()}</div>
      <details class="catalog-maintenance"><summary>Detalhes da atualização</summary>${catalogUpdatePanel()}</details>
    </section>`;
}

/* Coleções que existem na lista mas não têm nenhuma carta instalada. Elas
   abrem vazias e é o que mais confunde: a coleção aparece, o conteúdo não. */
function colecoesSemCartas() {
  const porSet = new Map();
  for (const card of cards) porSet.set(card.setId, (porSet.get(card.setId) || 0) + 1);
  return (catalog.sets || []).filter(set => !porSet.get(set.id));
}

function resumoCatalogo() {
  const quando = catalogUpdateMeta.updatedAt
    ? `atualizado em ${formatCatalogUpdateDate(catalogUpdateMeta.updatedAt)}`
    : 'nunca atualizado pela internet';
  return `${cards.length.toLocaleString('pt-BR')} cartas · ${catalog.sets.length} coleções · ${quando}`;
}

/* =====================================================================
   Produtos prontos — baralhos de batalha, kits e caixas

   O TCGdex e o pokemontcg.io publicam 218 e 174 coleções, e nenhuma das
   duas traz os Battle Decks: para elas, baralho pronto é produto lacrado,
   não coleção. Conferido também que não existe nada de Eevee.

   Então o app trabalha com duas origens:
   · os 21 kits que a fonte JÁ publica como coleção (Trainer Kits, Kalos
     Starter Set) — aparecem sozinhos, sem trabalho manual;
   · os que faltam, montados aqui uma vez e guardados no aparelho.

   Os dois aparecem juntos e são acompanhados igual: quantas cartas do
   produto você já tem.
   ===================================================================== */
function produtosDaFonte() {
  return (catalog.sets || []).filter(set => {
    const id = String(set.id || '');
    const nome = normalize(set.name || '');
    return /^tk[-_]/i.test(id) || id === 'xy0'
      || /trainer kit|starter set|battle deck|baralho de batalha/.test(nome);
  });
}

function produtosProprios() {
  if (!Array.isArray(state.produtos)) state.produtos = [];
  return state.produtos;
}

/** Progresso de um produto próprio: quantas das cartas dele você tem. */
function progressoDoProduto(produto) {
  const ids = Object.keys(produto?.cartas || {});
  if (!ids.length) return { tenho: 0, total: 0, porcento: 0 };
  const tenho = ids.filter(id => quantityFor(id) > 0).length;
  return { tenho, total: ids.length, porcento: Math.round((tenho / ids.length) * 100) };
}

function renderProdutos() {
  const daFonte = produtosDaFonte().sort(compareSetsByTimeline);
  const meus = produtosProprios();
  const stats = new Map(buildSetStats().map(item => [item.id, item]));

  const cartaoProprio = produto => {
    const p = progressoDoProduto(produto);
    return `<button class="set-card timeline-set-card ${p.tenho ? 'owned' : 'missing'}${p.total && p.tenho >= p.total ? ' completa' : ''}" onclick="abrirProduto('${esc(produto.id)}')">
      <span class="set-logo-fallback">◧</span>
      <span class="set-card-content">
        <span class="set-base-row"><small>MEU · ${p.tenho}/${p.total}</small><b>${p.porcento}%</b></span>
        <span class="progresso-linha">
          <span class="pokebola-progresso${p.porcento >= 100 ? ' cheia' : ''}" aria-hidden="true"><i style="height:${p.porcento}%"></i></span>
          <span class="progress"><span style="width:${p.porcento}%"></span></span>
        </span>
        <span class="set-name">${esc(produto.nome)}</span>
        <span class="set-card-meta"><small>${p.total} cartas</small><small>${esc(produto.ano || 'sem ano')}</small></span>
      </span>
    </button>`;
  };

  return `
    <section class="screen">
      <div class="vision-screen-head"><h2>Produtos prontos</h2><button onclick="novoProduto()">+ Criar</button></div>
      <p class="screen-subtitle">Baralhos de batalha, kits e caixas. Os que a fonte publica aparecem sozinhos; os demais você monta uma vez.</p>

      <h3 class="section-title">Meus produtos</h3>
      ${meus.length
        ? `<div class="set-timeline">${meus.map(cartaoProprio).join('')}</div>`
        : `<div class="empty catalogo-vazio">
             <strong>Nenhum produto montado ainda</strong>
             <span>Baralhos de batalha não vêm prontos de nenhuma fonte pública — nem o da Eevee de 2022. Monte o seu uma vez e o app acompanha o progresso como faz com as coleções.</span>
             <button class="primary-btn" onclick="novoProduto()">Criar meu primeiro produto</button>
           </div>`}

      ${secaoLacrados()}

      <h3 class="section-title">Kits publicados pela fonte</h3>
      <p class="screen-subtitle">${daFonte.length} produtos que o catálogo já traz completos.</p>
      <div class="set-timeline">${daFonte.map(set => renderSetCard(stats.get(set.id) || { ...set, ownedUnique: 0, progress: 0 })).join('')}</div>
    </section>`;
}

function secaoLacrados() {
  const lista = lacrados();
  const r = resumoLacrados();
  const lucro = r.valor - r.pago;

  return `
    <div class="secao-lacrados-topo">
      <h3 class="section-title">Lacrados</h3>
      <button class="mini-btn" onclick="novoLacrado()">+ Adicionar</button>
    </div>
    ${lista.length ? `
      <div class="lacrados-resumo">
        <span><small>Unidades</small><b>${r.unidades}</b></span>
        <span><small>Pago</small><b>${esc(money(r.pago))}</b></span>
        <span><small>Vale hoje</small><b>${esc(money(r.valor))}</b></span>
        ${r.pago && r.valor ? `<span><small>Diferença</small><b class="${lucro >= 0 ? 'sobe' : 'desce'}">${lucro >= 0 ? '+' : ''}${esc(money(lucro))}</b></span>` : ''}
      </div>
      <div class="lacrados-lista">${lista.map(cartaoLacrado).join('')}</div>`
      : `<div class="empty catalogo-vazio">
           <strong>Nenhum lacrado cadastrado</strong>
           <span>Caixas, latas e blisters fechados. Como o conteúdo é sorteado, aqui não há progresso de cartas — o app acompanha quantidade e valor.</span>
           <button class="primary-btn" onclick="novoLacrado()">Adicionar lacrado</button>
         </div>`}`;
}

/* =====================================================================
   Produtos lacrados

   Lacrado é diferente de baralho pronto: uma caixa de booster não tem
   lista de cartas — o conteúdo é sorteado. Não existe "progresso" nem
   "completar" aqui, e fingir que existe seria mentir.

   O que faz sentido acompanhar é inventário: o que você tem, quantas
   unidades, quanto pagou e quanto vale hoje. Nenhuma fonte pública
   publica preço de lacrado de graça — o TCGdex e o pokemontcg.io só
   trazem cartas —, então o valor é informado por você. O app faz a
   conta e o acompanhamento; o número de mercado é seu.
   ===================================================================== */
const TIPOS_LACRADO = [
  'Caixa de booster',
  'Elite Trainer Box',
  'Blister',
  'Lata',
  'Caixa de coleção',
  'Baralho lacrado',
  'Pacote avulso',
  'Outro',
];

function lacrados() {
  if (!Array.isArray(state.lacrados)) state.lacrados = [];
  return state.lacrados;
}

function resumoLacrados() {
  return lacrados().reduce((soma, item) => {
    const unidades = Math.max(0, Number(item.quantidade) || 0);
    soma.unidades += unidades;
    soma.pago += (Number(item.precoPago) || 0) * unidades;
    soma.valor += (Number(item.valorAtual) || 0) * unidades;
    return soma;
  }, { unidades: 0, pago: 0, valor: 0 });
}

function cartaoLacrado(item) {
  const unidades = Math.max(0, Number(item.quantidade) || 0);
  const valor = (Number(item.valorAtual) || 0) * unidades;
  const pago = (Number(item.precoPago) || 0) * unidades;
  const lucro = valor - pago;
  const set = item.setId ? catalog.sets.find(s => s.id === item.setId) : null;
  return `<button class="lacrado-card" onclick="editarLacrado('${esc(item.id)}')">
    <span class="lacrado-tipo">${esc(item.tipo || 'Outro')}</span>
    <span class="lacrado-nome">${esc(item.nome)}</span>
    <span class="lacrado-meta">${set ? esc(set.name) : 'sem coleção'}${item.comprado ? ` · ${esc(item.comprado)}` : ''}</span>
    <span class="lacrado-numeros">
      <b>${unidades}x</b>
      <em>${valor ? esc(money(valor)) : 'sem valor'}</em>
      ${pago && valor ? `<i class="${lucro >= 0 ? 'sobe' : 'desce'}">${lucro >= 0 ? '+' : ''}${esc(money(lucro))}</i>` : ''}
    </span>
  </button>`;
}

function formularioLacrado(item) {
  const ehNovo = !item;
  const dados = item || { tipo: TIPOS_LACRADO[0], nome: '', setId: '', quantidade: 1, precoPago: '', valorAtual: '', comprado: '', notas: '' };
  return `
    <button class="modal-close" onclick="closeModal()" aria-label="Fechar">×</button>
    <h2>${ehNovo ? 'Novo produto lacrado' : 'Editar lacrado'}</h2>
    <p class="screen-subtitle">Caixas, latas e blisters fechados. O conteúdo é sorteado, então aqui não há progresso de cartas — o que se acompanha é quantidade e valor.</p>
    <div class="registration-grid two-columns">
      ${registrationField('Tipo', `<select id="lacTipo" class="field">${TIPOS_LACRADO.map(t => option(t, t, dados.tipo)).join('')}</select>`)}
      ${registrationField('Quantidade', `<input id="lacQtd" class="field" type="number" inputmode="numeric" min="0" step="1" value="${Math.max(0, Number(dados.quantidade) || 0)}">`)}
      ${registrationField('Nome do produto', `<input id="lacNome" class="field" placeholder="Ex.: Caixa de booster Ilha Mítica" value="${esc(dados.nome)}">`, 'span-2')}
      ${registrationField('Coleção (opcional)', `<select id="lacSet" class="field"><option value="">Nenhuma</option>${catalog.sets.slice().sort(compareSetsByTimeline).map(s => option(s.id, s.name, dados.setId)).join('')}</select>`, 'span-2')}
      ${registrationField('Preço pago (por unidade)', `<input id="lacPago" class="field" inputmode="decimal" placeholder="0,00" value="${esc(dados.precoPago)}">`)}
      ${registrationField('Valor hoje (por unidade)', `<input id="lacValor" class="field" inputmode="decimal" placeholder="0,00" value="${esc(dados.valorAtual)}">`)}
      ${registrationField('Comprado em', `<input id="lacComprado" class="field" placeholder="Ex.: mar/2024" value="${esc(dados.comprado)}">`)}
      ${registrationField('Observações', `<input id="lacNotas" class="field" placeholder="Onde comprou, estado da caixa..." value="${esc(dados.notas)}">`, 'span-2')}
    </div>
    <small class="screen-subtitle">Nenhuma fonte pública publica preço de lacrado sem custo — o valor aqui é o que você informar.</small>
    <div class="modal-actions">
      <button class="primary-btn" onclick="salvarLacrado('${esc(item?.id || '')}')">Salvar</button>
      ${ehNovo ? '' : `<button class="danger-btn" onclick="apagarLacrado('${esc(item.id)}')">Apagar</button>`}
    </div>`;
}

function novoLacrado() { showModal(formularioLacrado(null), 'produto-sheet'); }

function editarLacrado(id) {
  const item = lacrados().find(x => x.id === id);
  if (item) showModal(formularioLacrado(item), 'produto-sheet');
}

function numeroDoCampo(id) {
  const bruto = String(document.getElementById(id)?.value || '').replace(/\./g, '').replace(',', '.');
  const valor = Number(bruto);
  return Number.isFinite(valor) && valor > 0 ? valor : 0;
}

function salvarLacrado(id) {
  const nome = String(document.getElementById('lacNome')?.value || '').trim();
  if (!nome) return notify('Dê um nome ao produto.');
  const dados = {
    tipo: document.getElementById('lacTipo')?.value || 'Outro',
    nome,
    setId: document.getElementById('lacSet')?.value || '',
    quantidade: Math.max(0, Math.trunc(Number(document.getElementById('lacQtd')?.value) || 0)),
    precoPago: numeroDoCampo('lacPago'),
    valorAtual: numeroDoCampo('lacValor'),
    comprado: String(document.getElementById('lacComprado')?.value || '').trim(),
    notas: String(document.getElementById('lacNotas')?.value || '').trim(),
  };
  const existente = id ? lacrados().find(x => x.id === id) : null;
  if (existente) Object.assign(existente, dados);
  else lacrados().push({ id: `lac-${Date.now().toString(36)}`, ...dados });
  saveState();
  closeModal();
  render();
  notify(existente ? 'Produto atualizado.' : 'Produto lacrado adicionado.');
}

function apagarLacrado(id) {
  const item = lacrados().find(x => x.id === id);
  if (!item || !window.confirm(`Apagar "${item.nome}"?`)) return;
  state.lacrados = lacrados().filter(x => x.id !== id);
  saveState();
  closeModal();
  render();
}

function novoProduto() {
  const nome = prompt('Nome do produto (ex.: Baralho de Batalha Eevee):');
  if (!nome?.trim()) return;
  const ano = prompt('Ano de lançamento (ex.: 2022):') || '';
  if (!Array.isArray(state.produtos)) state.produtos = [];
  const produto = {
    id: `prod-${Date.now().toString(36)}`,
    nome: nome.trim(),
    ano: String(ano).trim(),
    cartas: {},
    criadoEm: new Date().toISOString(),
  };
  state.produtos.push(produto);
  saveState();
  abrirProduto(produto.id);
}

function produtoPorId(id) {
  return produtosProprios().find(item => item.id === id) || null;
}

function abrirProduto(id) {
  const produto = produtoPorId(id);
  if (!produto) return;
  const p = progressoDoProduto(produto);
  const linhas = Object.keys(produto.cartas).map(cardId => {
    const card = cardMap.get(cardId);
    if (!card) return '';
    const tenho = quantityFor(cardId);
    return `<button class="busca-manual-item ${tenho ? 'tenho' : ''}" onclick="openCard('${esc(cardId)}')">
      ${card.imageUrl ? `<img src="${esc(upgradeCardImageUrl(card.imageUrl))}" alt="" loading="lazy">` : '<span class="card-placeholder">TCG</span>'}
      <span><strong>${esc(card.name)}</strong><small>${esc(formatCardNumber(card.localId || card.number, card.setTotal))} · ${esc(card.setName)}</small></span>
      <b class="produto-tenho">${tenho ? `✓ ${tenho}` : '—'}</b>
      <i class="produto-remover" onclick="event.stopPropagation();removerDoProduto('${esc(produto.id)}','${esc(cardId)}')">×</i>
    </button>`;
  }).join('');

  showModal(`
    <button class="modal-close" onclick="closeModal()" aria-label="Fechar">×</button>
    <h2>${esc(produto.nome)}</h2>
    <p class="screen-subtitle">${esc(produto.ano || 'sem ano')} · ${p.tenho} de ${p.total} cartas${p.total ? ` · ${p.porcento}%` : ''}</p>
    <div class="progresso-linha" style="margin:12px 0">
      <span class="pokebola-progresso${p.porcento >= 100 ? ' cheia' : ''}" aria-hidden="true"><i style="height:${p.porcento}%"></i></span>
      <span class="progress"><span style="width:${p.porcento}%"></span></span>
    </div>

    <label class="registration-field">
      <span>Acrescentar carta</span>
      <input id="produtoBusca" class="field" placeholder="Nome, número ou 069/086" autocomplete="off"
        oninput="buscarParaProduto('${esc(produto.id)}', this.value)">
    </label>
    <div id="produtoResultados" class="busca-manual-lista"></div>

    <h3 class="section-title">Cartas do produto</h3>
    <div class="busca-manual-lista produto-lista">${linhas || '<p class="busca-manual-dica">Nenhuma carta ainda. Use a busca acima.</p>'}</div>

    <div class="modal-actions">
      <button class="secondary-btn" onclick="renomearProduto('${esc(produto.id)}')">Renomear</button>
      <button class="danger-btn" onclick="apagarProduto('${esc(produto.id)}')">Apagar produto</button>
    </div>
  `, 'produto-sheet');
}

function buscarParaProduto(produtoId, termo) {
  const lista = document.getElementById('produtoResultados');
  if (!lista) return;
  const texto = String(termo || '').trim();
  if (texto.length < 2) { lista.innerHTML = ''; return; }

  const alvo = normalize(texto);
  const fracao = texto.match(/(\d{1,4})\s*\/\s*(\d{1,4})/);
  const numero = /^\d{1,4}$/.test(texto) ? String(Number(texto)) : '';
  const achados = cards.filter(card => {
    const local = String(Number(String(card.localId || '').match(/\d+/)?.[0] ?? -1));
    if (fracao) {
      const total = String(Number(card.setTotal || card.number?.split('/')?.[1] || -1));
      return local === String(Number(fracao[1])) && total === String(Number(fracao[2]));
    }
    if (numero) return local === numero;
    return normalize(card.name).includes(alvo);
  }).slice(0, 15);

  lista.innerHTML = achados.length
    ? achados.map(card => `<button class="busca-manual-item" onclick="acrescentarAoProduto('${esc(produtoId)}','${esc(card.id)}')">
        ${card.imageUrl ? `<img src="${esc(upgradeCardImageUrl(card.imageUrl))}" alt="" loading="lazy">` : '<span class="card-placeholder">TCG</span>'}
        <span><strong>${esc(card.name)}</strong><small>${esc(formatCardNumber(card.localId || card.number, card.setTotal))} · ${esc(card.setName)}</small></span>
      </button>`).join('')
    : '<p class="busca-manual-dica">Nenhuma carta encontrada.</p>';
}

function acrescentarAoProduto(produtoId, cardId) {
  const produto = produtoPorId(produtoId);
  if (!produto) return;
  produto.cartas[cardId] = (Number(produto.cartas[cardId]) || 0) + 1;
  saveState();
  abrirProduto(produtoId);
}

function removerDoProduto(produtoId, cardId) {
  const produto = produtoPorId(produtoId);
  if (!produto) return;
  delete produto.cartas[cardId];
  saveState();
  abrirProduto(produtoId);
}

function renomearProduto(produtoId) {
  const produto = produtoPorId(produtoId);
  if (!produto) return;
  const nome = prompt('Novo nome:', produto.nome);
  if (!nome?.trim()) return;
  produto.nome = nome.trim();
  saveState();
  abrirProduto(produtoId);
}

function apagarProduto(produtoId) {
  const produto = produtoPorId(produtoId);
  if (!produto) return;
  if (!window.confirm(`Apagar "${produto.nome}"? As cartas continuam na sua coleção.`)) return;
  state.produtos = produtosProprios().filter(item => item.id !== produtoId);
  saveState();
  closeModal();
  render();
}

function renderSetCard(item) {
  const total = item.officialCardCount || item.totalCardCount || 0;
  const owned = item.ownedUnique > 0;
  const imageCandidates = setImageCandidates(item);
  const logo = imageCandidates[0] || '';
  const fallbacks = imageCandidates.slice(1).join('|');
  const releaseYear = setReleaseYear(item);
  const releaseDate = mesAnoDoLancamento(item) || releaseYear;
  return `<button class="set-card timeline-set-card ${owned ? 'owned' : 'missing'}" onclick="openSet('${esc(item.id)}')">
    ${logo
      ? `<img class="set-logo-background" src="${esc(logo)}" loading="lazy" decoding="async" alt=""${fallbacks ? ` data-fallbacks="${esc(fallbacks)}"` : ''} onerror="loadNextSetImage(this)"><span class="set-logo-fallback" hidden>◓</span>`
      : '<span class="set-logo-fallback">◓</span>'}
    <span class="set-card-content">
      <span class="set-base-row"><small>BASE ${item.ownedUnique}/${total}</small><b>${item.progress}%</b></span>
      <!-- A Pokébola enche junto com a barra: a barra continua ali, dando a
           leitura exata, e a bola dá a leitura de relance. -->
      <span class="progresso-linha">
        <span class="pokebola-progresso${item.progress >= 100 ? ' cheia' : ''}" aria-hidden="true"><i style="height:${item.progress}%"></i></span>
        <span class="progress"><span style="width:${item.progress}%"></span></span>
      </span>
      <span class="set-name">${esc(item.name)}</span>
      <span class="set-card-meta"><small>${total} cartas</small><small>${esc(releaseDate)}</small></span>
    </span>
  </button>`;
}

function openSet(setId) {
  ui.cardSet = setId;
  ui.cardFilter = 'all';
  ui.cardQuery = '';
  setTab('cards');
}

function pendingPriceQuoteForVariant(cardId, variant) {
  if (!variant || Number(variant.quantity) <= 0 || hasFiniteNumber(variant.manualEstimatedValue)) return null;
  const stored = storedAutomaticPriceQuote(variant);
  if (stored?.confidence === 'review' && !stored.userValidated) return stored;
  const live = automaticPriceQuote(cardId, variant.finish || 'normal');
  const liveAccepted = Boolean(live?.fingerprint)
    && Boolean(variant.automaticPriceUserValidated)
    && variant.automaticPriceAcceptedFingerprint === live.fingerprint;
  return live?.confidence === 'review' && !liveAccepted ? live : null;
}

function cardNeedsPriceValidation(cardId) {
  if (quantityFor(cardId) <= 0) return false;
  return variantsFor(cardId).some(variant => pendingPriceQuoteForVariant(cardId, variant));
}

function sortablePriceForCard(cardId) {
  const variants = variantsFor(cardId);
  const owned = variants.filter(variant => Number(variant.quantity) > 0);
  const source = owned.length ? owned : variants;
  const values = [];
  for (const variant of source) {
    const effective = effectiveVariantPrice(cardId, variant);
    if (hasFiniteNumber(effective?.brl)) {
      values.push(Number(effective.brl));
      continue;
    }
    const pending = storedAutomaticPriceQuote(variant) || automaticPriceQuote(cardId, variant);
    if (hasFiniteNumber(pending?.brl)) values.push(Number(pending.brl));
  }
  return values.length ? Math.max(...values) : Number.NEGATIVE_INFINITY;
}

function cardsForCurrentFilter(filter) {
  const setCards = ui.cardSet === 'all' ? cards : (cardsBySet.get(ui.cardSet) || []);
  if (filter === 'all' || filter === 'missing') return setCards;
  const result = [];
  for (const [cardId, entry] of Object.entries(state.entries || {})) {
    const card = cardMap.get(cardId);
    if (!card || (ui.cardSet !== 'all' && card.setId !== ui.cardSet)) continue;
    const quantity = Number(entry.quantity) || 0;
    if (filter === 'owned' && quantity > 0) result.push(card);
    else if (filter === 'wishlist' && entry.wishlist) result.push(card);
    else if (filter === 'repeated' && temVersaoRepetida(cardId)) result.push(card);
    else if (filter === 'price-review' && cardNeedsPriceValidation(cardId)) result.push(card);
  }
  return result;
}

function cachedStaticSort(source, sort, cacheKey) {
  if (!['number','name','set'].includes(sort)) return source.slice().sort(cardSorter(sort));
  const key = `${cacheKey}|${sort}`;
  if (!staticCardSortCache.has(key)) staticCardSortCache.set(key, source.slice().sort(cardSorter(sort)));
  return staticCardSortCache.get(key);
}

function filteredCardsForUi() {
  const labStart = performance.now();
  const forcedFilter = ui.tab === 'wishlist' ? 'wishlist' : ui.tab === 'repeated' ? 'repeated' : null;
  const filter = forcedFilter || ui.cardFilter;
  const query = normalize(ui.cardQuery);
  const key = `${ui.tab}|${ui.cardSet}|${filter}|${query || '-'}|${ui.cardSort}`;

  let result;
  if (cardResultCache.key === key && cardResultCache.revision === stateRevision && cardResultCache.value) {
    result = cardResultCache.value;
  } else {
    result = cardsForCurrentFilter(filter);
    if (filter === 'missing') result = result.filter(card => quantityFor(card.id) <= 0);
    if (query) result = result.filter(card => (cardSearchIndex.get(card.id) || '').includes(query));
    const cacheKey = `${ui.cardSet}|${filter}|${query || '-'}`;
    result = (!query && (filter === 'all' || filter === 'missing'))
      ? cachedStaticSort(result, ui.cardSort, cacheKey)
      : result.slice().sort(cardSorter(ui.cardSort));
    cardResultCache = { key, revision: stateRevision, value: result };
  }

  const visible = result.slice(0, ui.cardLimit);
  scheduleVisibleImagePreload(result.slice(ui.cardLimit, ui.cardLimit + IMAGE_PRELOAD_AHEAD));
  labRecord('filtro_cartas', performance.now() - labStart, { results: result.length, visible: visible.length, queryLength: normalize(ui.cardQuery).length });
  return { result, visible, forcedFilter, filter };
}

function renderCardSearchResults() {
  const { result, visible } = filteredCardsForUi();
  // Busca em segundo plano o que falta para as etiquetas e o dourado.
  adiantarLotesDaLista(visible);
  return `
    <p class="card-results-count">${result.length.toLocaleString('pt-BR')} cartas encontradas</p>
    <div class="card-list">${visible.length ? visible.map(renderCardRow).join('') : emptyCards()}</div>
    ${visible.length < result.length ? `<button class="load-more" onclick="ui.cardLimit+=60;refreshSearchResults('cardQuery', true)">Mostrar mais ${Math.min(60, result.length-visible.length)}</button>` : ''}`;
}

function renderCards() {
  const forcedFilter = ui.tab === 'wishlist' ? 'wishlist' : ui.tab === 'repeated' ? 'repeated' : null;
  const filter = forcedFilter || ui.cardFilter;
  const title = ui.tab === 'wishlist' ? 'Quero' : ui.tab === 'repeated' ? 'Duplicadas' : 'Minha Coleção';
  const selectedSet = ui.cardSet === 'all' ? null : catalog.sets.find(set => set.id === ui.cardSet);
  return `
    <section class="screen vision-collection-screen">
      <div class="collection-head"><h2>${esc(title)}</h2></div>
      <!-- As fileiras "Tenho/Quero/Minhas" e "Sets/Cartas" saíram: a primeira
           repetia os filtros logo abaixo e a segunda repetia os botões
           Explorar e Coleção da barra de baixo. -->

      ${selectedSet ? `<div class="selected-set-banner"><span>${esc(selectedSet.name)}</span><button onclick="ui.cardSet='all';render()">Limpar</button></div>` : ''}
      <div class="toolbar collection-toolbar">
        <label class="vision-search"><span>${tabIcon('pokedex')}</span><input id="cardSearchInput" value="${esc(ui.cardQuery)}" placeholder="Buscar cartas..."
          oncompositionstart="this.dataset.composing='1'"
          oncompositionend="this.dataset.composing='';searchAndRender('cardQuery', this.value, 'cardSearchInput')"
          oninput="searchAndRender('cardQuery', this.value, 'cardSearchInput')"></label>
        <div class="filter-grid">
          <select class="field" onchange="ui.cardSort=this.value;ui.cardLimit=40;cardResultCache.key='';refreshSearchResults('cardQuery', true)">
            ${option('number','Número',ui.cardSort)}${option('name','Nome',ui.cardSort)}${option('quantity','Quantidade',ui.cardSort)}${option('price-desc','Preço: maior → menor',ui.cardSort)}${option('set','Coleção',ui.cardSort)}
          </select>
          <select class="field" onchange="ui.cardSet=this.value;ui.cardLimit=40;cardResultCache.key='';refreshSearchResults('cardQuery', true)">
            <option value="all">Todas as coleções</option>
            ${catalog.sets.map(set => option(set.id, set.name, ui.cardSet)).join('')}
          </select>
        </div>
        ${!forcedFilter ? `<div class="chips collection-filter-chips">${filterChips()}</div>` : ''}
      </div>
      <div id="cardSearchResults">${renderCardSearchResults()}</div>
    </section>`;
}

function filterChips() {
  // "Tenho" e "Desejo" tinham saído desta barra e ficado em outro menu, o que
  // deixava o conjunto de filtros incompleto. Voltaram para cá, todos juntos.
  const chips = [
    ['all', 'Tudo'],
    ['owned', 'Tenho'],
    ['missing', 'Faltantes'],
    ['wishlist', 'Desejo'],
    ['repeated', 'Duplicadas'],
    ['price-review', 'Preços pendentes'],
  ];
  return chips.map(([value, label]) =>
    `<button class="chip ${ui.cardFilter === value ? 'active' : ''}" onclick="aplicarFiltroCartas('${value}')">${esc(label)}</button>`
  ).join('');
}

function aplicarFiltroCartas(valor) {
  ui.cardFilter = valor;
  ui.cardLimit = 40;
  cardResultCache.key = '';
  renderKeepingScroll();
}

function cardSorter(sort) {
  if (sort === 'name') return (a,b) => a.name.localeCompare(b.name,'pt-BR') || a.setName.localeCompare(b.setName,'pt-BR');
  if (sort === 'quantity') return (a,b) => quantityFor(b.id)-quantityFor(a.id) || a.name.localeCompare(b.name,'pt-BR');
  if (sort === 'price-desc') return (a,b) => sortablePriceForCard(b.id)-sortablePriceForCard(a.id) || a.name.localeCompare(b.name,'pt-BR');
  if (sort === 'set') return (a,b) => a.setName.localeCompare(b.setName,'pt-BR') || numericLocal(a)-numericLocal(b);
  return (a,b) => numericLocal(a)-numericLocal(b) || a.localId.localeCompare(b.localId,undefined,{numeric:true}) || a.name.localeCompare(b.name,'pt-BR');
}

function numericLocal(card) {
  const match = String(card.localId).match(/\d+/);
  return match ? Number(match[0]) : 999999;
}

function scannerFinishLabel(value) {
  return finishPriceLabel(finishKind(value));
}

/* =====================================================================
   Sessão de leitura

   A carta lida NÃO entra na coleção na hora. Ela fica numa lista de espera
   até o usuário conferir tudo e tocar em "Adicionar X cartas". Isso permite
   corrigir um acabamento errado, mudar o idioma de todas de uma vez ou
   remover uma leitura repetida antes de sujar a coleção.

   A lista é gravada no aparelho a cada mudança: se o celular fechar o app no
   meio de cinquenta cartas, nenhuma leitura se perde.
   ===================================================================== */
const SCANNER_SESSAO_KEY = 'pokecard-scanner-sessao-v1';

function leiturasPendentes() {
  if (!Array.isArray(scannerSession.pendentes)) scannerSession.pendentes = [];
  return scannerSession.pendentes;
}

function totalLeituras() {
  return leiturasPendentes().reduce((soma, item) => soma + (Number(item.quantity) || 0), 0);
}

function salvarSessaoLeitura() {
  try {
    const pendentes = leiturasPendentes();
    if (!pendentes.length) localStorage.removeItem(SCANNER_SESSAO_KEY);
    else localStorage.setItem(SCANNER_SESSAO_KEY, JSON.stringify({ pendentes, setId: scannerSession.setId, salvoEm: Date.now() }));
  } catch (_) { /* aparelho sem espaço: a sessão segue só na memória */ }
}

function lerSessaoGuardada() {
  try {
    const bruto = JSON.parse(localStorage.getItem(SCANNER_SESSAO_KEY) || 'null');
    const pendentes = Array.isArray(bruto?.pendentes) ? bruto.pendentes.filter(item => cardMap.has(item?.cardId)) : [];
    return pendentes.length ? { pendentes, setId: bruto.setId || 'all' } : null;
  } catch (_) { return null; }
}

function descartarSessaoGuardada() {
  scannerSession.pendentes = [];
  try { localStorage.removeItem(SCANNER_SESSAO_KEY); } catch (_) {}
}

// Duas leituras iguais viram uma linha só com quantidade 2 — como no balcão
// de loja, onde três cópias da mesma carta são "3x", não três linhas.
function assinaturaLeitura(linha) {
  return [linha.cardId, linha.pricingVariant, linha.finish, linha.language, linha.condition, linha.edition, linha.distribution].join('|');
}

function registrarLeitura(cardId, opcoes = {}) {
  const card = cardMap.get(cardId);
  if (!card) return null;
  const linha = {
    id: `l${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`,
    cardId,
    quantity: 1,
    finish: opcoes.finish || scannerSession.finish || 'normal',
    pricingVariant: opcoes.pricingVariant || scannerSession.pricingVariant || 'normal',
    language: opcoes.language || scannerSession.language || 'pt-br',
    condition: opcoes.condition || scannerSession.condition || 'Near Mint',
    edition: opcoes.edition || scannerSession.edition || 'unlimited',
    distribution: opcoes.distribution || scannerSession.distribution || 'unstamped',
    lidoEm: Date.now(),
  };
  const pendentes = leiturasPendentes();
  const igual = pendentes.find(item => assinaturaLeitura(item) === assinaturaLeitura(linha));
  if (igual) igual.quantity = (Number(igual.quantity) || 0) + 1;
  else pendentes.unshift(linha);
  salvarSessaoLeitura();
  return igual || linha;
}

function removerLeitura(linhaId) {
  scannerSession.pendentes = leiturasPendentes().filter(item => item.id !== linhaId);
  salvarSessaoLeitura();
}

function mudarQuantidadeLeitura(linhaId, delta) {
  const linha = leiturasPendentes().find(item => item.id === linhaId);
  if (!linha) return;
  linha.quantity = Math.max(0, (Number(linha.quantity) || 0) + Number(delta || 0));
  if (linha.quantity === 0) removerLeitura(linhaId);
  else salvarSessaoLeitura();
}

/* O scanner tem um modo só: câmera ao vivo com a lista de leitura.
   O modo "uma por vez" abria o aplicativo de câmera do celular, tirava uma
   foto e voltava — fluxo que deixou de existir quando a confirmação passou a
   acontecer sem sair da câmera. Quem já tinha escolhido esse modo continuava
   com ele salvo no aparelho e caía na câmera antiga, que travava a tela.
   Por isso o modo é forçado aqui, ignorando o que estiver guardado. */
function scannerPreferences() {
  const defaults = { language: 'pt-br', speed: 'normal', fps: 'balanced', setId: ui.cardSet !== 'all' ? ui.cardSet : 'all' };
  let salvas = {};
  try { salvas = JSON.parse(localStorage.getItem(SCANNER_PREFS_KEY) || '{}') || {}; } catch (_) {}
  delete salvas.mode;
  return { ...defaults, ...salvas, mode: 'continuous' };
}

function chooseScannerPreference(group, value, button) {
  document.querySelectorAll(`[data-scanner-group="${group}"]`).forEach(item => item.classList.toggle('active', item === button));
  const field = document.getElementById(`scannerPref-${group}`);
  if (field) field.value = value;
}

/* Tocar no Scanner abre a câmera direto. Não há mais tela de escolha antes:
   o modo é um só, e idioma e coleção podem ser ajustados pelo ⚙ na própria
   câmera, sem parar a leitura. */
function openScannerSetup() {
  startScannerSession('normal', scannerPreferences().setId, scannerPreferences());
}

/* Ajustes durante a leitura: idioma das cartas e coleção alvo. */
function abrirAjustesScanner() {
  pausarCamera();
  const prefs = scannerPreferences();
  const html = `
    <div class="leitura-painel-alca" aria-hidden="true"></div>
    <div class="busca-manual">
      <strong>⚙ Ajustes da leitura</strong>
      <label class="registration-field">
        <span>Idioma das cartas lidas</span>
        <select id="scannerAjusteIdioma" class="field">
          ${PRICE_LANGUAGES.map(value => option(value, PRICE_LANGUAGE_LABELS[value] || value, scannerSession.language || prefs.language)).join('')}
        </select>
      </label>
      <label class="registration-field">
        <span>Coleção (aumenta a precisão)</span>
        <select id="scannerAjusteSet" class="field">
          <option value="all">Detectar entre todas as coleções</option>
          ${catalog.sets.slice().sort((a, b) => compareSetsByTimeline(a, b)).map(set => option(set.id, set.name, scannerSession.setId || prefs.setId)).join('')}
        </select>
      </label>
      <small>Vale para as próximas cartas lidas. O que já está na lista não muda.</small>
      <button class="leitura-sim" onclick="salvarAjustesScanner()">Salvar e continuar lendo</button>
    </div>`;
  const painel = document.getElementById('leituraPainel');
  if (painel) { painel.innerHTML = html; painel.classList.add('aberto'); return; }
  const folha = document.querySelector('.camera-sheet .camera-tela');
  if (folha) abrirPainelNovo(folha, html);
}

function salvarAjustesScanner() {
  const idioma = document.getElementById('scannerAjusteIdioma')?.value || 'pt-br';
  const setId = document.getElementById('scannerAjusteSet')?.value || 'all';
  scannerSession.language = idioma;
  scannerSession.setId = setId;
  try {
    const prefs = { ...scannerPreferences(), language: idioma, setId };
    localStorage.setItem(SCANNER_PREFS_KEY, JSON.stringify(prefs));
  } catch (_) {}
  retomarCamera();
  telaCameraAoVivo();
  avisarNaCamera(`Idioma: ${PRICE_LANGUAGE_LABELS[idioma] || idioma}`);
}

function startScannerSession(finish, setId = 'all', preferences = scannerPreferences()) {
  // Uma sessão interrompida antes (app fechado, ligação, bateria) volta com as
  // leituras intactas — só o que já entrou na coleção é que sai da lista.
  const guardada = lerSessaoGuardada();
  scannerSession = { active: true, pricingVariant: 'normal', finish, language: preferences.language || 'pt-br', condition: 'Near Mint', edition: 'unlimited', distribution: 'unstamped', artVariant: 'standard', region: 'Brasil', gradingCompany: 'Não graduada', grade: '', tags: [], manualVariationOverride: false, setId, mode: preferences.mode || 'continuous', speed: preferences.speed || 'normal', fps: preferences.fps || 'balanced', count: 0, lastIds: [], pendentes: guardada?.pendentes || [] };
  scannerCandidateBuffer = [];
  closeModal();
  if (guardada?.pendentes?.length) {
    notify(`${totalLeituras()} carta(s) lidas antes continuam na lista.`);
  }
  scanNextCard();
}

function scanNextCard() {
  if (!scannerSession.active) return openScannerSetup();

  // Caminho único: a câmera fica aberta dentro do app, atrás desta tela, e vai
  // lendo sozinha. A moldura, a faixa de miniaturas e os botões são desenhados
  // por cima da imagem.
  if (window.Android?.startLiveScanner) {
    scannerSession.live = true;
    window.Android.startLiveScanner(scannerSession.finish);
    telaCameraAoVivo();
    return;
  }

  /* Sem câmera ao vivo não há como continuar. Antes o app caía no aplicativo
     de câmera do celular: tirava uma foto, voltava, e a tela do scanner ficava
     desenhada sem imagem nenhuma atrás — parecia travado. Melhor dizer o que
     está acontecendo do que entregar uma tela morta. */
  scannerSession.active = false;
  scannerSession.live = false;
  document.documentElement.classList.remove('camera-ao-vivo');
  closeModal();
  notify(window.Android
    ? 'Esta versão do aplicativo ainda não tem a câmera do scanner. Instale a versão mais nova.'
    : 'A câmera do scanner só funciona dentro do aplicativo Android.');
}

// O aparelho avisa que a câmera ao vivo foi encerrada pelo botão da tela.
window.receiveLiveScannerClosed = function () {
  scannerSession.live = false;
  stopScannerSession();
};

function stopScannerSession() {
  const pendentes = totalLeituras();
  scannerSession.active = false;
  scannerCandidateBuffer = [];
  // Fecha também a câmera ao vivo, senão ela continuaria ligada por cima do app.
  sairDaCamera();
  closeModal();
  // As leituras não adicionadas ficam guardadas; elas reaparecem na próxima
  // vez que o scanner abrir, em vez de irem para o lixo em silêncio.
  notify(pendentes
    ? `${pendentes} carta(s) lidas continuam esperando — abra o scanner para concluir.`
    : `Sessão finalizada: ${scannerSession.count} carta(s) cadastrada(s).`);
}

function showScannerMessage(message, failed = false) {
  showModal(`
    <button class="modal-close" onclick="stopScannerSession()" aria-label="Fechar">×</button>
    <div class="scanner-status ${failed ? 'failed' : ''}">
      <span class="scanner-status-icon">${failed ? '!' : '✓'}</span>
      <h2>${failed ? 'Não foi possível reconhecer' : 'Scanner pronto'}</h2>
      <p>${esc(message)}</p>
      <small>${scannerSession.count} carta(s) cadastrada(s) nesta sessão · ${esc(scannerFinishLabel(scannerSession.finish))}</small>
    </div>
    <div class="modal-actions">
      <button class="primary-btn" onclick="closeModal();scanNextCard()">Fotografar novamente</button>
      <button class="secondary-btn" onclick="stopScannerSession()">Encerrar sessão</button>
    </div>
  `);
}

/**
 * A leitura veio mesmo de uma carta, ou é a mesa vazia?
 *
 * A câmera manda para o app qualquer texto que consiga ler. Apontada para uma
 * mesa, ela lê veio da madeira, sombra e reflexo como se fossem letras. Sem
 * esta conferência, esse ruído entrava no reconhecimento e às vezes casava com
 * alguma carta — o app cadastrava carta sem carta nenhuma na frente.
 *
 * Toda carta de Pokémon traz marcas fixas: a numeração do rodapé, a linha de
 * direitos autorais com a palavra "Pokémon", o crédito do ilustrador, o HP e
 * as palavras de fraqueza/resistência/recuo. Ruído de mesa não produz nenhuma
 * delas. Exigimos duas evidências para aceitar a leitura — uma só poderia ser
 * coincidência.
 */
/* Conserto de leitura só onde existe número de verdade.
   A câmera troca O por 0, I por 1, S por 5, T por 7 e assim por diante. Desfazer
   essas trocas no texto inteiro parecia esperto e foi o erro: palavras comuns
   viravam números. "TO" virava 70, "IS" virava 15, "IT" virava 17 — e cada um
   desses números falsos entregava pontos a centenas de cartas erradas.
   Agora um pedaço só é tratado como número se ele JÁ tiver ao menos um
   algarismo de verdade. "0O1" vira 001 (é numeração mal lida); "TO" continua
   sendo a palavra "to". */
const LETRAS_CONFUNDIDAS = /[OQDILSZBGT|!]/g;
function consertarAlgarismos(pedaco) {
  return String(pedaco).toUpperCase()
    .replace(/[OQD]/g, '0').replace(/[IL|!]/g, '1')
    .replace(/[SZ]/g, '5').replace(/B/g, '8')
    .replace(/G/g, '6').replace(/T/g, '7');
}
function temAlgarismoDeVerdade(pedaco) {
  return /[0-9]/.test(pedaco);
}

/* Devolve os números e as frações realmente impressos na carta. */
function numerosDaLeitura(ocrText) {
  /* Fora o HP: "330 HP" não é o número da carta, mas casava com a carta 330 de
     qualquer coleção e ainda levava o bônus cheio. */
  const texto = String(ocrText || '').replace(/\b\d{1,3}\s*HP\b/gi, ' ');
  const miolo = '[0-9OQDILSZBGT!]';
  const numbers = new Set();
  const fractions = new Set();

  // Frações: os dois lados precisam ter algarismo. Mata "GO/TO" e "IS/OL".
  const acheiFracoes = texto.toUpperCase().match(new RegExp(`\\b${miolo}{1,4}\\s*[\\/\\\\|]\\s*${miolo}{1,4}\\b`, 'g')) || [];
  for (const bruta of acheiFracoes) {
    const [esquerda, direita] = bruta.split(/[\/\\|]/).map(parte => parte.trim());
    if (!temAlgarismoDeVerdade(esquerda) || !temAlgarismoDeVerdade(direita)) continue;
    const local = Number(consertarAlgarismos(esquerda));
    const total = Number(consertarAlgarismos(direita));
    if (!Number.isFinite(local) || !Number.isFinite(total) || !total) continue;
    fractions.add(`${local}/${total}`);
    numbers.add(String(local));
  }

  // Números soltos: mesma exigência de ter algarismo de verdade.
  const acheiNumeros = texto.toUpperCase().match(new RegExp(`\\b${miolo}{1,4}\\b`, 'g')) || [];
  for (const bruto of acheiNumeros) {
    if (!temAlgarismoDeVerdade(bruto)) continue;
    const valor = Number(consertarAlgarismos(bruto));
    if (Number.isFinite(valor)) numbers.add(String(valor));
  }

  return { numbers, fractions };
}

function pareceUmaCarta(ocrText) {
  const texto = String(ocrText || '');
  const normalizado = normalize(texto);

  /* Sinais que SÓ carta tem, e sinais que qualquer papel escrito tem.
     Antes os dois valiam pontos no mesmo bolo, e duas marcas genéricas
     ("tem várias linhas" + "tem muitas letras") já abriam a porta sozinhas —
     era por isso que a mesa com qualquer coisa escrita virava leitura. Agora o
     genérico só reforça: sem nenhum sinal de carta, nada passa. */
  const especificos = [];
  const genericos = [];
  if (numerosDaLeitura(texto).fractions.size) especificos.push('numeração');
  if (/pok[eé]mon/i.test(normalizado)) especificos.push('marca');
  if (/\billus\b|\bilust/i.test(normalizado)) especificos.push('ilustrador');
  if (/\d{2,3}\s*hp\b|\bhp\s*\d{2,3}/i.test(normalizado)) especificos.push('HP');
  if (/fraqueza|resist[eê]ncia|recuo|weakness|resistance|retreat/i.test(normalizado)) especificos.push('rodapé');
  if (/nintendo|creatures|game\s*freak/i.test(normalizado)) especificos.push('direitos');

  const linhasComPalavras = texto.split(/\r?\n/).filter(linha => (linha.match(/[A-Za-zÀ-ÿ]/g) || []).length >= 4).length;
  if (linhasComPalavras >= 3) genericos.push('linhas');
  if ((normalizado.match(/[a-z]/g) || []).length >= 25) genericos.push('volume');

  const aceita = especificos.length >= 2 || (especificos.length >= 1 && genericos.length >= 2);
  return { aceita, achados: [...especificos, ...genericos], especificos, genericos };
}

function scannerCandidates(ocrText) {
  const canonical = value => normalize(value)
    .replace(/\bhisuian\b/g, 'hisui')
    .replace(/\balolan\b/g, 'alola')
    .replace(/\bgalarian\b/g, 'galar')
    .replace(/\bpaldean\b/g, 'paldea')
    .replace(/\s+/g, ' ')
    .trim();
  const normalized = canonical(ocrText);
  const compact = normalized.replace(/\s+/g, ' ');
  const lines = [...new Set(String(ocrText || '').split(/\r?\n/)
    .flatMap(line => [canonical(line), canonical(line.replace(/\d+\s*\/?\s*\d*/g, ' '))])
    .filter(line => line.length >= 3 && line.length <= 70 && !/ampliad|numero|faixa|canto/.test(line)))];
  /* O número do rodapé é a única coisa que identifica a carta sozinha.
     "Bulbasaur" existe em treze impressões diferentes; "001/165" existe em
     uma só. Por isso a leitura numérica recebe tratamento próprio, com as
     confusões clássicas do reconhecimento de caracteres desfeitas. */
  const { numbers, fractions } = numerosDaLeitura(ocrText);
  /* O Android manda a faixa de baixo da carta ampliada, colada no fim do texto.
     É lá que fica o número da carta. Um número lido nessa faixa vale muito mais
     do que um número solto no meio do texto, que quase sempre é dano de ataque
     ou ano de impressão. */
  const rodape = String(ocrText || '').split(/\[FAIXA INFERIOR AMPLIADA\]/i).slice(1).join('\n');
  const numerosDoRodape = rodape ? numerosDaLeitura(rodape).numbers : new Set();

  // Total impresso na fração: "015/094" diz que a coleção tem 94 cartas.
  // Sozinho já elimina quase todas as coleções.
  const totaisLidos = new Set([...fractions].map(f => f.split('/')[1]));

  const pool = scannerSession.setId && scannerSession.setId !== 'all'
    ? cards.filter(card => card.setId === scannerSession.setId)
    : cards;

  /* Atalho de certeza: numeração E nome concordando.
     A numeração sozinha mandava em tudo, e isso quebrou o leitor. O rodapé é a
     letra menor da carta e a primeira a sair errada na mão trêmula: bastava um
     algarismo trocado para o app devolver, com toda a confiança, uma carta que
     não tinha nada a ver — e a carta certa, cujo nome estava perfeitamente
     legível, nem entrava na disputa.
     Agora o atalho só vale quando os dois sinais independentes concordam. Se
     só a numeração bateu, ela continua valendo muito (+260 lá embaixo), mas
     disputa com o nome em vez de calar o nome. */
  if (fractions.size) {
    const exatas = pool.filter(card => {
      const local = String(Number(String(card.localId || '').match(/\d+/)?.[0] ?? -1));
      const total = String(Number(String(card.number || '').split('/')[1] ?? -1));
      return fractions.has(`${local}/${total}`);
    });
    const nomeConfere = card => {
      const nome = canonical(card.name);
      if (nome && compact.includes(nome)) return true;
      const tokens = scannerMeaningfulTokens(nome);
      if (tokens.length && tokens.every(token => compact.includes(token))) return true;
      return pokemonIdsForCard(card).some(id => {
        const pokemon = canonical(pokemonMap.get(id)?.name || '');
        return pokemon && compact.includes(pokemon);
      });
    };
    const concordam = exatas.filter(nomeConfere);
    if (concordam.length) {
      return concordam.map(card => ({
        card, score: 999, nameSimilarity: 1, pokemonSimilarity: 1, fractionMatch: true, numberMatch: true,
      })).sort((a, b) => a.card.name.localeCompare(b.card.name, 'pt-BR')).slice(0, 6);
    }
  }
  const candidatePool = scannerSession.setId !== 'all' ? pool : pool.filter(card => {
    const localNumber = String(Number(String(card.localId || '').match(/\d+/)?.[0] || -1));
    if (numbers.has(localNumber)) return true;
    const cardTokens = scannerMeaningfulTokens(canonical(card.name));
    if (cardTokens.some(token => compact.includes(token))) return true;
    return (card.pokemonIds || []).some(id => {
      const pokemonName = canonical(pokemonMap.get(Number(id))?.name || '');
      return pokemonName && compact.includes(pokemonName);
    });
  });
  const medidas = candidatePool.map(card => {
    const cardName = canonical(card.name);
    const setName = canonical(card.setName);
    const localNumber = String(Number(String(card.localId || '').match(/\d+/)?.[0] || -1));
    const official = String(Number(card.number?.split('/')?.[1] || -1));
    const fractionMatch = fractions.has(`${localNumber}/${official}`);
    const cardTokens = scannerMeaningfulTokens(cardName);
    const tokenCoverage = cardTokens.length
      ? cardTokens.filter(token => compact.includes(token)).length / cardTokens.length
      : 0;
    const lineSimilarity = scannerBestTextSimilarity(cardName, lines);
    const nameSimilarity = Math.max(tokenCoverage, lineSimilarity, compact.includes(cardName) ? 1 : 0);
    const pokemonNames = pokemonIdsForCard(card).map(id => canonical(pokemonMap.get(id)?.name || '')).filter(Boolean);
    const pokemonSimilarity = pokemonNames.reduce((best, name) => Math.max(best, scannerBestTextSimilarity(name, lines), compact.includes(name) ? 1 : 0), 0);
    const setSimilarity = setName.length >= 3 ? scannerBestTextSimilarity(setName, lines) : 0;
    const numberMatch = numbers.has(localNumber);
    return { card, cardName, localNumber, official, fractionMatch, nameSimilarity, pokemonSimilarity, setSimilarity, numberMatch };
  });

  /* O nome grande da carta é a coisa mais fácil de ler; a numeração do rodapé é
     a mais difícil. Quando os dois discordam, quem provavelmente saiu errado é
     a numeração. Sem esta regra, um único algarismo trocado no rodapé fazia o
     app devolver, com 501 pontos de confiança, um Squirtle no lugar de um
     Bulbasaur cujo nome estava perfeitamente legível na tela. */
  const nomeForteLido = medidas.some(m => m.nameSimilarity >= .85 || m.pokemonSimilarity >= .9);

  return medidas.map(item => {
    const { fractionMatch, nameSimilarity, pokemonSimilarity, setSimilarity, numberMatch, localNumber, official } = item;
    const desmentido = nomeForteLido && nameSimilarity < .5 && pokemonSimilarity < .5;
    let score = 0;
    score += Math.round(nameSimilarity * 175);
    score += Math.round(pokemonSimilarity * 70);
    score += Math.round(setSimilarity * 35);
    /* O número do rodapé pesa mais que o nome: um Pokémon repete em dezenas de
       impressões, o número repete em poucas. Mas só o número lido NA FAIXA DO
       RODAPÉ ganha esse peso. Número solto no meio da carta costuma ser dano de
       ataque ou ano de impressão, e dar 120 pontos a ele fazia o app escolher
       com confiança a impressão errada. */
    if (numerosDoRodape.has(localNumber)) score += desmentido ? 25 : 120;
    else if (numberMatch) score += desmentido ? 10 : 40;
    if (fractionMatch) score += desmentido ? 55 : 260;
    // O total impresso ("/094") sozinho já aponta a coleção.
    if (totaisLidos.has(official)) score += 70;
    if (numbers.has(official)) score += 8;
    if (scannerSession.setId !== 'all') score += 65;
    if (!fractionMatch && nameSimilarity < .54 && pokemonSimilarity < .68) score -= scannerSession.setId === 'all' ? 55 : 20;
    return { ...item, score };
  }).filter(item => item.score >= 55 && (
    item.nameSimilarity >= .46 || item.pokemonSimilarity >= .66 || item.fractionMatch
    /* Ter uma coleção escolhida deixava passar QUALQUER carta dela, mesmo sem
       nenhuma semelhança com o que foi lido: eram +65 de bônus e o filtro
       aceitava só por estar no set. Bastava qualquer ruído para o app escolher
       uma carta. Agora a coleção ajuda, mas ainda é preciso ter lido o número
       ou algo do nome. */
    || (scannerSession.setId !== 'all' && (item.numberMatch || item.nameSimilarity >= .42 || item.pokemonSimilarity >= .5))))
    .sort((a, b) => b.score - a.score
      || Number(b.fractionMatch) - Number(a.fractionMatch)
      || b.nameSimilarity - a.nameSimilarity
      || a.card.name.localeCompare(b.card.name, 'pt-BR'))
    .slice(0, 6);
}

function scannerMeaningfulTokens(value) {
  const ignored = new Set(['de','da','do','das','dos','the','of','ex','gx','v','vmax','vstar','mega']);
  return [...new Set(String(value || '').split(' ').filter(token => token.length >= 3 && !ignored.has(token)))];
}

function scannerBestTextSimilarity(target, lines) {
  if (!target) return 0;
  const targetTokens = scannerMeaningfulTokens(target);
  let best = 0;
  for (const line of lines) {
    if (line === target || line.includes(target) || target.includes(line) && line.length >= 5) best = Math.max(best, .98);
    const lineTokens = scannerMeaningfulTokens(line);
    if (targetTokens.length && lineTokens.length) {
      const common = targetTokens.filter(token => lineTokens.includes(token)).length;
      best = Math.max(best, common / Math.max(targetTokens.length, lineTokens.length));
    }
    if (Math.abs(line.length - target.length) <= Math.max(5, Math.round(target.length * .35))) {
      best = Math.max(best, scannerStringSimilarity(target, line));
    }
  }
  return best;
}

function scannerStringSimilarity(a, b) {
  if (a === b) return 1;
  if (!a || !b) return 0;
  const previous = Array.from({ length: b.length + 1 }, (_, index) => index);
  for (let i = 1; i <= a.length; i++) {
    let diagonal = previous[0];
    previous[0] = i;
    for (let j = 1; j <= b.length; j++) {
      const above = previous[j];
      previous[j] = Math.min(previous[j] + 1, previous[j - 1] + 1, diagonal + (a[i - 1] === b[j - 1] ? 0 : 1));
      diagonal = above;
    }
  }
  return 1 - previous[b.length] / Math.max(a.length, b.length);
}

/* Aviso curto sobre a imagem da câmera, sem tirar a câmera da tela. */
/* ---------- Diagnóstico da leitura ----------
   Quando o leitor erra, ninguém consegue ajudar sem saber o que a câmera de
   fato leu: a imagem some junto com o quadro. Aqui a última leitura recusada
   fica guardada e pode ser vista — e copiada — pelo botão "O que a câmera leu"
   na tela da câmera. */
let ultimaRecusa = null;
function registrarRecusa(texto, motivo, conferencia) {
  ultimaRecusa = {
    texto: String(texto || '').trim(),
    motivo,
    achados: conferencia?.achados || [],
    quando: Date.now(),
  };
  const botao = document.getElementById('cameraDiagnostico');
  if (botao) botao.hidden = false;
}

function abrirDiagnosticoLeitura() {
  if (!ultimaRecusa) return notify('Nenhuma leitura recusada ainda.');
  const { numbers, fractions } = numerosDaLeitura(ultimaRecusa.texto);
  const legivel = ultimaRecusa.texto
    .replace(/\[(?:FAIXA INFERIOR|CANTO INFERIOR|NUMERO)[^\]]*\]/gi, '\n— parte de baixo, ampliada —\n')
    .replace(/\n{3,}/g, '\n\n').trim();
  showModal(`
    <button class="modal-close" onclick="telaCameraAoVivo()">×</button>
    <h2>O que a câmera leu</h2>
    <p class="screen-subtitle">${esc(ultimaRecusa.motivo)}</p>
    <div class="panel">
      <strong>Marcas de carta encontradas</strong>
      <p class="card-meta">${ultimaRecusa.achados.length ? esc(ultimaRecusa.achados.join(', ')) : 'nenhuma'}</p>
      <strong>Numeração entendida</strong>
      <p class="card-meta">${fractions.size ? esc([...fractions].join(', ')) : 'nenhuma fração legível'}${numbers.size ? ` · números soltos: ${esc([...numbers].slice(0, 12).join(', '))}` : ''}</p>
    </div>
    <details class="scanner-ocr-debug" open><summary>Texto reconhecido</summary><pre>${esc(legivel || 'Nada legível.')}</pre></details>
    <div class="modal-actions">
      <button class="secondary-btn" onclick="copiarDiagnosticoLeitura()">Copiar</button>
      <button class="primary-btn" onclick="telaCameraAoVivo()">Voltar à câmera</button>
    </div>
  `);
}
window.abrirDiagnosticoLeitura = abrirDiagnosticoLeitura;

function copiarDiagnosticoLeitura() {
  if (!ultimaRecusa) return;
  const corpo = `${ultimaRecusa.motivo}\nMarcas: ${ultimaRecusa.achados.join(', ') || 'nenhuma'}\n\n${ultimaRecusa.texto}`;
  try {
    navigator.clipboard.writeText(corpo);
    notify('Copiado. Pode colar para eu ver.');
  } catch (_) {
    notify('Não consegui copiar neste aparelho.');
  }
}
window.copiarDiagnosticoLeitura = copiarDiagnosticoLeitura;

function avisarNaCamera(mensagem) {
  const dica = document.getElementById('cameraDica');
  if (!dica) return notify(mensagem);
  dica.textContent = mensagem;
  clearTimeout(avisarNaCamera.timer);
  avisarNaCamera.timer = setTimeout(() => {
    const alvo = document.getElementById('cameraDica');
    if (alvo) alvo.textContent = 'Encaixe a carta dentro da moldura';
  }, 2200);
}

window.receiveScannerText = function receiveScannerText(text, finish) {
  if (!scannerSession.active) return;
  scannerSession.finish = finish || scannerSession.finish;
  scannerLastOcrText = String(text || '').trim();

  /* Primeiro: isto é uma carta? A câmera lê a madeira da mesa como se fossem
     letras, e esse ruído chegava até o reconhecimento. */
  const conferencia = pareceUmaCarta(text);
  if (!conferencia.aceita) {
    registrarRecusa(text, 'Não reconheci nenhuma marca de carta (nome, HP, numeração, rodapé).', conferencia);
    if (scannerSession.live) return avisarNaCamera('Nenhuma carta na moldura');
    return showScannerMessage('Nenhuma carta reconhecida na imagem.', true);
  }

  const candidates = scannerCandidates(String(text || ''));

  if (!candidates.length) {
    registrarRecusa(text, 'Vi que é uma carta, mas não achei ela no catálogo.', conferencia);
    /* Com a câmera ao vivo, não dá para abrir um aviso em cima da tela: ele
       cobriria a imagem e os botões, e o caminho de volta reabria a câmera por
       cima dela mesma. Aqui a leitura falha em silêncio e a câmera continua. */
    if (scannerSession.live) return avisarNaCamera('Não reconheci — aproxime e evite reflexo');
    return showScannerMessage('Aproxime a carta, evite reflexos e mantenha nome e numeração visíveis.', true);
  }

  /* A carta recém-confirmada continua na frente da lente. Sem esta trava, o
     app perguntaria de novo sobre ela um segundo depois de ser adicionada. */
  const primeira = candidates[0].card;
  if (scannerSession.live && primeira.id === scannerSession.ultimaConfirmada
      && Date.now() - (scannerSession.confirmadaEm || 0) < 4000) {
    return avisarNaCamera('Já adicionada — mostre a próxima carta');
  }

  scannerCandidateBuffer = candidates;
  // Cada carta lida começa do zero: a versão normal em 1, o resto zerado e a
  // lista de versões recolhida de novo.
  scannerSession.pricingVariant = 'normal';
  scannerSession.quantidades = null;
  scannerSession.verTodasVersoes = false;
  showScannerPrimaryCandidate();
  loadScannerVariantAvailability(primeira);
};

function languageFromMarketKey(value) { return PRICE_LANGUAGES.includes(value) ? value : ''; }
function editionFromMarketKey(value) { return PRICE_PRINT_VARIATIONS.includes(value) ? value : ''; }
function distributionFromMarketKey(value) { return PRICE_STAMPS.includes(value) ? value : ''; }
function artVariantFromMarketKey() { return 'standard'; }

function refreshCardSpecificVariationFields(card) {
  if (!card || document.getElementById('regVariationCardId')?.value !== String(card.id)) return;
  /* `regLanguage` ficou de fora de propósito: os três idiomas valem para
     qualquer carta e o campo é montado uma vez só. Enquanto ele esteve nesta
     lista, era reconstruído a partir do que a fonte publicava para a carta —
     e sumiam opções, deixando só um ou dois idiomas disponíveis. */
  const fieldMap = { regPricingVariant: 'pricingVariants', regFinish: 'finishes', regEdition: 'editions', regDistribution: 'distributions', regArtVariant: 'artVariants' };
  const savedManualOverride = document.getElementById('regManualVariationOverride')?.value === '1';
  for (const [id, profileField] of Object.entries(fieldMap)) {
    const select = document.getElementById(id);
    if (!select) continue;
    const selected = select.value;
    const existingVariant = Boolean(document.getElementById('regVariantId')?.value);
    const language = document.getElementById('regLanguage')?.value || 'pt-br';
    const selectedPricingInfo = profileField === 'pricingVariants'
      ? pricingVariantDetailsForCard(card, language).find(item => item.value === selected)
      : null;
    const preserveSelected = savedManualOverride || profileField === 'languages' || select.dataset.manualOverride === '1'
      || (profileField === 'pricingVariants' && (existingVariant || selectedPricingInfo?.priced));
    select.innerHTML = optionListForCard(card, profileField, selected, preserveSelected, language);
    if (preserveSelected && [...select.options].some(item => item.value === selected)) select.value = selected;
  }
  reconstruirPills(card);
  atualizarResumoVariante(card);
  const profile = cardVariationProfile(card);
  document.querySelectorAll('.owned-variant-row[data-owned-pricing-variant]').forEach(row => {
    const supported = profile.pricingVariants.includes(exactSourceEnum(row.dataset.ownedPricingVariant));
    row.classList.toggle('needs-variation-review', !supported && !profile.loading && !profile.marketLoading);
    const warning = row.querySelector('.owned-variant-warning');
    if (warning) {
      warning.hidden = supported || profile.loading || profile.marketLoading;
      warning.textContent = supported ? '' : 'variantEnum não encontrado nas fontes desta carta';
    }
  });
  const note = document.getElementById('regVariationAvailabilityNote');
  if (note) note.textContent = profile.loading || profile.marketLoading
    ? 'Verificando as versões específicas desta carta…'
    : `${profile.pricingVariants.length} enum(s) exato(s) identificado(s) · ${profile.source || 'Price Database'}`;
  if (document.getElementById('automaticPriceBox')) {
    refreshAutomaticPriceField(card.id, document.getElementById('regFinish')?.value || 'normal');
  }
}

function refreshCardVariationAvailability(card) {
  // A fonte respondeu quais versões esta carta tem: se o painel de confirmação
  // dela está aberto, os botões provisórios dão lugar aos verdadeiros.
  if (scannerCandidateBuffer[0]?.card?.id === card.id && document.getElementById('leituraPainel')) {
    showScannerPrimaryCandidate();
  }
  refreshCardSpecificVariationFields(card);
}

async function loadCardVariantAvailability(card) {
  if (!card) return;
  const current = scannerVariantAvailability.get(card.id);
  if (current?.loading || current?.loaded) return;
  const local = localCardVariationProfile(card);
  scannerVariantAvailability.set(card.id, { ...local, loading: true, marketLoading: true });
  refreshCardVariationAvailability(card);
  try {
    await syncCentralPrices(false, true).catch(() => false);
    await ensureCentralPriceShard(card.id, false).catch(() => false);

    const localeRequests = [
      [TCGDEX_API_BASE, 'pt-br'],
      [TCGDEX_API_FALLBACK, 'en'],
      [TCGDEX_API_JAPANESE, 'ja'],
    ].map(async ([base, language]) => {
      const detail = await fetchJsonWithTimeout(`${base}/cards/${encodeURIComponent(card.id)}`, 25000);
      if (!detail || String(detail.id || '') !== String(card.id)) throw new Error('identificador divergente');
      return { detail, language };
    });
    const localeResults = (await Promise.allSettled(localeRequests)).filter(item => item.status === 'fulfilled').map(item => item.value);
    const finishes = [];
    const languages = [];
    const editions = ['unlimited'];
    const distributions = ['unstamped'];
    const sourceDetails = [];
    for (const result of localeResults) {
      const variants = result.detail?.variants || {};
      if (variants.normal !== false) finishes.push('normal');
      if (variants.holo === true) finishes.push('holo');
      if (variants.reverse === true) finishes.push('reverse');
      if (variants.firstEdition === true) editions.push('firstEdition');
      if (variants.wPromo === true) distributions.push('stamped');
      languages.push(result.language);
      sourceDetails.push(...sourceVariantEnumsFromTcgDexDetail(result.detail, result.language));
    }
    const centralDetails = centralVariantEntries(card.id);
    const pricingVariantDetails = mergeSourceVariantDetails(centralDetails, sourceDetails);
    const hasAnySource = localeResults.length || centralDetails.length;
    scannerVariantAvailability.set(card.id, {
      ...local,
      loading: false,
      marketLoading: false,
      loaded: true,
      unavailable: !hasAnySource,
      source: centralDetails.length ? 'Price Database + TCGdex' : (localeResults.length ? 'Catálogo TCGdex' : local.source),
      finishes: localeResults.length ? uniqueFinishValues(finishes) : local.finishes,
      languages: uniqueValues(languages, centralDetails.map(item => item.language)),
      editions: localeResults.length ? uniqueValues(editions) : local.editions,
      distributions: localeResults.length ? uniqueValues(distributions) : local.distributions,
      artVariants: ['standard'],
      pricingVariantDetails,
      pricingVariants: pricingVariantDetails.map(item => item.value),
    });
  } catch (_) {
    const centralDetails = centralVariantEntries(card.id);
    scannerVariantAvailability.set(card.id, {
      ...local,
      loading: false,
      marketLoading: false,
      loaded: true,
      unavailable: !centralDetails.length,
      pricingVariantDetails: mergeSourceVariantDetails(local.pricingVariantDetails, centralDetails),
    });
  }
  refreshCardVariationAvailability(card);
}

function loadScannerVariantAvailability(card) {
  return loadCardVariantAvailability(card);
}

function scannerFinishOptionsHtml(card) {
  const availability = cardVariationProfile(card);
  let available = CARD_FINISH_DEFINITIONS.filter(([, value]) => availability.finishes.some(item => finishKind(item) === finishKind(value)));
  if (!available.length) available = [CARD_FINISH_DEFINITIONS[0]];
  if (!available.some(([, value]) => finishKind(value) === finishKind(scannerSession.finish))) scannerSession.finish = available[0][1];
  const buttons = available.map(([, value, label, description]) => scannerFinishOption(value, label, description)).join('');
  const status = availability.loading || availability.marketLoading ? '<small class="scanner-variation-loading">Consultando as versões específicas desta carta…</small>'
    : availability.unavailable ? '<small class="scanner-variation-loading">Fonte remota indisponível; exibindo somente o que já está confirmado no catálogo local.</small>'
      : `<small class="scanner-variation-loading">${available.length} acabamento(s) confirmado(s) para esta carta.</small>`;
  return `${buttons}${status}`;
}

function scannerContextualIdentityHtml(card) {
  const availability = cardVariationProfile(card);
  const fields = [];
  const exactVariants = pricingVariantDetailsForCard(card, scannerSession.language);
  if (exactVariants.length && !exactVariants.some(item => item.value === scannerSession.pricingVariant)) {
    scannerSession.pricingVariant = exactVariants[0].value;
  }
  if (exactVariants.length) {
    fields.push(`<label>Versão da carta<select class="field" onchange="selectScannerPricingVariant(this.value)">${exactVariants.map(item => option(item.value,friendlyVariantLabel(item.value),scannerSession.pricingVariant)).join('')}</select><small>${exactVariants.filter(item => item.priced).length} de ${exactVariants.length} com preço disponível.</small></label>`);
  }
  if (availability.editions.length > 1 || availability.editions[0] !== 'unlimited') fields.push(`<label>Edição<select class="field" onchange="scannerSession.edition=this.value;scannerSession.manualVariationOverride=false">${uniqueValues(availability.editions, [scannerSession.edition]).map(value => option(value,value,scannerSession.edition)).join('')}</select></label>`);
  if (availability.distributions.length > 1 || availability.distributions[0] !== 'unstamped') fields.push(`<label>Distribuição<select class="field" onchange="scannerSession.distribution=this.value;scannerSession.manualVariationOverride=false">${uniqueValues(availability.distributions, [scannerSession.distribution]).map(value => option(value,value,scannerSession.distribution)).join('')}</select></label>`);
  return fields.join('');
}

function selectScannerPricingVariant(value) {
  scannerSession.pricingVariant = exactSourceEnum(value) || 'normal';
  scannerSession.manualVariationOverride = false;
  showScannerPrimaryCandidate();
}

function selectScannerLanguage(value) {
  scannerSession.language = exactSourceEnum(value) || 'pt-br';
  const card = scannerCandidateBuffer[0]?.card;
  const exactVariants = card ? pricingVariantDetailsForCard(card, scannerSession.language) : [];
  if (exactVariants.length) scannerSession.pricingVariant = exactVariants[0].value;
  showScannerPrimaryCandidate();
}

/* Acabamentos padrão do jogo, usados só enquanto a lista real não chegou.
   São os mesmos códigos que o banco de preços publica, então a carta salva
   com um deles continua achando preço. */
const VARIANTES_PADRAO = ['normal', 'holofoil', 'reverse-holofoil'];

/**
 * Versões oferecidas na hora de escanear.
 *
 * O catálogo que vem dentro do aplicativo não guarda quais versões cada carta
 * tem — essa informação chega do banco de preços, pela internet, e pode
 * demorar. Sem uma reserva, o painel apareceria sem nenhum botão justamente no
 * momento em que o usuário precisa escolher. Então: se já sabemos as versões
 * reais desta carta, mostramos só elas; se ainda não, oferecemos os três
 * acabamentos padrão e trocamos assim que a resposta chegar.
 */
function variantesParaEscolher(card, idioma, atual) {
  const conhecidas = pricingVariantDetailsForCard(card, idioma).map(item => item.value);
  const visiveis = variantesVisiveis(card?.id || '', conhecidas, atual, idioma);
  if (visiveis.length) return visiveis;
  return uniqueValues(VARIANTES_PADRAO, atual ? [atual] : []);
}

/* ---------- Tela da câmera ----------
   A imagem da câmera é desenhada pelo Android ATRÁS desta tela. Tudo aqui é
   HTML por cima: a moldura é só uma borda com o meio vazado, e a faixa de
   baixo mostra as artes já lidas. */
function telaCameraAoVivo() {
  /* Toda vez que esta tela é desenhada ela reafirma o estado da câmera.
     `startLiveScanner` é seguro de chamar repetido: se a câmera já estiver
     aberta ele só devolve a transparência do fundo. Sem isto, bastava um
     tropeço no meio da sessão para a tela do scanner continuar desenhada com
     o aplicativo aparecendo atrás, no lugar da imagem da câmera. */
  document.documentElement.classList.add('camera-ao-vivo');
  if (scannerSession.live) {
    try { window.Android?.startLiveScanner?.(scannerSession.finish); } catch (_) {}
  }
  const pendentes = leiturasPendentes();
  const total = totalLeituras();
  const tiras = pendentes.slice(0, 12).map(linha => {
    const card = cardMap.get(linha.cardId);
    const arte = card?.imageUrl ? upgradeCardImageUrl(card.imageUrl) : '';
    return `<div class="leitura-tira">
      ${arte ? `<img src="${esc(arte)}" alt="${esc(card.name)}" loading="lazy">` : `<span class="leitura-tira-vazia">TCG</span>`}
      ${linha.quantity > 1 ? `<b class="leitura-tira-qtd">${linha.quantity}x</b>` : ''}
      <button class="leitura-tira-x" onclick="removerLeituraDaTira('${esc(linha.id)}')" aria-label="Remover ${esc(card?.name || 'carta')}">×</button>
    </div>`;
  }).join('');

  showModal(`
    <div class="camera-tela">
      <div class="camera-topo">
        <button class="camera-icone" onclick="encerrarLeitura()" aria-label="Voltar">←</button>
        <strong>Escanear</strong>
        <span class="camera-topo-acoes">
          <button class="camera-icone" onclick="abrirAjustesScanner()" aria-label="Ajustes da leitura">⚙</button>
          <button class="camera-icone" onclick="comoEscanear()" aria-label="Como escanear">?</button>
        </span>
      </div>

      <div class="camera-moldura" aria-hidden="true"></div>
      <p class="camera-dica" id="cameraDica">Encaixe a carta dentro da moldura</p>

      <div class="camera-rodape">
        ${pendentes.length ? `<div class="leitura-tiras">${tiras}</div>` : '<p class="camera-vazio">Nenhuma carta lida ainda.</p>'}
        <div class="camera-atalhos">
          <button class="camera-atalho" onclick="abrirBuscaManual()">⌨ Digitar carta</button>
          <button class="camera-atalho" id="cameraDiagnostico" ${ultimaRecusa ? '' : 'hidden'} onclick="abrirDiagnosticoLeitura()">👁 O que a câmera leu</button>
        </div>
        <div class="camera-acoes">
          <button class="camera-cancelar" onclick="encerrarLeitura()">Cancelar</button>
          <button class="camera-revisar" ${total ? '' : 'disabled'} onclick="abrirRevisaoSessao()">✓ Revisar (${total})</button>
        </div>
      </div>
    </div>
  `, 'camera-sheet');
}

/* ---------- Painel flutuante "é esta carta?" ----------
   Sobe de baixo até o meio da tela, sem fechar a câmera. Traz a miniatura em
   tamanho de conferência (2 × 3 cm) e só as versões que a carta realmente
   tem, nos mesmos botões coloridos do cadastro. */
function showScannerPrimaryCandidate() {
  const candidate = scannerCandidateBuffer[0];
  if (!candidate) return telaCameraAoVivo();
  const { card, score } = candidate;
  pausarCamera();

  const idioma = scannerSession.language || 'pt-br';
  const visiveis = variantesParaEscolher(card, idioma, scannerSession.pricingVariant);
  if (visiveis.length && !visiveis.includes(scannerSession.pricingVariant)) {
    scannerSession.pricingVariant = visiveis[0];
  }
  // A lista definitiva vem do banco de preços; enquanto ela não chega, os
  // botões mostram os acabamentos padrão. Quando chegar, o painel se redesenha.
  loadScannerVariantAvailability(card);

  /* Uma linha por versão da carta, empilhadas — a mesma ideia da tela de
     cadastro. Cada linha tem o preço DAQUELA versão (holo não vale o mesmo que
     normal) e a sua própria quantidade. Os botões − e + ficam na linha
     selecionada; nas outras aparece só um + para trazer o foco para ela.
     Assim uma leitura só resolve "tenho 2 normais e 1 reverse". */
  const quantidades = quantidadesDaLeitura();
  /* A comum vem sempre em primeiro: é o padrão e a mais frequente no bulk.
     Depois holo, reverse e, por último, as versões especiais. Ordenar só por
     "tem holo no nome" deixava a comum em segundo, porque nomes como
     "illustration-rare" vinham antes de "normal" no alfabeto. */
  const ordenadas = visiveis.slice().sort((a, b) => {
    /* Comum, holo e reverse são as três de sempre — e são justamente as três
       que ficam à vista antes do "mostrar mais". As especiais (pokébola,
       masterball, ilustração rara) vão para o fim. Testar só por "holo no
       nome" jogava masterball-holofoil na frente da Reverse Holo. */
    const peso = value => value === 'normal' ? 0
      : /^holo(foil)?$/i.test(value) ? 1
        : /reverse/i.test(value) ? 2 : 3;
    return peso(a) - peso(b) || a.localeCompare(b, 'pt-BR');
  });
  /* Da quarta versão em diante fica escondido atrás de um botão. Três linhas
     dão conta de quase toda carta, e é o que garante o cartão inteiro na tela
     sem rolagem por dentro, mesmo em aparelho de tela curta. Versão que já tem
     quantidade escolhida nunca some — sumir com o que a pessoa marcou seria
     esconder trabalho feito. */
  const LIMITE_VERSOES = 3;
  const marcadas = new Set(ordenadas.filter(value => Number(quantidades[value]) > 0));
  const mostrarTodas = Boolean(scannerSession.verTodasVersoes);
  const naLista = mostrarTodas
    ? ordenadas
    : ordenadas.filter((value, indice) => indice < LIMITE_VERSOES || marcadas.has(value));
  const escondidas = ordenadas.length - naLista.length;

  const linhasVariante = naLista.map(value => {
    const estilo = variantEstilo(value);
    const escolhida = value === scannerSession.pricingVariant;
    const quantas = Number(quantidades[value]) || 0;
    const valor = precoDaVariante(card, value, idioma);
    return `<div class="leitura-versao ${estilo.classe}${escolhida ? ' escolhida' : ''}${quantas ? ' tem' : ''}">
      <button type="button" class="leitura-versao-nome" onclick="escolherVarianteLeitura('${esc(value)}')">
        <span class="variante-icone" aria-hidden="true">${estilo.icone}</span>
        <span class="leitura-versao-rotulo">${esc(friendlyVariantLabel(value))}</span>
        <span class="leitura-versao-preco">${esc(valor)}</span>
      </button>
      ${escolhida
        ? `<div class="leitura-qtd">
             <button type="button" onclick="mudarQuantidadeDaLeitura(-1)" aria-label="Menos uma ${esc(friendlyVariantLabel(value))}">−</button>
             <b>${quantas}</b>
             <button type="button" onclick="mudarQuantidadeDaLeitura(1)" aria-label="Mais uma ${esc(friendlyVariantLabel(value))}">+</button>
           </div>`
        : `<button type="button" class="leitura-versao-mais" onclick="somarNaVariante('${esc(value)}')"
             aria-label="Adicionar uma ${esc(friendlyVariantLabel(value))}">${quantas ? `<b>${quantas}</b>` : '+'}</button>`}
    </div>`;
  }).join('');

  const arte = card.imageUrl ? upgradeCardImageUrl(card.imageUrl) : '';
  const painel = document.getElementById('leituraPainel');
  const novoNaDex = pokemonInedito(card);
  const jaTem = quantityFor(card.id);
  const totalEscolhido = Object.values(quantidades).reduce((soma, n) => soma + (Number(n) || 0), 0);
  const outras = scannerCandidateBuffer.length - 1;

  const html = `
    <div class="leitura-painel-alca" aria-hidden="true"></div>
    ${novoNaDex ? `<button type="button" class="leitura-estrela" onclick="explicarEstrela()"
      title="Pokémon que ainda falta na sua Pokédex" aria-label="Pokémon novo na Pokédex">★</button>` : ''}

    <div class="leitura-selo ${candidate.fractionMatch ? 'ok' : 'duvida'}">
      ${candidate.fractionMatch ? '✓ CARTA IDENTIFICADA' : '⚠ CONFIRA A COLEÇÃO'}
      <em>${Math.min(99, score)}%</em>
    </div>

    <div class="leitura-painel-corpo">
      <button type="button" class="leitura-arte" onclick="abrirZoomLeitura('${esc(card.id)}')" aria-label="Ampliar a carta">
        ${arte ? `<img src="${esc(arte)}" alt="Arte de ${esc(card.name)}">` : '<span class="card-placeholder">TCG</span>'}
        <span class="leitura-lupa" aria-hidden="true">⌕</span>
      </button>
      <div class="leitura-info">
        <strong>${esc(card.name)}</strong>
        <span>${esc(card.setName)} · ${esc(String(card.number || '').includes('/')
          ? card.number
          : formatCardNumber(card.localId || card.number, card.setTotal))}</span>
        ${candidate.fractionMatch ? '' : `<small class="leitura-aviso">Não li a numeração do rodapé — confira a coleção antes de adicionar.</small>`}
        ${jaTem ? `<button type="button" class="leitura-jatem" onclick="verNaColecao('${esc(card.id)}')">
          <span aria-hidden="true">▤</span> Você já tem ${jaTem} <span aria-hidden="true">›</span></button>` : ''}
      </div>
    </div>

    <div class="leitura-copia">
      <div class="leitura-copia-topo">
        <span class="leitura-copia-titulo">SUA CÓPIA</span>
        <select class="leitura-campo" onchange="escolherIdiomaLeitura(this.value)" aria-label="Idioma da carta">
          ${PRICE_LANGUAGES.map(value => option(value, IDIOMA_CURTO[value] || value, idioma)).join('')}
        </select>
        <select class="leitura-campo" onchange="escolherCondicaoLeitura(this.value)" aria-label="Estado da carta">
          ${['Mint', 'Near Mint', 'Excelente', 'Bom', 'Regular', 'Danificada']
            .map(value => option(value, CONDICAO_CURTA[value] || value, scannerSession.condition)).join('')}
        </select>
      </div>
      <div class="leitura-versoes">${linhasVariante}</div>
      ${escondidas > 0 || mostrarTodas && ordenadas.length > LIMITE_VERSOES
        ? `<button type="button" class="leitura-mais-versoes" onclick="alternarVersoesEscondidas()">
             ${escondidas > 0
               ? `▾ Mostrar mais ${escondidas} ${escondidas > 1 ? 'versões' : 'versão'}`
               : '▴ Mostrar menos'}
           </button>`
        : ''}
      <button type="button" class="leitura-outra" onclick="recusarLeitura()">
        ⇄ Não é essa carta?${outras > 0 ? ` <em>(${outras} parecida${outras > 1 ? 's' : ''})</em>` : ''}
      </button>
    </div>

    <div class="leitura-painel-acoes">
      <button class="leitura-nao" onclick="encerrarLeituraAtual()">✕ Recusar</button>
      <button class="leitura-sim" ${totalEscolhido ? '' : 'disabled'} onclick="confirmScannedCard('${esc(card.id)}')">
        ✓ Adicionar${totalEscolhido > 1 ? ` ${totalEscolhido}` : ''}
      </button>
    </div>`;

  if (painel) {
    painel.innerHTML = html;
    painel.classList.add('aberto');
    return;
  }
  let folha = document.querySelector('.camera-sheet .camera-tela');
  if (!folha) {
    // A tela da câmera não estava desenhada: desenha e pega de novo. Sem
    // chamada recursiva — se ainda assim não existir, não há onde encaixar.
    telaCameraAoVivo();
    folha = document.querySelector('.camera-sheet .camera-tela');
  }
  if (folha) abrirPainelNovo(folha, html);
}

/* Cria o painel já fechado, obriga o navegador a desenhá-lo nessa posição e
   só então abre — é isso que faz a animação acontecer.

   Ler `offsetHeight` é o que força esse desenho. Usar o quadro de animação
   seria o caminho natural, mas ele não dispara com o aplicativo em segundo
   plano: o painel nasceria escondido embaixo da tela e nunca subiria. */
function abrirPainelNovo(folha, html) {
  const novo = document.createElement('div');
  novo.id = 'leituraPainel';
  novo.className = 'leitura-painel';
  novo.innerHTML = html;
  folha.appendChild(novo);
  void novo.offsetHeight;
  novo.classList.add('aberto');
  return novo;
}

function fecharPainelLeitura() {
  const painel = document.getElementById('leituraPainel');
  if (painel) painel.classList.remove('aberto');
}

function pausarCamera() {
  try { window.Android?.pauseLiveScanner?.(); } catch (_) {}
}

/* Encerra a câmera e devolve a tela normal do aplicativo.
   O Android também desliga a classe ao fechar a câmera, mas o app não pode
   depender disso: se a ponte não responder — versão antiga, câmera que nunca
   chegou a abrir —, `#app` continuaria escondido e a tela ficaria vazia. */
function sairDaCamera() {
  try { if (scannerSession.live) window.Android?.stopLiveScanner?.(); } catch (_) {}
  scannerSession.live = false;
  document.documentElement.classList.remove('camera-ao-vivo');
}

function retomarCamera() {
  fecharPainelLeitura();
  try { window.Android?.resumeLiveScanner?.(); } catch (_) {}
}

/* ---------- Peças do painel de confirmação ---------- */

const IDIOMA_CURTO = { 'pt-br': '🇧🇷 PT', en: '🇺🇸 EN', ja: '🇯🇵 JA' };
const CONDICAO_CURTA = {
  'Mint': 'M — perfeita',
  'Near Mint': 'NM — quase nova',
  'Excelente': 'EX — excelente',
  'Bom': 'GD — boa',
  'Regular': 'LP — usada',
  'Danificada': 'DMG — danificada',
};

/* A estrela dourada.
   Vale quando NENHUM Pokémon da carta tem carta na coleção ainda. Serve para
   garimpar bulk: o monte passa na frente da câmera e só as que fecham buraco
   na Pokédex chamam atenção. Carta sem Pokémon (energia, item) nunca acende. */
function pokemonInedito(card) {
  const ids = pokemonIdsForCard(card);
  if (!ids.length) return false;
  const stats = buildPokemonStats();
  return ids.every(id => !(stats.get(id)?.copies > 0));
}

function explicarEstrela() {
  const nomes = pokemonIdsForCard(scannerCandidateBuffer[0]?.card)
    .map(id => pokemonMap.get(id)?.name).filter(Boolean).join(' e ');
  notify(`★ ${nomes || 'Este Pokémon'} ainda não tem nenhuma carta na sua Pokédex.`);
}

/* Preço da versão exata, não da carta em geral.
   Um holo e um normal da mesma carta são preços muito diferentes; mostrar um só
   número para a carta inteira dava a impressão de que o app tinha errado o
   valor. Cada linha traz o preço da sua própria versão. */
function precoDaVariante(card, versao, idioma) {
  /* O preço mora num lote do banco que só é baixado sob demanda. Sem pedir o
     lote aqui, o painel mostraria "—" para sempre: nada mais na tela da câmera
     dispara esse download. */
  ensureCentralPriceShard(card.id).then(chegou => {
    if (chegou && document.getElementById('leituraPainel') && scannerCandidateBuffer[0]?.card?.id === card.id) {
      showScannerPrimaryCandidate();
    }
  }).catch(() => {});

  const cotacao = automaticPriceQuote(card.id, {
    pricingVariant: versao, finish: versao, language: idioma,
    condition: scannerSession.condition || 'Near Mint',
    edition: 'unlimited', distribution: 'unstamped', artVariant: 'standard',
    region: idioma === 'pt-br' ? 'Brasil' : 'Internacional',
  });
  const valor = Number(cotacao?.brl ?? cotacao?.basePriceBrl);
  return Number.isFinite(valor) && valor > 0 ? money(valor) : '—';
}

/* Quantidade por versão. A carta normal já começa com 1: é o caso de longe
   mais comum, e obrigar um toque para cada carta de um bulk seria pior. */
function quantidadesDaLeitura() {
  if (!scannerSession.quantidades) {
    scannerSession.quantidades = { [scannerSession.pricingVariant || 'normal']: 1 };
  }
  return scannerSession.quantidades;
}

function mudarQuantidadeDaLeitura(delta) {
  const versao = scannerSession.pricingVariant || 'normal';
  const quantidades = quantidadesDaLeitura();
  const atual = Number(quantidades[versao]) || 0;
  quantidades[versao] = Math.min(99, Math.max(0, atual + Number(delta || 0)));
  showScannerPrimaryCandidate();
}

/* Tocar no + de uma versão que não está selecionada faz as duas coisas de uma
   vez: seleciona aquela versão e soma uma. Sem isso seriam dois toques para o
   que a pessoa claramente quis num só. */
function alternarVersoesEscondidas() {
  scannerSession.verTodasVersoes = !scannerSession.verTodasVersoes;
  showScannerPrimaryCandidate();
}

function somarNaVariante(versao) {
  scannerSession.pricingVariant = versao;
  scannerSession.finish = /reverse/i.test(versao) ? 'reverse' : /holo/i.test(versao) ? 'holo' : 'normal';
  const quantidades = quantidadesDaLeitura();
  quantidades[versao] = Math.min(99, (Number(quantidades[versao]) || 0) + 1);
  showScannerPrimaryCandidate();
}

function escolherIdiomaLeitura(valor) {
  scannerSession.language = valor;
  showScannerPrimaryCandidate();   // o preço e as versões mudam com o idioma
}

function escolherCondicaoLeitura(valor) {
  scannerSession.condition = valor;
  showScannerPrimaryCandidate();   // a condição muda o preço
}

function verNaColecao(cardId) {
  sairDaCamera();
  openCard(cardId);
}

/* "Recusar" joga a carta fora e volta a ler; "Não é essa carta?" oferece as
   outras parecidas. Eram o mesmo botão antes, e recusar mostrava um palpite
   pior em vez de simplesmente seguir em frente. */
function encerrarLeituraAtual() {
  scannerCandidateBuffer = [];
  retomarCamera();
  telaCameraAoVivo();
}

function abrirZoomLeitura(cardId) {
  const card = cardMap.get(cardId);
  if (!card?.imageUrl) return notify('Esta carta não tem imagem grande.');
  const painel = document.getElementById('leituraPainel');
  if (painel) painel.classList.add('recuado');
  const caixa = document.createElement('div');
  caixa.className = 'leitura-zoom';
  caixa.innerHTML = `<img src="${esc(upgradeCardImageUrl(card.imageUrl))}" alt="Arte de ${esc(card.name)}">
    <span>${esc(card.name)} · ${esc(card.setName)}</span>`;
  caixa.onclick = () => { caixa.remove(); painel?.classList.remove('recuado'); };
  document.querySelector('.camera-sheet .camera-tela')?.appendChild(caixa);
}

function escolherVarianteLeitura(valor) {
  scannerSession.pricingVariant = valor;
  scannerSession.finish = /reverse/i.test(valor) ? 'reverse' : /holo/i.test(valor) ? 'holo' : 'normal';
  // Escolher uma versão que ainda está zerada já deixa ela em 1: quem tocou na
  // linha quis essa carta, não quis apenas mover o foco.
  const quantidades = quantidadesDaLeitura();
  if (!Number(quantidades[valor])) quantidades[valor] = 1;
  showScannerPrimaryCandidate();
}

function recusarLeitura() {
  const restantes = scannerCandidateBuffer.slice(1);
  if (restantes.length) {
    scannerCandidateBuffer = restantes;
    return showScannerPrimaryCandidate();
  }
  notify('Nenhuma outra correspondência. Aproxime a carta e tente de novo.');
  retomarCamera();
}

function removerLeituraDaTira(linhaId) {
  removerLeitura(linhaId);
  telaCameraAoVivo();
}

/* ---------- Digitar a carta na mão ----------
   Para quando o reconhecimento não pega: carta muito gasta, reflexo forte,
   ou arte sem texto legível. Busca pelo número do rodapé ou pelo nome. */
function abrirBuscaManual() {
  pausarCamera();
  const painel = document.getElementById('leituraPainel');
  const html = `
    <div class="leitura-painel-alca" aria-hidden="true"></div>
    <div class="busca-manual">
      <strong>⌨ Achar a carta na mão</strong>
      <small>Digite a numeração do rodapé (ex.: 035/073) ou o nome</small>
      <input id="buscaManualCampo" class="field" autocomplete="off"
        placeholder="Nome, número ou 069/086" oninput="buscarCartaManual(this.value)">
      <div id="buscaManualLista" class="busca-manual-lista">
        <p class="busca-manual-dica">Digite ao menos 2 caracteres para buscar.</p>
      </div>
      <button class="camera-cancelar" onclick="retomarCamera()">Fechar</button>
    </div>`;
  if (painel) {
    painel.innerHTML = html;
    painel.classList.add('aberto');
  } else {
    const folha = document.querySelector('.camera-sheet .camera-tela');
    if (!folha) return;
    abrirPainelNovo(folha, html);
  }
  setTimeout(() => document.getElementById('buscaManualCampo')?.focus(), 120);
}

function buscarCartaManual(termo) {
  const lista = document.getElementById('buscaManualLista');
  if (!lista) return;
  const texto = String(termo || '').trim();
  if (texto.length < 2) {
    lista.innerHTML = '<p class="busca-manual-dica">Digite ao menos 2 caracteres para buscar.</p>';
    return;
  }

  const alvo = normalize(texto);
  // "35/73" e "035/073" são a mesma carta: comparamos sem os zeros à esquerda.
  const fracao = texto.match(/(\d{1,4})\s*\/\s*(\d{1,4})/);
  const numeroSolto = /^\d{1,4}$/.test(texto) ? String(Number(texto)) : '';
  const pool = scannerSession.setId && scannerSession.setId !== 'all'
    ? cards.filter(card => card.setId === scannerSession.setId)
    : cards;

  const achados = pool.filter(card => {
    const local = String(Number(String(card.localId || '').match(/\d+/)?.[0] ?? -1));
    if (fracao) {
      const total = String(Number(card.setTotal || card.number?.split('/')?.[1] || -1));
      return local === String(Number(fracao[1])) && total === String(Number(fracao[2]));
    }
    if (numeroSolto) return local === numeroSolto;
    return normalize(card.name).includes(alvo);
  }).slice(0, 24);

  if (!achados.length) {
    lista.innerHTML = '<p class="busca-manual-dica">Nenhuma carta encontrada com esse termo.</p>';
    return;
  }
  lista.innerHTML = achados.map(card => `<button class="busca-manual-item" onclick="escolherCartaManual('${esc(card.id)}')">
    ${card.imageUrl ? `<img src="${esc(upgradeCardImageUrl(card.imageUrl))}" alt="" loading="lazy">` : '<span class="card-placeholder">TCG</span>'}
    <span><strong>${esc(card.name)}</strong><small>${esc(formatCardNumber(card.localId || card.number, card.setTotal))} · ${esc(card.setName)}</small></span>
  </button>`).join('');
}

function escolherCartaManual(cardId) {
  // Entra pelo mesmo caminho de uma leitura da câmera: o painel de confirmação
  // aparece com as versões daquela carta, e nada é gravado sem confirmar.
  scannerCandidateBuffer = [{ card: cardMap.get(cardId), score: 100 }];
  showScannerPrimaryCandidate();
}

function comoEscanear() {
  notify('Encaixe a carta na moldura, com o nome e o número do rodapé visíveis. Evite reflexo e sombra.');
}

/* Sair da câmera sem perder o que já foi lido. */
function encerrarLeitura() {
  const total = totalLeituras();
  if (total) return abrirRevisaoSessao();
  sairDaCamera();
  scannerSession.active = false;
  closeModal();
}

function scannerFinishOption(value, label, description) {
  const active = finishKind(scannerSession.finish) === finishKind(value);
  return `<button type="button" class="${active ? 'active' : ''}" onclick="selectScannerFinish('${esc(value)}')">
    <strong>${esc(label)}</strong><span>${esc(description)}</span>
  </button>`;
}

function selectScannerFinish(finish) {
  scannerSession.finish = finish;
  scannerSession.manualVariationOverride = false;
  showScannerPrimaryCandidate();
}

function selectScannerCandidate(cardId) {
  const index = scannerCandidateBuffer.findIndex(item => item.card.id === cardId);
  if (index > 0) scannerCandidateBuffer.unshift(scannerCandidateBuffer.splice(index, 1)[0]);
  showScannerPrimaryCandidate();
  loadScannerVariantAvailability(scannerCandidateBuffer[0]?.card);
}

function rejectScannedCandidate() {
  showScannerCandidateList(scannerCandidateBuffer.slice(1).length ? scannerCandidateBuffer.slice(1) : scannerCandidateBuffer);
}

function showScannerCandidateList(candidates) {
  showModal(`
    <button class="modal-close" onclick="stopScannerSession()" aria-label="Fechar">×</button>
    <h2>Escolha a carta correta</h2>
    <p class="screen-subtitle">A primeira arte foi recusada. Selecione uma das possíveis correspondências.</p>
    <div class="scanner-candidate-list">
      ${candidates.map(({ card, score }) => `<button onclick="selectScannerCandidate('${esc(card.id)}')">
        ${card.imageUrl ? `<img src="${esc(upgradeCardImageUrl(card.imageUrl))}" alt="">` : '<span class="card-placeholder">TCG</span>'}
        <span><strong>${esc(card.name)}</strong><small>${esc(card.number)} · ${esc(card.setName)}</small><em>${Math.min(99, score)}% de correspondência</em></span>
      </button>`).join('')}
    </div>
    ${scannerOcrDiagnosticHtml()}
    <div class="modal-actions"><button class="secondary-btn" onclick="closeModal();scanNextCard()">Tirar outra foto</button><button class="danger-btn" onclick="stopScannerSession()">Encerrar sessão</button></div>
  `);
}

function scannerOcrDiagnosticHtml() {
  const readable = scannerLastOcrText
    .replace(/\[(?:FAIXA INFERIOR|CANTO INFERIOR|NUMERO)[^\]]*\]/g, '\n— leitura ampliada —\n')
    .replace(/\n{3,}/g, '\n\n')
    .trim()
    .slice(0, 1400);
  return `<details class="scanner-ocr-debug"><summary>Ver texto reconhecido</summary><pre>${esc(readable || 'Nenhum texto legível.')}</pre></details>`;
}

window.receiveScannerError = function receiveScannerError(message) {
  if (!scannerSession.active) return;
  // Erro com a câmera aberta vira aviso na própria tela; abrir um painel por
  // cima esconderia a imagem e a lista de leituras.
  if (scannerSession.live) return avisarNaCamera(message || 'Não foi possível ler a carta.');
  showScannerMessage(message || 'Não foi possível ler a carta.', true);
};

window.receiveScannerCancelled = function receiveScannerCancelled() {
  if (!scannerSession.active) return;
  if (scannerSession.live) return avisarNaCamera('Captura cancelada.');
  showScannerMessage('A captura foi cancelada.', true);
};

/* Confirmar a leitura NÃO grava na coleção: só põe a carta na lista de
   espera. A gravação acontece uma vez só, em `adicionarCartasDaSessao`. */
function confirmScannedCard(cardId) {
  const card = cardMap.get(cardId);
  if (!card) { notify('Esta carta não está mais no catálogo.'); return retomarCamera(); }
  const inedito = pokemonInedito(card);
  /* Cada versão vira sua própria entrada na lista, com o acabamento certo.
     Antes só existia uma quantidade para a carta toda, e quem tinha o normal e
     o reverse na mão precisava ler a mesma carta duas vezes. */
  const quantidades = quantidadesDaLeitura();
  let quantas = 0;
  for (const [versao, numero] of Object.entries(quantidades)) {
    const vezes = Math.max(0, Number(numero) || 0);
    for (let i = 0; i < vezes; i += 1) {
      registrarLeitura(cardId, {
        pricingVariant: versao,
        finish: /reverse/i.test(versao) ? 'reverse' : /holo/i.test(versao) ? 'holo' : 'normal',
      });
    }
    quantas += vezes;
  }
  if (!quantas) return notify('Escolha ao menos uma versão antes de adicionar.');
  scannerSession.quantidades = null;
  scannerSession.ultimaConfirmada = cardId;
  scannerSession.confirmadaEm = Date.now();
  scannerCandidateBuffer = [];
  retomarCamera();
  telaCameraAoVivo();
  const quanto = quantas > 1 ? `${quantas}× ${card.name}` : card.name;
  avisarNaCamera(inedito
    ? `★ ${quanto} — Pokémon novo na Pokédex! · ${totalLeituras()} na lista`
    : `${quanto} adicionada · ${totalLeituras()} na lista`);
}

/* ---------- Tela de revisão ----------
   Última parada antes de a coleção ser tocada. Aqui dá para trocar o idioma
   de todas de uma vez, mudar acabamento, condição e quantidade, remover uma
   leitura errada e acrescentar a versão Reverse da mesma carta. */
function abrirRevisaoSessao() {
  const pendentes = leiturasPendentes();
  if (!pendentes.length) return notify('Nenhuma carta lida ainda.');
  pausarCamera();
  const total = totalLeituras();

  const linhas = pendentes.map(linha => {
    const card = cardMap.get(linha.cardId);
    if (!card) return '';
    const arte = card.imageUrl ? upgradeCardImageUrl(card.imageUrl) : '';
    const idioma = linha.language || 'pt-br';
    const variantes = variantesParaEscolher(card, idioma, linha.pricingVariant);
    const pills = variantes.map(value => {
      const estilo = variantEstilo(value);
      return `<button type="button" class="variante-pill ${estilo.classe}${value === linha.pricingVariant ? ' ativo' : ''}"
        onclick="mudarVarianteRevisao('${esc(linha.id)}','${esc(value)}')">
        <span class="variante-icone" aria-hidden="true">${estilo.icone}</span>${esc(friendlyVariantLabel(value))}
      </button>`;
    }).join('');
    // Versões desta carta que ainda não estão na lista: viram atalho "+".
    const faltando = variantes.filter(value => value !== linha.pricingVariant
      && !pendentes.some(item => item.cardId === linha.cardId && item.pricingVariant === value));
    const preco = centralPriceQuote(card.id, { ...linha, gradingCompany: 'Não graduada', variantTags: [], artVariant: 'standard', region: 'Brasil' });

    return `<article class="revisao-carta">
      <div class="revisao-arte">${arte ? `<img src="${esc(arte)}" alt="" loading="lazy">` : '<span class="card-placeholder">TCG</span>'}</div>
      <div class="revisao-dados">
        <div class="revisao-cabeca">
          <strong>${esc(card.name)}</strong>
          <button class="revisao-remover" onclick="removerLinhaRevisao('${esc(linha.id)}')" aria-label="Remover">×</button>
        </div>
        <span class="revisao-sub">${esc(card.setName)} · ${esc(formatCardNumber(card.localId || card.number, card.setTotal))}</span>
        ${card.rarity ? `<span class="revisao-raridade">${esc(card.rarity)}</span>` : ''}
        <div class="variante-pills revisao-pills">${pills}</div>
        <div class="revisao-linha-controles">
          <select class="revisao-campo" onchange="mudarIdiomaRevisao('${esc(linha.id)}',this.value)">
            ${PRICE_LANGUAGES.map(value => option(value, value, idioma)).join('')}
          </select>
          <select class="revisao-campo" onchange="mudarCondicaoRevisao('${esc(linha.id)}',this.value)">
            ${['Mint','Near Mint','Excelente','Bom','Regular','Danificada'].map(value => option(value, value, linha.condition)).join('')}
          </select>
          <div class="revisao-qtd">
            <button onclick="mudarQuantidadeRevisao('${esc(linha.id)}',-1)" aria-label="Menos uma">−</button>
            <b>${Number(linha.quantity) || 0}</b>
            <button onclick="mudarQuantidadeRevisao('${esc(linha.id)}',1)" aria-label="Mais uma">+</button>
          </div>
        </div>
        <span class="revisao-preco">${preco ? esc(money(preco.brl)) : 'sem preço publicado'}</span>
        ${faltando.length ? `<div class="revisao-atalhos">${faltando.map(value => `<button onclick="acrescentarVersao('${esc(linha.cardId)}','${esc(value)}')">+ ${esc(friendlyVariantLabel(value))}</button>`).join('')}</div>` : ''}
      </div>
    </article>`;
  }).join('');

  showModal(`
    <div class="revisao-tela">
      <div class="revisao-topo">
        <button class="camera-icone" onclick="voltarParaCamera()" aria-label="Voltar">←</button>
        <strong>Revisar (${total})</strong>
        <span class="revisao-contador">${pendentes.length} linha(s)</span>
      </div>
      <label class="revisao-global">
        <span>Idioma de todas as cartas</span>
        <select class="revisao-campo" onchange="aplicarIdiomaEmTodas(this.value)">
          ${PRICE_LANGUAGES.map(value => option(value, value, pendentes[0]?.language || 'pt-br')).join('')}
        </select>
      </label>
      <div class="revisao-lista">${linhas}</div>
      <div class="revisao-acoes">
        <button class="camera-cancelar" onclick="voltarParaCamera()">Voltar</button>
        <button class="camera-revisar" onclick="adicionarCartasDaSessao()">Adicionar ${total} carta${total === 1 ? '' : 's'}</button>
      </div>
    </div>
  `, 'revisao-sheet');
}

function linhaRevisao(linhaId) {
  return leiturasPendentes().find(item => item.id === linhaId);
}

function mudarVarianteRevisao(linhaId, valor) {
  const linha = linhaRevisao(linhaId);
  if (!linha) return;
  linha.pricingVariant = valor;
  linha.finish = /reverse/i.test(valor) ? 'reverse' : /holo/i.test(valor) ? 'holo' : 'normal';
  salvarSessaoLeitura();
  abrirRevisaoSessao();
}

function mudarIdiomaRevisao(linhaId, valor) {
  const linha = linhaRevisao(linhaId);
  if (!linha) return;
  linha.language = valor;
  salvarSessaoLeitura();
  abrirRevisaoSessao();
}

function mudarCondicaoRevisao(linhaId, valor) {
  const linha = linhaRevisao(linhaId);
  if (!linha) return;
  linha.condition = valor;
  salvarSessaoLeitura();
}

function mudarQuantidadeRevisao(linhaId, delta) {
  mudarQuantidadeLeitura(linhaId, delta);
  abrirRevisaoSessao();
}

function removerLinhaRevisao(linhaId) {
  removerLeitura(linhaId);
  if (!leiturasPendentes().length) return voltarParaCamera();
  abrirRevisaoSessao();
}

function aplicarIdiomaEmTodas(valor) {
  leiturasPendentes().forEach(linha => { linha.language = valor; });
  salvarSessaoLeitura();
  abrirRevisaoSessao();
}

function acrescentarVersao(cardId, valor) {
  registrarLeitura(cardId, {
    pricingVariant: valor,
    finish: /reverse/i.test(valor) ? 'reverse' : /holo/i.test(valor) ? 'holo' : 'normal',
  });
  abrirRevisaoSessao();
}

function voltarParaCamera() {
  if (scannerSession.active && scannerSession.live) {
    telaCameraAoVivo();
    retomarCamera();
    return;
  }
  closeModal();
}

/* O único ponto do scanner que escreve na coleção. */
function adicionarCartasDaSessao() {
  const pendentes = leiturasPendentes().slice();
  if (!pendentes.length) return notify('Nenhuma carta para adicionar.');

  let gravadas = 0;
  const cardIds = new Set();
  for (const linha of pendentes) {
    const card = cardMap.get(linha.cardId);
    const quantidade = Math.max(0, Number(linha.quantity) || 0);
    if (!card || !quantidade) continue;

    const entry = state.entries[linha.cardId] || { quantity: 0, priceBrl: null, wishlist: false, variants: [] };
    state.entries[linha.cardId] = entry;
    entry.variants = Array.isArray(entry.variants) ? entry.variants : [];

    let variant = entry.variants.find(item => exactSourceEnum(item.pricingVariant) === exactSourceEnum(linha.pricingVariant)
      && finishKind(item.finish) === finishKind(linha.finish)
      && item.language === linha.language && item.condition === linha.condition
      && item.edition === linha.edition && item.distribution === linha.distribution
      && item.gradingCompany === 'Não graduada' && !(item.variantTags || []).length);
    if (!variant) {
      variant = defaultVariant(0, {
        pricingVariant: linha.pricingVariant, finish: linha.finish, language: linha.language,
        condition: linha.condition, edition: linha.edition, distribution: linha.distribution,
        artVariant: 'standard', region: 'Brasil', gradingCompany: 'Não graduada', grade: '', variantTags: [],
      });
      entry.variants.push(variant);
    }
    variant.quantity = Math.max(0, Number(variant.quantity) || 0) + quantidade;
    variant.updatedAt = new Date().toISOString();
    syncEntry(linha.cardId);
    gravadas += quantidade;
    cardIds.add(linha.cardId);

    fetchVariantImage(card, variant.language).then(image => {
      if (!image?.url) return;
      variant.imageUrl = image.url;
      variant.imageSource = image.source;
      saveState();
    }).catch(() => {});
    fetchCardPricing(linha.cardId, true, variant)
      .then(() => { persistAutomaticPricesForCard(linha.cardId); })
      .catch(() => {});
  }

  saveState();
  conferirColecaoCompleta();
  scannerSession.count += gravadas;
  descartarSessaoGuardada();
  sairDaCamera();
  scannerSession.active = false;
  closeModal();
  render();
  notify(`${gravadas} carta(s) adicionada(s) à coleção · ${cardIds.size} carta(s) diferente(s).`);
}

/* =====================================================================
   Comemoração

   Fechar uma coleção ou destravar um troféu é o momento que o app inteiro
   existe para produzir, e até agora passava em branco. As faíscas duram menos
   de dois segundos e somem sozinhas; nada fica na tela atrapalhando.
   ===================================================================== */
function comemorar(mensagem, detalhe = '') {
  const antigo = document.getElementById('comemoracao');
  if (antigo) antigo.remove();

  const faiscas = Array.from({ length: 14 }, (_, i) => {
    const esquerda = 6 + Math.round(Math.random() * 88);
    const atraso = Math.round(Math.random() * 420);
    const giro = Math.round(Math.random() * 360);
    return `<i style="left:${esquerda}%;animation-delay:${atraso}ms;transform:rotate(${giro}deg)"></i>`;
  }).join('');

  const caixa = document.createElement('div');
  caixa.id = 'comemoracao';
  caixa.className = 'comemoracao';
  caixa.setAttribute('role', 'status');
  caixa.innerHTML = `<div class="comemoracao-faiscas" aria-hidden="true">${faiscas}</div>
    <div class="comemoracao-aviso">
      <strong>${esc(mensagem)}</strong>
      ${detalhe ? `<span>${esc(detalhe)}</span>` : ''}
    </div>`;
  document.body.appendChild(caixa);
  setTimeout(() => { caixa.classList.add('saindo'); }, 2200);
  setTimeout(() => { caixa.remove(); }, 2900);
}

/* Guarda quais coleções já estavam completas, para comemorar só quando uma
   nova fecha — e não toda vez que a tela é desenhada. */
const COLECOES_COMPLETAS_KEY = 'pokecard-colecoes-completas-v1';

function conferirColecaoCompleta() {
  let conhecidas = [];
  try { conhecidas = JSON.parse(localStorage.getItem(COLECOES_COMPLETAS_KEY) || '[]') || []; } catch (_) {}
  const antes = new Set(conhecidas);
  const completas = buildSetStats().filter(item => {
    const total = item.officialCardCount || item.totalCardCount || 0;
    return total > 0 && item.ownedUnique >= total;
  });
  const novas = completas.filter(item => !antes.has(item.id));
  try { localStorage.setItem(COLECOES_COMPLETAS_KEY, JSON.stringify(completas.map(item => item.id))); } catch (_) {}
  if (novas.length) {
    comemorar(`Coleção completa: ${novas[0].name}`, novas.length > 1 ? `e mais ${novas.length - 1}` : 'Todas as cartas cadastradas');
  }
}

/* =====================================================================
   Decoração das cartas

   Tudo aqui sai de dado que o app já tem: o tipo do Pokémon ligado à carta,
   o acabamento da versão cadastrada e a raridade do catálogo. Nenhuma consulta
   nova, nenhum arquivo novo — só marcas no HTML para o CSS pintar.
   ===================================================================== */
function tipoPrincipalDaCarta(card) {
  for (const id of pokemonIdsForCard(card)) {
    const tipo = pokemonMap.get(Number(id))?.types?.[0];
    if (tipo) return normalize(tipo).replace(/\s+/g, '-');
  }
  // Carta sem Pokémon: a categoria vira a cor (treinador, energia).
  const categoria = categoriaDaCarta(card);
  return categoria ? categoria.classe : '';
}

/** Holo e reverse ganham reflexo; carta comum não. */
function brilhoDaVariante(variant) {
  const valor = `${variant?.pricingVariant || ''} ${variant?.finish || ''}`;
  if (/reverse/i.test(valor)) return 'reverse';
  if (/holo/i.test(valor)) return 'holo';
  return '';
}

/* =====================================================================
   Etiquetas de versão na miniatura

   Uma etiqueta por versão que a carta tem, com a sigla do acabamento.
   A que você possui vem marcada; as que faltam ficam apagadas — dá para
   ver de relance o que ainda falta sem abrir a carta.

   Só aparecem versões CONHECIDAS: as que o Price Database publica para
   aquela carta, mais as que você já cadastrou. Enquanto o preço daquela
   coleção não foi baixado, aparecem só as suas — melhor mostrar pouco e
   certo do que inventar três versões para toda carta.
   ===================================================================== */
const SIGLAS_VARIANTE = {
  'normal': 'N',
  'holo': 'F',
  'holofoil': 'F',
  'reverse': 'RF',
  'reverse-holofoil': 'RF',
  '1st-edition': '1E',
  '1st-edition-holofoil': '1F',
  'unlimited': 'U',
  'unlimited-holofoil': 'UF',
  'wPromo': 'P',
  'firstEdition': '1E',
};

function siglaDaVariante(valor) {
  const exato = exactSourceEnum(valor);
  if (SIGLAS_VARIANTE[exato]) return SIGLAS_VARIANTE[exato];
  // Enum novo que a fonte inventar: duas primeiras letras, sem travar.
  const limpo = exato.replace(/[^a-z0-9]/gi, '');
  return (limpo.slice(0, 2) || '?').toUpperCase();
}

/**
 * O que esta carta tem, o que você tem, e se está completa.
 *
 * "Completa" exige que a FONTE conheça as versões da carta. Enquanto o preço
 * daquela coleção não foi baixado, o app só sabe das versões que você mesmo
 * cadastrou — e aí toda carta pareceria completa, o que seria mentira
 * justamente na informação que o dourado promete.
 */
function analiseDeVariantes(card) {
  const idioma = 'pt-br';
  const conhecidas = centralVariantEntries(card.id, idioma).map(item => item.value).filter(Boolean);
  const minhas = variantsFor(card.id)
    .filter(item => !item.isWishlist && (Number(item.quantity) || 0) > 0)
    .map(item => exactSourceEnum(item.pricingVariant) || finishKind(item.finish));

  // Uma etiqueta por sigla: "reverse" e "reverse-holofoil" são a mesma
  // coisa para quem olha a carta, como já vale nos botões do cadastro.
  const porSigla = new Map();
  for (const valor of [...conhecidas, ...minhas]) {
    const sigla = siglaDaVariante(valor);
    const tenho = minhas.some(meu => siglaDaVariante(meu) === sigla);
    if (!porSigla.has(sigla)) porSigla.set(sigla, { sigla, valor, tenho });
    else if (tenho) porSigla.get(sigla).tenho = true;
  }

  const siglasDaFonte = new Set(conhecidas.map(siglaDaVariante));
  const completa = siglasDaFonte.size > 0
    && [...siglasDaFonte].every(sigla => porSigla.get(sigla)?.tenho);

  return {
    // As que você tem vêm primeiro: é a informação que o olho procura.
    lista: [...porSigla.values()].sort((a, b) => Number(b.tenho) - Number(a.tenho)),
    completa,
    totalDaFonte: siglasDaFonte.size,
  };
}

function etiquetasDeVariante(analise) {
  const porSigla = new Map((analise?.lista || []).map(item => [item.sigla, item]));
  if (!porSigla.size) return '';
  const lista = analise.lista;
  const cabem = lista.slice(0, 4);
  const sobra = lista.length - cabem.length;

  const etiquetas = cabem.map(item => {
    const estilo = variantEstilo(item.valor);
    return `<span class="etiqueta-variante ${estilo.classe}${item.tenho ? ' tenho' : ''}" title="${esc(friendlyVariantLabel(item.valor))}${item.tenho ? ' · você tem' : ''}">${esc(item.sigla)}</span>`;
  }).join('');

  return `<span class="etiquetas-variante">${etiquetas}${sobra ? `<span class="etiqueta-variante mais">+${sobra}</span>` : ''}</span>`;
}

/** Selo só para o que é realmente raro — senão perde a graça. */
function seloDeRaridade(card) {
  const raridade = normalize(card?.rarity || '');
  if (!raridade || raridade === 'none') return '';
  if (/secret|rainbow|arco|hyper|crown|coroa/.test(raridade)) return 'secreta';
  if (/promo/.test(raridade)) return 'promo';
  if (/ultra|illustration|ilustra|especial|special|amazing|radiant|shiny|brilhante|double rare|dupla/.test(raridade)) return 'ultra';
  return '';
}

function renderCardRow(card) {
  const entry = entryFor(card.id);
  const quantity = quantityFor(card.id);
  const cardVariants = variantsFor(card.id);
  const displayVariant = cardVariants.find(item => item.imageUrl) || cardVariants[0];
  const displayImage = variantDisplayImage(card, displayVariant);
  const priceBadge = priceBadgeForCard(card.id);
  // A etiqueta única de acabamento saiu: as etiquetas de versão embaixo da
  // arte dizem a mesma coisa e ainda mostram o que falta.
  // Marcas para a decoração: tipo pinta a moldura e o símbolo de fundo,
  // acabamento liga o brilho holográfico, raridade liga o selo.
  const tipo = tipoPrincipalDaCarta(card);
  const brilho = brilhoDaVariante(displayVariant);
  const selo = seloDeRaridade(card);
  // Uma única análise serve para as etiquetas e para o estado dourado.
  const variantes = analiseDeVariantes(card);
  return `<article class="card-row vision-card-tile ${quantity > 0 ? '' : 'missing'}${variantes.completa ? ' completa' : ''}" data-card-id="${esc(card.id)}"${tipo ? ` data-tipo="${esc(tipo)}"` : ''}${selo ? ` data-raridade="${esc(selo)}"` : ''} onclick="openCard('${esc(card.id)}')">
    <div class="vision-card-art${brilho ? ` brilho-${brilho}` : ''}">
      ${displayImage ? `<img class="card-thumb" src="${esc(displayImage)}" loading="lazy" decoding="async" fetchpriority="low" onerror="this.outerHTML='<div class=&quot;card-placeholder&quot;>TCG</div>'">` : '<div class="card-placeholder">TCG</div>'}
      ${quantity ? `<span class="tile-quantity-badge">x${quantity}</span>` : ''}
      ${entry.wishlist ? '<span class="tile-wishlist-badge">Quero</span>' : ''}
      ${variantes.completa ? '<span class="marca-completa" title="Você tem todas as versões desta carta">★</span>' : ''}
      ${etiquetasDeVariante(variantes)}
    </div>
    <div class="card-main">
      <div class="card-name">${esc(card.name)}</div>
      <div class="card-meta">${esc(card.number)} · ${esc(card.setName)}</div>
      <div class="tile-price-row"><span>${priceBadge ? esc(priceBadge) : 'Sem preço'}</span>${cardVariants.length > 1 ? `<small>${cardVariants.length} versões</small>` : ''}</div>
    </div>
  </article>`;
}

function emptyCards() {
  /* Coleção escolhida que simplesmente não tem carta nenhuma instalada é caso
     diferente de filtro sem resultado: não adianta mexer nos filtros, as
     cartas nunca foram baixadas. O aviso precisa dizer isso e oferecer a
     solução, senão a tela vazia parece defeito. */
  const setId = ui.cardSet;
  if (setId && setId !== 'all') {
    const temAlguma = cards.some(card => card.setId === setId);
    if (!temAlguma) {
      const set = catalog.sets.find(item => item.id === setId);
      const esperado = set?.officialCardCount || set?.totalCardCount || 0;
      /* Depois de uma atualização bem-sucedida, coleção ainda vazia não é
         falta de download: é a fonte que não publica as cartas dela. Insistir
         no botão só faria o usuário repetir uma busca que nunca vai trazer
         nada. É o caso de várias expansões japonesas antigas. */
      if (catalogUpdateMeta.updatedAt) {
        return `<div class="empty catalogo-vazio">
          <strong>Esta coleção não tem cartas publicadas na fonte</strong>
          <span>${esc(set?.name || setId)} existe no catálogo${esperado ? ` e tem ${esperado} cartas impressas` : ''}, mas o TCGdex — de onde o app tira o catálogo — não disponibiliza as cartas dela. Acontece com expansões japonesas antigas.</span>
          <small>Você ainda pode cadastrar essas cartas à mão pelo scanner, digitando o número.</small>
        </div>`;
      }
      return `<div class="empty catalogo-vazio">
        <strong>As cartas desta coleção ainda não foram baixadas</strong>
        <span>${esc(set?.name || setId)}${esperado ? ` tem ${esperado} cartas` : ''}, mas nenhuma está instalada no aplicativo.</span>
        <button class="primary-btn" ${catalogUpdating ? 'disabled' : ''} onclick="startCatalogUpdate()">${catalogUpdating ? 'Buscando…' : '⟳ Buscar as cartas agora'}</button>
        <small>Precisa de internet. Suas quantidades e cadastros não são apagados.</small>
      </div>`;
    }
  }
  return '<div class="empty"><strong>Nenhuma carta encontrada</strong>Tente mudar os filtros ou buscar outro nome.</div>';
}


function automaticPriceBox(cardId, finish = 'normal', variantId = '', identityOverride = null) {
  const variant = variantId
    ? variantsFor(cardId).find(item => item.id === variantId)
    : variantsFor(cardId).find(item => finishKind(item.finish) === finishKind(finish));
  const priceIdentity = identityOverride || variant || { pricingVariant: exactSourceEnum(finish) || 'normal', finish, language: 'pt-br', condition: 'Near Mint', edition: 'unlimited', distribution: 'unstamped', artVariant: 'standard', region: 'Brasil' };
  const quote = automaticPriceQuote(cardId, priceIdentity);
  const stored = storedAutomaticPriceQuote(variant);
  const displayQuote = quote || stored;
  const loading = [...priceRequests.keys()].some(key => key.startsWith(`${cardId}|`)) || centralPriceSyncing;
  if (displayQuote) {
    const converted = displayQuote.brl != null ? money(displayQuote.brl) : 'Preço indisponível';
    const sourceValues = Number(displayQuote.acceptedCount) || 0;
    const rangeHtml = sourceValues
      ? `<div class="price-market-summary"><span>Menor referência <strong>${esc(money(displayQuote.low))}</strong></span><span>Preço calculado <strong>${esc(converted)}</strong></span><span>Maior referência <strong>${esc(money(displayQuote.high))}</strong></span></div>
        <small class="price-sample">${sourceValues} valor(es) do ${esc(displayQuote.priceMarketLabel || 'Price Database')} usados no cálculo</small>`
      : '';
    const confidence = displayQuote.confidence || 'review';
    const userValidated = Boolean(displayQuote.userValidated || (variant?.automaticPriceUserValidated && variant?.automaticPriceAcceptedFingerprint === displayQuote.fingerprint));
    const verified = confidence === 'verified';
    const reasons = displayQuote.validation?.reasons || [];
    const priceLanguage = displayQuote.priceLanguage || displayQuote.marketIdentity?.language || '';
    const usedFallback = Boolean(displayQuote.fallbackLanguage);
    const conditionHtml = displayQuote.conditionEstimated
      ? `<small class="price-condition-estimate">Preço-base NM <strong>${esc(money(displayQuote.basePriceBrl))}</strong> × ${Math.round(Number(displayQuote.conditionMultiplier) * 100)}% (${esc(CONDITION_LABELS[displayQuote.marketIdentity?.condition] || displayQuote.marketIdentity?.condition || '')}) · <em>estimativa, não é preço de fonte</em></small>`
      : '';
    const stampHtml = displayQuote.stamped
      ? `<small class="price-verification review">⚠ Carimbo não é precificado por nenhuma fonte — o valor acima é o da versão <strong>sem carimbo</strong>.</small>${
        (displayQuote.stampedCounterparts || []).length
          ? `<div class="price-stamped-suggestion"><small>Versões promocionais desta carta com preço próprio:</small>${
            displayQuote.stampedCounterparts.map(item =>
              `<button type="button" class="price-validate-btn" onclick="event.preventDefault();event.stopPropagation();openCard('${esc(item.id)}')">${esc(item.name)}${item.number ? ` ${esc(item.number)}` : ''} · ${esc(item.setName)}</button>`
            ).join('')
          }</div>`
          : '<small>Nenhuma versão promocional desta carta no catálogo — use o valor manual.</small>'
      }`
      : '';
    const statusHtml = verified
      ? (usedFallback
        ? `<small class="price-verification verified">✓ Carta e versão conferidas · valor de referência do ${esc(PRICE_MARKET_LABELS[priceLanguage] || priceLanguage)}, porque não existe preço publicado da tiragem ${esc(displayQuote.requestedLanguage || 'pt-br')}.</small>`
        : '<small class="price-verification verified">✓ Carta, idioma e versão conferidos.</small>')
      : userValidated
        ? '<small class="price-verification user-validated">✓ Correspondência do banco confirmada manualmente por você.</small>'
        : `<small class="price-verification review">⚠ Necessita validação${reasons.length ? `: ${esc(reasons.join('; '))}` : ''}. Este valor ainda não entra no total da coleção.</small>`;
    const validationButton = !verified && !userValidated
      ? (variant?.id
        ? `<button type="button" class="price-validate-btn" onclick="event.preventDefault();event.stopPropagation();confirmAutomaticPrice('${esc(cardId)}','${esc(variant.id)}',document.getElementById('regFinish')?.value||'${esc(finish)}')">Confirmar este preço</button>`
        : '<small>Salve esta variante antes de confirmar a correspondência.</small>')
      : '';
    return `<div class="automatic-price-card ${verified ? 'verified-price' : 'review-price'}">
      <strong>${esc(converted)}</strong>
      <span>Pokémon Price Database Brasil</span>
      ${rangeHtml}
      <small>${displayQuote.stored ? 'Valor salvo do Price Database' : 'Valor atual do Price Database'} · ${esc(marketVariantDescription(displayQuote.marketIdentity || priceIdentity))} · ${esc(formatPriceDate(displayQuote.fetchedAt))}</small>
      ${conditionHtml}
      ${stampHtml}
      ${statusHtml}
      ${validationButton}
      <button type="button" class="price-refresh-btn" ${loading ? 'disabled' : ''} onclick="event.preventDefault();event.stopPropagation();updateCardPrice('${esc(cardId)}', document.getElementById('regFinish')?.value || '${esc(finish)}', true, document.getElementById('regVariantId')?.value || '')">${loading ? 'Sincronizando...' : 'Atualizar Price Database'}</button>
    </div>`;
  }
  if (loading) return `<div class="automatic-price-card loading-price"><strong>Sincronizando o Price Database...</strong><span>Aguarde a atualização do banco.</span></div>`;
  return `<div class="automatic-price-card empty-price">
    <strong>Sem preço no Price Database</strong>
    <span>${esc(marketVariantDescription(priceIdentity))}. Cardmarket e TCGplayer não publicam valor para esta variação em nenhuma das tiragens cobertas.</span>
    <button type="button" class="price-refresh-btn" onclick="event.preventDefault();event.stopPropagation();updateCardPrice('${esc(cardId)}', document.getElementById('regFinish')?.value || '${esc(finish)}', true, document.getElementById('regVariantId')?.value || '')">Atualizar Price Database</button>
  </div>`;
}

function refreshAutomaticPriceField(cardId, finish) {
  const target = document.getElementById('automaticPriceBox');
  const variantId = document.getElementById('regVariantId')?.value || '';
  if (target) target.innerHTML = automaticPriceBox(cardId, finish, variantId, registrationVariantFromForm(finish));
}

function confirmAutomaticPrice(cardId, variantId, finish) {
  const variant = variantsFor(cardId).find(item => item.id === variantId);
  const quote = automaticPriceQuote(cardId, variant || finish || 'normal');
  if (!variant || !quote || quote.confidence !== 'review') return notify('Não há preço pendente para validar.');
  variant.automaticPriceUserValidated = true;
  variant.automaticPriceAcceptedFingerprint = quote.fingerprint;
  variant.automaticPriceUserValidatedAt = new Date().toISOString();
  applyAutomaticPriceToVariant(cardId, variant);
  syncEntry(cardId);
  saveState();
  refreshAutomaticPriceField(cardId, finish || variant.finish);
  renderKeepingScroll();
  notify('Preço confirmado e incluído no total da coleção.');
}

async function updateCardPrice(cardId, finish = 'normal', force = false, variantId = '') {
  const labStart = performance.now();
  refreshAutomaticPriceField(cardId, finish);
  const savedVariant = variantId ? variantsFor(cardId).find(item => item.id === variantId) : null;
  const identity = document.getElementById('regFinish') ? registrationVariantFromForm(finish) : (savedVariant || { finish });
  try {
    await fetchCardPricing(cardId, force, identity);
    const saved = persistAutomaticPricesForCard(cardId, true);
    // Só agora sabemos quais versões têm preço: os botões precisam ser
    // redesenhados para esconder as que ficaram sem valor publicado.
    const cardAtual = cardMap.get(cardId);
    if (cardAtual) refreshCardSpecificVariationFields(cardAtual);
    refreshAutomaticPriceField(cardId, finish);
    renderKeepingScroll();
    const quote = automaticPriceQuote(cardId, identity);
    notify(quote
      ? (quote.confidence === 'verified'
        ? (saved ? 'Preço do Database salvo' : 'Preço encontrado no Database')
        : 'Preço do Database encontrado para revisão')
      : 'O Price Database não possui preço para esta variação.');
  } catch (error) {
    refreshAutomaticPriceField(cardId, finish);
    const message = error?.name === 'AbortError' ? 'A sincronização do Database demorou demais.' : String(error?.message || 'Não foi possível sincronizar o Price Database agora.');
    lastPriceDiagnostic = message;
    try { localStorage.setItem('fichario-price-last-diagnostic', message); } catch (_) {}
    notify(message.length > 180 ? `${message.slice(0, 177)}...` : message);
  } finally {
    labRecord('consulta_preco', performance.now() - labStart, { cardId, finish: finishKind(finish), force, source: 'price-database' });
  }
}

function ensureCardPriceLoaded(cardId, variant) {
  const central = centralPriceQuote(cardId, variant);
  if (central) {
    if (persistAutomaticPricesForCard(cardId, true)) renderKeepingScroll();
    return;
  }
  const requestKey = `${cardId}|${marketVariantKey(variant)}`;
  if (priceRequests.has(requestKey) || centralPriceSyncing) return;
  setTimeout(() => {
    if (!document.getElementById('automaticPriceBox')) return;
    updateCardPrice(cardId, variant?.finish || 'normal', false, variant?.id || '');
  }, 180);
}

function openCard(cardId, variantId = undefined) {
  const labStart = performance.now();
  const card = cardMap.get(cardId);
  if (!card) return;
  const entry = entryFor(cardId);
  const quantity = quantityFor(cardId);
  const variants = variantsFor(cardId);
  const linked = pokemonIdsForCard(card).map(id => pokemonMap.get(id)).filter(Boolean);
  const automaticLinked = (card.pokemonIds || []).map(Number).map(id => pokemonMap.get(id)).filter(Boolean);
  const manualPokemonId = Number(entry.manualPokemonId) || 0;
  const creatingNew = variantId === null;
  // Só conta como "o usuário escolheu esta versão" quando o id veio de fato;
  // abrir a carta sem indicar versão apenas mostra a primeira, sem editar.
  const abriuVarianteEspecifica = typeof variantId === 'string' && variantId !== '';
  const selected = creatingNew ? null : (variantId ? variants.find(item => item.id === variantId) : variants[0]);
  const variationProfile = cardVariationProfile(card);
  const draft = selected || defaultVariant(scannerDraftFinish ? 1 : 0, {
    isWishlist: Boolean(entry.wishlist),
    finish: scannerDraftFinish || undefined,
    language: scannerDraftLanguage || undefined,
    condition: scannerDraftCondition || undefined,
    ...scannerDraftMetadata,
  });
  if (!selected && !scannerDraftFinish && !variationProfile.finishes.some(item => finishKind(item) === finishKind(draft.finish))) draft.finish = variationProfile.finishes[0] || 'normal';
  const exactPricingOptions = pricingVariantDetailsForCard(card, draft.language);
  if (!selected && exactPricingOptions.length && !exactPricingOptions.some(item => item.value === draft.pricingVariant)) draft.pricingVariant = exactPricingOptions[0].value;
  if (!selected && !scannerDraftMetadata.artVariant && variationProfile.artVariants.length === 1) draft.artVariant = variationProfile.artVariants[0];
  const draftImage = variantDisplayImage(card, draft);
  const draftImageSource = draft.imageSource || (draft.imageUrl ? 'Imagem da variação' : 'TCGdex PT-BR');
  const existingId = selected?.id || '';
  showModal(`
    <button class="modal-close card-detail-close" onclick="closeModal()" aria-label="Voltar">×</button>
    <div class="registration-header">
      <div>
      <div class="registration-image-frame" data-fichario-card-image="${esc(card.id)}" data-registration-variant-image>
        ${draftImage ? `<img class="registration-card-image" src="${esc(draftImage)}" alt="Arte de ${esc(card.name)}">` : '<div class="registration-placeholder">TCG</div>'}
        <span class="variant-image-badge">${esc(finishPriceLabel(finishKind(draft.finish)))}</span>
      </div>
      <small id="regVariantImageLabel" class="variant-image-source">Imagem: ${esc(draftImageSource)}</small>
      <input type="hidden" id="regVariantImageUrl" value="${esc(draft.imageUrl || '')}">
      <input type="hidden" id="regVariantImageSource" value="${esc(draft.imageSource || '')}">
      </div>
      <div>
        <span class="registration-kicker">${esc(card.setName)} · ${esc(card.number)}</span>
        <h2>${esc(card.name)}</h2>
        <p class="card-meta">${esc(card.number)} · ${esc(card.setName)}${card.rarity ? ` · ${esc(card.rarity)}` : ''}</p>
        <div class="badges">
          <span class="badge ${quantity ? 'owned' : ''}">${quantity ? `Total no fichário: ${quantity}` : 'Ainda não cadastrada'}</span>
          ${variants.length ? `<span class="badge purple">${variants.length} ${variants.length === 1 ? 'variante' : 'variantes'}</span>` : ''}
          ${identidadeDaCartaHtml(card, linked, manualPokemonId)}
        </div>
      </div>
    </div>

    <section class="registration-section card-owned-section">
      <div class="registration-section-title">
        <div><strong>Na coleção</strong><span>Somente as versões cadastradas para esta carta.</span></div>
        <button class="mini-btn" onclick="openCard('${esc(card.id)}', null)">Adicionar</button>
      </div>
      ${variants.length ? `<div class="owned-variant-list">${variants.map(item => `<button class="owned-variant-row ${item.id === existingId ? 'active' : ''}" data-owned-finish="${esc(item.finish)}" data-owned-pricing-variant="${esc(item.pricingVariant || '')}" onclick="openCard('${esc(card.id)}','${esc(item.id)}')"><span><strong>${esc(item.pricingVariant || finishPriceLabel(finishKind(item.finish)))}</strong><small>${esc(languageCode(item.language))} · ${esc(item.condition || 'Near Mint')}</small><small class="owned-variant-warning" hidden></small></span><b>${money(effectiveVariantPrice(card.id,item)?.brl)}</b><em>x${Number(item.quantity)||0}</em></button>`).join('')}</div>` : '<div class="notice compact">Nenhuma versão cadastrada. Toque em Adicionar para começar.</div>'}
    </section>

    <!-- Tocar numa versão já cadastrada abre o editor dela direto: era um
         toque a mais para fazer o que o toque anterior já pedia. -->
    <details class="card-editor" ${creatingNew || abriuVarianteEspecifica ? 'open' : ''}>
      <summary>${existingId ? 'Editar dados completos desta versão' : 'Cadastrar nova versão'}</summary>
    <section class="registration-section">
      <h3>${existingId ? 'Editar variante' : 'Nova variante'}</h3>
      <input type="hidden" id="regVariantId" value="${esc(existingId)}">
      <input type="hidden" id="regVariationCardId" value="${esc(card.id)}">
      <input type="hidden" id="regManualVariationOverride" value="${selected?.manualVariationOverride ? '1' : '0'}">
      <p id="regVariationAvailabilityNote" class="specific-variation-note">${variationProfile.loading || variationProfile.marketLoading ? 'Verificando as versões específicas desta carta…' : `${variationProfile.pricingVariants.length} enum(s) exato(s) identificado(s) · ${esc(variationProfile.source || 'Price Database')}`}</p>
      <div class="registration-grid two-columns">
        ${registrationField('Quantidade', `<div class="quantity-stepper"><button type="button" class="quantity-step-btn" onclick="changeRegistrationQuantity(-1)" aria-label="Diminuir quantidade">−</button><input id="regQuantity" class="field quantity-step-value" type="number" inputmode="numeric" min="0" step="1" value="${Math.max(0, Number(draft.quantity) || 0)}"><button type="button" class="quantity-step-btn" onclick="changeRegistrationQuantity(1)" aria-label="Aumentar quantidade">+</button></div>`)}
        ${registrationField('Condição', `<select id="regCondition" class="field" onchange="handleRegistrationVariantChange('${esc(card.id)}')">${['Mint','Near Mint','Excelente','Bom','Regular','Danificada'].map(value => option(value,value,draft.condition)).join('')}</select>`)}
        ${registrationField('Idioma da carta', `<select id="regLanguage" class="field" onchange="handleRegistrationVariantChange('${esc(card.id)}')">${PRICE_LANGUAGES.map(value => option(value, PRICE_LANGUAGE_LABELS[value] || value, draft.language)).join('')}</select>`)}
        <div id="regVarianteBloco" class="registration-field span-2">
          <label>Acabamento desta carta</label>
          <select id="regFinish" class="hidden" aria-hidden="true" tabindex="-1">${optionListForCard(card,'finishes',draft.finish,Boolean(selected?.manualVariationOverride))}</select>
          <select id="regPricingVariant" class="hidden" aria-hidden="true" tabindex="-1">${optionListForCard(card,'pricingVariants',draft.pricingVariant,true,draft.language)}</select>
          ${variantPillsHtml(card, draft)}
          <small>Só aparecem as versões desta carta que têm preço publicado. O app já marca a mais provável.</small>
        </div>
        ${registrationField('Preço automático', `<div id="automaticPriceBox">${automaticPriceBox(card.id, draft.finish, existingId, draft)}</div>`, 'span-2')}
      </div>

      <details class="registration-group">
        <summary>Mostrar detalhes da impressão <span id="regVarianteResumo" class="registration-group-hint">${esc(friendlyVariantLabel(draft.pricingVariant))}</span></summary>
        <div class="registration-grid two-columns">
        ${registrationField('Edição', `<select id="regEdition" class="field" onchange="handleRegistrationVariantChange('${esc(card.id)}')">${optionListForCard(card,'editions',draft.edition,Boolean(selected?.manualVariationOverride))}</select>`)}
        ${registrationField('Carimbo', `<select id="regDistribution" class="field" onchange="handleRegistrationVariantChange('${esc(card.id)}')">${optionListForCard(card,'distributions',draft.distribution,Boolean(selected?.manualVariationOverride))}</select>`)}
        <input type="hidden" id="regArtVariant" value="standard">
        ${registrationField('Região', `<select id="regRegion" class="field" onchange="handleRegistrationVariantChange('${esc(card.id)}')">${['Brasil','Estados Unidos','Europa','Japão','Coreia','China','Outra região'].map(value => option(value,value,draft.region)).join('')}</select>`)}
        ${registrationField('Guardada em', `<select id="regStorage" class="field">${['fichario','caixa','deck','troca','venda'].map(value => option(value,value,draft.storageLocation)).join('')}</select>`)}
        </div>
      </details>

      <details class="registration-group">
        <summary>Carta graduada e observações <span class="registration-group-hint">${esc(draft.gradingCompany && draft.gradingCompany !== 'Não graduada' ? draft.gradingCompany : 'opcional')}</span></summary>
        <div class="registration-grid two-columns">
        ${registrationField('Certificadora', `<select id="regGradingCompany" class="field" onchange="handleRegistrationVariantChange('${esc(card.id)}')">${['Não graduada','PSA','CGC','Beckett BGS','Beckett Black Label','SGC','Outra certificadora'].map(value => option(value,value,draft.gradingCompany)).join('')}</select>`)}
        ${registrationField('Nota da graduação', `<input id="regGrade" class="field" inputmode="decimal" placeholder="Ex.: 10" value="${esc(draft.grade)}" onchange="handleRegistrationVariantChange('${esc(card.id)}')">`)}
        ${registrationField('Marcações extras', `<input id="regVariantTags" class="field" placeholder="Ex.: erro de corte, assinada" value="${esc((draft.variantTags || []).join(', '))}" onchange="handleRegistrationVariantChange('${esc(card.id)}')">`)}
        </div>
      </details>

      <div class="registration-grid two-columns">
        ${registrationField('Pokémon representado', `<div class="pokemon-link-search">
          <input type="hidden" id="regPokemonId" value="${manualPokemonId || ''}" data-automatic="${automaticLinked.length ? '1' : '0'}">
          <input id="regPokemonSearch" class="field" type="search" autocomplete="off" inputmode="search" placeholder="Digite o nome ou número do Pokémon" value="${manualPokemonId ? esc(pokemonLinkDisplayValue(manualPokemonId)) : ''}" oninput="updatePokemonLinkSearch(this.value)" onfocus="updatePokemonLinkSearch(this.value)">
          <div id="regPokemonResults" class="pokemon-link-results">${renderPokemonLinkSearchResults('', manualPokemonId, automaticLinked)}</div>
        </div><small class="pokemon-link-help">${automaticLinked.length
          ? `Vínculo automático atual: ${esc(automaticLinked.map(item => item.name).join(' + '))}. Busque para substituir.`
          : (categoriaDaCarta(card)
            ? `Esta carta não representa um Pokémon — ela é ${esc(categoriaDaCarta(card).rotulo)}. Não precisa vincular; só use a busca se quiser ligá-la a um Pokémon mesmo assim.`
            : 'A carta não foi reconhecida automaticamente. Digite o nome ou número e toque no resultado.')}</small><small id="regPokemonError" class="field-validation-error hidden">Esta escolha é obrigatória para cadastrar a carta.</small>`, 'span-2')}
      </div>

      <details class="manual-variation-override">
        <summary>Selecionar nomenclatura do TCGdex</summary>
        <p>Os valores abaixo são os mesmos usados pelo TCGdex e pelo Price Database.</p>
        <div class="registration-grid two-columns">
          ${registrationField('Acabamento manual', `<select class="field" onchange="applyManualCardVariation('regFinish',this.value,'${esc(card.id)}')"><option value="">Selecione…</option>${PRICE_FINISHES.map(value => option(value,finishPriceLabel(value),'')).join('')}</select>`)}
          ${registrationField('Edição manual', `<select class="field" onchange="applyManualCardVariation('regEdition',this.value,'${esc(card.id)}')"><option value="">Selecione…</option>${PRICE_PRINT_VARIATIONS.map(value => option(value,value,'')).join('')}</select>`)}
          ${registrationField('Distribuição manual', `<select class="field" onchange="applyManualCardVariation('regDistribution',this.value,'${esc(card.id)}')"><option value="">Selecione…</option>${PRICE_STAMPS.map(value => option(value,value,'')).join('')}</select>`)}

        </div>
      </details>

      <div class="toggle-grid">
        ${registrationToggle('regWishlist','Wishlist',draft.isWishlist)}
        ${registrationToggle('regTrade','Para troca',draft.isForTrade)}
        ${registrationToggle('regSale','Para venda',draft.isForSale)}
        ${registrationToggle('regArt','Arte conferida',draft.artConfirmed)}
      </div>

      <div class="registration-grid two-columns">
        ${registrationField('Preço que paguei (R$)', `<input id="regPaidPrice" class="field" inputmode="decimal" placeholder="Ex.: 5,50" value="${esc(formatInputNumber(draft.paidPrice))}">`)}
        ${registrationField('Valor manual (R$)', `<input id="regManualValue" class="field" inputmode="decimal" placeholder="Ex.: 8,00" value="${esc(formatInputNumber(draft.manualEstimatedValue))}">`)}
      </div>
      ${registrationField('Observações da carta', `<textarea id="regNotes" class="field notes-field" rows="4" placeholder="Ex.: pequeno risco no verso, veio no booster...">${esc(draft.notes)}</textarea>`)}

      <div class="modal-actions">
        <button class="primary-btn" onclick="saveCardVariant('${esc(card.id)}','${esc(existingId)}')">${existingId ? 'Salvar alterações' : 'Cadastrar carta'}</button>
        ${existingId ? `<button class="danger-btn" onclick="deleteCardVariant('${esc(card.id)}','${esc(existingId)}')">Excluir esta variante</button>` : ''}
      </div>
    </section></details>`, 'card-detail-sheet');
  labRecord('abrir_cadastro', performance.now() - labStart, { cardId, variants: variants.length });
  if (!draft.imageUrl) refreshRegistrationVariantImage(card.id);
  refreshCardSpecificVariationFields(card);
  loadCardVariantAvailability(card);
  ensureCardPriceLoaded(card.id, draft);
}

function applyManualCardVariation(targetId, value, cardId) {
  if (!value) return;
  const target = document.getElementById(targetId);
  if (!target) return;
  if (![...target.options].some(item => item.value === value)) target.add(new Option(targetId === 'regFinish' ? finishPriceLabel(finishKind(value)) : value, value));
  target.value = value;
  target.dataset.manualOverride = '1';
  const marker = document.getElementById('regManualVariationOverride');
  if (marker) marker.value = '1';
  handleRegistrationVariantChange(cardId);
}

function changeRegistrationQuantity(delta) {
  const input = document.getElementById('regQuantity');
  if (!input) return;
  const current = Math.max(0, Number.parseInt(input.value || '0', 10) || 0);
  input.value = String(Math.max(0, current + Number(delta || 0)));
  input.dispatchEvent(new Event('change', { bubbles: true }));
}

/**
 * O que esta carta É.
 *
 * Carta de Pokémon mostra o Pokémon a que está ligada. Carta que não
 * representa Pokémon nenhum — Item, Apoiador, Estádio, Ferramenta, Energia —
 * mostra a própria categoria, em vez de ficar sem identidade alguma na tela.
 */
function categoriaDaCarta(card) {
  const categoria = String(card?.category || '');

  /* Cerca de um terço das cartas não tem categoria na fonte: são tiragens que
     só existem em português ou japonês, e a consulta que informa a categoria
     cobre apenas o catálogo inglês (conferido: essas cartas dão 404 lá).
     Para elas sobra o nome. Energia é reconhecível com segurança — o nome
     começa com "Energia" ou termina em "Energy" —, então vale o palpite.
     Item, Apoiador e Estádio não dão para adivinhar pelo nome, e inventar
     seria pior do que admitir que não sabemos. */
  if (!categoria) {
    const nome = normalize(card?.name);
    if (/^energia(?:\s|$)/.test(nome) || /\benergy$/.test(nome)) {
      return { rotulo: 'Energia', classe: 'energia', icone: '⚡' };
    }
    if (pokemonIdsForCard(card).length) return { rotulo: 'Pokémon', classe: 'pokemon', icone: '◓' };
    return null;
  }
  if (categoria === 'Trainer') {
    const tipo = String(card?.trainerType || '');
    return { rotulo: TREINADOR_ROTULOS[tipo] || 'Treinador', classe: 'treinador', icone: '🎒' };
  }
  if (categoria === 'Energy') {
    const tipo = String(card?.energyType || '');
    return { rotulo: ENERGIA_ROTULOS[tipo] || 'Energia', classe: 'energia', icone: '⚡' };
  }
  if (categoria === 'Pokemon') return { rotulo: 'Pokémon', classe: 'pokemon', icone: '◓' };
  return { rotulo: CATEGORIA_ROTULOS[categoria] || categoria, classe: 'outra', icone: '◈' };
}

function identidadeDaCartaHtml(card, linked, manualPokemonId) {
  // Vínculo com Pokémon tem prioridade: é a informação mais específica.
  if (linked?.length) {
    return linked.map(item => `<span class="badge">#${String(item.id).padStart(4, '0')} ${esc(item.name)}</span>`).join('');
  }
  const categoria = categoriaDaCarta(card);
  if (categoria) return `<span class="badge categoria-${categoria.classe}">${categoria.icone} ${esc(categoria.rotulo)}</span>`;
  // Marcação antiga, de antes de existir categoria vinda da fonte.
  if (Number(manualPokemonId) === 1026) return '<span class="badge categoria-treinador">🎒 Energia / Ferramenta</span>';
  return '<span class="badge">Sem categoria — atualize o catálogo</span>';
}

function pokemonLinkDisplayValue(id) {
  const numericId = Number(id);
  if (numericId === 1026) return 'Nº 1026 — Energia / Ferramenta';
  const pokemon = pokemonMap.get(numericId);
  return pokemon ? `Nº ${String(pokemon.id).padStart(4,'0')} — ${pokemon.name}` : '';
}

function pokemonLinkSearchMatches(query) {
  const raw = String(query || '').trim();
  const normalizedQuery = normalize(raw.replace(/^n[º°o]?\s*/i, ''));
  const numericQuery = raw.replace(/\D/g, '');
  if (!normalizedQuery && !numericQuery) return [];
  const rows = pokedex.map(item => ({
    id: item.id,
    name: item.name,
    sprite: item.sprite,
    normalizedName: normalize(item.name),
  }));
  rows.push({ id: 1026, name: 'Energia / Ferramenta', sprite: '', normalizedName: 'energia ferramenta' });
  return rows
    .map(item => {
      const idText = String(item.id);
      let score = 0;
      if (numericQuery) {
        if (idText === String(Number(numericQuery))) score += 200;
        else if (idText.startsWith(String(Number(numericQuery)))) score += 80;
      }
      if (normalizedQuery) {
        if (item.normalizedName === normalizedQuery) score += 180;
        else if (item.normalizedName.startsWith(normalizedQuery)) score += 120;
        else if (item.normalizedName.includes(normalizedQuery)) score += 75;
      }
      return { ...item, score };
    })
    .filter(item => item.score > 0)
    .sort((a,b) => b.score - a.score || a.id - b.id)
    .slice(0, 10);
}

function renderPokemonLinkSearchResults(query, selectedId = 0, automaticLinked = null) {
  const hidden = document.getElementById('regPokemonId');
  const hasAutomatic = automaticLinked ? automaticLinked.length > 0 : hidden?.dataset.automatic === '1';
  const selected = Number(selectedId || hidden?.value || 0);
  const matches = pokemonLinkSearchMatches(query);
  if (!String(query || '').trim()) {
    if (selected) {
      return `<div class="pokemon-link-selected"><strong>Selecionado</strong><span>${esc(pokemonLinkDisplayValue(selected))}</span><button type="button" onclick="clearPokemonLinkSelection()">Trocar</button></div>`;
    }
    if (hasAutomatic) {
      return '<div class="pokemon-link-selected automatic"><strong>Vínculo automático ativo</strong><span>Digite acima somente se quiser corrigir.</span></div>';
    }
    return '<div class="pokemon-link-empty">Digite pelo menos parte do nome ou o número da Pokédex.</div>';
  }
  if (!matches.length) return '<div class="pokemon-link-empty">Nenhum Pokémon encontrado. Tente outro nome ou número.</div>';
  return matches.map(item => `<button type="button" class="pokemon-link-result" data-pokemon-link-id="${item.id}" onclick="choosePokemonLink(${item.id})">
    ${item.sprite ? `<img src="${esc(item.sprite)}" alt="">` : '<span class="pokemon-link-energy">⚡</span>'}
    <span><strong>${esc(item.name)}</strong><small>Nº ${String(item.id).padStart(4,'0')}</small></span>
  </button>`).join('');
}

function updatePokemonLinkSearch(value) {
  const results = document.getElementById('regPokemonResults');
  const field = document.getElementById('regPokemonSearch');
  const hidden = document.getElementById('regPokemonId');
  if (hidden?.value && String(value || '') !== pokemonLinkDisplayValue(hidden.value)) hidden.value = '';
  if (field) field.classList.remove('field-error');
  document.getElementById('regPokemonError')?.classList.add('hidden');
  if (results) results.innerHTML = renderPokemonLinkSearchResults(value);
}

function choosePokemonLink(id) {
  const hidden = document.getElementById('regPokemonId');
  const field = document.getElementById('regPokemonSearch');
  const results = document.getElementById('regPokemonResults');
  if (!hidden || !field) return;
  hidden.value = String(Number(id) || '');
  field.value = pokemonLinkDisplayValue(id);
  field.classList.remove('field-error');
  document.getElementById('regPokemonError')?.classList.add('hidden');
  if (results) results.innerHTML = renderPokemonLinkSearchResults('', id);
}

function clearPokemonLinkSelection() {
  const hidden = document.getElementById('regPokemonId');
  const field = document.getElementById('regPokemonSearch');
  if (hidden) hidden.value = '';
  if (field) {
    field.value = '';
    field.focus();
  }
  updatePokemonLinkSearch('');
}

function registrationField(label, control, extraClass = '') {
  return `<label class="registration-field${extraClass ? ` ${extraClass}` : ''}"><span>${esc(label)}</span>${control}</label>`;
}

function registrationToggle(id, label, checked) {
  return `<label class="toggle-card"><input id="${id}" type="checkbox" ${checked ? 'checked' : ''}><span class="toggle-ui"></span><strong>${esc(label)}</strong></label>`;
}

function filteredPokedexForUi() {
  const stats = buildPokemonStats();
  const query = normalize(ui.dexQuery);
  const result = pokedex.filter(item => {
    const owned = stats.get(item.id).copies > 0;
    if (ui.dexRegion !== 'all' && item.region !== ui.dexRegion) return false;
    if (ui.dexType !== 'all' && !item.types.includes(ui.dexType)) return false;
    if (ui.dexStatus === 'owned' && !owned) return false;
    if (ui.dexStatus === 'missing' && owned) return false;
    return !query || normalize(`${item.name} ${item.id}`).includes(query);
  });
  if (ui.dexSort === 'name') result.sort((a,b) => a.name.localeCompare(b.name,'pt-BR'));
  else if (ui.dexSort === 'owned') result.sort((a,b) => stats.get(b.id).copies-stats.get(a.id).copies || a.id-b.id);
  else result.sort((a,b) => a.id-b.id);
  const grouped = new Map();
  for (const item of result) {
    if (!grouped.has(item.region)) grouped.set(item.region, []);
    grouped.get(item.region).push(item);
  }
  return { result, grouped, stats };
}

function renderPokedexSearchResults() {
  const { result, stats } = filteredPokedexForUi();
  const visible = result.slice(0, ui.dexLimit);
  const grouped = new Map();
  for (const item of visible) {
    if (!grouped.has(item.region)) grouped.set(item.region, []);
    grouped.get(item.region).push(item);
  }
  return result.length
    ? `${REGION_ORDER.filter(region=>grouped.has(region)).map(region => renderRegion(region, grouped.get(region), stats)).join('')}
      ${visible.length < result.length ? `<button class="load-more" onclick="ui.dexLimit+=180;refreshSearchResults('dexQuery', true)">Mostrar mais ${Math.min(180, result.length-visible.length)} Pokémon</button>` : ''}`
    : '<div class="empty"><strong>Nenhum Pokémon encontrado</strong>Altere os filtros para continuar.</div>';
}

function renderPokedex() {
  if (ui.selectedPokemon) return renderPokemonDetail(ui.selectedPokemon);
  const stats = buildPokemonStats();
  const ownedCount = pokedex.filter(item => stats.get(item.id).copies > 0).length;
  const types = [...new Set(pokedex.flatMap(item => item.types))].sort((a,b)=>a.localeCompare(b,'pt-BR'));
  return `<section class="screen">
    <h2 class="screen-title">Pokédex</h2>
    <p class="screen-subtitle">${ownedCount} de ${pokedex.length} Pokémon têm cartas na sua coleção. Os que faltam aparecem transparentes.</p>
    <div class="toolbar">
      <input id="dexSearchInput" class="field search" value="${esc(ui.dexQuery)}" placeholder="Buscar Pokémon por nome ou número"
        oncompositionstart="this.dataset.composing='1'"
        oncompositionend="this.dataset.composing='';searchAndRender('dexQuery', this.value, 'dexSearchInput')"
        oninput="searchAndRender('dexQuery', this.value, 'dexSearchInput')">
      <div class="filter-grid">
        <select class="field" onchange="ui.dexRegion=this.value;ui.dexLimit=180;render()">
          <option value="all">Todas as regiões</option>${REGION_ORDER.slice(0,-1).map(region=>option(region,region,ui.dexRegion)).join('')}
        </select>
        <select class="field" onchange="ui.dexType=this.value;ui.dexLimit=180;render()">
          <option value="all">Todos os tipos</option>${types.map(type=>option(type,type,ui.dexType)).join('')}
        </select>
        <select class="field" onchange="ui.dexStatus=this.value;ui.dexLimit=180;render()">
          ${option('all','Tenho e faltam',ui.dexStatus)}${option('owned','Somente tenho',ui.dexStatus)}${option('missing','Somente faltam',ui.dexStatus)}
        </select>
        <select class="field" onchange="ui.dexSort=this.value;ui.dexLimit=180;render()">
          ${option('number','Número da Pokédex',ui.dexSort)}${option('name','Nome',ui.dexSort)}${option('owned','Mais cartas',ui.dexSort)}
        </select>
      </div>
    </div>
    <div id="dexSearchResults">${renderPokedexSearchResults()}</div>
  </section>`;
}

function renderRegion(region, items, stats) {
  const owned = items.filter(item => stats.get(item.id).copies > 0).length;
  return `<section class="region-section">
    <div class="region-heading"><h3>${esc(region)}</h3><span>${owned} com cartas · ${items.length} exibidos</span></div>
    <div class="pokemon-grid">${items.map(item => renderPokemonTile(item, stats.get(item.id))).join('')}</div>
  </section>`;
}

function renderPokemonTile(item, stat) {
  const owned = stat.copies > 0;
  // Começa com o sprite local (instantâneo, funciona sem internet) e o
  // arte3d.js troca pela arte 3D quando o quadradinho entra na tela.
  return `<button class="pokemon-tile ${owned ? '' : 'missing'}" onclick="openPokemon(${item.id})">
    ${owned ? `<span class="pokemon-owned-count">${stat.copies}</span>` : ''}
    <img src="${esc(item.sprite)}" loading="lazy" alt="${esc(item.name)}" data-arte3d="${Number(item.id)}">
    <span class="pokemon-number">Nº ${String(item.id).padStart(4,'0')}</span>
    <span class="pokemon-name">${esc(item.name)}</span>
  </button>`;
}

function openPokemon(id) {
  ui.selectedPokemon = Number(id);
  render();
  window.scrollTo(0,0);
}

function renderPokemonDetail(id) {
  const pokemon = pokemonMap.get(Number(id));
  if (!pokemon) { ui.selectedPokemon = null; return renderPokedex(); }
  const stat = buildPokemonStats().get(pokemon.id);
  const related = cards.filter(card => pokemonIdsForCard(card).includes(pokemon.id))
    .sort((a,b) => quantityFor(b.id)-quantityFor(a.id) || a.setName.localeCompare(b.setName,'pt-BR') || numericLocal(a)-numericLocal(b));
  return `<section class="screen">
    <button class="back-btn" onclick="ui.selectedPokemon=null;render();window.scrollTo(0,0)">← Voltar à Pokédex</button>
    <div class="pokemon-hero">
      <!-- Faltava o data-arte3d aqui: a tela de detalhe era a única que ficava
           com o sprite embutido de 96px esticado, a maior ampliação do app. -->
      <img src="${esc(pokemon.sprite)}" alt="${esc(pokemon.name)}" data-arte3d="${Number(pokemon.id)}">
      <div><span class="pokemon-number">Nº ${String(pokemon.id).padStart(4,'0')} · ${esc(pokemon.region)}</span><h2>${esc(pokemon.name)}</h2><div class="badges">${pokemon.types.map(type=>`<span class="badge">${esc(type)}</span>`).join('')}</div></div>
    </div>
    <div class="stats-grid">
      ${statCard(stat.copies,'Cartas no fichário')}
      ${statCard(stat.cardIds.size,'Cartas únicas')}
    </div>
    <h3 class="section-title">Todas as cartas de ${esc(pokemon.name)}</h3>
    <p class="screen-subtitle">As suas aparecem primeiro e totalmente visíveis. Você pode atualizar a quantidade aqui mesmo.</p>
    <div class="card-list">${related.length ? related.map(renderCardRow).join('') : '<div class="empty">Nenhuma carta desse Pokémon foi encontrada no catálogo atual.</div>'}</div>
  </section>`;
}

function ownedDeckPool() {
  return cards.map(card => ({ card, owned: quantityFor(card.id) }))
    .filter(item => item.owned > 0);
}

function deckCardClass(card) {
  const name = normalize(card?.name);
  const category = normalize(card?.category || '');
  // Quando a fonte diz o que a carta é, essa resposta vale mais do que
  // qualquer palpite pelo nome.
  if (category === 'energy' || category === 'energia') return 'energy';
  if (category === 'trainer' || category === 'treinador') return 'trainer';
  if (category === 'pokemon') return 'pokemon';
  // O vínculo 1026 significa apenas “não representa um Pokémon” e inclui
  // Ferramentas. Ele não pode ser usado para classificar a carta como Energia.
  if (/^energia(?:\s|$)/.test(name) || /^(?:basic|double|special)\s+.+\s+energy$/.test(name)) return 'energy';
  if (pokemonIdsForCard(card).length) return 'pokemon';
  return 'trainer';
}

function deckCardTypes(card) {
  const types = new Set();
  for (const pokemonId of pokemonIdsForCard(card)) {
    const pokemon = pokemonMap.get(Number(pokemonId));
    for (const type of (pokemon?.types || [])) types.add(type);
  }
  return [...types];
}

function deckNameKey(card) {
  return normalize(card?.name || '');
}

function deckCardLimit(card) {
  return deckCardClass(card) === 'energy' ? 60 : 4;
}

function deckTotal(deck) {
  return Object.values(deck?.cards || {}).reduce((sum, value) => sum + Math.max(0, Number(value) || 0), 0);
}

// Subgrupo exato da carta. Depende de `category`/`trainerType` gravados no
// catálogo por scripts/enriquecer-catalogo.mjs. Sem esses campos o app não
// consegue distinguir Item de Apoiador e cai em 'trainer' genérico.
const DECK_GROUP_LABELS = {
  pokemon: 'Pokémon',
  item: 'Itens',
  supporter: 'Apoiadores',
  tool: 'Ferramentas',
  stadium: 'Estádios',
  trainer: 'Treinadores (tipo não identificado)',
  energy: 'Energias',
};
const TRAINER_TYPE_GROUPS = {
  item: 'item',
  supporter: 'supporter',
  tool: 'tool',
  stadium: 'stadium',
  'technical machine': 'item',
};

function deckCardGroup(card) {
  const base = deckCardClass(card);
  if (base !== 'trainer') return base;
  const trainerType = normalize(card?.trainerType || '');
  return TRAINER_TYPE_GROUPS[trainerType] || 'trainer';
}

function catalogHasTrainerTypes() {
  for (const card of cardMap.values()) if (card?.trainerType) return true;
  return false;
}

function deckBreakdown(deck) {
  const result = { pokemon: 0, item: 0, supporter: 0, tool: 0, stadium: 0, trainer: 0, energy: 0 };
  for (const [cardId, qty] of Object.entries(deck?.cards || {})) {
    const card = cardMap.get(cardId);
    if (card) result[deckCardGroup(card)] += Math.max(0, Number(qty) || 0);
  }
  // `trainer` agregado mantém compatibilidade com as telas já existentes.
  result.trainerTotal = result.item + result.supporter + result.tool + result.stadium + result.trainer;
  return result;
}

function deckNameQuantity(deck, card, ignoreCardId = null) {
  const key = deckNameKey(card);
  return Object.entries(deck?.cards || {}).reduce((sum, [cardId, qty]) => {
    if (cardId === ignoreCardId) return sum;
    const other = cardMap.get(cardId);
    return sum + (other && deckNameKey(other) === key ? Math.max(0, Number(qty) || 0) : 0);
  }, 0);
}

function deckStrengthScore(card, preferredType = '') {
  const name = normalize(card?.name);
  let score = 1;
  if (/\bex\b|vmax|vstar|\bgx\b/.test(name)) score += 14;
  else if (/\bv\b|break|prime|level x/.test(name)) score += 9;
  if (/ordens do chefe|boss|pesquisa de professores|professor|ultra bola|bola ninho|ninho|captura|doce raro|rare candy|troca|switch|recuperacao|recuperação/.test(name)) score += 8;
  if (/energia|energy/.test(name)) score += 3;
  if (preferredType && deckCardTypes(card).includes(preferredType)) score += 10;
  score += Math.min(4, quantityFor(card.id));
  return score;
}

function deckValidation(deck) {
  const errors = [];
  const warnings = [];
  const total = deckTotal(deck);
  const split = deckBreakdown(deck);
  if (total !== 60) errors.push(`O deck precisa ter exatamente 60 cartas; atualmente tem ${total}.`);
  if (!split.pokemon) errors.push('O deck precisa ter pelo menos um Pokémon.');
  if (!split.energy) warnings.push('Nenhuma Energia foi encontrada no deck.');
  if (split.pokemon < 10) warnings.push('Poucos Pokémon: o deck pode ter dificuldade para iniciar a partida.');
  if (split.trainerTotal < 20) warnings.push('Poucos Treinadores: o deck pode ficar inconsistente.');
  const byName = new Map();
  for (const [cardId, qtyRaw] of Object.entries(deck?.cards || {})) {
    const card = cardMap.get(cardId);
    const qty = Math.max(0, Number(qtyRaw) || 0);
    if (!card || !qty) continue;
    if (qty > quantityFor(cardId)) errors.push(`${card.name}: o deck usa ${qty}, mas você possui ${quantityFor(cardId)}.`);
    if (deckCardClass(card) !== 'energy') {
      const key = deckNameKey(card);
      byName.set(key, { name: card.name, qty: (byName.get(key)?.qty || 0) + qty });
    }
  }
  for (const item of byName.values()) if (item.qty > 4) errors.push(`${item.name}: máximo de 4 cópias somando todas as versões.`);
  const score = Math.max(0, Math.min(100, 100 - errors.length * 25 - warnings.length * 7 + (total === 60 ? 10 : 0)));
  return { valid: !errors.length, errors, warnings, score, total, split };
}

function addDeckCardQuantity(target, cardId, wanted) {
  const card = cardMap.get(cardId);
  if (!card) return 0;
  const available = quantityFor(cardId);
  const current = Math.max(0, Number(target[cardId]) || 0);
  const sameNameElsewhere = deckCardClass(card) === 'energy' ? 0 : deckNameQuantity({ cards: target }, card, cardId);
  const nameLimit = deckCardClass(card) === 'energy' ? 60 : Math.max(0, 4 - sameNameElsewhere);
  const allowed = Math.min(available, deckCardLimit(card), nameLimit);
  const room = Math.max(0, 60 - Object.values(target).reduce((a,b)=>a+Math.max(0,Number(b)||0),0));
  const add = Math.max(0, Math.min(Number(wanted) || 0, allowed - current, room));
  if (add) target[cardId] = current + add;
  return add;
}

function availableDeckTypes() {
  const counts = new Map();
  for (const item of ownedDeckPool()) for (const type of deckCardTypes(item.card)) counts.set(type, (counts.get(type) || 0) + item.owned);
  return [...counts.entries()].sort((a,b)=>b[1]-a[1] || a[0].localeCompare(b[0],'pt-BR'));
}

function generateStrongDeck() {
  const pool = ownedDeckPool();
  if (!pool.length) return notify('Cadastre cartas antes de gerar um deck.');
  const preferredType = document.getElementById('deckPreferredType')?.value || '';
  const groups = { pokemon: [], trainer: [], energy: [] };
  pool.forEach(item => groups[deckCardClass(item.card)].push(item));
  Object.values(groups).forEach(list => list.sort((a,b) => deckStrengthScore(b.card, preferredType)-deckStrengthScore(a.card, preferredType) || b.owned-a.owned || a.card.name.localeCompare(b.card.name,'pt-BR')));
  if (preferredType) groups.pokemon.sort((a,b) => Number(deckCardTypes(b.card).includes(preferredType))-Number(deckCardTypes(a.card).includes(preferredType)) || deckStrengthScore(b.card,preferredType)-deckStrengthScore(a.card,preferredType));
  const target = {};
  const fill = (list, desired) => {
    let added = 0;
    for (const item of list) {
      if (added >= desired || deckTotal({cards:target}) >= 60) break;
      added += addDeckCardQuantity(target, item.card.id, Math.min(item.owned, desired-added));
    }
    return added;
  };
  fill(groups.pokemon, 16);
  fill(groups.trainer, 32);
  fill(groups.energy, 12);
  const all = [...groups.trainer, ...groups.pokemon, ...groups.energy];
  for (const item of all) {
    if (deckTotal({cards:target}) >= 60) break;
    addDeckCardQuantity(target, item.card.id, 60-deckTotal({cards:target}));
  }
  state.decks = state.decks || [];
  const deck = { id: `deck-${Date.now()}`, name: `Deck forte ${preferredType || state.decks.length + 1}`, cards: target, preferredType, createdAt: new Date().toISOString(), generated: true };
  state.decks.push(deck);
  selectedDeckId = deck.id;
  saveState(); render();
  const validation = deckValidation(deck);
  notify(validation.valid ? 'Deck válido de 60 cartas criado.' : `Deck criado com ${validation.total} cartas. Confira os avisos.`);
}

function renderDeckCardRow(deck, cardId, qty) {
  const card = cardMap.get(cardId);
  if (!card) return '';
  const owned = quantityFor(cardId);
  return `<div class="deck-card-row">
    <div class="deck-card-art">
      ${card.imageUrl
        ? `<img class="deck-card-image" src="${esc(card.imageUrl)}" alt="${esc(card.name)}" onerror="this.style.display='none';this.nextElementSibling.style.display='flex'">`
        : ''}
      <div class="deck-card-placeholder" style="${card.imageUrl ? 'display:none' : 'display:flex'}">Buscando arte…</div>
    </div>
    <div class="deck-card-info"><strong>${esc(card.name)}</strong><span>${esc(card.number)} · ${esc(card.setName)} · você tem ${owned}</span></div>
    <div class="deck-qty"><button onclick="changeDeckCard('${esc(deck.id)}','${esc(cardId)}',-1)">−</button><b>${qty}</b><button onclick="changeDeckCard('${esc(deck.id)}','${esc(cardId)}',1)">+</button></div>
  </div>`;
}

function renderDeckEditor(deck) {
  const report = deckValidation(deck);
  const entries = Object.entries(deck.cards || {}).filter(([,q]) => Number(q)>0).sort((a,b)=>deckCardClass(cardMap.get(a[0])).localeCompare(deckCardClass(cardMap.get(b[0]))) || (cardMap.get(a[0])?.name||'').localeCompare(cardMap.get(b[0])?.name||'','pt-BR'));
  const messages = [...report.errors.map(x=>`<li class="deck-error">${esc(x)}</li>`), ...report.warnings.map(x=>`<li class="deck-warning">${esc(x)}</li>`)].join('');
  return `<section class="screen">
    <button class="back-btn" onclick="selectedDeckId=null;render()">← Voltar aos decks</button>
    <div class="deck-editor-head"><div><h2 class="screen-title">${esc(deck.name)}</h2><p class="screen-subtitle">Edite usando somente as cartas que você possui.</p></div><button class="danger-btn compact-btn" onclick="deleteDeck('${esc(deck.id)}')">Excluir</button></div>
    <div class="deck-summary ${report.valid?'valid':'invalid'}"><strong>${report.total}/60 cartas · força ${report.score}/100</strong><span>${report.split.pokemon} Pokémon · ${report.split.trainerTotal} Treinadores · ${report.split.energy} Energias</span><small>${report.valid?'Deck validado para batalha.':'Ainda existem ajustes necessários.'}</small></div>
    ${messages ? `<ul class="deck-validation">${messages}</ul>` : ''}
    ${deckImprovementPanel(deck)}
    <div class="deck-actions"><button class="secondary-btn" onclick="renameDeck('${esc(deck.id)}')">Renomear</button><button class="secondary-btn" onclick="duplicateDeck('${esc(deck.id)}')">Duplicar</button><button class="secondary-btn" onclick="exportDeckList('${esc(deck.id)}')">Exportar lista</button></div>
    <div class="deck-search"><input id="deckCardSearch" class="field" placeholder="Buscar nas minhas cartas"><button class="primary-btn" onclick="openDeckCardPicker('${esc(deck.id)}')">Adicionar carta</button></div>
    <div class="deck-card-list">${entries.length ? entries.map(([id,q])=>renderDeckCardRow(deck,id,q)).join('') : '<div class="empty">Este deck ainda está vazio.</div>'}</div>
  </section>`;
}

function renderDecks() {
  const decks = state.decks || [];
  const selected = decks.find(deck => deck.id === selectedDeckId);
  if (selected) return renderDeckEditor(selected);
  const types = availableDeckTypes();
  return `<section class="screen">
    <h2 class="screen-title">Decks</h2>
    <p class="screen-subtitle">Monte um baralho de 60 cartas usando apenas o que existe no seu fichário. O gerador prioriza Pokémon do tipo escolhido e cartas de suporte.</p>
    <div class="deck-generator"><select id="deckPreferredType" class="field"><option value="">Melhor combinação geral</option>${types.map(([type,count])=>`<option value="${esc(type)}">Foco ${esc(type)} (${count} cópias)</option>`).join('')}</select><button class="primary-btn" onclick="generateStrongDeck()">⚔️ Montar deck forte</button></div>
    <button class="primary-btn deck-auto-btn" onclick="openAutoBuilder()">🧠 Montador avançado — 3 opções analisadas</button>
    <p class="deck-auto-note">O montador avançado simula milhares de mãos iniciais, completa linhas evolutivas, confere energia compatível e pontua legalidade, consistência e velocidade de setup, priorizando o que você já possui.</p>
    <div class="deck-row"><input id="deckName" class="field" placeholder="Nome do novo deck"><button class="primary-btn" onclick="addDeck()">Criar vazio</button></div>
    <div class="set-list">${decks.length ? decks.map(deck => { const report=deckValidation(deck); return `<button class="panel deck-panel" onclick="selectedDeckId='${esc(deck.id)}';render()"><div class="set-title-row"><span class="set-name">${esc(deck.name)}</span><span class="badge ${report.valid?'owned':''}">${report.total}/60</span></div><p class="card-meta">${report.split.pokemon} Pokémon · ${report.split.trainerTotal} Treinadores · ${report.split.energy} Energias · força ${report.score}/100</p></button>`; }).join('') : '<div class="empty"><strong>Nenhum deck criado</strong>Use o gerador automático ou crie um deck vazio.</div>'}</div>
  </section>`;
}

// Faixas de referência de construção para um deck de 60 cartas. São
// convenções consolidadas do formato, não regra oficial — por isso aparecem
// como sugestão, nunca como erro de validação.
const DECK_TARGETS = {
  pokemon: { min: 12, max: 20, label: 'Pokémon' },
  supporter: { min: 8, max: 16, label: 'Apoiadores' },
  item: { min: 12, max: 26, label: 'Itens' },
  energy: { min: 8, max: 15, label: 'Energias' },
};

function ownedCardsByGroup(group, excludeDeck = null) {
  const used = excludeDeck?.cards || {};
  const found = [];
  for (const cardId of Object.keys(state?.entries || {})) {
    const card = cardMap.get(cardId);
    if (!card || deckCardGroup(card) !== group) continue;
    const free = quantityFor(cardId) - Math.max(0, Number(used[cardId]) || 0);
    if (free > 0) found.push({ card, free });
  }
  return found.sort((a, b) => b.free - a.free);
}

/**
 * Aponta melhorias concretas comparando o deck com as faixas de referência e
 * com o que existe na coleção. Só sugere cartas que o usuário realmente possui
 * e que ainda não estão totalmente alocadas neste deck.
 */
function deckImprovementReport(deck) {
  const split = deckBreakdown(deck);
  const total = deckTotal(deck);
  const suggestions = [];
  const unknownTrainers = split.trainer;

  if (total < 60) {
    suggestions.push({ level: 'gap', text: `Faltam ${60 - total} cartas para fechar o deck de 60.` });
  } else if (total > 60) {
    suggestions.push({ level: 'gap', text: `Há ${total - 60} cartas além do limite de 60.` });
  }

  for (const [group, target] of Object.entries(DECK_TARGETS)) {
    const count = split[group] || 0;
    if (count >= target.min && count <= target.max) continue;
    const short = count < target.min;
    const options = short ? ownedCardsByGroup(group, deck).slice(0, 4) : [];
    suggestions.push({
      level: short ? 'low' : 'high',
      group,
      text: short
        ? `${target.label}: ${count} no deck, abaixo da faixa usual de ${target.min}–${target.max}.`
        : `${target.label}: ${count} no deck, acima da faixa usual de ${target.min}–${target.max}.`,
      options,
    });
  }

  // Um deck sem Pokémon Básico simplesmente não abre a partida.
  let basics = 0;
  for (const [cardId, qty] of Object.entries(deck?.cards || {})) {
    const card = cardMap.get(cardId);
    if (!card || deckCardGroup(card) !== 'pokemon') continue;
    const speciesId = pokemonIdsForCard(card)[0];
    const evolvesFrom = window.__POKEMON_EVOLVES_FROM__?.[speciesId];
    if (!evolvesFrom) basics += Math.max(0, Number(qty) || 0);
  }
  if (!basics) {
    suggestions.push({ level: 'critical', text: 'Nenhum Pokémon Básico identificado: o deck não consegue começar a partida.' });
  } else if (basics < 6) {
    suggestions.push({ level: 'low', text: `Apenas ${basics} Pokémon Básicos — o risco de mão inicial sem Básico fica alto.` });
  }

  return { split, total, basics, unknownTrainers, suggestions };
}

function deckImprovementPanel(deck) {
  const report = deckImprovementReport(deck);
  const enriched = catalogHasTrainerTypes();
  const groups = ['pokemon', 'item', 'supporter', 'tool', 'stadium', 'trainer', 'energy']
    .filter(group => report.split[group] > 0)
    .map(group => `<span>${esc(DECK_GROUP_LABELS[group])}: <strong>${report.split[group]}</strong></span>`)
    .join('');

  const items = report.suggestions.map(item => {
    const options = (item.options || []).length
      ? `<div class="deck-suggestion-options">${item.options.map(option =>
          `<button type="button" onclick="addCardToDeck('${esc(deck.id)}','${esc(option.card.id)}')">+ ${esc(option.card.name)} <small>(${option.free} livre${option.free > 1 ? 's' : ''})</small></button>`
        ).join('')}</div>`
      : '';
    return `<li class="deck-suggestion ${esc(item.level)}">${esc(item.text)}${options}</li>`;
  }).join('');

  return `<section class="deck-analysis">
    <h3>Análise de balanceamento</h3>
    <div class="deck-analysis-split">${groups || '<span>Deck vazio</span>'}</div>
    ${!enriched ? '<div class="deck-data-warning">O catálogo ainda não tem os tipos de Treinador. Rode <code>node scripts/enriquecer-catalogo.mjs</code> para separar Itens, Apoiadores, Ferramentas e Estádios.</div>' : ''}
    ${report.unknownTrainers ? `<div class="deck-data-warning">${report.unknownTrainers} carta(s) de Treinador sem tipo identificado no catálogo.</div>` : ''}
    ${items ? `<ul class="deck-suggestions">${items}</ul>` : '<p class="deck-suggestion ok">Distribuição dentro das faixas usuais de construção.</p>'}
    <small class="deck-analysis-note">As faixas são convenções de construção do formato, não regra oficial. Só são sugeridas cartas que você possui e que ainda estão livres neste deck.</small>
  </section>`;
}

function addCardToDeck(deckId, cardId) {
  const deck = (state.decks || []).find(item => item.id === deckId);
  const card = cardMap.get(cardId);
  if (!deck || !card) return;
  const current = Math.max(0, Number(deck.cards?.[cardId]) || 0);
  if (current >= quantityFor(cardId)) return notify(`Você já usou todas as suas cópias de ${card.name}.`);
  if (deckTotal(deck) >= 60) return notify('O deck já tem 60 cartas.');
  deck.cards = deck.cards || {};
  deck.cards[cardId] = current + 1;
  saveState();
  renderKeepingScroll();
  notify(`${card.name} adicionada ao deck.`);
}

function addDeck() {
  const input = document.getElementById('deckName');
  const name = input?.value.trim();
  if (!name) return notify('Digite um nome para o deck');
  state.decks = state.decks || [];
  const deck = { id: `deck-${Date.now()}`, name, cards: {}, createdAt: new Date().toISOString() };
  state.decks.push(deck); selectedDeckId = deck.id;
  saveState(); render(); notify('Deck criado');
}

function deleteDeck(id) {
  state.decks = (state.decks || []).filter(deck => deck.id !== id);
  if (selectedDeckId === id) selectedDeckId = null;
  saveState(); render();
}

function renameDeck(id) {
  const deck = (state.decks || []).find(item => item.id === id);
  if (!deck) return;
  const name = prompt('Novo nome do deck:', deck.name);
  if (!name?.trim()) return;
  deck.name = name.trim(); saveState(); render();
}

function duplicateDeck(id) {
  const source = (state.decks || []).find(item => item.id === id);
  if (!source) return;
  const copy = { ...source, id:`deck-${Date.now()}`, name:`${source.name} (cópia)`, cards:{...(source.cards||{})}, createdAt:new Date().toISOString(), generated:false };
  state.decks.push(copy); selectedDeckId=copy.id; saveState(); render(); notify('Deck duplicado.');
}

function exportDeckList(id) {
  const deck = (state.decks || []).find(item => item.id === id);
  if (!deck) return;
  const lines = Object.entries(deck.cards || {}).filter(([,q])=>Number(q)>0).map(([cardId,qty])=>{const card=cardMap.get(cardId);return card?`${qty}x ${card.name} — ${card.setName} ${card.number}`:''}).filter(Boolean);
  const text = `${deck.name}\n\n${lines.join('\n')}\n\nTotal: ${deckTotal(deck)} cartas`;
  if (navigator.share) navigator.share({title:deck.name,text}).catch(()=>{});
  else if (navigator.clipboard) navigator.clipboard.writeText(text).then(()=>notify('Lista copiada.')).catch(()=>showModal(`<pre>${esc(text)}</pre>`));
  else showModal(`<button class="modal-close" onclick="closeModal()">×</button><h2>${esc(deck.name)}</h2><pre>${esc(text)}</pre>`);
}

function changeDeckCard(deckId, cardId, delta) {
  const deck = (state.decks || []).find(item => item.id === deckId);
  const card = cardMap.get(cardId);
  if (!deck || !card) return;
  deck.cards = deck.cards || {};
  const current = Math.max(0, Number(deck.cards[cardId]) || 0);
  const sameNameElsewhere = deckCardClass(card) === 'energy' ? 0 : deckNameQuantity(deck, card, cardId);
  const nameLimit = deckCardClass(card) === 'energy' ? 60 : Math.max(0, 4-sameNameElsewhere);
  const max = Math.min(quantityFor(cardId), deckCardLimit(card), nameLimit);
  const roomMax = current + Math.max(0, 60-deckTotal(deck));
  const next = Math.max(0, Math.min(max, roomMax, current + Number(delta || 0)));
  if (next) deck.cards[cardId] = next; else delete deck.cards[cardId];
  if (delta > 0 && next === current) notify(deckTotal(deck)>=60 ? 'O deck já tem 60 cartas.' : 'Limite de cópias atingido.');
  saveState(); render();
}

function openDeckCardPicker(deckId) {
  const deck = (state.decks || []).find(item => item.id === deckId);
  if (!deck) return;
  const query = normalize(document.getElementById('deckCardSearch')?.value || '');
  const pool = ownedDeckPool().filter(item => !query || normalize(`${item.card.name} ${item.card.number} ${item.card.setName}`).includes(query)).sort((a,b)=>deckStrengthScore(b.card,deck.preferredType)-deckStrengthScore(a.card,deck.preferredType)).slice(0,160);
  showModal(`<button class="modal-close" onclick="closeModal()">×</button><h2>Adicionar carta ao deck</h2><p class="screen-subtitle">Respeita sua quantidade, o limite de 4 cópias pelo mesmo nome e o máximo de 60 cartas.</p><div class="deck-picker">${pool.length ? pool.map(item=>`<button onclick="changeDeckCard('${esc(deckId)}','${esc(item.card.id)}',1);closeModal()"><strong>${esc(item.card.name)}</strong><span>${esc(item.card.number)} · ${esc(item.card.setName)} · você tem ${item.owned} · ${deckCardClass(item.card)}</span></button>`).join('') : '<div class="empty">Nenhuma carta encontrada.</div>'}</div>`);
}


function option(value, label, selected) {
  return `<option value="${esc(value)}" ${value === selected ? 'selected' : ''}>${esc(label)}</option>`;
}

let searchRenderTimer = null;

function setScrollingPerformanceMode() {
  document.documentElement.classList.add('is-scrolling');
  clearTimeout(scrollIdleTimer);
  scrollIdleTimer = setTimeout(() => {
    document.documentElement.classList.remove('is-scrolling');
  }, 140);
}

window.addEventListener('scroll', setScrollingPerformanceMode, { passive: true });
window.addEventListener('touchmove', setScrollingPerformanceMode, { passive: true });

function pumpImagePreloadQueue() {
  while (imagePreloadActive < IMAGE_PRELOAD_MAX && imagePreloadQueue.length) {
    const url = imagePreloadQueue.shift();
    if (!url) continue;
    imagePreloadActive++;
    const image = new Image();
    const done = () => {
      imagePreloadActive = Math.max(0, imagePreloadActive - 1);
      pumpImagePreloadQueue();
    };
    image.onload = done;
    image.onerror = done;
    image.decoding = 'async';
    image.src = url;
  }
}

function scheduleVisibleImagePreload(cardsToPreload) {
  const urls = (cardsToPreload || [])
    .map(card => card?.imageUrl)
    .filter(Boolean)
    .slice(0, IMAGE_PRELOAD_AHEAD);
  if (!urls.length) return;
  imagePreloadQueue = [...new Set([...imagePreloadQueue, ...urls])].slice(0, 40);
  const run = () => pumpImagePreloadQueue();
  if ('requestIdleCallback' in window) requestIdleCallback(run, { timeout: 700 });
  else setTimeout(run, 80);
}

function searchResultsTarget(field) {
  if (field === 'setQuery') return ['setSearchResults', renderSetSearchResults];
  if (field === 'cardQuery') return ['cardSearchResults', renderCardSearchResults];
  if (field === 'dexQuery') return ['dexSearchResults', renderPokedexSearchResults];
  return [null, null];
}

function refreshSearchResults(field, keepScroll = false) {
  const labStart = performance.now();
  const [targetId, renderer] = searchResultsTarget(field);
  const target = targetId ? document.getElementById(targetId) : null;
  if (!target || typeof renderer !== 'function') return;
  const y = window.scrollY;
  const html = renderer();
  requestAnimationFrame(() => {
    target.innerHTML = html;
    if (keepScroll) window.scrollTo(0, y);
    labRecord('atualizar_busca', performance.now() - labStart, { field, htmlLength: html.length });
  });
}

function searchAndRender(field, value, inputId) {
  ui[field] = value;
  if (field === 'cardQuery') {
    ui.cardLimit = 40;
    cardResultCache.key = '';
  }
  if (field === 'dexQuery') ui.dexLimit = 180;
  const input = document.getElementById(inputId);
  if (input?.dataset.composing === '1') return;
  clearTimeout(searchRenderTimer);
  searchRenderTimer = setTimeout(() => refreshSearchResults(field), field === 'cardQuery' ? 250 : 100);
}

function showModal(html, className = '') {
  const sheet = document.getElementById('modal-content');
  sheet.className = `modal-sheet ${className}`.trim();
  sheet.innerHTML = html;
  document.getElementById('modal').classList.remove('hidden');
}

function closeModal() {
  document.getElementById('modal').classList.add('hidden');
  const sheet = document.getElementById('modal-content');
  sheet.innerHTML = '';
  sheet.className = 'modal-sheet';
}

function openBackupPanel() {
  const summary = collectionSummary();
  showModal(`
    <button class="modal-close" onclick="closeModal()">×</button>
    <h2>Backup da coleção</h2>
    <p class="screen-subtitle">Salve uma cópia para não depender da assinatura ou da instalação do aplicativo.</p>
    <div class="panel"><strong>${summary.uniqueOwned} cartas únicas · ${summary.totalCopies} cartas no total</strong><p class="card-meta">Inclui quantidades, wishlist e decks desta nova versão.</p></div>
    <div class="backup-actions">
      <button class="primary-btn" onclick="exportBackup()">Exportar backup</button>
      <button class="secondary-btn" onclick="importBackup()">Importar backup</button>
      <button class="secondary-btn" onclick="closeModal();openLigaExportPanel()">Exportar p/ Liga Pokémon</button>
      <button class="secondary-btn" onclick="checkForAppUpdate(true)">Verificar atualização</button>
      <button class="secondary-btn lab-open-btn" onclick="openLaboratoryPanel()">⚗ Modo Laboratório</button>
    </div>`);
}

async function exportBackup() {
  try {
    const localImages = await window.FicharioLocalImages?.exportData?.() || [];
    const payload = JSON.stringify({
      format: 'fichario-pokemon-br-plus-backup',
      backupVersion: 2,
      exportedAt: new Date().toISOString(),
      state,
      ligaSetCache,
      localImages,
    }, null, 2);
    if (window.Android?.exportBackup) window.Android.exportBackup(payload);
    else {
      const link = document.createElement('a');
      link.href = URL.createObjectURL(new Blob([payload], {type:'application/json'}));
      link.download = 'fichario-pokemon-backup.json';
      link.click();
    }
  } catch (_) {
    notify('Não foi possível preparar o backup');
  }
}

function importBackup() {
  if (window.Android?.importBackup) window.Android.importBackup();
}

window.receiveImportedBackup = async function(raw) {
  try {
    const payload = JSON.parse(raw);
    if (payload?.format !== 'fichario-pokemon-br-plus-backup' || !payload.state?.entries) throw new Error('Formato inválido');
    const migrated = migrateState(payload.state);
    if (!migrated) throw new Error('Estado inválido');
    state = migrated;
    clearNonDatabaseAutomaticPrices();
    if (payload.ligaSetCache && typeof payload.ligaSetCache === 'object') { ligaSetCache = payload.ligaSetCache; saveLigaSetCache(); }
    let restoredImages = 0;
    if (Array.isArray(payload.localImages)) {
      restoredImages = await window.FicharioLocalImages?.importData?.(payload.localImages, true) || 0;
    }
    saveState();
    closeModal();
    render();
    notify(restoredImages ? `Backup importado · ${restoredImages} imagens restauradas` : 'Backup importado com sucesso');
  } catch (_) {
    notify('Este arquivo não é um backup válido');
  }
};

// --- Exportação para a Liga Pokémon (planilha de venda/troca/desejo) ---
// Formato próprio do Fichário, não confirmado pela ferramenta de importação
// da Liga (que exige login e não é acessível para leitura pública). As
// colunas usam o vocabulário já usado no restante do aplicativo e o mesmo
// código e número de coleção usados no catálogo. O recurso de exportação é
// independente da fonte automática de preços do aplicativo.
const LIGA_EXPORT_HEADERS = ['Tipo', 'Carta', 'Coleção', 'Código Liga', 'Número', 'Idioma', 'Acabamento', 'Condição', 'Edição', 'Distribuição', 'Variação artística', 'Graduação', 'Quantidade', 'Preço unitário (R$)', 'Observações'];

function ligaExportSelectableVariants() {
  const rows = [];
  for (const [cardId, entry] of Object.entries(state?.entries || {})) {
    const card = cardMap.get(cardId);
    if (!card) continue;
    for (const variant of (entry.variants || [])) {
      const quantity = Math.max(0, Math.trunc(Number(variant.quantity) || 0));
      const wanted = Boolean(variant.isWishlist);
      const sellable = (variant.isForSale || variant.isForTrade) && quantity > 0;
      if (!sellable && !wanted) continue;
      const tipo = [
        variant.isForSale ? 'Venda' : '',
        variant.isForTrade ? 'Troca' : '',
        wanted ? 'Desejo' : '',
      ].filter(Boolean).join(' + ');
      rows.push({ cardId, card, variant, quantity, tipo });
    }
  }
  rows.sort((a, b) => a.card.name.localeCompare(b.card.name, 'pt-BR'));
  return rows;
}

async function ligaExportSetCodes(rows) {
  const codes = new Map();
  const setIds = [...new Set(rows.map(row => row.card?.setId).filter(Boolean))];
  for (const setId of setIds) {
    try { codes.set(setId, await ligaSetCode(setId)); }
    catch (_) { codes.set(setId, null); }
  }
  return codes;
}

function ligaExportGrading(variant) {
  const company = String(variant?.gradingCompany || '').trim();
  if (!company || /^n[aã]o\s+gradua/i.test(company)) return '';
  const grade = String(variant?.grade || '').trim();
  return grade ? `${company} ${grade}` : company;
}

function csvPriceBr(value) {
  return hasFiniteNumber(value) ? Number(value).toFixed(2).replace('.', ',') : '';
}

function csvField(value, delimiter = ';') {
  const str = value === null || value === undefined ? '' : String(value);
  return (str.includes(delimiter) || str.includes('"') || str.includes('\n') || str.includes('\r'))
    ? `"${str.replace(/"/g, '""')}"`
    : str;
}

function buildLigaExportCsv(rows, codes = new Map()) {
  const lines = [LIGA_EXPORT_HEADERS.map(header => csvField(header)).join(';')];
  for (const row of rows) {
    const { cardId, card, variant, quantity, tipo } = row;
    const price = effectiveVariantPrice(cardId, variant);
    const values = [
      tipo,
      card.name,
      card.setName || '',
      codes.get(card.setId) || '',
      ligaCardNumber(card).full,
      variant.language || '',
      finishPriceLabel(finishKind(variant.finish)),
      variant.condition || '',
      variant.edition || '',
      variant.distribution || '',
      variant.artVariant && variant.artVariant !== 'standard' ? variant.artVariant : '',
      ligaExportGrading(variant),
      quantity,
      csvPriceBr(price?.brl),
      variant.notes || '',
    ];
    lines.push(values.map(value => csvField(value)).join(';'));
  }
  // BOM UTF-8 + CRLF: abre corretamente acentuação e colunas no Excel/Sheets em pt-BR.
  return '\uFEFF' + lines.join('\r\n') + '\r\n';
}

function openLigaExportPanel() {
  const rows = ligaExportSelectableVariants();
  const forSaleOrTrade = rows.filter(row => row.tipo.includes('Venda') || row.tipo.includes('Troca')).length;
  const wishlist = rows.filter(row => row.tipo.includes('Desejo')).length;
  showModal(`
    <button class="modal-close" onclick="closeModal()">×</button>
    <h2>Exportar para a Liga Pokémon</h2>
    <p class="screen-subtitle">Gera uma planilha (CSV) com as cartas marcadas para venda, troca ou desejo, usando o número e a coleção no formato esperado para organizar os anúncios.</p>
    <div class="panel">
      <strong>${forSaleOrTrade} para venda/troca · ${wishlist} na lista de desejos</strong>
      <p class="card-meta">O nome das colunas segue o vocabulário do Fichário. A Liga pode pedir um cabeçalho levemente diferente dependendo de onde você for colar (Bazar ou Loja Virtual) — não consegui confirmar o modelo exato porque essa área exige login. Abra o CSV e ajuste o cabeçalho se for preciso antes de colar lá.</p>
    </div>
    <div class="backup-actions">
      <button class="primary-btn" onclick="exportLigaPokemonCsv()" ${rows.length ? '' : 'disabled'}>Exportar CSV</button>
      <button class="secondary-btn" onclick="closeModal()">Fechar</button>
    </div>`);
}

async function exportLigaPokemonCsv() {
  const rows = ligaExportSelectableVariants();
  if (!rows.length) {
    notify('Marque cartas para venda, troca ou wishlist antes de exportar.');
    return;
  }
  notify('Preparando planilha da Liga Pokémon...');
  const codes = await ligaExportSetCodes(rows);
  const csv = buildLigaExportCsv(rows, codes);
  const fileName = `pokecard-liga-pokemon-${new Date().toISOString().slice(0, 10)}.csv`;
  try {
    if (window.Android?.exportCsv) window.Android.exportCsv(csv, fileName);
    else {
      const link = document.createElement('a');
      link.href = URL.createObjectURL(new Blob([csv], { type: 'text/csv;charset=utf-8' }));
      link.download = fileName;
      link.click();
    }
  } catch (_) {
    notify('Não foi possível preparar o arquivo.');
  }
}

function markdownToSafeHtml(text) {
  const safe = esc(String(text || ''));
  return safe
    .replace(/^###\s+(.+)$/gm, '<strong>$1</strong>')
    .replace(/^##\s+(.+)$/gm, '<strong>$1</strong>')
    .replace(/^#\s+(.+)$/gm, '<strong>$1</strong>')
    .replace(/^[-*]\s+(.+)$/gm, '✓ $1')
    .replace(/\n/g, '<br>');
}

function checkForAppUpdate(manual = true) {
  if (updateCheckInProgress) return;
  if (!window.Android?.checkForUpdate) {
    if (manual) notify('Verificação disponível apenas no aplicativo Android.');
    return;
  }
  updateCheckInProgress = true;
  if (manual) notify('Verificando atualizações...');
  window.Android.checkForUpdate();
}

window.receiveUpdateInfo = function(raw) {
  updateCheckInProgress = false;
  try {
    const info = typeof raw === 'string' ? JSON.parse(raw) : raw;
    if (!info?.ok) {
      if (info?.error) notify(`Atualização: ${info.error}`);
      return;
    }
    latestUpdateInfo = info;
    if (!info.updateAvailable) {
      if (document.getElementById('modal') && !document.getElementById('modal').classList.contains('hidden')) {
        notify(`Você já está na versão mais recente (${info.currentVersion}).`);
      }
      return;
    }
    showUpdateModal(info);
  } catch (_) {
    notify('Não foi possível interpretar a atualização disponível.');
  }
};

function showUpdateModal(info) {
  showModal(`
    <button class="modal-close" onclick="closeModal()">×</button>
    <div class="update-hero">⬆</div>
    <h2>${esc(info.latestVersion || 'Nova versão disponível')}</h2>
    <p class="screen-subtitle">Instalada: ${esc(info.currentVersion || '')} · atualização assinada e compatível.</p>
    <div class="panel update-notes"><strong>Novidades</strong><p>${markdownToSafeHtml(info.notes || 'Melhorias e correções.')}</p></div>
    <p id="update-status" class="card-meta">O aplicativo baixará o APK oficial publicado no GitHub.</p>
    <div class="backup-actions">
      <button class="primary-btn" onclick="startAppUpdate()">Atualizar agora</button>
      <button class="secondary-btn" onclick="closeModal()">Depois</button>
    </div>`);
}

function startAppUpdate() {
  if (!latestUpdateInfo?.apkUrl || !window.Android?.downloadAndInstallUpdate) {
    notify('Link da atualização indisponível.');
    return;
  }
  const status = document.getElementById('update-status');
  if (status) status.textContent = 'Iniciando download...';
  window.Android.downloadAndInstallUpdate(latestUpdateInfo.apkUrl, latestUpdateInfo.apkName || 'Fichario-Pokemon.apk');
}

window.receiveUpdateDownload = function(success, message) {
  const status = document.getElementById('update-status');
  if (status) status.textContent = message || '';
  if (success === false) notify(message || 'Falha ao baixar atualização.');
};

window.handleAndroidBack = function() {
  const modal = document.getElementById('modal');
  if (modal && !modal.classList.contains('hidden')) { closeModal(); return true; }
  if (ui.tab === 'pokedex' && ui.selectedPokemon) { ui.selectedPokemon = null; render(); return true; }
  if (ui.tab !== 'dashboard') { setTab('dashboard'); return true; }
  return false;
};

init();
