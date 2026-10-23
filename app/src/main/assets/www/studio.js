'use strict';
// Keep the artwork-cache tool formerly housed in the theme picker.
window.limparArtes = function () {
  if (typeof window.limparArtesGuardadas !== 'function') return;
  notify('Limpando…');
  window.limparArtesGuardadas().then(ok => notify(ok
    ? 'Artes apagadas. Serão baixadas de novo conforme você rolar.'
    : 'Não foi possível limpar agora.'));
};
