/* Tema por Pokémon favorito.

   O app usa o visual "Vision" (styles.css v3.5.0), que já centraliza todas as
   cores em variáveis --vision-*. Este arquivo só repinta essas variáveis com
   as cores do tipo do Pokémon escolhido e troca a imagem do mascote. Nenhuma
   regra de layout é reescrita e nenhuma lógica do app é tocada.

   A arte 3D vem do Pokémon HOME e pesa ~140 KB por Pokémon. Guardar as 1.025
   deixaria o aplicativo com mais de 140 MB, então o app baixa apenas a do tema
   escolhido e guarda no aparelho. Sem internet vale o sprite local, que já vem
   embutido para todos os 1.025. */
(function () {
  'use strict';

  var STORAGE_KEY = 'fichario-pokemon-tema-favorito-v1';
  var DEFAULT_ID = 94;
  var ART_BASE = 'https://cdn.jsdelivr.net/gh/PokeAPI/sprites@master/sprites/pokemon/other/home/';

  // bg = fundo geral · s1/s2/s3 = cartões · line = bordas
  // mut = texto secundário · pri = cor de destaque · soft = fundo do destaque
  //
  // dex/dexDark = a cor do "plástico" da Pokédex, bem mais viva. Ela veste
  // só o cabeçalho e a barra de abas — a área de conteúdo continua escura,
  // como as telas encaixadas no aparelho de verdade.
  var PLASTICO = {
    'Fantasma':  ['#9640dd', '#4a2070'],
    'Elétrico':  ['#f7c422', '#9a7008'],
    'Fogo':      ['#ef4a34', '#8a2412'],
    'Água':      ['#358ce0', '#14497e'],
    'Planta':    ['#4cc45f', '#155e28'],
    'Gelo':      ['#54cfe0', '#12626e'],
    'Lutador':   ['#e0654a', '#7e2a18'],
    'Venenoso':  ['#c05ad8', '#5e2470'],
    'Terrestre': ['#dfae4e', '#7e5a14'],
    'Voador':    ['#8fb0f5', '#33477e'],
    'Psíquico':  ['#f5608a', '#7e2540'],
    'Inseto':    ['#a8cc3a', '#4c5c15'],
    'Pedra':     ['#c9a962', '#5f4d14'],
    'Sombrio':   ['#9c8672', '#3f3327'],
    'Dragão':    ['#8b72ff', '#332270'],
    'Metálico':  ['#a3bccc', '#3d5058'],
    'Fada':      ['#f588c8', '#7e2a5a'],
    'Normal':    ['#c8bda0', '#4f4a3b']
  };

  var TYPES = {
    'Fantasma':  { bg:'#1c1230', s1:'#2a1c45', s2:'#332352', s3:'#3d2b60', line:'#553a7a', mut:'#c9bcd9', pri:'#c78aff', soft:'#3b2861' },
    'Elétrico':  { bg:'#241f0a', s1:'#352d10', s2:'#403716', s3:'#4b421c', line:'#665a27', mut:'#dbd0a6', pri:'#f7d040', soft:'#4a3f18' },
    'Fogo':      { bg:'#2a150c', s1:'#3b2013', s2:'#472819', s3:'#54311f', line:'#71442c', mut:'#dcbcab', pri:'#ff8f4d', soft:'#50291a' },
    'Água':      { bg:'#0d1e33', s1:'#152c49', s2:'#1a3557', s3:'#203f66', line:'#2d5688', mut:'#b4c8de', pri:'#5aabff', soft:'#1d3d63' },
    'Planta':    { bg:'#10251a', s1:'#193526', s2:'#1e402e', s3:'#244b37', line:'#316648', mut:'#b6d0bd', pri:'#67d47a', soft:'#204431' },
    'Gelo':      { bg:'#0d2429', s1:'#153439', s2:'#1a3f46', s3:'#204a53', line:'#2c6570', mut:'#b4d2d8', pri:'#78dfea', soft:'#1d4550' },
    'Lutador':   { bg:'#2a1512', s1:'#3b201b', s2:'#472822', s3:'#543129', line:'#714438', mut:'#dbb8ae', pri:'#ee7a5b', soft:'#4f2a22' },
    'Venenoso':  { bg:'#211230', s1:'#301c45', s2:'#3a2352', s3:'#452b60', line:'#5f3a7a', mut:'#d0bcd9', pri:'#d072e8', soft:'#432861' },
    'Terrestre': { bg:'#241c0b', s1:'#352a12', s2:'#403318', s3:'#4b3d1e', line:'#66532a', mut:'#d7c9a6', pri:'#e8b95c', soft:'#4a3c1a' },
    'Voador':    { bg:'#141a2b', s1:'#1e263c', s2:'#242e48', s3:'#2b3755', line:'#3c4b73', mut:'#bfc7dd', pri:'#a3bcf7', soft:'#293351' },
    'Psíquico':  { bg:'#2b1119', s1:'#3d1b26', s2:'#4a222e', s3:'#572a38', line:'#743a4d', mut:'#e0b3c0', pri:'#ff7a9c', soft:'#522433' },
    'Inseto':    { bg:'#1c220c', s1:'#2a3213', s2:'#333c19', s3:'#3d471f', line:'#53602b', mut:'#c9d3aa', pri:'#b6d84c', soft:'#3b451c' },
    'Pedra':     { bg:'#231c0d', s1:'#332a15', s2:'#3e341b', s3:'#493e21', line:'#63552e', mut:'#d5c9a8', pri:'#d4b673', soft:'#473c1e' },
    'Sombrio':   { bg:'#1a1613', s1:'#28221d', s2:'#312a24', s3:'#3a322b', line:'#50463c', mut:'#cec2b6', pri:'#b39c86', soft:'#382f28' },
    'Dragão':    { bg:'#161230', s1:'#221c45', s2:'#2a2352', s3:'#322b60', line:'#463a7a', mut:'#c1b8dd', pri:'#a48eff', soft:'#302861' },
    'Metálico':  { bg:'#141b21', s1:'#1e2731', s2:'#25303b', s3:'#2c3946', line:'#3e4e5e', mut:'#c3cdd6', pri:'#b0c8de', soft:'#2a3644' },
    'Fada':      { bg:'#2b1322', s1:'#3d1d31', s2:'#4a243c', s3:'#572c47', line:'#743d60', mut:'#e2b6d0', pri:'#ff96d4', soft:'#522742' },
    'Normal':    { bg:'#1b1913', s1:'#29251c', s2:'#322e23', s3:'#3b362a', line:'#514b3b', mut:'#cfc9b8', pri:'#d3c8a8', soft:'#383326' }
  };
  var FALLBACK = TYPES['Fantasma'];

  function pokedex() { return Array.isArray(window.__POKEDEX__) ? window.__POKEDEX__ : []; }

  function findPokemon(id) {
    var list = pokedex();
    for (var i = 0; i < list.length; i++) if (Number(list[i].id) === Number(id)) return list[i];
    return null;
  }

  function tipoPrincipal(pokemon) {
    var types = (pokemon && pokemon.types) || [];
    for (var i = 0; i < types.length; i++) if (TYPES[types[i]]) return types[i];
    return 'Fantasma';
  }

  function paletteFor(pokemon) {
    return TYPES[tipoPrincipal(pokemon)] || FALLBACK;
  }

  function spritePath(id) { return 'sprites/' + Number(id) + '.png'; }

  /* ---- Nível de claridade escolhido pelo usuário ----
     A cor de cada tipo continua vindo da tabela acima; o que muda aqui é o
     quanto o fundo e os cartões são claros. Os tons são recalculados na hora
     a partir do matiz da cor de destaque, então qualquer nível funciona para
     os 18 tipos sem precisar de tabela nova. */
  var CLARIDADE_KEY = 'fichario-pokemon-claridade-v1';
  /* Os níveis pulam a faixa média de luminosidade de propósito. Fundo com
     claridade entre 35% e 60% não dá contraste bom nem com letra clara nem
     com escura — testando, o texto caía para 3,9:1 ali. Pulando essa faixa,
     o pior caso das 18 cores × 6 níveis fica em 5,1:1. */
  var NIVEIS = [
    { nome: 'Bem escuro', fundo: 6,  passo: 5,  sat: 44 },
    { nome: 'Escuro',     fundo: 12, passo: 6,  sat: 40 },
    { nome: 'Médio',      fundo: 19, passo: 6,  sat: 36 },
    { nome: 'Suave',      fundo: 66, passo: -5, sat: 32 },
    { nome: 'Claro',      fundo: 82, passo: -4, sat: 28 },
    { nome: 'Bem claro',  fundo: 95, passo: -4, sat: 24 }
  ];
  var NIVEL_PADRAO = 2; // parecido com o que o app já mostrava

  function hexParaHsl(hex) {
    var r = parseInt(hex.substr(1, 2), 16) / 255;
    var g = parseInt(hex.substr(3, 2), 16) / 255;
    var b = parseInt(hex.substr(5, 2), 16) / 255;
    var max = Math.max(r, g, b), min = Math.min(r, g, b);
    var l = (max + min) / 2, h = 0, s = 0;
    if (max !== min) {
      var d = max - min;
      s = l > 0.5 ? d / (2 - max - min) : d / (max + min);
      if (max === r) h = ((g - b) / d + (g < b ? 6 : 0));
      else if (max === g) h = (b - r) / d + 2;
      else h = (r - g) / d + 4;
      h *= 60;
    }
    return { h: h, s: s * 100, l: l * 100 };
  }

  function hslParaHex(h, s, l) {
    s = Math.max(0, Math.min(100, s)) / 100;
    l = Math.max(0, Math.min(100, l)) / 100;
    var c = (1 - Math.abs(2 * l - 1)) * s;
    var x = c * (1 - Math.abs(((h / 60) % 2) - 1));
    var m = l - c / 2;
    var r = 0, g = 0, b = 0;
    if (h < 60) { r = c; g = x; }
    else if (h < 120) { r = x; g = c; }
    else if (h < 180) { g = c; b = x; }
    else if (h < 240) { g = x; b = c; }
    else if (h < 300) { r = x; b = c; }
    else { r = c; b = x; }
    var par = function (v) {
      var n = Math.round((v + m) * 255);
      return ('0' + Math.max(0, Math.min(255, n)).toString(16)).slice(-2);
    };
    return '#' + par(r) + par(g) + par(b);
  }

  function nivelSalvo() {
    try {
      var n = Number(localStorage.getItem(CLARIDADE_KEY));
      if (n >= 1 && n <= NIVEIS.length) return n;
    } catch (e) {}
    return NIVEL_PADRAO;
  }

  function hexParaRgba(hex, alfa) {
    var r = parseInt(hex.substr(1, 2), 16);
    var g = parseInt(hex.substr(3, 2), 16);
    var b = parseInt(hex.substr(5, 2), 16);
    return 'rgba(' + r + ',' + g + ',' + b + ',' + alfa + ')';
  }

  function luminanciaHex(hex) {
    var c = [1, 3, 5].map(function (i) {
      var v = parseInt(hex.substr(i, 2), 16) / 255;
      return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    });
    return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
  }

  function contraste(a, b) {
    var A = luminanciaHex(a), B = luminanciaHex(b);
    return (Math.max(A, B) + 0.05) / (Math.min(A, B) + 0.05);
  }

  /* Procura o tom mais próximo do desejado que ainda tenha contraste
     suficiente com o fundo. Sem isto, os níveis intermediários deixavam o
     texto quase invisível: fundo médio com letra clara não funciona. */
  function tomLegivel(h, s, fundo, alvo, comecarClaro) {
    var passo = comecarClaro ? -3 : 3;
    var inicio = comecarClaro ? 97 : 8;
    for (var l = inicio; l >= 0 && l <= 100; l += passo) {
      var cor = hslParaHex(h, s, l);
      if (contraste(cor, fundo) >= alvo) return cor;
    }
    return comecarClaro ? '#ffffff' : '#000000';
  }

  /** Monta os tons do tema a partir da cor do tipo e do nível escolhido. */
  function paletaComClaridade(base, nivelIndice) {
    var nivel = NIVEIS[nivelIndice - 1] || NIVEIS[NIVEL_PADRAO - 1];
    var h = hexParaHsl(base.pri).h;
    var s = nivel.sat;
    var f = nivel.fundo;
    var p = nivel.passo;

    var fundo = hslParaHex(h, s, f);
    var s1 = hslParaHex(h, s, f + p);
    var s2 = hslParaHex(h, s, f + p * 1.7);
    var s3 = hslParaHex(h, s, f + p * 2.4);
    var linha = hslParaHex(h, s + 6, f + p * 3.4);

    // Quem decide se a letra é clara ou escura é o próprio cartão, não o
    // número do nível — assim os tons do meio também ficam legíveis.
    var claro = luminanciaHex(s1) > 0.18;
    var texto = tomLegivel(h, 14, s1, 8.5, !claro);
    var mut = tomLegivel(h, 24, s1, 4.6, !claro);
    var pri = tomLegivel(h, 68, s1, 3.2, !claro);
    var soft = hslParaHex(h, s + 10, claro ? Math.min(95, f + p * 2.6) : f + p * 2);

    return {
      bg: fundo, s1: s1, s2: s2, s3: s3, line: linha,
      mut: mut, pri: pri, soft: soft, texto: texto, claro: claro
    };
  }

  // Plásticos claros (Elétrico, Gelo, Fada...) pedem texto escuro; escuros
  // pedem texto claro. Calculado na hora para valer também em cores futuras.
  function luminancia(hex) {
    var c = [1, 3, 5].map(function (i) {
      var v = parseInt(hex.substr(i, 2), 16) / 255;
      return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    });
    return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
  }

  // Compara o contraste real contra preto e contra branco e fica com o melhor,
  // em vez de usar um limiar fixo — que errava nos tons médios.
  function tintaSobre(hex) {
    var L = luminancia(hex);
    var contrasteBranco = 1.05 / (L + 0.05);
    var contratePreto = (L + 0.05) / 0.05;
    return contratePreto >= contrasteBranco ? '#16161a' : '#ffffff';
  }

  function savedId() {
    try {
      var raw = Number(localStorage.getItem(STORAGE_KEY));
      if (isFinite(raw) && raw > 0) return raw;
    } catch (e) {}
    return DEFAULT_ID;
  }

  /* Cinco artes diferentes para o mesmo Pokémon, sorteadas a cada abertura do
     app — assim o fundo não fica sempre igual. As quatro primeiras existem
     para todos os 1.025; as duas últimas faltam em alguns, e nesse caso o
     sorteio simplesmente cai na próxima da fila. */
  /* Só as artes animadas. A normal e a brilhante se revezam a cada abertura,
     dando variedade sem perder o movimento. Alguns Pokémon da geração 9 ainda
     não têm animação; para esses vale o modelo 3D parado, e por último o
     sprite local, que existe para todos e funciona sem internet. */
  var ARTES = [
    { id: 'anim',       caminho: 'other/showdown/',       ext: '.gif', animada: true },
    { id: 'anim-shiny', caminho: 'other/showdown/shiny/', ext: '.gif', animada: true }
  ];
  var ARTE_RESERVA = { id: 'home', caminho: 'other/home/', ext: '.png', animada: false };
  var SPRITES_BASE = 'https://cdn.jsdelivr.net/gh/PokeAPI/sprites@master/sprites/pokemon/';
  var DB_NOME = 'fichario-pokemon-arte-tema';
  var LOJA = 'artes';
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

  function lerGuardada(chave) {
    return abrirBanco().then(function (db) {
      return new Promise(function (resolve) {
        var req = db.transaction(LOJA, 'readonly').objectStore(LOJA).get(chave);
        req.onsuccess = function () { resolve(req.result || ''); };
        req.onerror = function () { resolve(''); };
      });
    }).catch(function () { return ''; });
  }

  function guardar(chave, dataUrl) {
    // IndexedDB e não localStorage: são várias artes de ~140 KB e a cota do
    // localStorage estouraria já na segunda.
    abrirBanco().then(function (db) {
      db.transaction(LOJA, 'readwrite').objectStore(LOJA).put(dataUrl, chave);
    }).catch(function () {});
  }

  function embaralhar(lista) {
    var copia = lista.slice();
    for (var i = copia.length - 1; i > 0; i--) {
      var j = Math.floor(Math.random() * (i + 1));
      var t = copia[i]; copia[i] = copia[j]; copia[j] = t;
    }
    return copia;
  }

  /** Tenta as artes em ordem sorteada até uma funcionar. */
  function buscarArte(id, aoConcluir) {
    var fila = embaralhar(ARTES).concat([ARTE_RESERVA]);

    function tentar(indice) {
      if (indice >= fila.length) return; // nenhuma deu certo: fica o sprite local
      var arte = fila[indice];
      var chave = Number(id) + ':' + arte.id;

      lerGuardada(chave).then(function (guardada) {
        if (guardada) { aoConcluir(guardada, arte.id); return null; }
        return fetch(SPRITES_BASE + arte.caminho + Number(id) + arte.ext).then(function (r) {
          if (!r.ok) throw new Error('HTTP ' + r.status);
          return r.blob();
        }).then(function (blob) {
          return new Promise(function (resolve, reject) {
            var leitor = new FileReader();
            leitor.onload = function () { resolve(leitor.result); };
            leitor.onerror = reject;
            leitor.readAsDataURL(blob);
          });
        }).then(function (dataUrl) {
          guardar(chave, dataUrl);
          aoConcluir(dataUrl, arte.id);
          return null;
        });
      }).catch(function () { tentar(indice + 1); });
    }

    tentar(0);
  }

  function aplicarImagem(url, ehArte3d) {
    var root = document.documentElement;
    root.style.setProperty('--theme-wallpaper', 'url("' + url + '")');
    root.style.setProperty('--theme-wallpaper-size', ehArte3d ? '84vw' : '74vw');
    root.style.setProperty('--theme-wallpaper-pos', 'center 8%');
    // Arte escura sobre fundo escuro some com opacidade baixa: a 12% não dava
    // para ver nada. No tema claro o contrário — precisa baixar, senão a arte
    // briga com o texto.
    var claroAgora = window.__TEMA_CLARO__ === true;
    root.style.setProperty('--theme-wallpaper-opacity',
      ehArte3d ? (claroAgora ? '.16' : '.32') : (claroAgora ? '.10' : '.20'));
    root.style.setProperty('--theme-wallpaper-render', ehArte3d ? 'auto' : 'pixelated');
    root.style.setProperty('--theme-veil', 'rgba(0,0,0,.82)');
    var art = document.querySelector('.gengar-header-art');
    if (art) {
      art.src = url;
      art.style.imageRendering = ehArte3d ? 'auto' : 'pixelated';
    }
  }

  function apply(id) {
    var pokemon = findPokemon(id);
    var p = paletaComClaridade(paletteFor(pokemon), nivelSalvo());
    var root = document.documentElement;

    root.style.setProperty('--vision-bg', p.bg);
    root.style.setProperty('--vision-surface', p.s1);
    // Versão semitransparente do cartão: deixa a arte do favorito respirar
    // por trás. Feita aqui em rgba porque color-mix não existe em WebView
    // antigo e o cartão ficaria sem cor nenhuma.
    root.style.setProperty('--vision-surface-soft', hexParaRgba(p.s1, 0.88));
    root.style.setProperty('--vision-surface-2', p.s2);
    root.style.setProperty('--vision-surface-3', p.s3);
    root.style.setProperty('--vision-line', p.line);
    root.style.setProperty('--vision-muted', p.mut);
    root.style.setProperty('--vision-primary', p.pri);
    root.style.setProperty('--vision-primary-soft', p.soft);
    // O brilho do mascote acompanha a cor de destaque.
    root.style.setProperty('--theme-glow', p.pri);

    // No nível bem claro a letra vira escura e o visor deixa de ser um poço
    // preto — senão o texto sumiria e os cartões ficariam manchados.
    root.style.setProperty('--vision-text', p.texto);
    // Guardado para a marca d'água saber se o fundo está claro ou escuro.
    window.__TEMA_CLARO__ = p.claro === true;
    root.style.setProperty('--dex-visor', p.claro ? 'rgba(0,0,0,.07)' : 'rgba(0,0,0,.42)');
    root.style.setProperty('--dex-sink', p.claro
      ? 'inset 0 2px 5px rgba(0,0,0,.16), inset 0 -1px 0 rgba(255,255,255,.7)'
      : 'inset 0 3px 8px rgba(0,0,0,.55), inset 0 -1px 0 rgba(255,255,255,.10)');
    root.style.setProperty('--dex-plastic', p.claro
      ? 'linear-gradient(180deg, rgba(255,255,255,.26), rgba(0,0,0,.08))'
      : 'linear-gradient(180deg, rgba(255,255,255,.16), rgba(0,0,0,.14))');

    // Plástico da Pokédex: cabeçalho e barra de abas.
    var plastico = PLASTICO[tipoPrincipal(pokemon)] || PLASTICO['Fantasma'];
    root.style.setProperty('--dex-body', plastico[0]);
    root.style.setProperty('--dex-body-dark', plastico[1]);
    root.style.setProperty('--dex-body-text', tintaSobre(plastico[0]));
    root.style.setProperty('--dex-body-shadow', tintaSobre(plastico[0]) === '#ffffff'
      ? '0 1px 2px rgba(0,0,0,.5)'
      : '0 1px 1px rgba(255,255,255,.45)');
    root.style.setProperty('--dex-lcd-text', p.mut);

    try { localStorage.setItem(STORAGE_KEY, String(id)); } catch (e) {}
    window.__TEMA_ATUAL__ = {
      id: id,
      nome: pokemon ? pokemon.name : 'Gengar',
      tipo: (pokemon && pokemon.types && pokemon.types[0]) || 'Fantasma'
    };

    // Mostra o sprite local na hora e troca pela arte sorteada quando chegar.
    aplicarImagem(spritePath(id), false);
    buscarArte(id, function (dataUrl) { aplicarImagem(dataUrl, true); });

    // O fundo mudou: quem cuida do contraste precisa refazer as contas, porque
    // as correções antigas foram calculadas contra as cores anteriores.
    try { window.dispatchEvent(new CustomEvent('tema-aplicado')); } catch (e) {}
  }

  function nomeAtual() {
    return window.__TEMA_ATUAL__ ? window.__TEMA_ATUAL__.nome : 'Gengar';
  }

  function tipoAtual() {
    return window.__TEMA_ATUAL__ ? window.__TEMA_ATUAL__.tipo : 'Fantasma';
  }

  // ---- Tela de escolha ----
  var busca = '';

  function listaFiltrada() {
    var termo = String(busca || '').trim().toLowerCase();
    var list = pokedex();
    if (!termo) return list.slice(0, 60);
    var found = [];
    for (var i = 0; i < list.length && found.length < 60; i++) {
      var p = list[i];
      if (String(p.name).toLowerCase().indexOf(termo) >= 0 || String(p.id) === termo) found.push(p);
    }
    return found;
  }

  function grade() {
    var list = listaFiltrada();
    if (!list.length) return '<div class="tema-grade"><div class="empty">Nenhum Pokémon encontrado.</div></div>';
    var atual = savedId();
    return '<div class="tema-grade">' + list.map(function (p) {
      return '<button type="button" class="tema-item' + (Number(p.id) === Number(atual) ? ' ativo' : '') + '"'
        + ' onclick="escolherTemaPokemon(' + p.id + ')">'
        + '<img src="' + spritePath(p.id) + '" alt="" loading="lazy">'
        + '<span>' + p.name + '</span></button>';
    }).join('') + '</div>';
  }

  function cabecalho() {
    return '<div class="tema-atual">Tema agora: <strong>' + nomeAtual() + '</strong> · tipo ' + tipoAtual() + '</div>';
  }

  function barraClaridade() {
    var n = nivelSalvo();
    return '<div class="claridade-caixa">'
      + '<div class="claridade-topo"><span>Claridade do app</span><b id="claridadeNome">' + NIVEIS[n - 1].nome + '</b></div>'
      + '<div class="claridade-linha">'
      + '<span class="claridade-icone">🌑</span>'
      + '<input type="range" id="claridadeBarra" min="1" max="' + NIVEIS.length + '" step="1" value="' + n + '"'
      + ' oninput="ajustarClaridade(this.value)">'
      + '<span class="claridade-icone">☀️</span>'
      + '</div>'
      + '<small>Vale para qualquer Pokémon escolhido. A mudança aparece na hora.</small>'
      + '</div>';
  }

  /* Animada é 60×60 e anima; nítida é 512×512 e fica parada. Não existe
     fonte que seja as duas coisas — a única que anima é pixel art. */
  function barraArtePokedex() {
    var modo = (typeof window.arteDaPokedex === 'function') ? window.arteDaPokedex() : 'animada';
    return '<div class="claridade-caixa">'
      + '<div class="claridade-topo"><span>Arte da Pokédex</span></div>'
      + '<div class="arte-opcoes">'
      + '<button type="button" class="arte-opcao' + (modo === 'animada' ? ' ativo' : '') + '"'
      + ' onclick="trocarArtePokedex(\'animada\')"><strong>Animada</strong><small>Mexe. Pixel art de 60px, desenhada sem borrão.</small></button>'
      + '<button type="button" class="arte-opcao' + (modo === 'nitida' ? ' ativo' : '') + '"'
      + ' onclick="trocarArtePokedex(\'nitida\')"><strong>Nítida</strong><small>Modelo 3D de 512px. Bem mais definida, mas parada.</small></button>'
      + '</div>'
      + '<button type="button" class="arte-limpar" onclick="limparArtes()">Baixar as artes de novo</button>'
      + '<small>As artes ficam guardadas no aparelho e não são baixadas de novo. Use isto se gerar sprites novos ou quiser liberar espaço.</small>'
      + '</div>';
  }

  window.limparArtes = function () {
    if (typeof window.limparArtesGuardadas !== 'function') return;
    if (typeof notify === 'function') notify('Limpando…');
    window.limparArtesGuardadas().then(function (ok) {
      if (typeof notify === 'function') {
        notify(ok ? 'Artes apagadas. Serão baixadas de novo conforme você rolar.' : 'Não foi possível limpar agora.');
      }
    });
  };

  window.trocarArtePokedex = function (modo) {
    if (typeof window.definirArtePokedex === 'function') window.definirArtePokedex(modo);
    var caixa = document.querySelector('.arte-opcoes');
    if (caixa) caixa.parentElement.outerHTML = barraArtePokedex();
    if (typeof notify === 'function') notify(modo === 'nitida' ? 'Arte nítida (parada).' : 'Arte animada.');
  };

  function corpo() {
    return '<button class="modal-close" onclick="closeModal()" aria-label="Fechar">×</button>'
      + '<h2>Tema do aplicativo</h2>'
      + '<p class="screen-subtitle">Escolha seu Pokémon favorito. O app assume as cores do tipo dele e mostra a arte no topo e ao fundo.</p>'
      + cabecalho()
      + barraClaridade()
      + barraArtePokedex()
      + '<input class="field" placeholder="Buscar Pokémon por nome ou número" oninput="filtrarTemaPokemon(this.value)">'
      + grade();
  }

  function redesenhar() {
    var alvo = document.querySelector('.tema-grade');
    if (alvo) alvo.outerHTML = grade();
    var atualEl = document.querySelector('.tema-atual');
    if (atualEl) atualEl.outerHTML = cabecalho();
  }

  window.abrirTemaPokemon = function () {
    busca = '';
    if (typeof showModal === 'function') showModal(corpo());
  };
  window.ajustarClaridade = function (valor) {
    var n = Math.max(1, Math.min(NIVEIS.length, Number(valor) || NIVEL_PADRAO));
    try { localStorage.setItem(CLARIDADE_KEY, String(n)); } catch (e) {}
    var rotulo = document.getElementById('claridadeNome');
    if (rotulo) rotulo.textContent = NIVEIS[n - 1].nome;
    apply(savedId());
  };
  window.filtrarTemaPokemon = function (valor) { busca = valor; redesenhar(); };
  window.escolherTemaPokemon = function (id) {
    apply(Number(id));
    redesenhar();
    if (typeof notify === 'function') notify('Tema alterado para ' + nomeAtual() + '.');
  };

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', function () { apply(savedId()); });
  } else {
    apply(savedId());
  }
})();
