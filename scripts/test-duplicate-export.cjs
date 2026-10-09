const fs = require('fs');
const vm = require('vm');
const assert = require('assert');

const appSource = fs.readFileSync('app/src/main/assets/www/app.js', 'utf8');
const exportSource = fs.readFileSync('app/src/main/assets/www/exportar.js', 'utf8');

// Extrai assinaturaVariante e funções auxiliares do app.js
const sigStart = appSource.indexOf('function assinaturaVariante(');
const sigEnd = appSource.indexOf('function pokemonIdsForCard(');
assert(sigStart >= 0 && sigEnd > sigStart, 'Não achou assinaturaVariante');
const sigBlock = appSource.slice(sigStart, sigEnd);

const exactSourceEnumBlock = 'function exactSourceEnum(v) { return v || ""; }\nfunction finishKind(f) { return f || ""; }\n';

// Cria o contexto mockado
const context = {
  state: { entries: {} },
  cardMap: new Map(),
  catalog: { sets: [] },
  ui: {
    cardFilter: 'owned',
    cardQuery: '',
    cardSet: 'all',
    cardArtist: 'all',
    tab: 'cards',
  },
  normalize: value => String(value || '').normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase(),
  hasFiniteNumber: value => value !== null && value !== undefined && value !== '' && Number.isFinite(Number(value)),
  money: val => `R$ ${Number(val).toFixed(2).replace('.', ',')}`,
  effectiveVariantPrice: (cardId, variant) => {
    return { brl: variant.preco || 10 };
  },
  automaticPriceQuote: () => ({ brl: 5 }),
  variantsFor: cardId => context.state.entries[cardId]?.variants || [],
  quantityFor: cardId => (context.state.entries[cardId]?.variants || []).reduce((s, v) => s + (Number(v.quantity) || 0), 0),
  rotuloDaVariante: v => (v.pricingVariant === 'reverse-holo' ? 'Reverse Holo' : 'Comum'),
  languageCode: l => String(l || '').toUpperCase(),
  CONDICAO_CURTA: {},
  formatCardNumber: (num) => String(num),
  numericLocal: c => Number(c.localId || c.number) || 0,
  compareSetsByTimeline: () => 0,
  cardGridImage: () => '',
  upgradeCardImageUrl: url => url,
  currentCardFilter: () => context.ui.cardFilter,
  centralPriceIndex: null,
  centralPriceLoadedShards: new Set(),
  esc: s => String(s || ''),
  tabIcon: () => '',
  showModal: () => {},
  closeModal: () => {},
  document: {
    getElementById: () => null,
    querySelectorAll: () => [],
  },
};
context.window = context;

vm.createContext(context);
vm.runInContext(exactSourceEnumBlock + sigBlock + exportSource + '\nthis.exportacao = exportacao; this.dadosDaCartaExportada = dadosDaCartaExportada; this.resumoDasVersoes = resumoDasVersoes; this.mudarFonteDaExportacao = mudarFonteDaExportacao;', context);

// Teste 1: Carta com 3 Comuns e 1 Reverse Holo
// No "Minha coleção" (colecao): deve exportar 3 Comuns + 1 Reverse Holo = 4 cópias no total
// Nas "Duplicadas" (repetidas): deve descontar 1 cópia de segurança de cada versão!
// -> 3 Comuns - 1 segurança = 2 cópias repetidas
// -> 1 Reverse Holo - 1 segurança = 0 cópias repetidas (não entra no arquivo de repetidas!)
const card1 = { id: 'card-1', name: 'Pikachu', number: '25/100', setId: 'base' };
context.cardMap.set('card-1', card1);
context.state.entries['card-1'] = {
  variants: [
    { id: 'v1', pricingVariant: 'normal', quantity: 3, preco: 2.0 },
    { id: 'v2', pricingVariant: 'reverse-holo', quantity: 1, preco: 15.0 },
  ],
};

// 1. Exportando colecao inteira
context.exportacao.fonte = 'colecao';
context.exportacao.dados.clear();
const dadosColecao = context.dadosDaCartaExportada(card1);
assert.strictEqual(dadosColecao.quantidade, 4, 'Coleção deve ter 4 cópias no total');
assert.strictEqual(dadosColecao.versoes.length, 2, 'Coleção deve ter 2 versões');
assert.strictEqual(dadosColecao.valor, 3 * 2.0 + 1 * 15.0, 'Valor da coleção deve somar todas as cópias');

// 2. Exportando repetidas
context.exportacao.fonte = 'repetidas';
context.exportacao.dados.clear();
const dadosRepetidas = context.dadosDaCartaExportada(card1);
assert.strictEqual(dadosRepetidas.quantidade, 2, 'Repetidas deve ter apenas 2 cópias (3 - 1 cópia de segurança)');
assert.strictEqual(dadosRepetidas.versoes.length, 1, 'Apenas a versão comum tem sobra; a reverse de 1 cópia única foi mantida na coleção');
assert.strictEqual(dadosRepetidas.versoes[0].quantidade, 2, 'A versão comum deve ter 2 cópias excedentes');
assert.strictEqual(dadosRepetidas.valor, 2 * 2.0, 'Valor de repetidas deve ser 2 * preço unitário');
assert.strictEqual(context.resumoDasVersoes(dadosRepetidas), '2× Comum', 'Resumo deve mostrar 2x Comum');

// 3. Carta com 2 linhas separadas cadastradas da MESMA versão (ex: 2 cópias cadastradas + 1 cópia depois)
const card2 = { id: 'card-2', name: 'Bulbasaur', number: '1/100', setId: 'base' };
context.cardMap.set('card-2', card2);
context.state.entries['card-2'] = {
  variants: [
    { id: 'b1', pricingVariant: 'normal', language: 'pt-br', condition: 'Near Mint', quantity: 2, preco: 3.0 },
    { id: 'b2', pricingVariant: 'normal', language: 'pt-br', condition: 'Near Mint', quantity: 1, preco: 3.0 },
  ],
};

context.exportacao.fonte = 'repetidas';
context.exportacao.dados.clear();
const dadosBulba = context.dadosDaCartaExportada(card2);
// Total de 3 cópias cadastradas da mesma versão idêntica: 3 - 1 segurança = 2 sobras
assert.strictEqual(dadosBulba.quantidade, 2, 'Linhas separadas da mesma versão devem agrupar e descontar 1 de segurança no total');
assert.strictEqual(dadosBulba.valor, 6.0, 'Valor das 2 sobras deve ser 6.0');

// 4. Teste mudarFonteDaExportacao recalcula com a nova fonte
context.mudarFonteDaExportacao('colecao');
const dadosNovo = context.exportacao.dados.get('card-1');
assert(dadosNovo, 'dados devem ter sido recalculados no rodapé da nova fonte');
assert.strictEqual(dadosNovo.quantidade, 4, 'dados de card-1 devem ser recalculados para 4 cópias na fonte colecao');

console.log('Todos os testes de desconto de cópia de segurança na exportação de repetidas passaram com sucesso!');
