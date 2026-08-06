const fs = require('fs');
const vm = require('vm');
const assert = require('assert');

const sandbox = { window: {} };
vm.createContext(sandbox);
vm.runInContext(fs.readFileSync('app/src/main/assets/www/data/catalog-data.js', 'utf8'), sandbox);
vm.runInContext(fs.readFileSync('app/src/main/assets/www/data/evolution-data.js', 'utf8'), sandbox);
const cards = sandbox.window.__CATALOG__.cards;
const evolvesFrom = sandbox.window.__POKEMON_EVOLVES_FROM__;
const normalize = value => String(value || '').normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase();
const isEnergy = card => /^energia(?:\s|$)/.test(normalize(card?.name));
const isPocket = card => /^(?:[ab]\d|p-a)/i.test(String(card?.setId || ''));

assert(cards.length > 10000, 'Catálogo não foi carregado.');
assert(cards.some(card => card.name === 'Energia de Água' && isEnergy(card)), 'Energia de Água deve ser Energia.');
assert(cards.some(card => card.name === 'Energia de Luta' && isEnergy(card)), 'Energia de Luta deve ser Energia.');
assert(cards.some(card => card.name === 'Busca de Energia' && !isEnergy(card)), 'Busca de Energia deve ser Treinador.');
assert(cards.some(card => card.name === 'Recuperação de Energia' && !isEnergy(card)), 'Recuperação de Energia deve ser Treinador.');
assert(cards.some(card => card.name === 'Substituição de Energia' && !isEnergy(card)), 'Substituição de Energia deve ser Treinador.');
assert(isPocket({setId: 'A1'}), 'Série A1 do TCG Pocket deve ser reconhecida.');
assert(isPocket({setId: 'P-A'}), 'Promos P-A do TCG Pocket devem ser reconhecidas.');
assert(!isPocket({setId: 'sv10'}), 'Série física Escarlate e Violeta não pode ser excluída.');
assert.strictEqual(Number(evolvesFrom[975]), 974, 'Cetitan deve exigir Cetoddle.');
assert.strictEqual(Number(evolvesFrom[9]), 8, 'Blastoise deve exigir Wartortle.');
assert.strictEqual(Number(evolvesFrom[8]), 7, 'Wartortle deve exigir Squirtle.');

console.log('Deck coherence tests: OK');
