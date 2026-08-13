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

  /* ---- Sprites melhorados (opcional) ----
     Quando existir um repositório com as versões em alta, o app passa a
     preferi-las. O manifesto diz quais Pokémon têm arquivo — sem ele o app
     tentaria baixar 1.025 endereços inexistentes, um por vez, a cada rolagem.

     Enquanto o endereço estiver vazio, nada muda: o app usa a fonte de
     sempre. É só preencher para ligar. */
  /* Branch separada do próprio repositório, servida pelo CDN. Enquanto o
     serviço "Gerar sprites HD" não tiver rodado, o manifesto não existe, a
     consulta falha em silêncio e o app segue usando a fonte de sempre. */
  /* DESLIGADO de propósito.

     Os 1.011 arquivos publicados nessa branch são WebP ANIMADOS de 126 KB cada
     — foram gerados quando a Pokédex ainda animava, justamente para deixar a
     animação mais nítida. Como eles têm preferência sobre qualquer outra fonte,
     passaram a ser a arte de verdade da lista: 180 na tela davam 22 MB e 180
     animações ao mesmo tempo, quatro vezes mais pesado que os GIFs originais.
     Era essa a lentidão.

     A branch continua no GitHub, intacta. Basta devolver o endereço aqui se um
     dia forem gerados sprites HD PARADOS. */
  var HD_BASE = '';
  var hdDisponiveis = null;   // Set com os números que têm versão HD
  var hdVersao = '';          // marca da geração, para renovar o que está guardado
  var hdConsultado = false;

  function carregarManifestoHD() {
    if (hdConsultado) return Promise.resolve(hdDisponiveis);
    hdConsultado = true;
    if (!HD_BASE) { hdDisponiveis = null; return Promise.resolve(null); }
    return fetch(HD_BASE + 'manifesto.json')
      .then(function (r) { return r.ok ? r.json() : null; })
      .then(function (dados) {
        if (!dados) { hdDisponiveis = null; return null; }
        hdVersao = String(dados._versao || '');
        var numeros = Object.keys(dados).filter(function (k) { return k.charAt(0) !== '_'; });
        hdDisponiveis = new Set(numeros);
        return hdDisponiveis;
      })
      .catch(function () { hdDisponiveis = null; return null; });
  }

  /* ---- Por que a animação saiu ----

     São até 180 Pokémon desenhados na lista ao mesmo tempo, cada GIF com 33 a
     47 quadros. O aparelho decodificava todos de uma vez e a rolagem engasgava.
     Limitar o desenho ao que está na tela ajudou, mas não resolveu: animação
     nenhuma sai de graça quando são dezenas ao mesmo tempo.

     Medido no CDN, por Pokémon e para 180 na tela:

       GIF animado (showdown, 60×60)       31,6 KB   →  5,5 MB   e anima
       HOME 3D (512×512)                   98,0 KB   → 17,2 MB   parado
       Ícone Switch (68×56)                 1,0 KB   →  0,2 MB   parado

     Daí os dois modos de hoje, ambos SEM animação:

       "leve"   — ícone Switch na lista. 27 vezes mais leve que o GIF e não
                  gasta nada para se manter na tela. É o padrão.
       "nitida" — arte 3D do HOME. Muito mais bonita, mas 98 KB cada: pesada
                  para a lista inteira, perfeita para a tela de um Pokémon só.

     A tela de detalhe usa sempre a arte grande, independentemente do modo:
     ali é UM Pokémon, e 98 KB não custam nada. */
  var CONJUNTOS = {
    leve: [
      { caminho: 'versions/generation-viii/icons/', ext: '.png', pixelada: true },  // 68×56
      { caminho: 'other/home/', ext: '.png', pixelada: false },                     // 512×512
      { caminho: '', ext: '.png', pixelada: true }                                  // 96×96
    ],
    nitida: [
      { caminho: 'other/home/', ext: '.png', pixelada: false },              // 512×512
      { caminho: 'other/official-artwork/', ext: '.png', pixelada: false },  // 475×475
      { caminho: 'versions/generation-viii/icons/', ext: '.png', pixelada: true }
    ]
  };

  function modoAtual() {
    try {
      var v = localStorage.getItem(PREFERENCIA_KEY);
      if (v === 'nitida') return 'nitida';
      // "animada" era o padrão antigo. Quem tinha essa preferência guardada
      // cairia num modo que não existe mais e ficaria sem arte nenhuma.
      if (v === 'animada') return 'leve';
    } catch (e) {}
    return 'leve';
  }

  /* `forcado` existe para a tela de um Pokémon só: ali sempre vale a arte
     grande, mesmo com o modo leve escolhido para a lista. Um Pokémon na tela
     custa 98 KB uma vez; a lista inteira custaria 17 MB. */
  function fontes(forcado) { return CONJUNTOS[forcado] || CONJUNTOS[modoAtual()] || CONJUNTOS.leve; }

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

  /** A versão melhorada, quando existir para este Pokémon. */
  function buscarHD(id) {
    return carregarManifestoHD().then(function (disponiveis) {
      if (!disponiveis || !disponiveis.has(String(id))) return null;
      return fetch(HD_BASE + id + '.webp').then(function (r) {
        if (!r.ok) throw new Error('HTTP ' + r.status);
        return r.blob();
      }).then(comoDataUrl).then(function (dataUrl) {
        // Arte reconstruída não é pixel art: desenhar suavizado fica melhor.
        return { dataUrl: dataUrl, pixelada: false, hd: true };
      }).catch(function () { return null; });
    });
  }

  /** Tenta a arte animada e, se não existir para este Pokémon, a parada. */
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

  /* A chave carrega o modo E a versão da geração HD.

     Cada arte fica guardada no aparelho para sempre — é isso que faz a Pokédex
     abrir sem baixar nada da segunda vez em diante. Só que "para sempre" tem
     um custo: se a chave não mudasse ao gerar sprites novos, quem já tivesse
     baixado continuaria vendo os antigos, sem jeito de atualizar. */
  function chaveDaArte(id, temHD, forcado) {
    return Number(id) + ':' + (forcado || modoAtual()) + (temHD ? ':hd' + hdVersao : '');
  }

  function carregar(img) {
    var id = Number(img.getAttribute('data-arte3d'));
    if (!id) return;
    // data-arte3d-modo="nitida" na tela de detalhe: ignora o modo da lista.
    var forcado = img.getAttribute('data-arte3d-modo') || '';
    if (!CONJUNTOS[forcado]) forcado = '';

    // O manifesto é consultado uma vez só; daí em diante responde na hora.
    carregarManifestoHD().then(function (disponiveis) {
      var temHD = Boolean(disponiveis && disponiveis.has(String(id)));
      var chave = chaveDaArte(id, temHD, forcado);
      if (falhou.has(chave)) return null;
      if (memoria.has(chave)) { aplicar(img, memoria.get(chave)); return null; }
      if (baixando.has(chave)) return null;
      baixando.add(chave);

      return lerGuardada(chave).then(function (guardada) {
        if (guardada && guardada.dataUrl) {
          memoria.set(chave, guardada);
          baixando.delete(chave);
          aplicar(img, guardada);
          return null;
        }
        // A versão melhorada tem preferência; sem ela, segue a fonte de sempre.
        return (temHD ? buscarHD(id) : Promise.resolve(null)).then(function (hd) {
          return hd || buscarNasFontes(id, 0, forcado);
        }).then(function (arte) {
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
    });
  }

  /** Apaga tudo o que está guardado e baixa de novo. */
  window.limparArtesGuardadas = function () {
    memoria.clear();
    falhou.clear();
    hdConsultado = false;
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

  /* Trocar entre leve e nítida: limpa o que está na tela e redesenha. */
  window.definirArtePokedex = function (modo) {
    try { localStorage.setItem(PREFERENCIA_KEY, modo === 'nitida' ? 'nitida' : 'leve'); } catch (e) {}
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
