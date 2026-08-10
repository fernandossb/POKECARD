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
      // Banco antigo (sem `priceMarket`) com valores dos dois mercados: aqui a
      // prioridade muda o número, então o app aplica a regra na hora.
      'sv03.5-002::pt-br::normal': {
        cardId: 'sv03.5-002', language: 'pt-br', variantEnum: 'normal',
        priceBrl: 30, confidence: 80, matchLevel: 'exact', updatedAt: '2026-08-05T12:00:00Z',
        sources: [
          { source: 'tcgplayer:normal:marketPrice', valueBrl: 10 },
          { source: 'tcgplayer:normal:lowPrice', valueBrl: 8 },
          { source: 'cardmarket:normal:trend', valueBrl: 50 },
          { source: 'cardmarket:normal:avg7', valueBrl: 52 },
        ],
      },
      // Banco novo: já publicou o mercado escolhido, o app respeita o valor.
      'sv03.5-003::pt-br::normal': {
        cardId: 'sv03.5-003', language: 'pt-br', variantEnum: 'normal',
        priceBrl: 7.77, priceMarket: 'cardmarket', confidence: 80, matchLevel: 'exact', updatedAt: '2026-08-05T12:00:00Z',
        sources: [
          { source: 'cardmarket:normal:trend', valueBrl: 7.5, used: true },
          { source: 'cardmarket:normal:avg7', valueBrl: 8.04, used: true },
        ],
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

// --- Prioridade de mercado: TCGplayer, depois TCGdex, depois Cardmarket ---

const normalVariant = { ...exactVariant, pricingVariant: 'normal', finish: 'normal' };

// Banco antigo com os dois mercados: só o TCGplayer entra na conta.
const mixed = context.automaticPriceQuote('sv03.5-002', normalVariant);
assert(mixed, 'Deve encontrar preço da carta com dois mercados');
assert.strictEqual(mixed.brl, 9, 'Deve usar só o TCGplayer: média de 10 e 8');
assert.strictEqual(mixed.priceMarket, 'tcgplayer');
assert.strictEqual(mixed.low, 8, 'Menor referência deve ignorar o Cardmarket');
assert.strictEqual(mixed.high, 10, 'Maior referência deve ignorar o Cardmarket');

// Banco novo já resolveu a prioridade: o app não recalcula por cima.
const published = context.automaticPriceQuote('sv03.5-003', normalVariant);
assert(published, 'Deve encontrar preço da carta com mercado já publicado');
assert.strictEqual(published.brl, 7.77, 'Deve respeitar o priceBrl publicado pelo banco');
assert.strictEqual(published.priceMarket, 'cardmarket', 'Sem TCGplayer, cai para o Cardmarket');

// Um mercado só: a prioridade não muda nada e o valor publicado fica de pé.
assert.strictEqual(quote.priceMarket, 'tcgplayer');

// --- Botões de versão: sem repetir nome e sem opção sem preço ---

// Só `reverse-holofoil` tem preço nesta carta; `reverse` e `normal` somem.
assert.deepStrictEqual(
  context.variantesVisiveis('sv03.5-001', ['normal', 'reverse', 'reverse-holofoil'], 'reverse-holofoil', 'pt-br'),
  ['reverse-holofoil'],
  'Só deve sobrar a versão com preço publicado'
);

// Sem preço nenhum, mostramos as opções — mas "reverse" e "reverse-holofoil"
// têm o mesmo nome na tela, então vira um botão só.
assert.deepStrictEqual(
  context.variantesVisiveis('carta-sem-preco', ['reverse', 'reverse-holofoil'], '', 'pt-br'),
  ['reverse'],
  'Nomes repetidos devem virar um botão só'
);

console.log('Precificação exclusiva pelo Price Database, prioridade de mercado e variantes visíveis aprovados.');
