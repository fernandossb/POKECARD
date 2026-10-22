const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const source = fs.readFileSync('app/src/main/assets/www/trainer.js', 'utf8');
let payload;
const collection = { entries: { 'sv01-258': {}, 'swsh4-43': {}, unknown: {}, wishlist: {} }, decks: [{ name: 'Preservar' }] };
const before = JSON.stringify(collection);
const quantities = { 'sv01-258': 12, 'swsh4-43': 3, unknown: 2, wishlist: 0 };
const context = vm.createContext({ state: collection, quantityFor: id => quantities[id], window: {
  Android: { openTcgTrainer: text => payload = JSON.parse(text) }, alert: () => assert.fail('Unexpected alert')
} });
vm.runInContext(source + '\nopenTcgTrainer();', context);
assert.deepEqual(payload, { version: 1, counts: { 'sv01-258': 12, 'swsh4-43': 3, unknown: 2 } });
assert.equal(JSON.stringify(collection), before, 'Export must never mutate collection or original decks');
quantities['swsh4-43'] = 1;
vm.runInContext('openTcgTrainer();', context);
assert.equal(payload.counts['swsh4-43'], 1, 'Reopening must capture current quantities');
let alert = '';
vm.runInNewContext(source + '\nopenTcgTrainer();', { state: collection, window: { alert: msg => alert = msg } });
assert.match(alert, /Android/);
console.log('Trainer bridge: exact quantities, refreshed collection, read-only export and browser fallback passed.');
