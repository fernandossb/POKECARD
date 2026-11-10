const assert = require('node:assert/strict');
const { combinar } = require('../app/src/main/assets/www/sync-merge.js');

const base = { entries: { a: { quantity: 1 }, b: { quantity: 1 } }, decks: [] };
const local = { entries: { a: { quantity: 2 }, b: { quantity: 1 } }, decks: [] };
const remoto = { entries: { a: { quantity: 1 }, b: { quantity: 3 } }, decks: [] };
assert.deepEqual(combinar(base, local, remoto), {
  dados: { entries: { a: { quantity: 2 }, b: { quantity: 3 } }, decks: [] },
  conflitos: [],
});

const conflito = combinar(base, local, { entries: { a: { quantity: 4 }, b: { quantity: 1 } }, decks: [] });
assert.deepEqual(conflito.conflitos, [['entries', 'a', 'quantity']]);
assert.equal(conflito.dados.entries.a.quantity, 2);

const exclusao = combinar({ carta: { nome: 'A' } }, {}, { carta: { nome: 'B' } });
assert.deepEqual(exclusao.conflitos, [['carta']]);

const arrays = combinar({ decks: [1] }, { decks: [2] }, { decks: [3] });
assert.deepEqual(arrays.conflitos, [['decks']]);

assert.deepEqual(combinar({ a: 1 }, {}, { a: 1 }), { dados: {}, conflitos: [] });
assert.deepEqual(combinar({}, { a: 1 }, { b: 2 }), { dados: { a: 1, b: 2 }, conflitos: [] });
console.log('Combinação de revisões e conflitos: OK');
