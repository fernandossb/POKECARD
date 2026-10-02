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
  // Guardado à parte do preço (loteCompacto): [setId, setName, number, setTotal, rarity].
  centralPriceCards: {},
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
    /* Formato compacto (precoCompacto, fora destas fatias do app.js): cada
       preço grava só o que o app usa — p preço em BRL, c confiança 0-100,
       x 1 quando matchLevel é "exact", k mercado escolhido, u updatedAt,
       v os valores desse mercado (para low/high) e t o total de fontes.
       As fixtures abaixo são o resultado de precoCompacto sobre um preço
       "cru" do banco — comentado ao lado de cada uma para explicar a conta. */
    prices: {
      // priceBrl: 12,34 · confidence: 75 · exact · um mercado só (tcgplayer).
      'sv03.5-001::pt-br::reverse-holofoil': {
        p: 12.34, c: 75, x: 1, k: 'tcgplayer', u: '2026-08-05T12:00:00Z', v: [13.68], t: 1,
      },
      // Banco antigo (sem `priceMarket`) com valores dos dois mercados: a
      // prioridade (TCGplayer antes de Cardmarket) já foi aplicada ao
      // compactar, então só o TCGplayer [10, 8] entra em v — média 9.
      'sv03.5-002::pt-br::normal': {
        p: 9, c: 80, x: 1, k: 'tcgplayer', u: '2026-08-05T12:00:00Z', v: [10, 8], t: 4,
      },
      // Banco novo: já publicou o mercado escolhido (cardmarket) e o preço
      // (7,77) sem recalcular — v guarda as referências dele só para low/high.
      'sv03.5-003::pt-br::normal': {
        p: 7.77, c: 80, x: 1, k: 'cardmarket', u: '2026-08-05T12:00:00Z', v: [7.5, 8.04], t: 2,
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

/* --- O que o Cardmarket quer dizer (5.87) ---
   O banco grava o preço da carta no Cardmarket como "normal" e o preço
   "-holo" dele (que é o do REVERSE) como "holo". As marcações do TCGdex
   ("tcgdex-flag") e o "holofoil" do TCGplayer dizem que versão é cada um. */
const preco = (p, k) => ({ p, c: 80, x: 1, k, u: '2026-10-01T12:00:00Z', v: [p], t: 1 });
const entrada = (value, sources, kinds = ['market-variant']) => ({ language: 'en', value, sources, kinds, priced: true });
const marcacao = value => ({ language: 'en', value, sources: ['tcgdex'], kinds: ['tcgdex-flag'], priced: false });
Object.assign(context.centralPriceData.variantCatalog, {
  // Dragonite V 076/078: só existe holo.
  'x-dragonite': [marcacao('holo'), entrada('holofoil', ['tcgplayer']), entrada('normal', ['cardmarket'])],
  // Só holo, sem TCGplayer: o preço da carta no Cardmarket é o da holo.
  'x-so-holo': [marcacao('holo'), entrada('normal', ['cardmarket'])],
  // Bulbasaur 151: comum + reverse; o "holo" do Cardmarket é o reverse.
  'x-bulbasaur': [
    entrada('normal', ['cardmarket', 'tcgdex', 'tcgplayer'], ['market-variant', 'tcgdex-flag']),
    marcacao('reverse'), entrada('holo', ['cardmarket']), entrada('reverse-holofoil', ['tcgplayer']),
  ],
  // Comum com reverse que só o Cardmarket precifica.
  'x-reverse-cm': [entrada('normal', ['cardmarket', 'tcgdex'], ['market-variant', 'tcgdex-flag']), entrada('holo', ['cardmarket'])],
  // Holo rara com reverse: a holo não pode ficar com o preço do reverse.
  'x-holo-rara': [
    entrada('holo', ['cardmarket', 'tcgdex'], ['market-variant', 'tcgdex-flag']), entrada('holofoil', ['tcgplayer']),
    entrada('normal', ['cardmarket']), marcacao('reverse'), entrada('reverse-holofoil', ['tcgplayer']),
  ],
});
Object.assign(context.centralPriceData.prices, {
  'x-dragonite::en::holofoil': preco(83.19, 'tcgplayer'), 'x-dragonite::en::normal': preco(51.03, 'cardmarket'),
  'x-so-holo::en::normal': preco(40, 'cardmarket'),
  'x-bulbasaur::en::normal': preco(0.9, 'tcgplayer'), 'x-bulbasaur::en::holo': preco(1.78, 'cardmarket'),
  'x-bulbasaur::en::reverse-holofoil': preco(1.53, 'tcgplayer'),
  'x-reverse-cm::en::normal': preco(1, 'cardmarket'), 'x-reverse-cm::en::holo': preco(2, 'cardmarket'),
  'x-holo-rara::en::holo': preco(20.91, 'cardmarket'), 'x-holo-rara::en::holofoil': preco(18.01, 'tcgplayer'),
  'x-holo-rara::en::normal': preco(14.36, 'cardmarket'), 'x-holo-rara::en::reverse-holofoil': preco(25.84, 'tcgplayer'),
});
const precoDe = (cardId, pricingVariant) => context.automaticPriceQuote(cardId, { ...exactVariant, pricingVariant, finish: pricingVariant })?.brl ?? null;

assert.strictEqual(visiveis('x-dragonite', ['holo', 'holofoil', 'normal'], ''), 'holofoil', 'Dragonite V: o "normal" do Cardmarket é a holo, não uma Comum');
assert.strictEqual(precoDe('x-dragonite', 'holo'), 83.19, 'Dragonite V: a holo usa o preço da holofoil do TCGplayer');
assert.strictEqual(visiveis('x-so-holo', ['holo', 'normal'], ''), 'holo', 'Só holo: nada de Comum');
assert.strictEqual(precoDe('x-so-holo', 'holo'), 40, 'Só holo sem TCGplayer: vale o preço da carta no Cardmarket');
assert.strictEqual(visiveis('x-bulbasaur', ['holo', 'normal', 'reverse', 'reverse-holofoil'], ''), 'normal|reverse-holofoil', 'Bulbasaur 151: o "holo" do Cardmarket é o reverse, não uma Holográfica');
assert.strictEqual(precoDe('x-bulbasaur', 'reverse-holofoil'), 1.53);
assert.strictEqual(visiveis('x-reverse-cm', ['holo', 'normal'], ''), 'normal|reverse-holofoil', 'Reverse só no Cardmarket: aparece como Reverse');
assert.strictEqual(precoDe('x-reverse-cm', 'reverse-holofoil'), 2, 'Reverse só no Cardmarket: usa o preço "holo" dele');
assert.strictEqual(visiveis('x-holo-rara', ['holo', 'holofoil', 'normal', 'reverse', 'reverse-holofoil'], ''), 'holofoil|reverse-holofoil', 'Holo rara: uma holo e um reverse');
assert.strictEqual(precoDe('x-holo-rara', 'holo'), 18.01, 'Holo rara: a holo não pode usar o preço do reverse (20,91)');
assert.strictEqual(precoDe('x-holo-rara', 'reverse-holofoil'), 25.84);
// Cópia já cadastrada com o nome antigo continua aparecendo para poder mudar.
assert.strictEqual(visiveis('x-bulbasaur', ['holo', 'normal', 'reverse', 'reverse-holofoil'], 'holo'), 'holo|normal|reverse-holofoil', 'A versão escolhida não pode sumir');

console.log('Precificação exclusiva pelo Price Database, prioridade de mercado e variantes visíveis aprovados.');
