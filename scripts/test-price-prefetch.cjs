const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

async function run(fail) {
  const source = fs.readFileSync('app/src/main/assets/www/app.js', 'utf8');
  const start = source.indexOf('function adiantarLotesDaLista(');
  const end = source.indexOf('\nfunction atualizarLinhasVisiveisEmSegundoPlano', start);
  assert(start >= 0 && end > start);
  const calls = [];
  const loaded = new Set();
  const cards = Array.from({ length: 7 }, (_, i) => ({ id: String(i) }));
  const context = {
    centralPriceIndex: { cards: Object.fromEntries(cards.map((c, i) => [c.id, i])) },
    centralPriceLoadedShards: loaded,
    lotesEmAndamento: new Set(),
    atualizarLinhasVisiveisEmSegundoPlano() {},
    ensureCentralPriceShard: async cardId => {
      calls.push(cardId);
      if (calls.length > cards.length) throw new Error('retry loop');
      if (fail) throw new Error('network unavailable');
      loaded.add(Number(cardId));
      return true;
    },
  };
  vm.createContext(context);
  vm.runInContext(source.slice(start, end), context);
  context.adiantarLotesDaLista(cards);
  for (let i = 0; i < 20; i++) await new Promise(resolve => setImmediate(resolve));
  assert.equal(calls.length, cards.length);
  assert.equal(new Set(calls).size, cards.length);
  assert.equal(context.lotesEmAndamento.size, 0);
}

Promise.all([run(false), run(true)]).then(() => {
  console.log('Pré-carregamento de preços sem repetição infinita: OK');
}).catch(error => { console.error(error); process.exitCode = 1; });
