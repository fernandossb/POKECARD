/* Troca os sprites antigos pelas artes 3D do Pokémon HOME conforme a lista
   é rolada.

   Baixar as 1.025 artes de uma vez seriam ~140 MB e travaria o aplicativo.
   Aqui cada arte só é buscada quando o Pokémon aparece na tela, e fica
   guardada no aparelho para as próximas vezes. Sem internet, o sprite local
   que já vem embutido continua valendo — a lista nunca fica vazia. */
(function () {
  'use strict';

  /* A arte animada continua sendo a principal — é a que dá vida à lista.

     Ela existe numa resolução só: 60×60 pixels. Numa lista que mostra o
     Pokémon a 82 pontos, num celular de densidade 3×, isso é uma ampliação de
     quatro vezes. Não existe fonte animada maior: o Pokémon Showdown, de onde
     ela vem, é a única que anima, e é pixel art por natureza.

     O que estragava a imagem não era só o tamanho: era o navegador SUAVIZANDO
     a ampliação. Suavizar pixel art borra as bordas e transforma pixels
     nítidos em manchas. Desenhando com os pixels preservados, a mesma imagem
     fica limpa e proposital, como num jogo antigo.

     Quem preferir nitidez absoluta pode trocar pelo modelo 3D de 512×512 nos
     ajustes do tema — mas aí ele fica parado. */
  var BASE = 'https://cdn.jsdelivr.net/gh/PokeAPI/sprites@master/sprites/pokemon/';
  var PREFERENCIA_KEY = 'pokecard-arte-pokedex-v1';

  var CONJUNTOS = {
    animada: [
      { caminho: 'other/showdown/', ext: '.gif', pixelada: true },   //  60×60, anima
      { caminho: 'other/home/', ext: '.png', pixelada: false }       // 512×512, parada
    ],
    nitida: [
      { caminho: 'other/home/', ext: '.png', pixelada: false },              // 512×512
      { caminho: 'other/official-artwork/', ext: '.png', pixelada: false },  // 475×475
      { caminho: 'other/showdown/', ext: '.gif', pixelada: true }
    ]
  };

  function modoAtual() {
    try {
      var v = localStorage.getItem(PREFERENCIA_KEY);
      if (v === 'nitida') return 'nitida';
    } catch (e) {}
    return 'animada';
  }

  function fontes() { return CONJUNTOS[modoAtual()] || CONJUNTOS.animada; }

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

  /** Tenta a arte animada e, se não existir para este Pokémon, a parada. */
  function buscarNasFontes(id, indice) {
    var lista = fontes();
    if (indice >= lista.length) return Promise.reject(new Error('sem arte'));
    var fonte = lista[indice];
    return fetch(BASE + fonte.caminho + id + fonte.ext).then(function (r) {
      if (!r.ok) throw new Error('HTTP ' + r.status);
      return r.blob();
    }).then(comoDataUrl).then(function (dataUrl) {
      // Guardamos junto se a arte é pixel art: é o que decide como desenhar.
      return { dataUrl: dataUrl, pixelada: fonte.pixelada };
    }).catch(function () {
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

  /* A arte guardada leva o modo junto na chave: trocar entre animada e nítida
     não pode devolver a imagem do modo anterior. */
  function chaveDaArte(id) { return Number(id) + ':' + modoAtual(); }

  function carregar(img) {
    var id = Number(img.getAttribute('data-arte3d'));
    if (!id) return;
    var chave = chaveDaArte(id);
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
      return buscarNasFontes(id, 0).then(function (arte) {
        memoria.set(chave, arte);
        guardar(chave, arte);
        baixando.delete(chave);
        aplicar(img, arte);
        return null;
      });
    }).catch(function () {
      // Sem internet ou arte inexistente: fica o sprite local, sem erro na tela.
      baixando.delete(chave);
      falhou.add(chave);
    });
  }

  /* Trocar entre animada e nítida: limpa o que está na tela e redesenha. */
  window.definirArtePokedex = function (modo) {
    try { localStorage.setItem(PREFERENCIA_KEY, modo === 'nitida' ? 'nitida' : 'animada'); } catch (e) {}
    memoria.clear();
    falhou.clear();
    var alvos = document.querySelectorAll('img[data-arte3d]');
    for (var i = 0; i < alvos.length; i++) {
      alvos[i].removeAttribute('data-arte3d-visto');
      alvos[i].classList.remove('arte3d-carregada');
    }
    registrar();
  };
  window.arteDaPokedex = modoAtual;

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
