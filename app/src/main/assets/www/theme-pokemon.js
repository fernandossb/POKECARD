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


  function pokedex() { return Array.isArray(window.__POKEDEX__) ? window.__POKEDEX__ : []; }

  function findPokemon(id) {
    var list = pokedex();
    for (var i = 0; i < list.length; i++) if (Number(list[i].id) === Number(id)) return list[i];
    return null;
  }

  function tipoPrincipal(pokemon) {
    var types = (pokemon && pokemon.types) || [];
    for (var i = 0; i < types.length; i++) if (PLASTICO[types[i]]) return types[i];
    return 'Fantasma';
  }

  function spritePath(id) { return 'sprites/' + Number(id) + '.png'; }

  /* ---- Claridade: uma só, a "Suave" ----
     Até a 5.78 havia uma barra com seis níveis (do bem escuro ao bem claro).
     Desde a 5.79 todos os temas usam o Suave: fundo a 68% de luz e os cartões
     8 pontos mais escuros a cada camada. Os outros níveis saíram da tabela; a
     preferência antiga, guardada no aparelho, é apagada. */
  var NIVEIS = [
    { nome: 'Suave', fundo: 68, passo: -8, sat: 34 }
  ];
  var NIVEL_PADRAO = 1;
  try { localStorage.removeItem('fichario-pokemon-claridade-v1'); } catch (e) {}

  /* ---- As duas cores do Pokémon ----
     Medidas no sprite dele (data/cores-pokemon.js, gerado por
     scripts/gerar-cores-pokemon.cjs): a cor 1, a que mais aparece no corpo,
     vai no FUNDO da tela; a cor 2 vai nos CARTÕES e BOTÕES — o Venusaur fica
     com fundo verde e botões vermelhos, da flor. Pokémon de uma cor só usa a
     cor do tipo como segunda (ou um vizinho no círculo de cores, se o tipo
     tiver a mesma cor do corpo). Sem a medida, as duas saem do tipo. */
  function coresDoPokemon(pokemon) {
    var medida = pokemon && (window.__CORES_POKEMON__ || {})[pokemon.id];
    var tipos = (pokemon && pokemon.types) || [];
    var doTipo = function (tipo) {
      var c = PLASTICO[tipo] ? hexParaHsl(PLASTICO[tipo][0]) : null;
      return c ? { h: Math.round(c.h), s: Math.round(c.s) } : null;
    };
    var tipo1 = doTipo(tipoPrincipal(pokemon)) || { h: 272, s: 70 };
    if (!medida) return { fundo: tipo1, destaque: tipo1 };
    var fundo = { h: medida[0], s: medida[1] };
    if (medida[3] >= 0) return { fundo: fundo, destaque: { h: medida[3], s: medida[4] } };
    var longe = function (c) { var d = Math.abs(c.h - fundo.h) % 360; return Math.min(d, 360 - d) >= 40; };
    var candidatos = tipos.map(doTipo).filter(Boolean).filter(longe);
    return { fundo: fundo, destaque: candidatos[0] || { h: (fundo.h + 40) % 360, s: Math.max(fundo.s, 40) } };
  }

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

  /* Cor "companheira": gira o matiz de destaque para um lado vívido e
     saturado, sem tocar no fundo/cartões (que continuam na rampa monocromática
     — é o que garante legibilidade). Um Charizard vermelho-alaranjado-amarelo
     de verdade vem de girar o mesmo matiz de base pra dois lados, não de uma
     cor fixa: funciona para os 18 tipos sem precisar de tabela nova. */
  function tomVivido(h, deslocamento, p) {
    var hh = ((h + deslocamento) % 360 + 360) % 360;
    // Sem a paleta (chamada antiga), fica o tom vivo de sempre. Com ela, o
    // tom vivo precisa ler sobre o cartão: as companheiras aparecem como
    // números grandes (3:1 basta) e, nos temas claros, o rosa e o amarelo
    // claros sumiam.
    if (!p) return hslParaHex(hh, 82, 64);
    return tomLegivel(hh, 82, p.s1, 3, !p.claro, p.claro ? 40 : 64);
  }

  function nivelSalvo() { return NIVEL_PADRAO; }

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
     texto quase invisível: fundo médio com letra clara não funciona.

     Parte do tom DESEJADO e só se afasta dele (clareando sobre fundo escuro,
     escurecendo sobre fundo claro) até alcançar o contraste. A versão antiga
     partia do extremo (quase branco ou quase preto), que sempre passa no
     teste — e parava ali: texto, texto secundário e cor de destaque saíam
     todos quase brancos (ou quase pretos), sem hierarquia nem cor do tema. */
  function tomLegivel(h, s, fundo, alvo, claroSobreEscuro, desejado) {
    var passo = claroSobreEscuro ? 2 : -2;
    var l = typeof desejado === 'number' ? desejado : (claroSobreEscuro ? 97 : 8);
    for (; l >= 0 && l <= 100; l += passo) {
      var cor = hslParaHex(h, s, l);
      if (contraste(cor, fundo) >= alvo) return cor;
    }
    return claroSobreEscuro ? '#ffffff' : '#000000';
  }

  /** Monta os tons do tema: o fundo na cor 1 do Pokémon, os cartões (e o
      destaque dos botões) na cor 2, sempre na claridade Suave. */
  function paletaDuasCores(cores, nivelIndice) {
    var nivel = NIVEIS[nivelIndice - 1] || NIVEIS[NIVEL_PADRAO - 1];
    var f = nivel.fundo;
    var p = nivel.passo;
    // A saturação acompanha a do corpo (um Pokémon cinza fica com fundo
    // cinza), com teto para a tela não cansar.
    var satDe = function (c) { return Math.max(8, Math.min(42, (c.s || 0) * 0.5)); };
    var hf = cores.fundo.h, sf = satDe(cores.fundo);
    var h = cores.destaque.h, s = satDe(cores.destaque);

    var fundo = hslParaHex(hf, sf, f);
    var s1 = hslParaHex(h, s, f + p);
    var s2 = hslParaHex(h, s, f + p * 1.7);
    var s3 = hslParaHex(h, s, f + p * 2.4);
    var linha = hslParaHex(h, s + 6, f + p * 3.4);

    // O Suave é um tema claro: letra escura sempre. (Com a régua antiga, um
    // azul puro a 60% de luz ficava no limite e podia virar letra branca.)
    var claro = true;
    // Tons desejados: texto quase branco (ou quase preto), secundário no
    // meio do caminho e destaque vivo na cor do tema.
    //
    // O secundário é medido contra s2: ele aparece também em selos e botões,
    // que usam a segunda camada. O destaque é medido contra o cartão (s1)
    // para continuar vivo — medido contra as camadas mais claras, o laranja
    // do Charizard virava bege. Onde o destaque ficaria como texto sobre
    // fundo mais claro (aba ativa, selos), o CSS usa a cor do texto.
    var texto = tomLegivel(h, 14, s1, 8.5, !claro, claro ? 12 : 95);
    var mut = tomLegivel(h, 20, s2, 4.6, !claro, claro ? 36 : 72);
    // 4,5:1 e não 3:1: o destaque também vira texto pequeno ("Ver todos",
    // "Atualizar", o filtro ligado).
    var pri = tomLegivel(h, 70, s1, 4.5, !claro, claro ? 38 : 66);
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
    var cores = coresDoPokemon(pokemon);
    var p = paletaDuasCores(cores, nivelSalvo());
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
    // Brilho em rgba pronto, não color-mix(): WebView antigo não entende
    // color-mix() e o elemento ficaria sem cor nenhuma (mesmo motivo do
    // --vision-surface-soft acima).
    root.style.setProperty('--vision-primary-glow', hexParaRgba(p.pri, 0.55));
    // Tons transparentes do destaque, no lugar do verde-água fixo que várias
    // regras antigas usavam em fundos e sombras.
    root.style.setProperty('--vision-primary-tenue', hexParaRgba(p.pri, 0.10));
    root.style.setProperty('--vision-primary-leve', hexParaRgba(p.pri, 0.25));
    // Bordas e fundo de campo "suaves": branco transparente some num tema
    // claro, então ali eles viram preto transparente.
    var tinta = p.claro ? '0,0,0' : '255,255,255';
    // Cores de significado usadas como TEXTO (verde de "ok", âmbar de aviso,
    // vermelho de erro): matiz fixo, mas o tom sai da mesma busca de contraste
    // do resto do tema — tons fixos sumiam nos níveis claros e no "Suave".
    root.style.setProperty('--ok-texto', tomLegivel(150, 62, p.s2, 5.2, !p.claro, p.claro ? 30 : 60));
    root.style.setProperty('--aviso-texto', tomLegivel(38, 88, p.s2, 5.2, !p.claro, p.claro ? 32 : 62));
    root.style.setProperty('--erro-texto', tomLegivel(4, 78, p.s2, 5.2, !p.claro, p.claro ? 40 : 68));
    /* Cores de significado como ORNAMENTO — barras, selos, bordas, fundos.
       (As de texto ficam logo acima.) O significado é o mesmo em qualquer
       tema; só o tom muda, pela mesma busca de contraste do resto: um verde
       fixo sumia nos níveis claros, e um dourado fixo virava bege.
         tenho    verde     "você tem"
         completa dourado   "completa"
         pendente laranja   "precisa de atenção" (preço a conferir)
         falta    rosa      "falta"
         troca    azul      "repetida / para trocar"
         desejo   pink      "quero"
       Cada uma dá: a cor viva (--cor-X), uma companheira mais clara para
       degradês (-2), a cor como texto (-texto), fundo, borda e brilho. */
    var SIGNIFICADOS = [
      ['tenho', 152, 60], ['completa', 46, 94], ['pendente', 25, 92],
      ['falta', 352, 72], ['troca', 203, 78], ['desejo', 330, 78]
    ];
    for (var si = 0; si < SIGNIFICADOS.length; si++) {
      var sNome = SIGNIFICADOS[si][0], sMatiz = SIGNIFICADOS[si][1], sSat = SIGNIFICADOS[si][2];
      var sForte = tomLegivel(sMatiz, sSat, p.s1, 3, !p.claro, p.claro ? 40 : 58);
      // Fundo médio com letra escura (nível 4): 3:1 empurraria o dourado e o
      // verde para quase preto. Um piso de claridade mantém a cor viva; o
      // texto dessas cores continua garantido por -texto, abaixo.
      if (p.claro && hexParaHsl(sForte).l < 28) sForte = hslParaHex(sMatiz, sSat, 28);
      var sLuz = Math.max(18, Math.min(88, hexParaHsl(sForte).l + (p.claro ? -6 : 13)));
      root.style.setProperty('--cor-' + sNome, sForte);
      root.style.setProperty('--cor-' + sNome + '-2', hslParaHex((sMatiz + 10) % 360, sSat, sLuz));
      root.style.setProperty('--cor-' + sNome + '-texto', tomLegivel(sMatiz, sSat, p.s2, 4.6, !p.claro, p.claro ? 30 : 68));
      root.style.setProperty('--cor-' + sNome + '-fundo', hexParaRgba(sForte, p.claro ? 0.16 : 0.17));
      root.style.setProperty('--cor-' + sNome + '-borda', hexParaRgba(sForte, 0.55));
      root.style.setProperty('--cor-' + sNome + '-brilho', hexParaRgba(sForte, 0.42));
      // Letra sobre um preenchimento dessa cor (selo dourado, botão verde...).
      root.style.setProperty('--cor-' + sNome + '-tinta', tintaSobre(sForte));
    }
    /* Profundidade: a luz vem de cima. O cartão ganha um fio claro no topo e
       uma sombra por baixo; no tema claro o fio é branco e a sombra é azulada
       (sombra preta suja o claro). */
    root.style.setProperty('--elev-fio', p.claro ? 'rgba(255,255,255,.92)' : 'rgba(255,255,255,.09)');
    root.style.setProperty('--elev-luz', p.claro
      ? 'linear-gradient(180deg, rgba(255,255,255,.62), rgba(255,255,255,0) 60%)'
      : 'linear-gradient(180deg, rgba(255,255,255,.075), rgba(255,255,255,0) 55%)');
    root.style.setProperty('--elev-sombra', p.claro
      ? '0 1px 0 rgba(255,255,255,.95) inset, 0 10px 22px -14px rgba(26,36,70,.38)'
      : '0 1px 0 rgba(255,255,255,.10) inset, 0 10px 24px -12px rgba(0,0,0,.65)');
    root.style.setProperty('--elev-sombra-alta', p.claro
      ? '0 1px 0 rgba(255,255,255,.95) inset, 0 18px 34px -16px rgba(26,36,70,.46)'
      : '0 1px 0 rgba(255,255,255,.12) inset, 0 18px 38px -14px rgba(0,0,0,.75)');
    /* Explorar: o cartão de cada coleção usa o matiz do logo dela (vem do
       app, inline em --cc-h/--cc-s); aqui fica só a claridade, que depende do
       nível escolhido — a mesma cor precisa ser um vinho escuro no tema
       escuro e um rosé claro no claro. */
    root.style.setProperty('--cc-l-a', p.claro ? '93%' : '31%');
    root.style.setProperty('--cc-l-b', p.claro ? '85%' : '13%');
    root.style.setProperty('--cc-l-linha', p.claro ? '55%' : '64%');
    root.style.setProperty('--cc-l-brilho', p.claro ? '72%' : '54%');
    root.style.setProperty('--cc-l-era', p.claro ? '26%' : '82%');
    /* Pokédex: o quadradinho e o cabeçalho do Pokémon vestem a cor do tipo.
       Mais quietos que as capas do Explorar — são 180 por tela. */
    root.style.setProperty('--tp-l-a', p.claro ? '92%' : '24%');
    root.style.setProperty('--tp-l-b', p.claro ? '86%' : '14%');
    root.style.setProperty('--tp-l-brilho', p.claro ? '66%' : '48%');
    root.style.setProperty('--tp-l-linha', p.claro ? '56%' : '58%');
    root.style.setProperty('--tp-l-texto', p.claro ? '26%' : '84%');
    // Logo com letra escura (Pitch Black, Black & White) some no fundo
    // escuro: um halo de luz atrás e um fio claro em volta dão contorno sem
    // incomodar os logos coloridos.
    root.style.setProperty('--cc-halo', p.claro ? 'rgba(255,255,255,.80)' : 'rgba(255,255,255,.24)');
    root.style.setProperty('--cc-filtro-logo', p.claro
      ? 'drop-shadow(0 0 1px rgba(255,255,255,.85)) drop-shadow(0 2px 4px rgba(26,36,70,.30))'
      : 'drop-shadow(0 0 1.5px rgba(255,255,255,.55)) drop-shadow(0 3px 7px rgba(0,0,0,.50))');
    root.style.setProperty('--cc-trilho', p.claro ? 'rgba(26,36,70,.14)' : 'rgba(0,0,0,.34)');
    root.style.setProperty('--cc-h-padrao', String(Math.round(hexParaHsl(p.pri).h)));
    root.style.setProperty('--borda-suave', 'rgba(' + tinta + ',.14)');
    root.style.setProperty('--v5-borda', 'rgba(' + tinta + ',.08)');
    root.style.setProperty('--v5-borda-forte', 'rgba(' + tinta + ',.14)');
    root.style.setProperty('--fundo-campo', 'rgba(' + tinta + ',.05)');
    // Duas cores companheiras vívidas, giradas a partir do mesmo matiz — a
    // variedade "vermelho, laranja, amarelo" do Charizard, não um roxo e um
    // dourado soltos que não têm nada a ver com o tema escolhido.
    var hueBase = hexParaHsl(p.pri).h;
    var flair1 = tomVivido(hueBase, -30, p);
    var flair2 = tomVivido(hueBase, 34, p);
    root.style.setProperty('--vision-flair-1', flair1);
    root.style.setProperty('--vision-flair-2', flair2);
    root.style.setProperty('--vision-flair-1-glow', hexParaRgba(flair1, 0.28));
    root.style.setProperty('--vision-flair-2-glow', hexParaRgba(flair2, 0.28));
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

    // Plástico da Pokédex (cabeçalho e barra de abas): a cor 2, a dos botões.
    var plastico = [
      hslParaHex(cores.destaque.h, Math.max(50, Math.min(80, cores.destaque.s)), 52),
      hslParaHex(cores.destaque.h, Math.max(50, Math.min(80, cores.destaque.s)), 26)
    ];
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
      cores: [p.bg, p.s1],
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
        + '<span>' + p.name + '</span>' + amostras(coresDaAmostra(p)) + '</button>';
    }).join('') + '</div>';
  }

  function amostras(cores) {
    return '<span class="tema-cores" aria-hidden="true"><i style="background:' + cores[0] + '"></i><i style="background:' + cores[1] + '"></i></span>';
  }

  // As duas cores, já na claridade do tema, para a amostra da lista.
  function coresDaAmostra(pokemon) {
    var p = paletaDuasCores(coresDoPokemon(pokemon), nivelSalvo());
    return [p.bg, p.s1];
  }

  function cabecalho() {
    var cores = window.__TEMA_ATUAL__ && window.__TEMA_ATUAL__.cores;
    return '<div class="tema-atual">' + (cores ? amostras(cores) : '') + 'Tema agora: <strong>' + nomeAtual() + '</strong></div>';
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

  function corpo() {
    return '<button class="modal-close" onclick="closeModal()" aria-label="Fechar">×</button>'
      + '<h2>Tema do aplicativo</h2>'
      + '<p class="screen-subtitle">Escolha seu Pokémon favorito. O app veste as duas cores do corpo dele — a primeira no fundo da tela, a segunda nos cartões e botões — e mostra a arte dele no topo e ao fundo.</p>'
      + cabecalho()
      + '<input class="field" placeholder="Buscar Pokémon por nome ou número" oninput="filtrarTemaPokemon(this.value)">'
      + grade()
      + '<button type="button" class="arte-limpar" onclick="limparArtes()">Baixar as artes 3D de novo</button>';
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
