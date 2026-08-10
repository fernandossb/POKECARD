/* Troca os sprites antigos pelas artes 3D do Pokémon HOME conforme a lista
   é rolada.

   Baixar as 1.025 artes de uma vez seriam ~140 MB e travaria o aplicativo.
   Aqui cada arte só é buscada quando o Pokémon aparece na tela, e fica
   guardada no aparelho para as próximas vezes. Sem internet, o sprite local
   que já vem embutido continua valendo — a lista nunca fica vazia. */
(function () {
  'use strict';

  /* Arte animada primeiro. Além de dar vida à lista, ela é bem mais leve que
     o modelo 3D parado (~50 KB contra ~140 KB), o que ajuda numa tela que
     mostra centenas de Pokémon. Alguns da geração 9 ainda não têm animação;
     nesses casos entra o modelo parado. */
  var FONTES = [
    'https://cdn.jsdelivr.net/gh/PokeAPI/sprites@master/sprites/pokemon/other/showdown/',
    'https://cdn.jsdelivr.net/gh/PokeAPI/sprites@master/sprites/pokemon/other/home/'
  ];
  var EXTENSOES = ['.gif', '.png'];
  var DB_NOME = 'fichario-pokemon-arte3d';
  var LOJA = 'imagens';
  var memoria = new Map();
  var falhou = new Set();
  var baixando = new Set();
  var banco = null;

  function abrirBanco() {
    if (banco) return Promise.resolve(banco);
    return new Promise(function (resolve, reject) {
      var pedido = indexedDB.open(DB_NOME, 1);
      pedido.onupgradeneeded = function () {
        if (!pedido.result.objectStoreNames.contains(LOJA)) pedido.result.createObjectStore(LOJA);
      };
      pedido.onsuccess = function () { banco = pedido.result; resolve(banco); };
      pedido.onerror = function () { reject(pedido.error); };
    });
  }

  function lerGuardada(id) {
    return abrirBanco().then(function (db) {
      return new Promise(function (resolve) {
        var tx = db.transaction(LOJA, 'readonly');
        var req = tx.objectStore(LOJA).get(Number(id));
        req.onsuccess = function () { resolve(req.result || ''); };
        req.onerror = function () { resolve(''); };
      });
    }).catch(function () { return ''; });
  }

  function guardar(id, dataUrl) {
    abrirBanco().then(function (db) {
      var tx = db.transaction(LOJA, 'readwrite');
      tx.objectStore(LOJA).put(dataUrl, Number(id));
    }).catch(function () {});
  }

  /** Tenta a arte animada e, se não existir para este Pokémon, a parada. */
  function buscarNasFontes(id, indice) {
    if (indice >= FONTES.length) return Promise.reject(new Error('sem arte'));
    return fetch(FONTES[indice] + id + EXTENSOES[indice]).then(function (r) {
      if (!r.ok) throw new Error('HTTP ' + r.status);
      return r.blob();
    }).then(comoDataUrl).catch(function () {
      return buscarNasFontes(id, indice + 1);
    });
  }

  function comoDataUrl(blob) {
    return new Promise(function (resolve, reject) {
      var leitor = new FileReader();
      leitor.onload = function () { resolve(leitor.result); };
      leitor.onerror = reject;
      leitor.readAsDataURL(blob);
    });
  }

  function aplicar(img, dataUrl) {
    if (!img || !img.isConnected) return;
    img.src = dataUrl;
    img.style.imageRendering = 'auto';
    img.classList.add('arte3d-carregada');
  }

  function carregar(img) {
    var id = Number(img.getAttribute('data-arte3d'));
    if (!id || falhou.has(id)) return;

    if (memoria.has(id)) { aplicar(img, memoria.get(id)); return; }
    if (baixando.has(id)) return;
    baixando.add(id);

    lerGuardada(id).then(function (guardada) {
      if (guardada) {
        memoria.set(id, guardada);
        baixando.delete(id);
        aplicar(img, guardada);
        return null;
      }
      return buscarNasFontes(id, 0).then(function (dataUrl) {
        memoria.set(id, dataUrl);
        guardar(id, dataUrl);
        baixando.delete(id);
        aplicar(img, dataUrl);
        return null;
      });
    }).catch(function () {
      // Sem internet ou arte inexistente: fica o sprite local, sem erro na tela.
      baixando.delete(id);
      falhou.add(id);
    });
  }

  var observador = ('IntersectionObserver' in window)
    ? new IntersectionObserver(function (entradas) {
        entradas.forEach(function (entrada) {
          if (!entrada.isIntersecting) return;
          observador.unobserve(entrada.target);
          carregar(entrada.target);
        });
      }, { rootMargin: '250px' })
    : null;

  function registrar() {
    var alvos = document.querySelectorAll('img[data-arte3d]:not([data-arte3d-visto])');
    for (var i = 0; i < alvos.length; i++) {
      alvos[i].setAttribute('data-arte3d-visto', '1');
      if (observador) observador.observe(alvos[i]);
      else carregar(alvos[i]);
    }
  }

  // A lista é redesenhada a cada filtro ou rolagem, então observamos o DOM.
  var agendado = null;
  var vigia = new MutationObserver(function () {
    if (agendado) return;
    agendado = setTimeout(function () { agendado = null; registrar(); }, 120);
  });

  function iniciar() {
    registrar();
    vigia.observe(document.body, { childList: true, subtree: true });
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', iniciar);
  else iniciar();

  window.registrarArtes3D = registrar;
})();
