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
      'sv06.5-010': [
        { language: 'en', value: 'cosmos-holofoil', sources: ['tcgdex'], kinds: ['special-foil'], priced: true },
        { language: 'en', value: 'holo', sources: ['cardmarket', 'tcgdex'], kinds: ['market-variant', 'tcgdex-flag'], priced: true },
        { language: 'en', value: 'normal', sources: ['tcgplayer', 'tcgdex'], kinds: ['market-variant', 'tcgdex-flag'], priced: true },
      ],
    },
    // Formato compacto (precoCompacto, fora desta fatia do app.js): p preço
    // em BRL, c confiança 0-100, x 1 quando matchLevel é "exact", k mercado
    // escolhido, u updatedAt, v valores desse mercado, t total de fontes.
    prices: {
      'sv03.5-001::pt-br::normal': {
        p: 2.5, c: 95, x: 1, k: 'ligapokemon', v: [2.5], t: 1,
      },
      'sv03.5-001::pt-br::reverse-holofoil': {
        p: 12.34, c: 75, x: 1, k: 'tcgplayer', u: '2026-08-05T12:00:00Z', v: [12.34], t: 1,
      },
      'sv03.5-001::pt-br::future-parallel-foil': {
        p: 20, c: 60, x: 1, k: 'tcgplayer', u: '2026-08-05T12:00:00Z', v: [20], t: 1,
      },
      'sv06.5-010::en::holo': {
        p: 0.56, c: 92, x: 1, k: 'cardmarket', u: '2026-08-05T12:00:00Z', v: [0.56], t: 1,
      },
      'sv06.5-010::en::normal': {
        p: 0.48, c: 95, x: 1, k: 'tcgplayer', u: '2026-08-05T12:00:00Z', v: [0.48], t: 1,
      },
      'sv06.5-010::en::cosmos-holofoil': {
        p: 1.09, c: 90, x: 1, k: 'cardmarket', u: '2026-08-05T12:00:00Z', v: [1.09], t: 1,
      },
    },
  },
  centralPriceStatus: {},
  // Guardado à parte do preço (loteCompacto): [setId, setName, number, setTotal, rarity].
  centralPriceCards: {},
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

const common = context.centralPriceQuote('sv03.5-001', { pricingVariant: 'Comum', language: 'pt-br' });
assert.strictEqual(common?.databaseKey, 'sv03.5-001::pt-br::normal');
assert.strictEqual(common?.brl, 2.5);
assert.strictEqual(common?.confidence, 'verified');

const future = context.centralPriceQuote('sv03.5-001', {
  pricingVariant: 'future-parallel-foil', language: 'pt-br', condition: 'Near Mint',
  gradingCompany: 'Não graduada', variantTags: [],
});
assert(future && future.brl === 20, 'Enum futuro desconhecido deve funcionar sem atualização de allowlist');

const ligaHolo = context.centralPriceQuote('sv06.5-010', {
  pricingVariant: 'holo', language: 'pt-br', condition: 'Near Mint',
  gradingCompany: 'Não graduada', variantTags: [],
});
assert(ligaHolo && ligaHolo.brl === 0.56, 'A chave holo publicada pela Liga/Cardmarket deve ser usada diretamente');
const tcgplayerHolo = context.centralPriceQuote('sv06.5-010', {
  pricingVariant: 'holofoil', language: 'pt-br', condition: 'Near Mint',
  gradingCompany: 'Não graduada', variantTags: [],
});
assert(tcgplayerHolo && tcgplayerHolo.brl === 0.56, 'Holofoil deve encontrar o alias holo quando a fonte usa esse nome');
const specialFoil = context.centralPriceQuote('sv06.5-010', {
  pricingVariant: 'cosmos-holofoil', language: 'pt-br', condition: 'Near Mint',
  gradingCompany: 'Não graduada', variantTags: [],
});
assert(specialFoil && specialFoil.brl === 1.09, 'Foil especial com chave própria deve continuar exato');

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
