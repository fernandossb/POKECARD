'use strict';

// Read the hydrated collection (IndexedDB/localStorage); never write to its storage.
function openTcgTrainer() {
  if (!state || !window.Android?.openTcgTrainer) {
    window.alert('O TCG Trainer está disponível no aplicativo Android atualizado do PokeCard.');
    return;
  }
  const counts = {};
  for (const id of Object.keys(state.entries || {})) {
    const quantity = quantityFor(id);
    if (Number.isSafeInteger(quantity) && quantity > 0 && quantity <= 1000000) counts[id] = quantity;
  }
  window.Android.openTcgTrainer(JSON.stringify({ version: 1, counts }));
}
