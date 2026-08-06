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
  var ART_KEY = 'fichario-pokemon-tema-arte-v1';
  var DEFAULT_ID = 94;
  var ART_BASE = 'https://cdn.jsdelivr.net/gh/PokeAPI/sprites@master/sprites/pokemon/other/home/';

  // bg = fundo geral · s1/s2/s3 = cartões · line = bordas
  // mut = texto secundário · pri = cor de destaque · soft = fundo do destaque
  var TYPES = {
    'Fantasma':  { bg:'#0d0618', s1:'#1a0d2b', s2:'#221136', s3:'#2b1743', line:'#3d2159', mut:'#b3a3c4', pri:'#b46cff', soft:'#2c1547' },
    'Elétrico':  { bg:'#141002', s1:'#241d06', s2:'#2e2409', s3:'#392d0c', line:'#4f3f11', mut:'#c8bd90', pri:'#f5c518', soft:'#3a2d08' },
    'Fogo':      { bg:'#150703', s1:'#261008', s2:'#31150a', s3:'#3d1b0d', line:'#552712', mut:'#c9ac9c', pri:'#ff7a2f', soft:'#3d1a09' },
    'Água':      { bg:'#030d17', s1:'#08192b', s2:'#0b2038', s3:'#0f2845', line:'#17395e', mut:'#9fb4cc', pri:'#3d9bff', soft:'#0d2440' },
    'Planta':    { bg:'#04120a', s1:'#0a2113', s2:'#0d2b18', s3:'#10351e', line:'#194a2a', mut:'#a3c0ad', pri:'#4fc463', soft:'#0f3020' },
    'Gelo':      { bg:'#031216', s1:'#082228', s2:'#0b2c34', s3:'#0e3640', line:'#154c59', mut:'#a0c3ca', pri:'#5fd3e0', soft:'#0d3038' },
    'Lutador':   { bg:'#150705', s1:'#26110c', s2:'#31160f', s3:'#3d1c13', line:'#55291b', mut:'#c8a89e', pri:'#e0603f', soft:'#3c1a11' },
    'Venenoso':  { bg:'#100618', s1:'#1e0d2b', s2:'#271136', s3:'#301743', line:'#452159', mut:'#bda3c8', pri:'#c05ad8', soft:'#301447' },
    'Terrestre': { bg:'#120d03', s1:'#221a08', s2:'#2c220b', s3:'#372a0e', line:'#4d3b14', mut:'#c5b795', pri:'#d9a441', soft:'#382a0b' },
    'Voador':    { bg:'#060911', s1:'#0d1424', s2:'#111a2e', s3:'#152039', line:'#1f2e4f', mut:'#a9b3cc', pri:'#8aa9f0', soft:'#141e38' },
    'Psíquico':  { bg:'#15060c', s1:'#260c17', s2:'#31101e', s3:'#3d1425', line:'#551c33', mut:'#cca0b0', pri:'#f2557f', soft:'#3c1322' },
    'Inseto':    { bg:'#0b1003', s1:'#161f08', s2:'#1d290b', s3:'#24330e', line:'#334814', mut:'#b4c095', pri:'#9dc030', soft:'#24310b' },
    'Pedra':     { bg:'#110d04', s1:'#211a0a', s2:'#2b220d', s3:'#352b10', line:'#4a3c17', mut:'#c2b596', pri:'#c2a15a', soft:'#362b0d' },
    'Sombrio':   { bg:'#0a0806', s1:'#17130e', s2:'#1e1913', s3:'#261f17', line:'#372e23', mut:'#bcaea1', pri:'#a08a74', soft:'#251e16' },
    'Dragão':    { bg:'#080513', s1:'#120c24', s2:'#18102e', s3:'#1e1439', line:'#2c1f52', mut:'#aea4cc', pri:'#8b72ff', soft:'#1d1440' },
    'Metálico':  { bg:'#070a0d', s1:'#10171d', s2:'#151e26', s3:'#1a252f', line:'#273541', mut:'#aeb8c2', pri:'#9ab4cc', soft:'#19232d' },
    'Fada':      { bg:'#14060f', s1:'#250c1c', s2:'#301024', s3:'#3c142d', line:'#541c40', mut:'#cea3bd', pri:'#f07ac0', soft:'#3b132a' },
    'Normal':    { bg:'#0b0a07', s1:'#191710', s2:'#211e15', s3:'#29251a', line:'#3b3627', mut:'#bcb6a6', pri:'#c0b596', soft:'#282318' }
  };
  var FALLBACK = TYPES['Fantasma'];

  function pokedex() { return Array.isArray(window.__POKEDEX__) ? window.__POKEDEX__ : []; }

  function findPokemon(id) {
    var list = pokedex();
    for (var i = 0; i < list.length; i++) if (Number(list[i].id) === Number(id)) return list[i];
    return null;
  }

  function paletteFor(pokemon) {
    var types = (pokemon && pokemon.types) || [];
    for (var i = 0; i < types.length; i++) if (TYPES[types[i]]) return TYPES[types[i]];
    return FALLBACK;
  }

  function spritePath(id) { return 'sprites/' + Number(id) + '.png'; }

  function savedId() {
    try {
      var raw = Number(localStorage.getItem(STORAGE_KEY));
      if (isFinite(raw) && raw > 0) return raw;
    } catch (e) {}
    return DEFAULT_ID;
  }

  function cachedArt(id) {
    try {
      var cache = JSON.parse(localStorage.getItem(ART_KEY) || 'null');
      return cache && Number(cache.id) === Number(id) && cache.dataUrl ? cache.dataUrl : '';
    } catch (e) { return ''; }
  }

  function baixarArte(id, aoConcluir) {
    fetch(ART_BASE + Number(id) + '.png').then(function (r) {
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
      // Guarda só a arte em uso: várias estouram a cota e só uma fica visível.
      try { localStorage.setItem(ART_KEY, JSON.stringify({ id: Number(id), dataUrl: dataUrl })); } catch (e) {}
      aoConcluir(dataUrl);
    }).catch(function () { /* sem internet: segue com o sprite local */ });
  }

  function aplicarImagem(url, ehArte3d) {
    var root = document.documentElement;
    root.style.setProperty('--theme-wallpaper', 'url("' + url + '")');
    root.style.setProperty('--theme-wallpaper-size', ehArte3d ? '84vw' : '74vw');
    root.style.setProperty('--theme-wallpaper-pos', 'center 8%');
    root.style.setProperty('--theme-wallpaper-opacity', ehArte3d ? '.15' : '.12');
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
    var p = paletteFor(pokemon);
    var root = document.documentElement;

    root.style.setProperty('--vision-bg', p.bg);
    root.style.setProperty('--vision-surface', p.s1);
    root.style.setProperty('--vision-surface-2', p.s2);
    root.style.setProperty('--vision-surface-3', p.s3);
    root.style.setProperty('--vision-line', p.line);
    root.style.setProperty('--vision-muted', p.mut);
    root.style.setProperty('--vision-primary', p.pri);
    root.style.setProperty('--vision-primary-soft', p.soft);
    // O brilho do mascote acompanha a cor de destaque.
    root.style.setProperty('--theme-glow', p.pri);

    try { localStorage.setItem(STORAGE_KEY, String(id)); } catch (e) {}
    window.__TEMA_ATUAL__ = {
      id: id,
      nome: pokemon ? pokemon.name : 'Gengar',
      tipo: (pokemon && pokemon.types && pokemon.types[0]) || 'Fantasma'
    };

    var guardada = cachedArt(id);
    aplicarImagem(guardada || spritePath(id), Boolean(guardada));
    if (!guardada) baixarArte(id, function (dataUrl) { aplicarImagem(dataUrl, true); });
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

  function corpo() {
    return '<button class="modal-close" onclick="closeModal()" aria-label="Fechar">×</button>'
      + '<h2>Tema do aplicativo</h2>'
      + '<p class="screen-subtitle">Escolha seu Pokémon favorito. O app assume as cores do tipo dele e mostra a arte no topo e ao fundo.</p>'
      + cabecalho()
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
