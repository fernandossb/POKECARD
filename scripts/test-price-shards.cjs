const fs = require('fs');
const vm = require('vm');
const assert = require('assert');

(async () => {
  const source = fs.readFileSync('app/src/main/assets/www/app.js', 'utf8');
  const variantStart = source.indexOf('const PRICE_FINISHES =');
  const variantEnd = source.indexOf('function ligaNumberPart(', variantStart);
  const centralStart = source.indexOf('function centralPriceGeneratedAt(');
  const centralEnd = source.indexOf('function centralPriceStatusPanel(', centralStart);
  assert(variantStart >= 0 && variantEnd > variantStart);
  assert(centralStart >= 0 && centralEnd > centralStart);

  const requests = [];
  const context = {
    normalize: value => String(value || '').normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase(),
    hasFiniteNumber: value => value !== null && value !== undefined && value !== '' && Number.isFinite(Number(value)),
    CENTRAL_PRICE_STATUS_URL: 'https://database.test/status.json',
    CENTRAL_PRICE_INDEX_URL: 'https://database.test/card-shard-index.json',
    CENTRAL_PRICE_SHARD_BASE: 'https://database.test/shards',
    CENTRAL_PRICE_SYNC_TTL: 0,
    centralPriceData: { meta: {}, prices: {}, variantCatalog: {} },
    centralPriceStatus: {},
    centralPriceIndex: { meta: {}, cards: {} },
    centralPriceLoadedShards: new Set(),
    centralPriceSyncing: false,
    centralPriceLastCheck: 0,
    lastPriceDiagnostic: '',
    saveCentralPriceCache: async () => true,
    notify: () => {},
    scannerVariantAvailability: new Map(),
    variantsFor: () => [],
    document: { getElementById: () => null },
    scannerSession: { language: 'pt-br' },
    fetchJsonWithTimeout: async url => {
      requests.push(url);
      if (url.includes('status.json')) return {
        schemaVersion: 4, format: 'sharded-v2', status: 'complete', generatedAt: '2026-08-05T20:00:00Z',
        catalogHash: 'hash-1', cardsInCatalog: 1, variantsDiscovered: 2, variantsPriced: 1,
      };
      if (url.includes('card-shard-index.json')) return {
        meta: { schemaVersion: 4, format: 'card-shard-index-v2', generatedAt: '2026-08-05T20:00:00Z', catalogHash: 'hash-1', shardCount: 12 },
        cards: { 'sv03.5-001': 7 },
      };
      if (url.includes('shard-07.json')) return {
        meta: { schemaVersion: 4, format: 'price-shard-v2', shardIndex: 7, shardCount: 12, catalogHash: 'hash-1' },
        variantCatalog: {
          'sv03.5-001': [
            { language: 'pt-br', value: 'reverse-holofoil', sources: ['tcgplayer'], priced: true },
            { language: 'pt-br', value: 'future-no-price', sources: ['tcgdex'], priced: false },
          ],
        },
        prices: {
          'sv03.5-001::pt-br::reverse-holofoil': {
            cardId: 'sv03.5-001', language: 'pt-br', variantEnum: 'reverse-holofoil',
            priceBrl: 12.34, confidence: 75, matchLevel: 'exact', updatedAt: '2026-08-05T20:00:00Z', sources: [],
          },
        },
      };
      throw new Error(`URL inesperada: ${url}`);
    },
  };
  vm.createContext(context);
  vm.runInContext(source.slice(variantStart, variantEnd) + '\n' + source.slice(centralStart, centralEnd), context);

  const synced = await context.syncCentralPrices(true, true);
  assert.strictEqual(synced, true);
  await context.ensureCentralPriceShard('sv03.5-001', false);
  assert(context.centralPriceLoadedShards.has(7));
  assert(requests.some(url => url.includes('/shards/shard-07.json')));
  assert(context.centralPriceData.variantCatalog['sv03.5-001'].some(item => item.value === 'future-no-price'));

  const quote = context.centralPriceQuote('sv03.5-001', {
    pricingVariant: 'reverse-holofoil', language: 'pt-br', condition: 'Near Mint',
    gradingCompany: 'Não graduada', variantTags: [],
  });
  assert(quote && quote.brl === 12.34);

  const before = requests.length;
  await context.ensureCentralPriceShard('sv03.5-001', false);
  assert.strictEqual(requests.length, before, 'Shard carregado deve usar cache');

  console.log('Price Database v4: índice, shard seletivo, catálogo dinâmico e preço exato aprovados.');
})().catch(error => {
  console.error(error);
  process.exit(1);
});
