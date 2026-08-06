const fs = require('fs');
const vm = require('vm');
const assert = require('assert');

const source = fs.readFileSync('app/src/main/assets/www/app.js', 'utf8');
const variantStart = source.indexOf('const PRICE_FINISHES =');
const variantEnd = source.indexOf('function ligaNumberPart(', variantStart);
const centralStart = source.indexOf('function centralPriceGeneratedAt(');
const centralEnd = source.indexOf('async function syncCentralPrices(', centralStart);
assert(variantStart >= 0 && variantEnd > variantStart, 'Bloco de enums não encontrado');
assert(centralStart >= 0 && centralEnd > centralStart, 'Bloco do banco central não encontrado');

const context = {
  normalize: value => String(value || '').normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase(),
  hasFiniteNumber: value => value !== null && value !== undefined && value !== '' && Number.isFinite(Number(value)),
  centralPriceData: {
    meta: { generatedAt: '2026-08-05T12:00:00Z', schemaVersion: 4 },
    variantCatalog: {
      'sv03.5-001': [
        { language: 'pt-br', value: 'reverse-holofoil', sources: ['tcgplayer'], priced: true },
        { language: 'pt-br', value: 'future-parallel-foil', sources: ['tcgdex'], priced: true },
      ],
    },
    prices: {
      'sv03.5-001::pt-br::reverse-holofoil': {
        cardId: 'sv03.5-001', language: 'pt-br', variantEnum: 'reverse-holofoil',
        priceBrl: 12.34, confidence: 75, matchLevel: 'exact', updatedAt: '2026-08-05T12:00:00Z',
        sources: [{ source: 'tcgplayer:reverse-holofoil:marketPrice', valueBrl: 12.34 }],
      },
      'sv03.5-001::pt-br::future-parallel-foil': {
        cardId: 'sv03.5-001', language: 'pt-br', variantEnum: 'future-parallel-foil',
        priceBrl: 20, confidence: 60, matchLevel: 'exact', updatedAt: '2026-08-05T12:00:00Z',
        sources: [{ source: 'tcgplayer:future-parallel-foil:marketPrice', valueBrl: 20 }],
      },
    },
  },
  centralPriceStatus: {},
  scannerVariantAvailability: new Map(),
  variantsFor: () => [],
  document: { getElementById: () => null },
  scannerSession: { language: 'pt-br' },
};
vm.createContext(context);
vm.runInContext(source.slice(variantStart, variantEnd) + '\n' + source.slice(centralStart, centralEnd), context);

const exact = context.centralPriceQuote('sv03.5-001', {
  pricingVariant: 'reverse-holofoil', language: 'pt-br', condition: 'Near Mint',
  gradingCompany: 'Não graduada', variantTags: [],
});
assert(exact, 'Enum exato deveria localizar preço');
assert.strictEqual(exact.brl, 12.34);
assert.strictEqual(exact.confidence, 'verified');
assert.strictEqual(exact.databaseKey, 'sv03.5-001::pt-br::reverse-holofoil');

const future = context.centralPriceQuote('sv03.5-001', {
  pricingVariant: 'future-parallel-foil', language: 'pt-br', condition: 'Near Mint',
  gradingCompany: 'Não graduada', variantTags: [],
});
assert(future && future.brl === 20, 'Enum futuro desconhecido deve funcionar sem atualização de allowlist');

for (const invalid of ['Reverse Holofoil', 'reversa', 'future parallel foil']) {
  assert.strictEqual(context.centralPriceQuote('sv03.5-001', {
    pricingVariant: invalid, language: 'pt-br', condition: 'Near Mint',
    gradingCompany: 'Não graduada', variantTags: [],
  }), null, `Nome alterado não deve localizar preço: ${invalid}`);
}

assert.strictEqual(context.centralPriceQuote('sv03.5-001', {
  pricingVariant: 'reverse-holofoil', language: 'pt-br', condition: 'Near Mint',
  gradingCompany: 'PSA', grade: '10', variantTags: [],
}), null, 'Carta graduada não pode usar preço de carta crua');

const discovered = context.sourceVariantEnumsFromTcgDexDetail({
  variants: { normal: true, futureParallelFoil: true },
  pricing: {
    tcgplayer: { updated: 1, 'galaxy-foil-v2': { marketPrice: 4 } },
    cardmarket: { trend: 2, 'trend-holo': 3 },
  },
}, 'pt-br').map(item => item.value);
for (const value of ['normal', 'futureParallelFoil', 'galaxy-foil-v2', 'holo']) {
  assert(discovered.includes(value), `Enum da fonte não pode ser descartado: ${value}`);
}

console.log('Integração dinâmica aprovada: enum exato, valores futuros e nenhum alias antigo.');
