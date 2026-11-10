const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const merge = require('../app/src/main/assets/www/sync-merge.js');

async function main() {
  const stored = new Map();
  let remote = null;
  let revision = '';
  let writes = 0;
  let downloads = 0;
  let failDurableSave = false;
  let notification = '';
  const context = {
    console, JSON, Map, Promise, Error, clearTimeout, PokecardSyncMerge: merge,
    setTimeout: () => 1, setInterval: () => 1,
    state: { version: 2, entries: { a: { quantity: 1 } }, decks: [], importedAt: null },
    document: { hidden: false, addEventListener() {}, querySelector() { return null; } },
    esc: String, confirm: () => true, notify: value => { notification = value; },
    openBackupPanel() {}, salvarBackupDeEmergencia() {}, saveState() {},
    saveStateNow() {}, render() {}, migrateState: value => value,
    textoParaGravar: () => '{}',
    gravarColecaoNoBanco: async () => {
      if (failDurableSave) throw new Error('indexeddb unavailable');
    },
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
    syncRead(id, knownRevision) {
      let result = { revision: '' };
      if (revision && revision === knownRevision) result = { revision, unchanged: true };
      else if (revision) {
        downloads++;
        result = { revision, data: JSON.stringify(remote) };
      }
      queueMicrotask(() => context.pokecardSyncReply(id, result));
    },
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
  context.pokecardSyncNow();
  await settle();
  assert.equal(downloads, 0, 'an unchanged revision must not download the collection');
  assert.equal(writes, 1, 'an unchanged collection must not be uploaded');

  context.state.entries.b = { quantity: 2 };
  context.pokecardSyncChanged();
  remote.entries.c = { quantity: 3 };
  revision = 'remote-change';
  context.pokecardSyncNow();
  await settle();
  assert.equal(writes, 2);
  assert.equal(remote.entries.b.quantity, 2);
  assert.equal(remote.entries.c.quantity, 3);

  remote.entries.d = { quantity: 7 };
  revision = 'durability-check';
  failDurableSave = true;
  context.pokecardSyncNow();
  await settle();
  assert.equal(stored.get('user').revisao, '2', 'failed local persistence must not advance the base');
  assert.match(notification, /pendente/);
  failDurableSave = false;
  context.pokecardSyncNow();
  await settle();
  assert.equal(stored.get('user').revisao, 'durability-check');

  context.state.entries.a.quantity = 5;
  context.pokecardSyncChanged();
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
  stored.clear();
  remote = { version: 2, entries: { pikachu: { quantity: 4 } }, decks: [], importedAt: null };
  revision = 'poco';
  context.state = { version: 2, entries: {}, decks: [{ id: 'starter', tcgTrainer: true }], importedAt: null };
  await context.pokecardSyncReply('login', { uid: 'tablet', email: 'test@example.com' });
  context.pokecardSyncNow();
  await settle();
  assert.match(context.pokecardSyncPanel(), /Usar coleção da nuvem/);
  assert.equal(context.state.decks[0].id, 'starter');
  context.pokecardSyncRestore();
  await settle();
  assert.equal(context.state.entries.pikachu.quantity, 4);
  assert.equal(stored.get('tablet').revisao, 'poco');
  assert.equal(writes, 2, 'restoring must not overwrite the cloud collection');
  const downloadsBefore = downloads;
  context.state.entries.pikachu.quantity = 5;
  context.pokecardSyncChanged();
  context.pokecardSyncNow();
  await settle();
  assert.equal(writes, 3, 'local changes must upload even if the remote revision is unchanged');
  assert.equal(remote.entries.pikachu.quantity, 5);
  assert.equal(downloads, downloadsBefore, 'local changes must not force an unchanged download');
  console.log('Ativação, combinação e preservação de conflitos: OK');
}

main().catch(error => { console.error(error); process.exitCode = 1; });
