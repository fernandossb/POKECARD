/* Troféus do POKECARD — medalhas com níveis, no espírito do Pokémon GO.

   Tudo é calculado a partir do que o app já guarda (coleção, variantes, decks,
   Pokédex, preços). Nada é inventado nem salvo separado: se você apagar uma
   carta, a medalha volta atrás sozinha.

   São 6 níveis por medalha — bronze, prata, ouro, platina, diamante e mestre —
   com metas pensadas para uma coleção que pode chegar a 100 mil cartas. As
   famílias por tipo, região e raridade geram uma medalha para cada valor. */
(function () {
  'use strict';

  function pokedex() { return Array.isArray(window.__POKEDEX__) ? window.__POKEDEX__ : []; }
  function entradas() { return (state && state.entries) || {}; }

  // ---- Números da coleção, calculados uma vez por abertura da tela ----
  function medir() {
    var m = {
      copias: 0, unicas: 0, valor: 0,
      porTipo: {}, porRegiao: {}, porRaridade: {},
      pokemonRegistrados: 0,
      setsCompletos: 0, setsIniciados: 0,
      pokemonCompletos: 0, cartasComTodasVariantes: 0,
      decks: ((state && state.decks) || []).length,
      graduadas: 0, carimbadas: 0, idiomas: {}, noFichario: 0, paraNegociar: 0, desejadas: 0
    };

    var vistos = new Set();
    var cartasPorPokemon = new Map();   // pokemonId -> Set(cardId) que possuo
    var totalPorPokemon = new Map();    // pokemonId -> Set(cardId) que existe

    if (cardMap) {
      cardMap.forEach(function (card, id) {
        var ids = pokemonIdsForCard ? pokemonIdsForCard(card) : [];
        for (var i = 0; i < ids.length; i++) {
          if (!totalPorPokemon.has(ids[i])) totalPorPokemon.set(ids[i], new Set());
          totalPorPokemon.get(ids[i]).add(id);
        }
      });
    }

    var mapaPokedex = {};
    pokedex().forEach(function (p) { mapaPokedex[p.id] = p; });

    Object.keys(entradas()).forEach(function (cardId) {
      var qtd = quantityFor ? quantityFor(cardId) : 0;
      if (!qtd) return;
      var card = cardMap && cardMap.get(cardId);
      if (!card) return;

      m.copias += qtd;
      m.unicas += 1;
      if (card.rarity) m.porRaridade[card.rarity] = (m.porRaridade[card.rarity] || 0) + 1;

      var variantes = variantsFor ? variantsFor(cardId) : [];
      variantes.forEach(function (v) {
        var copias = Math.max(0, Number(v.quantity) || 0);
        if (v.language) m.idiomas[v.language] = (m.idiomas[v.language] || 0) + 1;
        if (v.gradingCompany && v.gradingCompany !== 'Não graduada') m.graduadas += 1;
        if (v.distribution === 'stamped') m.carimbadas += 1;
        if (v.storageLocation === 'fichario') m.noFichario += copias;
        if (v.storageLocation === 'troca' || v.storageLocation === 'venda'
            || v.isForTrade || v.isForSale) m.paraNegociar += copias;
        if (v.isWishlist) m.desejadas += 1;
        var preco = effectiveVariantPrice ? effectiveVariantPrice(cardId, v) : null;
        if (preco && isFinite(preco.brl)) m.valor += Number(preco.brl) * copias;
      });

      // Todas as variantes conhecidas desta carta já cadastradas?
      var perfil = cardVariationProfile ? cardVariationProfile(card) : null;
      var possiveis = (perfil && perfil.pricingVariants) || [];
      if (possiveis.length > 1) {
        var tenho = {};
        variantes.forEach(function (v) { if (Number(v.quantity) > 0) tenho[v.pricingVariant] = true; });
        var faltou = possiveis.some(function (p) { return !tenho[p]; });
        if (!faltou) m.cartasComTodasVariantes += 1;
      }

      var ids = pokemonIdsForCard ? pokemonIdsForCard(card) : [];
      ids.forEach(function (pid) {
        vistos.add(pid);
        if (!cartasPorPokemon.has(pid)) cartasPorPokemon.set(pid, new Set());
        cartasPorPokemon.get(pid).add(cardId);
        var p = mapaPokedex[pid];
        if (!p) return;
        (p.types || []).forEach(function (t) { m.porTipo[t] = (m.porTipo[t] || 0) + qtd; });
        if (p.region) m.porRegiao[p.region] = (m.porRegiao[p.region] || 0) + qtd;
      });
    });

    m.pokemonRegistrados = vistos.size;

    cartasPorPokemon.forEach(function (tenho, pid) {
      var todas = totalPorPokemon.get(pid);
      if (todas && todas.size > 1 && tenho.size >= todas.size) m.pokemonCompletos += 1;
    });

    if (buildSetStats) {
      buildSetStats().forEach(function (s) {
        if (s.ownedUnique > 0) m.setsIniciados += 1;
        if (s.progress >= 100) m.setsCompletos += 1;
      });
    }
    return m;
  }

  // ---- Catálogo de medalhas ----
  // valor: função que lê a medida · metas: os 6 degraus
  function catalogo(m) {
    var lista = [
      { id:'colecionador', nome:'Colecionador', icone:'📇', desc:'Cartas cadastradas', valor:m.copias, metas:[10,250,2500,15000,50000,100000] },
      { id:'variedade',    nome:'Variedade',    icone:'🃏', desc:'Cartas diferentes', valor:m.unicas, metas:[10,150,1500,6000,15000,31000] },
      { id:'pokedex',      nome:'Pesquisador',  icone:'🔎', desc:'Pokémon na sua Pokédex', valor:m.pokemonRegistrados, metas:[25,150,400,700,900,1025] },
      { id:'sets',         nome:'Arquivista',   icone:'🗂️', desc:'Coleções completas', valor:m.setsCompletos, metas:[1,5,20,50,100,180] },
      { id:'explorador',   nome:'Explorador',   icone:'🧭', desc:'Coleções iniciadas', valor:m.setsIniciados, metas:[3,15,60,150,280,390] },
      { id:'linhagem',     nome:'Linhagem',     icone:'🧬', desc:'Pokémon com todas as cartas', valor:m.pokemonCompletos, metas:[1,10,50,200,500,1025] },
      { id:'perfeccionista', nome:'Perfeccionista', icone:'✨', desc:'Cartas com todas as variantes', valor:m.cartasComTodasVariantes, metas:[1,25,250,1500,6000,15000] },
      { id:'tesouro',      nome:'Tesouro',      icone:'💎', desc:'Valor da coleção em reais', valor:Math.round(m.valor), metas:[100,2500,25000,150000,500000,1500000], moeda:true },
      { id:'estrategista', nome:'Estrategista', icone:'⚔️', desc:'Decks montados', valor:m.decks, metas:[1,5,15,40,90,180] },
      { id:'certificado',  nome:'Certificado',  icone:'🏅', desc:'Cartas graduadas', valor:m.graduadas, metas:[1,5,25,100,350,1000] },
      { id:'carimbo',      nome:'Carimbada',    icone:'🖃', desc:'Cartas com carimbo', valor:m.carimbadas, metas:[1,10,50,250,900,2500] },
      { id:'poliglota',    nome:'Poliglota',    icone:'🌐', desc:'Idiomas diferentes na coleção', valor:Object.keys(m.idiomas).length, metas:[1,2,3,3,3,3] },
      { id:'guardiao',     nome:'Guardião',     icone:'🛡️', desc:'Cartas guardadas no fichário', valor:m.noFichario, metas:[10,250,2500,15000,50000,100000] },
      { id:'negociante',   nome:'Negociante',   icone:'🤝', desc:'Cartas separadas para troca ou venda', valor:m.paraNegociar, metas:[1,25,250,1500,6000,20000] },
      { id:'caçador',      nome:'Caçador',      icone:'🎯', desc:'Cartas na lista de desejos', valor:m.desejadas, metas:[1,20,100,500,1500,4000] },
      { id:'veterano',     nome:'Veterano',     icone:'⏳', desc:'Coleções diferentes representadas', valor:m.setsIniciados, metas:[5,25,80,180,300,391] }
    ];

    // Uma medalha por raridade encontrada na coleção. A raridade vem do
    // catálogo enriquecido; sem ele, esta família simplesmente não aparece.
    var ICONE_RARIDADE = {
      'Comum':'⚪','Incomum':'🔵','Rara':'⭐','Rara Holo':'🌟','Ultra Rara':'💫',
      'Promo':'🎁','Rara Secreta':'🔒','Rara Dupla':'✌️','Hiper Rara':'🌈',
      'Rara Holo V':'🅥','Rara Holo VMAX':'🆚','Rara Arco-Íris':'🌈','Amazing Rare':'💠'
    };
    Object.keys(m.porRaridade).sort().forEach(function (r) {
      lista.push({ id:'rar-'+r, nome:r, icone:ICONE_RARIDADE[r] || '🎴',
        desc:'Cartas de raridade '+r, valor:m.porRaridade[r],
        metas:[5,50,400,2000,8000,25000], familia:'Raridades' });
    });

    // Uma medalha por tipo, como as do Pokémon GO.
    var TIPOS = ['Planta','Fogo','Água','Elétrico','Psíquico','Lutador','Sombrio','Metálico',
                 'Fada','Dragão','Voador','Venenoso','Terrestre','Pedra','Inseto','Fantasma','Gelo','Normal'];
    var ICONE_TIPO = { 'Planta':'🌿','Fogo':'🔥','Água':'💧','Elétrico':'⚡','Psíquico':'🔮','Lutador':'🥊',
      'Sombrio':'🌑','Metálico':'⚙️','Fada':'🎀','Dragão':'🐉','Voador':'🕊️','Venenoso':'☠️',
      'Terrestre':'⛰️','Pedra':'🪨','Inseto':'🐛','Fantasma':'👻','Gelo':'❄️','Normal':'⭐' };
    TIPOS.forEach(function (t) {
      lista.push({ id:'tipo-'+t, nome:t, icone:ICONE_TIPO[t]||'⭐', desc:'Cartas do tipo '+t,
        valor:m.porTipo[t]||0, metas:[10,100,600,3000,10000,30000], familia:'Tipos' });
    });

    // Uma medalha por região.
    var REGIOES = ['Kanto','Johto','Hoenn','Sinnoh','Unova','Kalos','Alola','Galar','Paldea'];
    REGIOES.forEach(function (r) {
      lista.push({ id:'regiao-'+r, nome:r, icone:'🗺️', desc:'Cartas de '+r,
        valor:m.porRegiao[r]||0, metas:[10,100,600,3000,9000,25000], familia:'Regiões' });
    });

    return lista;
  }

  function nivelDe(item) {
    var n = 0;
    for (var i = 0; i < item.metas.length; i++) if (item.valor >= item.metas[i]) n = i + 1;
    return n; // 0 = ainda bloqueada
  }

  function progresso(item) {
    var n = nivelDe(item);
    if (n >= item.metas.length) return { pct: 100, alvo: item.metas[item.metas.length - 1], completo: true };
    var base = n === 0 ? 0 : item.metas[n - 1];
    var alvo = item.metas[n];
    var pct = Math.max(0, Math.min(100, Math.round(((item.valor - base) / (alvo - base)) * 100)));
    return { pct: pct, alvo: alvo, completo: false };
  }

  function fmt(valor, moeda) {
    if (moeda) return 'R$ ' + Number(valor).toLocaleString('pt-BR');
    return Number(valor).toLocaleString('pt-BR');
  }

  /* Seis níveis. Os dois últimos existem porque a meta é uma coleção de
     ~100 mil cartas: com quatro níveis tudo virava platina cedo demais. */
  var CORES = { 0:'#4a4a52', 1:'#b4763a', 2:'#9aa6ae', 3:'#e8bf21', 4:'#6fe0d0', 5:'#7fd1ff', 6:'#ff7ad4' };
  var ROTULOS = { 0:'Bloqueada', 1:'Bronze', 2:'Prata', 3:'Ouro', 4:'Platina', 5:'Diamante', 6:'Mestre' };

  function cartao(item) {
    var n = nivelDe(item);
    var p = progresso(item);
    var cor = CORES[n];
    return '<div class="trofeu' + (n ? '' : ' bloqueado') + '" style="--tr-cor:' + cor + '">'
      + '<div class="trofeu-medalha"><span>' + item.icone + '</span>'
      + (n ? '<b class="trofeu-nivel">' + ROTULOS[n] + '</b>' : '') + '</div>'
      + '<div class="trofeu-info">'
      + '<strong>' + item.nome + '</strong>'
      + '<small>' + item.desc + '</small>'
      + '<div class="trofeu-barra"><span style="width:' + p.pct + '%"></span></div>'
      + '<small class="trofeu-conta">' + fmt(item.valor, item.moeda)
      + (p.completo ? ' · máximo alcançado' : ' de ' + fmt(p.alvo, item.moeda)) + '</small>'
      + '</div></div>';
  }

  function abrir() {
    var m = medir();
    var lista = catalogo(m);
    var conquistadas = lista.filter(function (i) { return nivelDe(i) > 0; }).length;
    var platinas = lista.filter(function (i) { return nivelDe(i) === 4; }).length;

    var familias = { 'Coleção': [], 'Raridades': [], 'Tipos': [], 'Regiões': [] };
    lista.forEach(function (i) { (familias[i.familia || 'Coleção'] || familias['Coleção']).push(i); });

    var corpo = '<button class="modal-close" onclick="closeModal()" aria-label="Fechar">×</button>'
      + '<h2>Troféus</h2>'
      + '<p class="screen-subtitle">' + conquistadas + ' de ' + lista.length + ' medalhas conquistadas'
      + (platinas ? ' · ' + platinas + ' na platina' : '') + '.</p>';

    Object.keys(familias).forEach(function (nome) {
      if (!familias[nome].length) return;
      var ganhas = familias[nome].filter(function (i) { return nivelDe(i) > 0; }).length;
      corpo += '<h3 class="trofeu-familia">' + nome + ' <span>' + ganhas + '/' + familias[nome].length + '</span></h3>'
        + '<div class="trofeu-grade">' + familias[nome].map(cartao).join('') + '</div>';
    });

    if (typeof showModal === 'function') showModal(corpo);
  }

  window.abrirTrofeus = abrir;
  window.resumoTrofeus = function () {
    var lista = catalogo(medir());
    return {
      total: lista.length,
      conquistadas: lista.filter(function (i) { return nivelDe(i) > 0; }).length
    };
  };
})();
