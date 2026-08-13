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
  // O Ampharos 1/64 do Neo Revelation: carta só holográfica, com 1ª edição.
  // É o catálogo que diz isso, e é o que permite descartar os nomes de mercado
  // que não correspondem a carta nenhuma.
  cardMap: new Map([
    ['neo3-1', { id: 'neo3-1', name: 'Ampharos', variants: { normal: false, holo: true, reverse: false, firstEdition: true, wPromo: false } }],
  ]),
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

// --- Botões de versão: uma opção por carta física ---

// O array volta de dentro do vm, de outro "realm": comparar com deepStrictEqual
// falharia pelo protótipo mesmo com o conteúdo certo. Comparamos como texto.
const visiveis = (cardId, valores, selecionada) =>
  context.variantesVisiveis(cardId, valores, selecionada, 'pt-br').join('|');

/* Esconder versão sem preço deixava de fora carta que existe de verdade: quem
   coleciona precisa registrar a cópia mesmo antes de o mercado publicar valor.
   Agora aparecem todas — o que não pode é a MESMA carta aparecer duas vezes só
   porque cada mercado a batiza de um jeito. */
assert.strictEqual(
  visiveis('sv03.5-001', ['normal', 'reverse', 'reverse-holofoil'], 'reverse-holofoil'),
  'normal|reverse-holofoil',
  'A comum deve aparecer mesmo sem preço; reverse e reverse-holofoil são a mesma carta'
);

// Entre nomes da mesma carta física, fica o que tem preço publicado.
assert.strictEqual(
  visiveis('sv03.5-001', ['reverse', 'reverse-holofoil'], ''),
  'reverse-holofoil',
  'Empatando na carta, deve ficar o nome que tem preço'
);

// Sem preço nenhum, sobra o primeiro — mas continua sendo um botão só.
assert.strictEqual(
  visiveis('carta-sem-preco', ['reverse', 'reverse-holofoil'], ''),
  'reverse',
  'Nomes da mesma carta devem virar um botão só'
);

/* O caso Ampharos 1/64: cinco nomes de mercado para duas cartas de verdade.
   "1st-edition-holofoil" e "firstEdition" são a 1ª edição holográfica;
   "holo" e "unlimited-holofoil" são a holográfica de tiragem normal;
   "normal" não corresponde a carta nenhuma — o catálogo diz normal:false. */
assert.strictEqual(
  visiveis('neo3-1', ['1st-edition-holofoil', 'firstEdition', 'holo', 'normal', 'unlimited-holofoil'], ''),
  '1st-edition-holofoil|holo',
  'Ampharos 1/64: cinco nomes do mercado devem virar duas versões'
);

// Sem o catálogo dizendo o contrário, nada é descartado: carta nova que a
// fonte ainda não marcou precisa aparecer inteira.
assert.strictEqual(
  visiveis('carta-sem-catalogo', ['normal', 'holo', 'reverse-holofoil'], ''),
  'normal|holo|reverse-holofoil',
  'Sem marcação no catálogo, todas as versões do mercado valem'
);

console.log('Precificação exclusiva pelo Price Database, prioridade de mercado e variantes visíveis aprovados.');
