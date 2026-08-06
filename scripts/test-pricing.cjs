const fs = require('fs');
const vm = require('vm');
const assert = require('assert');

const source = fs.readFileSync('app/src/main/assets/www/app.js', 'utf8');
const variantStart = source.indexOf('const PRICE_FINISHES =');
const variantEnd = source.indexOf('function ligaNumberPart(', variantStart);
const centralStart = source.indexOf('function centralPriceGeneratedAt(');
const centralEnd = source.indexOf('async function syncCentralPrices(', centralStart);
const automaticStart = source.indexOf('function automaticPriceQuote(');
const automaticEnd = source.indexOf('function legacyPriceQuote(', automaticStart);
assert(variantStart >= 0 && variantEnd > variantStart);
assert(centralStart >= 0 && centralEnd > centralStart);
assert(automaticStart >= 0 && automaticEnd > automaticStart);

const context = {
  normalize: value => String(value || '').normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase(),
  hasFiniteNumber: value => value !== null && value !== undefined && value !== '' && Number.isFinite(Number(value)),
  centralPriceStatus: {},
  scannerVariantAvailability: new Map(),
  variantsFor: () => [],
  document: { getElementById: () => null },
  scannerSession: { language: 'pt-br' },
  centralPriceData: {
    meta: { generatedAt: '2026-08-05T12:00:00Z', schemaVersion: 4 },
    variantCatalog: {},
    prices: {
      'sv03.5-001::pt-br::reverse-holofoil': {
        cardId: 'sv03.5-001', language: 'pt-br', variantEnum: 'reverse-holofoil',
        priceBrl: 12.34, confidence: 75, matchLevel: 'exact', updatedAt: '2026-08-05T12:00:00Z',
        sources: [{ source: 'tcgplayer:reverse-holofoil:marketPrice', valueBrl: 13.68 }],
      },
    },
  },
};
vm.createContext(context);
vm.runInContext(source.slice(variantStart, variantEnd) + '\n' + source.slice(centralStart, centralEnd) + '\n' + source.slice(automaticStart, automaticEnd), context);

const exactVariant = { pricingVariant: 'reverse-holofoil', finish: 'reverse', language: 'pt-br', condition: 'Near Mint', edition: 'unlimited', distribution: 'unstamped', artVariant: 'standard', region: 'Brasil', gradingCompany: 'Não graduada', grade: '', variantTags: [] };
const quote = context.automaticPriceQuote('sv03.5-001', exactVariant);
assert(quote);
assert.strictEqual(quote.brl, 12.34);
assert.strictEqual(quote.source, 'preco-brasil');
assert.strictEqual(quote.provider, 'Pokémon Price Database Brasil');
assert.strictEqual(context.automaticPriceQuote('missing-card', exactVariant), null);
assert.strictEqual(context.automaticPriceQuote('sv03.5-001', { ...exactVariant, pricingVariant: 'Reverse Holofoil' }), null, 'Não deve aceitar enum alterado');
assert(!source.includes('requestLigaPokemon'), 'O app não pode manter ponte de consulta à Liga');
assert(!source.includes('fetchLigaPokemonPricing'), 'O app não pode manter fallback de preço da Liga');
console.log('Precificação exclusiva pelo Price Database e variantEnum exato aprovados.');
