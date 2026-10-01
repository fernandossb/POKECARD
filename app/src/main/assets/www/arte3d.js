/* Troca os sprites antigos pelas artes 3D do Pokémon HOME conforme a lista
   é rolada.

   Baixar as 1.025 artes de uma vez seriam ~140 MB e travaria o aplicativo.
   Aqui cada arte só é buscada quando o Pokémon aparece na tela, e fica
   guardada no aparelho para as próximas vezes. Sem internet, o sprite local
   que já vem embutido continua valendo — a lista nunca fica vazia. */
(function () {
  'use strict';

  var BASE = 'https://cdn.jsdelivr.net/gh/PokeAPI/sprites@master/sprites/pokemon/';
  var PREFERENCIA_KEY = 'pokecard-arte-pokedex-v1';

  /* ---- Por que a animação saiu ----

     São até 180 Pokémon desenhados na lista ao mesmo tempo, cada GIF com 33 a
     47 quadros. O aparelho decodificava todos de uma vez e a rolagem engasgava.
     Limitar o desenho ao que está na tela ajudou, mas não resolveu: animação
     nenhuma sai de graça quando são dezenas ao mesmo tempo.

     Medido no CDN, por Pokémon e para 180 na tela:

       GIF animado (showdown, 60×60)       31,6 KB   →  5,5 MB   e anima
       HOME 3D (512×512)                   98,0 KB   → 17,2 MB   parado
       Ícone Switch (68×56)                 1,0 KB   →  0,2 MB   parado

     Desde a 5.79 a lista usa só a arte 3D do HOME (a "nítida"): o modo
     "leve", com o ícone pixelado do Switch, saiu — uma escolha a menos para
     quem usa. Se o HOME não tiver a arte, vale a arte oficial (também lisa).
     Sem internet, continua o sprite local que já vem no app. */
  var CONJUNTOS = {
    nitida: [
      { caminho: 'other/home/', ext: '.png', pixelada: false },              // 512×512
      { caminho: 'other/official-artwork/', ext: '.png', pixelada: false }   // 475×475
    ]
  };

  function modoAtual() { return 'nitida'; }

  // A preferência antiga (leve/nítida) não existe mais, e as artes leves
  // guardadas no aparelho ("25:leve") não têm mais uso: libera o espaço uma vez.
  try {
    if (!localStorage.getItem('pokecard-arte-leve-limpa')) {
      localStorage.setItem('pokecard-arte-leve-limpa', '1');
      localStorage.removeItem(PREFERENCIA_KEY);
      setTimeout(function () {
        abrirBanco().then(function (db) {
          var loja = db.transaction(LOJA, 'readwrite').objectStore(LOJA);
          var cursor = loja.openCursor();
          cursor.onsuccess = function () {
            var c = cursor.result;
            if (!c) return;
            if (/:(leve|animada)/.test(String(c.key))) c.delete();
            c.continue();
          };
        }).catch(function () {});
      }, 4000);
    }
  } catch (e) {}

  /* `forcado` vinha da tela de um Pokémon só (data-arte3d-modo="nitida"),
     quando a lista podia estar no modo leve. Hoje tudo é 3D; fica aceito
     para as telas que ainda mandam o atributo. */
  function fontes(forcado) { return CONJUNTOS[forcado] || CONJUNTOS[modoAtual()] || CONJUNTOS.nitida; }

  var DB_NOME = 'fichario-pokemon-arte3d';
  var LOJA = 'imagens';
  var memoria = new Map();
  var falhou = new Set();
  var baixando = new Set();
  var banco = null;

  /* Abre o banco garantindo que a loja existe.
     Um banco pode acabar criado na versão certa mas SEM a loja dentro — basta
     alguém abri-lo sem tratar a criação, ou uma atualização interrompida. Aí
     toda leitura falha em silêncio e nenhuma arte aparece, sem erro na tela.
     Quando isso acontece, subimos a versão para forçar a criação. */
  function abrirBanco() {
    if (banco) return Promise.resolve(banco);
    return new Promise(function (resolve, reject) {
      var pedido = indexedDB.open(DB_NOME);
      pedido.onupgradeneeded = function () {
        if (!pedido.result.objectStoreNames.contains(LOJA)) pedido.result.createObjectStore(LOJA);
      };
      pedido.onsuccess = function () {
        var db = pedido.result;
        if (db.objectStoreNames.contains(LOJA)) { banco = db; resolve(db); return; }
        // Loja faltando: reabre uma versão acima só para criá-la.
        var versao = db.version + 1;
        db.close();
        var reparo = indexedDB.open(DB_NOME, versao);
        reparo.onupgradeneeded = function () {
          if (!reparo.result.objectStoreNames.contains(LOJA)) reparo.result.createObjectStore(LOJA);
        };
        reparo.onsuccess = function () { banco = reparo.result; resolve(banco); };
        reparo.onerror = function () { reject(reparo.error); };
      };
      pedido.onerror = function () { reject(pedido.error); };
    });
  }

  /* A chave é texto ("25:animada"), não número: ela carrega o modo junto para
     que trocar entre animada e nítida não devolva a imagem do modo anterior.
     Converter para número aqui produzia NaN, que o banco recusa — e nenhuma
     arte era guardada nem lida. */
  function lerGuardada(chave) {
    return abrirBanco().then(function (db) {
      return new Promise(function (resolve) {
        var tx = db.transaction(LOJA, 'readonly');
        var req = tx.objectStore(LOJA).get(String(chave));
        req.onsuccess = function () { resolve(req.result || null); };
        req.onerror = function () { resolve(null); };
      });
    }).catch(function () { return null; });
  }

  function guardar(chave, arte) {
    abrirBanco().then(function (db) {
      var tx = db.transaction(LOJA, 'readwrite');
      tx.objectStore(LOJA).put(arte, String(chave));
    }).catch(function () {});
  }

  /** Tenta a arte do HOME e, se não existir para este Pokémon, a oficial. */
  function buscarNasFontes(id, indice, forcado) {
    var lista = fontes(forcado);
    if (indice >= lista.length) return Promise.reject(new Error('sem arte'));
    var fonte = lista[indice];
    return fetch(BASE + fonte.caminho + id + fonte.ext).then(function (r) {
      if (!r.ok) throw new Error('HTTP ' + r.status);
      return r.blob();
    }).then(comoDataUrl).then(function (dataUrl) {
      // Guardamos junto se a arte é pixel art: é o que decide como desenhar.
      return { dataUrl: dataUrl, pixelada: fonte.pixelada };
    }).catch(function () {
      return buscarNasFontes(id, indice + 1, forcado);
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

  function aplicar(img, arte) {
    if (!img || !img.isConnected || !arte) return;
    img.src = arte.dataUrl;
    /* Aqui está a diferença que tira o borrão.

       O navegador, por padrão, suaviza toda imagem ampliada — ótimo para
       fotografia, péssimo para pixel art: cada pixel vira um borrão e a figura
       perde a forma. Pedindo para preservar os pixels, a mesma arte de 60×60
       sobe para o tamanho da lista com as bordas limpas. */
    img.style.imageRendering = arte.pixelada ? 'pixelated' : 'auto';
    img.classList.add('arte3d-carregada');
    img.classList.toggle('arte-pixelada', Boolean(arte.pixelada));
  }

  /* Cada arte fica guardada no aparelho para sempre — é isso que faz a
     Pokédex abrir sem baixar nada da segunda vez em diante. A chave leva o
     modo ("25:nitida"), o mesmo formato de antes, para nada ser baixado de novo. */
  function chaveDaArte(id, forcado) {
    return Number(id) + ':' + (forcado || modoAtual());
  }

  function carregar(img) {
    var id = Number(img.getAttribute('data-arte3d'));
    if (!id) return;
    // data-arte3d-modo="nitida" na tela de detalhe: ignora o modo da lista.
    var forcado = img.getAttribute('data-arte3d-modo') || '';
    if (!CONJUNTOS[forcado]) forcado = '';

    var chave = chaveDaArte(id, forcado);
    if (falhou.has(chave)) return;
    if (memoria.has(chave)) { aplicar(img, memoria.get(chave)); return; }
    if (baixando.has(chave)) return;
    baixando.add(chave);

    lerGuardada(chave).then(function (guardada) {
      if (guardada && guardada.dataUrl) {
        memoria.set(chave, guardada);
        baixando.delete(chave);
        aplicar(img, guardada);
        return null;
      }
      return buscarNasFontes(id, 0, forcado).then(function (arte) {
        memoria.set(chave, arte);
        guardar(chave, arte);
        baixando.delete(chave);
        aplicar(img, arte);
        return null;
      });
    }).catch(function () {
      // Sem internet ou arte inexistente: fica o sprite local, sem erro.
      baixando.delete(chave);
      falhou.add(chave);
    });
  }

  /** Apaga tudo o que está guardado e baixa de novo. */
  window.limparArtesGuardadas = function () {
    memoria.clear();
    falhou.clear();
    return abrirBanco().then(function (db) {
      return new Promise(function (resolve) {
        var tx = db.transaction(LOJA, 'readwrite');
        tx.objectStore(LOJA).clear();
        tx.oncomplete = function () { resolve(true); };
        tx.onerror = function () { resolve(false); };
      });
    }).then(function (ok) {
      var alvos = document.querySelectorAll('img[data-arte3d]');
      for (var i = 0; i < alvos.length; i++) {
        alvos[i].removeAttribute('data-arte3d-visto');
        alvos[i].classList.remove('arte3d-carregada');
      }
      registrar();
      return ok;
    }).catch(function () { return false; });
  };

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

})();
