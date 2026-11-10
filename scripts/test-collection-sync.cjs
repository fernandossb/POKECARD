const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const merge = require('../app/src/main/assets/www/sync-merge.js');

async function main() {
  const stored = new Map();
  let remote = null;
  let revision = '';
  let writes = 0;
  let notification = '';
  const context = {
    console, JSON, Map, Promise, Error, clearTimeout, PokecardSyncMerge: merge,
    setTimeout: () => 1, setInterval: () => 1,
    state: { version: 2, entries: { a: { quantity: 1 } }, decks: [], importedAt: null },
    document: { hidden: false, addEventListener() {}, querySelector() { return null; } },
    esc: String, confirm: () => true, notify: value => { notification = value; },
    openBackupPanel() {}, salvarBackupDeEmergencia() {}, saveState() {},
    saveStateNow() {}, render() {}, migrateState: value => value,
    indexedDB: {
      open() {
        const request = {};
        queueMicrotask(() => {
          request.result = {
            createObjectStore() {},
            close() {},
            transaction() {
              const tx = {
                objectStore() {
                  return {
                    get(key) {
                      const result = { result: stored.get(key) };
                      queueMicrotask(() => { result.onsuccess(); tx.oncomplete?.(); });
                      return result;
                    },
                    put(value, key) {
                      stored.set(key, structuredClone(value));
                      queueMicrotask(() => tx.oncomplete());
                    },
                  };
                },
              };
              return tx;
            },
          };
          request.onupgradeneeded?.();
          request.onsuccess();
        });
        return request;
      },
    },
    addEventListener() {},
  };
  context.window = context;
  context.Android = {
    syncAccount(id) { queueMicrotask(() => context.pokecardSyncReply(id, { uid: 'user', email: 'test@example.com' })); },
    syncRead(id) { queueMicrotask(() => context.pokecardSyncReply(id, revision ? { revision, data: JSON.stringify(remote) } : { revision: '' })); },
    syncWrite(id, expected, data) {
      assert.equal(expected, revision);
      writes++;
      remote = JSON.parse(data);
      revision = String(writes);
      queueMicrotask(() => context.pokecardSyncReply(id, { revision }));
    },
  };
  vm.createContext(context);
  vm.runInContext(fs.readFileSync('app/src/main/assets/www/collection-sync.js', 'utf8'), context);
  const settle = async () => { for (let i = 0; i < 10; i++) await new Promise(resolve => setImmediate(resolve)); };
  await settle();
  context.pokecardSyncNow();
  await settle();
  assert.equal(writes, 0, 'first upload requires explicit activation');
  context.pokecardSyncStart();
  await settle();
  assert.equal(writes, 1);
  assert.equal(remote.entries.a.quantity, 1);

  context.state.entries.b = { quantity: 2 };
  remote.entries.c = { quantity: 3 };
  revision = 'remote-change';
  context.pokecardSyncNow();
  await settle();
  assert.equal(writes, 2);
  assert.equal(remote.entries.b.quantity, 2);
  assert.equal(remote.entries.c.quantity, 3);

  context.state.entries.a.quantity = 5;
  remote.entries.a.quantity = 8;
  revision = 'conflict';
  context.pokecardSyncNow();
  await settle();
  assert.equal(writes, 2, 'conflict must never upload');
  assert.equal(context.state.entries.a.quantity, 5);
  assert.match(notification, /conflitantes/);
  await context.pokecardSyncReply('login', { uid: 'outra-conta', email: 'other@example.com' });
  context.pokecardSyncStart();
  await settle();
  assert.equal(writes, 2, 'another account must never receive the local collection');
  assert.match(notification, /outra conta/);
  console.log('Ativação, combinação e preservação de conflitos: OK');
}

main().catch(error => { console.error(error); process.exitCode = 1; });
