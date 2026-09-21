(function () {
  const RULES_VERSION = '2026.07.29';
  const OBJECTIVES = {
    competitive: 'Mais competitivo possível',
    consistent: 'Alta consistência',
    fast: 'Ataque rápido',
    control: 'Controle',
    single: 'Uma carta de Prêmio',
    beginner: 'Fácil para iniciantes',
    collection: 'Aproveitar minha coleção'
  };
  let lastCandidates = [];
  let handTest = null;

  function safeDecks() {
    state.decks = Array.isArray(state.decks) ? state.decks : [];
    return state.decks;
  }

  function owned(card) { return Math.max(0, Number(quantityFor(card.id)) || 0); }
  function basic(card) {
    if (deckCardClass(card) !== 'pokemon') return false;
    const ids = pokemonIdsForCard(card);
    return ids.length > 0 && ids.every(id => !Number(window.__POKEMON_EVOLVES_FROM__?.[id] || 0));
  }
  function evolutionChainIds(speciesId) {
    const chain = [];
    let current = Number(speciesId) || 0;
    const seen = new Set();
    while (current > 0 && !seen.has(current)) {
      seen.add(current);
      chain.unshift(current);
      current = Number(window.__POKEMON_EVOLVES_FROM__?.[current] || 0);
    }
    return chain;
  }
  let descendantsCache = null;
  function descendantsMap() {
    if (descendantsCache) return descendantsCache;
    descendantsCache = new Map();
    const evolvesFrom = window.__POKEMON_EVOLVES_FROM__ || {};
    for (const childKey of Object.keys(evolvesFrom)) {
      const parentId = Number(evolvesFrom[childKey]);
      if (!descendantsCache.has(parentId)) descendantsCache.set(parentId, []);
      descendantsCache.get(parentId).push(Number(childKey));
    }
    return descendantsCache;
  }
  // A linha evolutiva INTEIRA de um Pokémon — subindo até a base e descendo por
  // todos os ramos — não importa qual estágio foi escolhido. Bulbasaur, Ivysaur
  // ou Venusaur levam sempre aos três; Eevee leva a todas as Eeveelutions.
  function evolutionFamilyIds(speciesId) {
    let root = Number(speciesId) || 0;
    if (!root) return [];
    const evolvesFrom = window.__POKEMON_EVOLVES_FROM__ || {};
    const seenUp = new Set();
    while (evolvesFrom[root] && !seenUp.has(root)) { seenUp.add(root); root = Number(evolvesFrom[root]); }
    const desc = descendantsMap();
    const family = [];
    const seen = new Set();
    const queue = [root];
    while (queue.length) {
      const current = queue.shift();
      if (seen.has(current)) continue;
      seen.add(current);
      family.push(current);
      for (const child of desc.get(current) || []) queue.push(child);
    }
    return family.sort((a, b) => a - b);
  }
  // O elemento do Pokémon escolhido (não da família inteira — Eevee é Normal
  // mesmo tendo Eeveelutions de vários tipos), na mesma escala de energia usada
  // pelo resto do montador.
  function pokemonKindForId(speciesId) {
    const kinds = [...new Set((pokemonMap.get(Number(speciesId))?.types || [])
      .map(type => TYPE_TO_ENERGY[normalize(type)]).filter(Boolean))];
    const colored = kinds.filter(kind => kind !== 'colorless' && kind !== 'dragon');
    return colored[0] || kinds[0] || 'colorless';
  }
  function matchesKind(card, chosenKind) {
    const kinds = pokemonEnergyKinds(card);
    const coloredKinds = kinds.filter(kind => kind !== 'colorless' && kind !== 'dragon');
    const isPureColorless = kinds.length === 1 && kinds[0] === 'colorless';
    return coloredKinds.includes(chosenKind) || isPureColorless;
  }
  function compatibleEnergyPool(energyGroup, chosenKind) {
    return energyGroup.filter(card => {
      const kinds = energyKinds(card);
      return kinds.includes(chosenKind) || kinds.includes('any') || (chosenKind === 'colorless' && kinds.includes('colorless'));
    }).sort((a,b) => Number(isBasicEnergy(b,chosenKind))-Number(isBasicEnergy(a,chosenKind)) || owned(b)-owned(a) || a.name.localeCompare(b.name,'pt-BR'));
  }
  // Treinadores e Energia até completar as metas — o mesmo reparo final tanto
  // para o montador geral quanto para o deck temático: nunca sobra espaço vazio
  // nem entra Pokémon evoluído isolado.
  function fillTrainersAndEnergy(target, trainerPool, energyPool, chosenKind, goals, allowMissing) {
    [trainerPool, energyPool].forEach((group, offset) => {
      const gi = offset + 1;
      let count = 0;
      for (const card of group) {
        if (count >= goals[gi]) break;
        let wanted = Math.min(4, goals[gi]-count);
        if (deckCardClass(card) === 'energy' && isBasicEnergy(card,chosenKind)) wanted = goals[gi]-count;
        else if (deckCardClass(card) === 'energy') wanted = Math.min(2, goals[gi]-count);
        else if (deckCardClass(card) === 'trainer' && role(card) === 'suporte') wanted = Math.min(2, goals[gi]-count);
        count += addTo(target,card,wanted,allowMissing);
      }
    });
    for (const card of [...trainerPool,...energyPool]) {
      if (Object.values(target).reduce((a,b)=>a+Number(b),0) >= 60) break;
      addTo(target,card,4,allowMissing);
    }
  }
  function looksLikeUnlinkedPokemon(card) {
    return deckCardClass(card) === 'trainer'
      && !pokemonIdsForCard(card).length
      && /(?:\bex\b|\bvmax\b|\bvstar\b|\bgx\b|\bbreak\b|\bradiante\b|\bradiant\b)/.test(normalize(card?.name || ''));
  }
  const TYPE_TO_ENERGY = {
    planta:'grass', inseto:'grass', venenoso:'grass',
    fogo:'fire', agua:'water', gelo:'water',
    eletrico:'lightning', psiquico:'psychic', fantasma:'psychic',
    lutador:'fighting', terrestre:'fighting', pedra:'fighting',
    sombrio:'darkness', metalico:'metal', fada:'fairy',
    normal:'colorless', voador:'colorless', dragao:'dragon'
  };
  const ENERGY_LABEL = {
    grass:'Planta', fire:'Fogo', water:'Água', lightning:'Elétrica',
    psychic:'Psíquica', fighting:'Luta', darkness:'Escuridão',
    metal:'Metal', fairy:'Fada', colorless:'Incolor', dragon:'Dragão'
  };
  function pokemonEnergyKinds(card) {
    return [...new Set(deckCardTypes(card).map(type => TYPE_TO_ENERGY[normalize(type)]).filter(Boolean))];
  }
  function energyKinds(card) {
    if (deckCardClass(card) !== 'energy') return [];
    const n = normalize(card.name || '');
    if (/arco-iris|aurora|luminosa|prisma|unitaria|fusao/.test(n)) return ['any'];
    const result = [];
    if (/grass|grama|planta|herbal|aromatica/.test(n)) result.push('grass');
    if (/fire|fogo|ardente|ignicao/.test(n)) result.push('fire');
    if (/water|agua|aqua|borrifada/.test(n)) result.push('water');
    if (/lightning|raios|eletric|voltaica/.test(n)) result.push('lightning');
    if (/psychic|psiquic|misterio|horripilante/.test(n)) result.push('psychic');
    if (/fighting|luta|petrea|rochosa/.test(n)) result.push('fighting');
    if (/darkness|escuridao|noturna/.test(n)) result.push('darkness');
    if (/metal|revestida|magnetica/.test(n)) result.push('metal');
    if (/fairy|fada|encantada/.test(n)) result.push('fairy');
    if (/incolor|colorless|gemea|turbo dupla|tripla/.test(n)) result.push('colorless');
    return result.length ? [...new Set(result)] : ['special'];
  }
  function isBasicEnergy(card, kind = '') {
    if (deckCardClass(card) !== 'energy') return false;
    const n = normalize(card.name || '');
    const basicName = /basica|basic/.test(n)
      || /^energia de (agua|fogo|grama|planta|luta|metal|fada|raios|escuridao)$/.test(n)
      || /^energia psiquica$/.test(n);
    return basicName && (!kind || energyKinds(card).includes(kind));
  }
  function isPocketCard(card) {
    return /^(?:[ab]\d|p-a)/i.test(String(card?.setId || ''));
  }
  function allowedInFormat(card, format) {
    if (!card || isPocketCard(card)) return false;
    if (format === 'casual') return true;
    const setId = String(card.setId || '').toLowerCase();
    if (format === 'standard') return /^(?:sv|me)/.test(setId);
    if (format === 'glc' && /\bex\b|vmax|vstar|\bgx\b|\bv\b/.test(normalize(card.name || ''))) return false;
    return true;
  }
  function trainerDependencyPenalty(card) {
    const n = normalize(card?.name || '');
    if (/fossil|fossil|restaurado|restored/.test(n)) return 8;
    if (/energia|energy/.test(n) && !/busca|search|recuper|retrieval/.test(n)) return 4;
    if (/ferramenta|tool/.test(n)) return 2;
    return 0;
  }
  function role(card) {
    const n = normalize(card?.name || '');
    if (deckCardClass(card) === 'pokemon') {
      if (/\bex\b|vmax|vstar|\bgx\b|\bv\b/.test(n)) return 'atacante principal';
      return 'Pokémon e atacante';
    }
    if (deckCardClass(card) === 'energy') return 'Energia para ataques';
    if (/bola|ball|captura|comunicacao|comunicação/.test(n)) return 'busca e consistência';
    if (/professor|pesquisa|research|refinamento|draw/.test(n)) return 'compra de cartas';
    if (/ordens|boss|gust/.test(n)) return 'alvo e pressão';
    if (/troca|switch|corda|rope/.test(n)) return 'mobilidade';
    if (/recuper|superior energy retrieval|vara/.test(n)) return 'recuperação';
    return 'suporte';
  }
  function roleScore(card, objective) {
    let score = deckStrengthScore(card, '');
    const r = role(card);
    if (/busca|compra|consistência/.test(r)) score += objective === 'consistent' ? 18 : 11;
    if (/atacante/.test(r)) score += objective === 'fast' || objective === 'competitive' ? 12 : 7;
    if (/pressão|mobilidade|recuperação/.test(r)) score += objective === 'control' ? 10 : 5;
    if (objective === 'beginner' && deckCardClass(card) === 'pokemon' && basic(card)) score += 8;
    return score;
  }
  function seeded(seed) {
    let value = seed >>> 0;
    return () => ((value = (value * 1664525 + 1013904223) >>> 0) / 4294967296);
  }
  function shuffle(values, random) {
    const out = values.slice();
    for (let i = out.length - 1; i > 0; i--) {
      const j = Math.floor(random() * (i + 1));
      [out[i], out[j]] = [out[j], out[i]];
    }
    return out;
  }
  function expanded(deck) {
    const result = [];
    Object.entries(deck.cards || {}).forEach(([id, qty]) => {
      for (let i = 0; i < Number(qty || 0); i++) result.push(id);
    });
    return result;
  }
  function simulate(deck, runs = 1000, seed = 20260729) {
    const list = expanded(deck);
    if (list.length !== 60) return { runs: 0, mulliganRate: 100, basicRate: 0, energyRate: 0, searchRate: 0, attackerRate: 0 };
    const random = seeded(seed);
    let mulligans = 0, energy = 0, search = 0, attacker = 0;
    for (let i = 0; i < runs; i++) {
      const hand = shuffle(list, random).slice(0, 7).map(id => cardMap.get(id)).filter(Boolean);
      if (!hand.some(c => deckCardClass(c) === 'pokemon' && basic(c))) mulligans++;
      if (hand.some(c => deckCardClass(c) === 'energy')) energy++;
      if (hand.some(c => /busca|compra|consistência/.test(role(c)))) search++;
      if (hand.some(c => /atacante/.test(role(c)))) attacker++;
    }
    const percent = n => Math.round(n * 1000 / runs) / 10;
    return { runs, mulliganRate: percent(mulligans), basicRate: percent(runs - mulligans), energyRate: percent(energy), searchRate: percent(search), attackerRate: percent(attacker) };
  }
  function confidence(deck) {
    const used = Object.keys(deck.cards || {}).map(id => cardMap.get(id)).filter(Boolean);
    const rich = used.filter(c => c.attacks || c.abilities || c.cardText || c.regulationMark).length;
    const coverage = used.length ? rich / used.length : 0;
    if (coverage > .8) return { label: 'Alta', reason: 'A maior parte das cartas possui dados estratégicos completos.' };
    if (coverage > .45) return { label: 'Média', reason: 'Parte das cartas ainda não possui texto ou marca de regulamentação.' };
    return { label: 'Baixa', reason: 'O catálogo atual não traz texto competitivo e marca de regulamentação para várias cartas; nenhum efeito foi inventado.' };
  }
  function validate(deck) {
    const base = deckValidationOriginal(deck);
    const errors = base.errors.filter(message => deck.generationMode === 'owned' || !/vocÃª possui|você possui/.test(message));
    const warnings = base.warnings.slice();
    const cardsUsed = Object.entries(deck.cards || {}).filter(([,q]) => Number(q) > 0);
    if (!cardsUsed.some(([id]) => { const c = cardMap.get(id); return c && deckCardClass(c) === 'pokemon' && basic(c); })) {
      errors.push('O deck precisa de pelo menos um Pokémon Básico confirmado.');
    }
    const speciesInDeck = new Set(cardsUsed.flatMap(([id]) => {
      const card = cardMap.get(id);
      return card && deckCardClass(card) === 'pokemon' ? pokemonIdsForCard(card) : [];
    }));
    for (const speciesId of speciesInDeck) {
      const parentId = Number(window.__POKEMON_EVOLVES_FROM__?.[speciesId] || 0);
      if (parentId && !speciesInDeck.has(parentId)) {
        errors.push(`${pokemonMap.get(speciesId)?.name || 'Evolução'} está sem ${pokemonMap.get(parentId)?.name || 'a etapa anterior'} no deck.`);
      }
    }
    const ace = cardsUsed.reduce((n,[id,q]) => n + (/ace spec/.test(normalize(cardMap.get(id)?.name || '')) ? Number(q) : 0), 0);
    if (ace > 1) errors.push('Só é permitida uma carta ACE SPEC no deck.');
    const radiant = cardsUsed.reduce((n,[id,q]) => n + (/radiante|radiant/.test(normalize(cardMap.get(id)?.name || '')) ? Number(q) : 0), 0);
    if (radiant > 1) errors.push('Só é permitido um Pokémon Radiante no deck.');
    if ((deck.format || 'standard') !== 'casual' && cardsUsed.some(([id]) => !cardMap.get(id)?.regulationMark)) {
      warnings.push('Legalidade de algumas impressões não pôde ser confirmada: falta marca de regulamentação no catálogo.');
    }
    if (base.split.energy < 8) errors.push(`O plano energético precisa de pelo menos 8 Energias reais; foram encontradas ${base.split.energy}.`);
    if (deck.energyPlan?.kind) {
      // A linha evolutiva de um deck temático entra garantida pelo tema, não
      // por bater com a Energia escolhida — Eevee é Incolor, mas Vaporeon,
      // Jolteon e as outras Eeveelutions não são, e isso não é defeito do
      // deck. Sem esta exceção, todo deck temático de família ramificada
      // nasceria "inválido" reclamando dos próprios atacantes principais.
      const familyIds = new Set(deck.themeFamilyIds || []);
      const wrongPokemon = cardsUsed.filter(([id]) => {
        const card = cardMap.get(id);
        if (!card || deckCardClass(card) !== 'pokemon') return false;
        if (pokemonIdsForCard(card).some(pid => familyIds.has(pid))) return false;
        const kinds = pokemonEnergyKinds(card);
        const coloredKinds = kinds.filter(kind => kind !== 'colorless' && kind !== 'dragon');
        const isPureColorless = kinds.length === 1 && kinds[0] === 'colorless';
        return kinds.length && !coloredKinds.includes(deck.energyPlan.kind) && !isPureColorless;
      });
      if (wrongPokemon.length) errors.push(`${wrongPokemon.length} Pokémon não combinam com a Energia ${ENERGY_LABEL[deck.energyPlan.kind] || deck.energyPlan.kind} escolhida.`);
    }
    const conf = confidence(deck);
    const valid = !errors.length;
    const simulation = deck.simulationResults || simulate(deck, 1000);
    const score = valid ? Math.max(0, Math.min(100, Math.round(
      30 + Math.max(0, 22 - simulation.mulliganRate * .5) +
      Math.min(16, simulation.searchRate * .16) +
      Math.min(15, cardsUsed.filter(([id]) => /busca|compra|atacante/.test(role(cardMap.get(id)))).length * 2) +
      Math.min(10, base.split.energy) + Math.min(7, (deck.ownedCards || base.total) / 60 * 7)
    ))) : 0;
    return {...base, errors:[...new Set(errors)], warnings:[...new Set(warnings)], valid, score, confidence:conf, simulation};
  }
  const deckValidationOriginal = deckValidation;
  deckValidation = validate;

  function addTo(target, card, wanted, allowMissing) {
    const current = Number(target[card.id] || 0);
    const same = deckNameQuantity({cards:target}, card, card.id);
    const maxName = deckCardClass(card) === 'energy' ? 60 : Math.max(0, 4 - same);
    const maxOwned = allowMissing ? deckCardLimit(card) : owned(card);
    const room = 60 - Object.values(target).reduce((a,b)=>a+Number(b||0),0);
    const add = Math.max(0, Math.min(wanted, maxName, maxOwned - current, room));
    if (add) target[card.id] = current + add;
    return add;
  }
  function candidatePool(source, format) {
    if (source === 'owned') return cards.filter(c => owned(c) > 0 && allowedInFormat(c, format) && !looksLikeUnlinkedPokemon(c));
    const representatives = new Map();
    for (const card of cards) {
      if (!allowedInFormat(card, format) || looksLikeUnlinkedPokemon(card)) continue;
      const key = `${deckCardClass(card)}:${deckNameKey(card)}`;
      const current = representatives.get(key);
      if (!current || owned(card) > owned(current) || (!current.regulationMark && card.regulationMark)) representatives.set(key, card);
    }
    return [...representatives.values()];
  }
  function bestCardForSpecies(pool, speciesId, scored) {
    return pool.filter(card => pokemonIdsForCard(card).includes(Number(speciesId)))
      .sort((a,b) => (scored.get(b.id)-scored.get(a.id)) || owned(b)-owned(a))[0] || null;
  }
  function addPokemonWithEvolutionLine(target, attacker, pool, scored, wanted, allowMissing, room) {
    const speciesId = pokemonIdsForCard(attacker)[0];
    const chain = evolutionChainIds(speciesId);
    if (!chain.length) return {added:0, complete:false};
    const lineCards = chain.map(id => id === speciesId ? attacker : bestCardForSpecies(pool,id,scored));
    if (lineCards.some(card => !card)) return {added:0, complete:false};
    const copies = Math.max(1, Math.min(wanted, Math.floor(room / lineCards.length)));
    if (!copies) return {added:0, complete:false};
    let added = 0;
    for (const card of lineCards) added += addTo(target,card,copies,allowMissing);
    return {added,complete:added === copies * lineCards.length};
  }
  function buildCandidate(config, variant) {
    const allowMissing = config.source !== 'owned';
    const pool = candidatePool(config.source, config.format);
    const groups = {pokemon:[], trainer:[], energy:[]};
    pool.forEach(card => groups[deckCardClass(card)].push(card));
    const preferred = normalize(config.favorite || '');
    const requestedKind = TYPE_TO_ENERGY[normalize(config.type || '')] || '';
    const favoriteCards = groups.pokemon.filter(card => preferred && normalize(card.name).includes(preferred));
    const favoriteKinds = [...new Set(favoriteCards.flatMap(pokemonEnergyKinds).filter(kind => kind !== 'colorless' && kind !== 'dragon'))];
    const familyScores = new Map();
    for (const card of groups.pokemon) {
      for (const kind of pokemonEnergyKinds(card)) {
        if (kind === 'colorless' || kind === 'dragon') continue;
        familyScores.set(kind, (familyScores.get(kind) || 0) + roleScore(card, config.objective));
      }
    }
    const rankedKinds = [...familyScores.entries()].sort((a,b) => b[1]-a[1]).map(([kind]) => kind);
    const chosenKind = requestedKind && requestedKind !== 'colorless' && requestedKind !== 'dragon'
      ? requestedKind
      : (favoriteKinds[0] || rankedKinds[variant % Math.max(1, Math.min(3, rankedKinds.length))] || 'colorless');
    const pokemonPool = groups.pokemon.filter(card => {
      const favoriteCompatible = preferred && normalize(card.name).includes(preferred) && matchesKind(card, chosenKind);
      return matchesKind(card, chosenKind) || favoriteCompatible;
    });
    const scored = new Map(pool.map(card => [card.id,
      roleScore(card,config.objective)
      + (preferred && normalize(card.name).includes(preferred) ? 45 : 0)
      - (deckCardClass(card) === 'trainer' ? trainerDependencyPenalty(card) : 0)
      + Math.min(4, owned(card))
    ]));
    pokemonPool.sort((a,b) => (scored.get(b.id)-scored.get(a.id)) || a.name.localeCompare(b.name,'pt-BR'));
    groups.trainer.sort((a,b) => (scored.get(b.id)-scored.get(a.id)) || a.name.localeCompare(b.name,'pt-BR'));
    const compatibleEnergy = compatibleEnergyPool(groups.energy, chosenKind);
    const target = {};
    const goals = config.objective === 'fast' ? [18,31,11] : config.objective === 'control' ? [14,35,11] : config.objective === 'beginner' ? [18,28,14] : [16,32,12];
    let pokemonAdded = 0;
    let mainAttacker = null;
    for (const card of pokemonPool) {
      if (pokemonAdded >= goals[0]) break;
      const chain = evolutionChainIds(pokemonIdsForCard(card)[0]);
      const wanted = chain.length > 1 ? 3 : 4;
      const result = addPokemonWithEvolutionLine(target,card,pokemonPool,scored,wanted,allowMissing,goals[0]-pokemonAdded);
      pokemonAdded = deckBreakdown({cards:target}).pokemon;
      if (result.added && !mainAttacker && /atacante/.test(role(card))) mainAttacker = card;
    }
    fillTrainersAndEnergy(target, groups.trainer, compatibleEnergy, chosenKind, goals, allowMissing);
    const ownedCount = Object.entries(target).reduce((n,[id,q])=>n+Math.min(Number(q),owned(cardMap.get(id))),0);
    const deck = {
      id:`candidate-${Date.now()}-${variant}`, name:`${config.favorite || 'Estratégia'} · ${OBJECTIVES[config.objective] || 'Competitivo'} ${variant+1}`,
      cards:target, format:config.format, generationMode:config.source, objective:config.objective, planejando:allowMissing,
      preferredType:config.type || ENERGY_LABEL[chosenKind] || '', energyPlan:{kind:chosenKind,label:ENERGY_LABEL[chosenKind] || chosenKind},
      rulesVersion:RULES_VERSION, createdAt:new Date().toISOString(),
      ownedCards:ownedCount, missingCards:60-ownedCount, generated:true, status:'draft'
    };
    deck.simulationResults = simulate(deck,1000,20260729+variant);
    const report = validate(deck);
    deck.score = report.score;
    deck.confidence = report.confidence;
    deck.status = report.valid ? 'valid' : 'invalid';
    deck.strengths = [
      report.simulation.basicRate >= 75 ? 'Boa chance estimada de abrir com Pokémon Básico.' : 'Plano adaptável com as cartas disponíveis.',
      `Plano de Energia ${ENERGY_LABEL[chosenKind] || chosenKind}, com ${report.split.energy} Energias reais para os atacantes selecionados.`
    ];
    deck.weaknesses = report.warnings.length ? report.warnings.slice(0,3) : ['Matchups ainda sem dados confiáveis.'];
    const mainPokemon = mainAttacker || Object.keys(target).map(id=>cardMap.get(id)).find(card=>card && deckCardClass(card)==='pokemon');
    deck.name = `${mainPokemon?.name || config.favorite || ENERGY_LABEL[chosenKind] || 'Estratégia'} · ${OBJECTIVES[config.objective] || 'Competitivo'}`;
    deck.explanation = `O núcleo usa Pokémon compatíveis com Energia ${ENERGY_LABEL[chosenKind] || chosenKind}. Prepare ${mainPokemon?.name || 'o atacante principal'}, preserve busca e compra, e mantenha Energia suficiente para o atacante seguinte. Cartas do Pokémon TCG Pocket e recursos sem alvo no deck foram descartados.`;
    return deck;
  }
  // Deck temático: o Pokémon escolhido e TODA a linha evolutiva dele entram
  // garantidos como atacantes principais; o resto do baralho é preenchido com
  // Pokémon do mesmo elemento, Treinadores e Energia — igual ao montador geral,
  // mas sem depender de pontuação para a linha evolutiva aparecer.
  function buildThematicDeck(pokemonId, config) {
    const mainPokemon = pokemonMap.get(Number(pokemonId));
    if (!mainPokemon) return null;
    const allowMissing = config.source !== 'owned';
    const pool = candidatePool(config.source, config.format);
    const groups = {pokemon:[], trainer:[], energy:[]};
    pool.forEach(card => groups[deckCardClass(card)].push(card));
    const chosenKind = pokemonKindForId(pokemonId);
    const scored = new Map(pool.map(card => [card.id, roleScore(card,'collection') + Math.min(4, owned(card))]));

    const familyIds = evolutionFamilyIds(pokemonId);
    const familyCards = familyIds.map(id => bestCardForSpecies(groups.pokemon,id,scored)).filter(Boolean);
    if (!familyCards.length) return null;

    const target = {};
    // Famílias grandes (Eevee e suas Eeveelutions) não podem valer 3-4 cópias
    // cada uma, ou sobra baralho só de atacante principal e falta Treinador e
    // Energia. Quanto mais estágios, menos cópias por estágio.
    const copiesPerStage = familyCards.length <= 1 ? 4 : familyCards.length === 2 ? 3 : familyCards.length <= 4 ? 2 : 1;
    for (const card of familyCards) addTo(target, card, copiesPerStage, allowMissing);

    const familySpeciesIds = new Set(familyIds);
    const supportPool = groups.pokemon.filter(card => {
      if (pokemonIdsForCard(card).some(id => familySpeciesIds.has(id))) return false;
      return matchesKind(card, chosenKind);
    }).sort((a,b) => (scored.get(b.id)-scored.get(a.id)) || owned(b)-owned(a) || a.name.localeCompare(b.name,'pt-BR'));

    const goals = [16, 32, 12];
    let pokemonAdded = deckBreakdown({cards:target}).pokemon;
    for (const card of supportPool) {
      if (pokemonAdded >= goals[0]) break;
      const chain = evolutionChainIds(pokemonIdsForCard(card)[0]);
      const wanted = chain.length > 1 ? 3 : 4;
      addPokemonWithEvolutionLine(target,card,supportPool,scored,wanted,allowMissing,goals[0]-pokemonAdded);
      pokemonAdded = deckBreakdown({cards:target}).pokemon;
    }

    groups.trainer.sort((a,b) => (scored.get(b.id)-scored.get(a.id)) || a.name.localeCompare(b.name,'pt-BR'));
    const compatibleEnergy = compatibleEnergyPool(groups.energy, chosenKind);
    fillTrainersAndEnergy(target, groups.trainer, compatibleEnergy, chosenKind, goals, allowMissing);

    const ownedCount = Object.entries(target).reduce((n,[id,q])=>n+Math.min(Number(q),owned(cardMap.get(id))),0);
    const familyNames = familyCards.map(card => pokemonMap.get(pokemonIdsForCard(card)[0])?.name).filter(Boolean);
    const deck = {
      id:`deck-${Date.now()}`, name:`${mainPokemon.name} · Temático`,
      cards:target, format:config.format, generationMode:config.source, objective:'collection', planejando:allowMissing,
      themePokemonId:Number(pokemonId), themeFamilyIds:familyIds,
      preferredType:ENERGY_LABEL[chosenKind] || '', energyPlan:{kind:chosenKind,label:ENERGY_LABEL[chosenKind] || chosenKind},
      rulesVersion:RULES_VERSION, createdAt:new Date().toISOString(), updatedAt:new Date().toISOString(),
      ownedCards:ownedCount, missingCards:60-ownedCount, generated:true, status:'draft'
    };
    const report = validate(deck);
    deck.score = report.score;
    deck.confidence = report.confidence;
    deck.status = report.valid ? 'valid' : 'invalid';
    deck.explanation = `Deck temático de ${mainPokemon.name}: ${familyNames.join(' + ')} como atacantes principais, reforçado por Pokémon de Energia ${ENERGY_LABEL[chosenKind] || chosenKind}.`;
    return deck;
  }
  function openAutoBuilder() {
    const types = availableDeckTypes();
    showModal(`<button class="modal-close" onclick="closeModal()">×</button>
      <h2>Montador automático</h2><p class="screen-subtitle">Gera três opções usando sua coleção real e regras locais. Nenhum efeito ausente será inventado.</p>
      <div class="auto-deck-form">
        <label>Formato<select id="autoFormat" class="field"><option value="standard">Padrão</option><option value="expanded">Expandido</option><option value="glc">Gym Leader Challenge</option><option value="casual">Livre / Casual</option></select></label>
        <label>Fonte das cartas<select id="autoSource" class="field"><option value="owned">Somente minhas cartas</option><option value="prioritize">Priorizar minhas cartas</option><option value="catalog">Catálogo completo</option></select></label>
        <label>Objetivo<select id="autoObjective" class="field">${Object.entries(OBJECTIVES).map(([v,l])=>`<option value="${v}">${l}</option>`).join('')}</select></label>
        <label>Tipo principal (opcional)<select id="autoType" class="field"><option value="">Qualquer tipo</option>${types.map(([t])=>`<option value="${esc(t)}">${esc(t)}</option>`).join('')}</select></label>
        <label>Pokémon ou carta obrigatória (opcional)<input id="autoFavorite" class="field" placeholder="Ex.: Charizard"></label>
      </div>
      <div class="deck-data-warning">A legalidade do formato terá confiança reduzida quando a impressão não possuir marca de regulamentação.</div>
      <button class="primary-btn auto-generate-btn" onclick="runAutoBuilder()">Analisar e montar 3 decks</button>`);
  }
  function runAutoBuilder() {
    const config = {
      format:document.getElementById('autoFormat').value,
      source:document.getElementById('autoSource').value,
      objective:document.getElementById('autoObjective').value,
      type:document.getElementById('autoType').value,
      favorite:document.getElementById('autoFavorite').value.trim()
    };
    if (config.source === 'owned' && ownedDeckPool().reduce((n,x)=>n+x.owned,0) < 60) {
      notify('Sua coleção ainda não possui 60 cartas disponíveis. Use “Priorizar minhas cartas” para receber sugestões com faltantes.');
      return;
    }
    const button = document.querySelector('.auto-generate-btn');
    if (button) { button.disabled=true; button.textContent='Analisando cartas e simulando mãos…'; }
    setTimeout(() => {
      lastCandidates = [0,1,2].map(i=>buildCandidate(config,i)).filter(d=>deckTotal(d)===60).sort((a,b)=>b.score-a.score);
      closeModal(); render(); window.scrollTo(0,0);
      notify(lastCandidates.length ? 'Três sugestões foram preparadas.' : 'Não foi possível completar 60 cartas com estes filtros.');
    },80);
  }
  function saveCandidate(index) {
    const source = lastCandidates[index];
    if (!source) return;
    const deck = JSON.parse(JSON.stringify(source));
    deck.id=`deck-${Date.now()}`; deck.createdAt=new Date().toISOString(); deck.updatedAt=deck.createdAt;
    safeDecks().push(deck); selectedDeckId=deck.id; lastCandidates=[]; saveState(); render(); notify('Deck salvo no fichário.');
  }
  function candidateCard(deck,index) {
    const r=validate(deck), s=r.simulation;
    return `<article class="auto-candidate"><div class="candidate-head"><div><span class="candidate-rank">Opção ${index+1}</span><h3>${esc(deck.name)}</h3></div><b>${r.score}/100</b></div>
      <div class="candidate-stats"><span>${r.split.pokemon} Pokémon</span><span>${r.split.trainer} Treinadores</span><span>${r.split.energy} Energias</span><span>${deck.ownedCards}/60 possuídas</span></div>
      <p>${esc(deck.explanation)}</p><small>Consistência estimada: ${s.searchRate}% · Mulligan: ${s.mulliganRate}% · Confiança ${r.confidence.label}</small>
      <div class="candidate-actions"><button class="secondary-btn" onclick="previewCandidate(${index})">Ver análise</button><button class="primary-btn" onclick="saveCandidate(${index})">Salvar este deck</button></div></article>`;
  }
  function previewCandidate(index) {
    const d=lastCandidates[index]; if(!d)return; const r=validate(d);
    showModal(`<button class="modal-close" onclick="closeModal()">×</button><h2>${esc(d.name)}</h2>
      <div class="deck-summary ${r.valid?'valid':'invalid'}"><strong>${r.score}/100 · ${r.total}/60 cartas</strong><span>Confiança ${r.confidence.label}</span><small>${esc(r.confidence.reason)}</small></div>
      <h3>Como o deck funciona</h3><p>${esc(d.explanation)}</p><h3>Pontos fortes</h3><ul>${d.strengths.map(x=>`<li>${esc(x)}</li>`).join('')}</ul>
      <h3>Pontos fracos</h3><ul>${d.weaknesses.map(x=>`<li>${esc(x)}</li>`).join('')}</ul>
      <h3>Teste de consistência</h3><p>${r.simulation.runs} mãos · ${r.simulation.basicRate}% com Básico · ${r.simulation.energyRate}% com Energia · ${r.simulation.searchRate}% com busca/compra.</p>
      <p class="deck-data-warning">Avaliação estimada por regras, funções confirmadas e simulação de consistência. Matchup ainda sem dados confiáveis.</p>`);
  }
  function testDeckHand(id) {
    const deck=safeDecks().find(d=>d.id===id); if(!deck)return;
    const random=seeded(Date.now()); let hand=shuffle(expanded(deck),random).slice(0,7);
    let mulligans=0;
    while(mulligans<20 && !hand.some(cid=>{const c=cardMap.get(cid);return c&&deckCardClass(c)==='pokemon'&&basic(c)})){mulligans++;hand=shuffle(expanded(deck),random).slice(0,7);}
    handTest={id,hand,mulligans};
    showModal(`<button class="modal-close" onclick="closeModal()">×</button><h2>Teste de mão inicial</h2><p>${mulligans?`${mulligans} mulligan(s) até encontrar um Pokémon Básico.`:'Mão válida sem mulligan.'}</p>
      <div class="hand-grid">${hand.map(cid=>{const c=cardMap.get(cid);return `<button onclick="openCard('${esc(cid)}')">${c?.imageUrl?`<img src="${esc(c.imageUrl)}" alt="">`:''}<span>${esc(c?.name||cid)}</span></button>`}).join('')}</div>
      <button class="primary-btn" onclick="closeModal();testDeckHand('${esc(id)}')">Embaralhar novamente</button>`);
  }

  /* ---------- Pokédex de decks temáticos ----------
     Um deck temático guarda `themePokemonId`: o número da Pokédex que ele
     representa. O status de cada um dos 1.025 é recalculado a partir da
     coleção ATUAL (não do que foi salvo na criação), para o quadradinho virar
     "completo" assim que a última carta que faltava for cadastrada. */
  // Ao contrário do `owned(cardMap.get(id))` usado logo após montar um deck
  // (mesma chamada síncrona, catálogo garantidamente presente), este lê
  // `deck.cards` salvo de sessões anteriores: se o catálogo foi atualizado e a
  // impressão saiu, o id fica órfão. Sem a guarda, `owned(undefined)` derruba
  // a tela inteira de Decks.
  function ownedById(cardId) {
    const card = cardMap.get(cardId);
    return card ? owned(card) : 0;
  }
  let themeStatusCache = { revision: -1, value: null };
  function themeStatusMap() {
    if (themeStatusCache.revision === stateRevision && themeStatusCache.value) return themeStatusCache.value;
    const byPokemon = new Map();
    for (const deck of safeDecks()) {
      const pid = Number(deck.themePokemonId);
      if (!pid) continue;
      if (!byPokemon.has(pid)) byPokemon.set(pid, []);
      byPokemon.get(pid).push(deck);
    }
    const result = new Map();
    for (const [pid, decks] of byPokemon) {
      let best = null, bestMissing = Infinity;
      for (const deck of decks) {
        let missing = Math.max(0, 60 - deckTotal(deck));
        for (const [cardId, qty] of Object.entries(deck.cards || {})) missing += Math.max(0, Number(qty) - ownedById(cardId));
        if (missing < bestMissing) { bestMissing = missing; best = deck; }
      }
      result.set(pid, { deck: best, missing: bestMissing, complete: bestMissing === 0 });
    }
    themeStatusCache = { revision: stateRevision, value: result };
    return result;
  }
  function themeDeckPickerRows(query) {
    const norm = normalize(query || '');
    const items = pokedex.filter(item => !norm || normalize(`${item.name} ${item.id}`).includes(norm)).slice(0, 60);
    return items.length
      ? items.map(item => `<button class="pokemon-tile" onclick="closeModal();confirmThematicDeck(${item.id})">
          <img src="${esc(item.sprite)}" loading="lazy" alt="${esc(item.name)}">
          <span class="pokemon-number">Nº ${String(item.id).padStart(4,'0')}</span>
          <span class="pokemon-name">${esc(item.name)}</span>
        </button>`).join('')
      : '<div class="empty">Nenhum Pokémon encontrado.</div>';
  }
  function renderThemeDeckPickerResults(query) {
    const el = document.getElementById('themeDeckPickerResults');
    if (el) el.innerHTML = themeDeckPickerRows(query);
  }
  function openThematicDeckBuilder(presetId) {
    if (presetId) { confirmThematicDeck(presetId); return; }
    showModal(`<button class="modal-close" onclick="closeModal()">×</button>
      <h2>Criar deck temático</h2>
      <p class="screen-subtitle">Escolha um Pokémon: ele e toda a linha evolutiva dele entram como atacantes principais, e o resto do baralho é preenchido com Pokémon do mesmo tipo.</p>
      <input id="themeDeckSearch" class="field search" placeholder="Buscar Pokémon por nome ou número" oninput="renderThemeDeckPickerResults(this.value)">
      <div id="themeDeckPickerResults" class="pokemon-grid theme-picker-grid">${themeDeckPickerRows('')}</div>`);
  }
  function confirmThematicDeck(pokemonId) {
    const mon = pokemonMap.get(Number(pokemonId));
    if (!mon) return;
    const familyNames = evolutionFamilyIds(pokemonId).map(id => pokemonMap.get(id)?.name).filter(Boolean);
    const kind = pokemonKindForId(pokemonId);
    const existing = themeStatusMap().get(Number(pokemonId));
    showModal(`<button class="modal-close" onclick="closeModal()">×</button>
      <h2>Deck temático · ${esc(mon.name)}</h2>
      <p class="screen-subtitle">Atacantes principais: ${esc(familyNames.join(' + ') || mon.name)}. Reforço de Pokémon do tipo ${esc(ENERGY_LABEL[kind] || kind)}, Treinadores e Energia.</p>
      ${existing ? `<div class="deck-data-warning">Você já tem um deck temático de ${esc(mon.name)} (${60-existing.missing}/60 cartas). Gerar de novo cria outro deck, sem apagar o atual.</div>` : ''}
      <button class="primary-btn" onclick="generateThematicDeck(${pokemonId})">Gerar deck temático</button>`);
  }
  function generateThematicDeck(pokemonId) {
    const deck = buildThematicDeck(pokemonId, { format:'casual', source:'catalog' });
    if (!deck) { notify('Não encontrei cartas dessa linha evolutiva no catálogo.'); return; }
    safeDecks().push(deck);
    selectedDeckId = deck.id;
    ui.decksView = 'dex';
    closeModal(); saveState(); render(); window.scrollTo(0,0);
    notify(deck.status === 'valid' ? 'Deck temático criado com 60 cartas!' : `Deck temático criado com ${deckTotal(deck)} cartas. Confira os avisos.`);
  }
  function renderThemeTile(item, status) {
    const complete = Boolean(status?.complete);
    const started = Boolean(status);
    const badge = complete ? '<span class="pokemon-owned-count">✓</span>'
      : started ? `<span class="pokemon-owned-count progresso">${60-status.missing}/60</span>` : '';
    const action = status?.deck ? `selectedDeckId='${esc(status.deck.id)}';render()` : `openThematicDeckBuilder(${item.id})`;
    return `<button class="pokemon-tile${complete ? '' : ' missing'}" onclick="${action}">
      ${badge}
      <img src="${esc(item.sprite)}" loading="lazy" alt="${esc(item.name)}">
      <span class="pokemon-number">Nº ${String(item.id).padStart(4,'0')}</span>
      <span class="pokemon-name">${esc(item.name)}</span>
    </button>`;
  }
  function renderThemeGridResults() {
    const statusMap = themeStatusMap();
    const query = normalize(ui.deckDexQuery || '');
    const items = pokedex.filter(item => !query || normalize(`${item.name} ${item.id}`).includes(query));
    const grouped = new Map();
    for (const item of items) { if (!grouped.has(item.region)) grouped.set(item.region, []); grouped.get(item.region).push(item); }
    return items.length
      ? REGION_ORDER.filter(region=>grouped.has(region)).map(region => `<section class="region-section">
          <div class="region-heading"><h3>${esc(region)}</h3><span>${grouped.get(region).filter(item=>statusMap.get(item.id)?.complete).length} completos · ${grouped.get(region).length} exibidos</span></div>
          <div class="pokemon-grid">${grouped.get(region).map(item => renderThemeTile(item, statusMap.get(item.id))).join('')}</div>
        </section>`).join('')
      : '<div class="empty"><strong>Nenhum Pokémon encontrado</strong>Altere a busca para continuar.</div>';
  }
  function renderThemeGrid() {
    const statusMap = themeStatusMap();
    const completos = pokedex.filter(item => statusMap.get(item.id)?.complete).length;
    const emAndamento = pokedex.filter(item => statusMap.get(item.id) && !statusMap.get(item.id).complete).length;
    return `<p class="screen-subtitle">${completos} de ${pokedex.length} decks temáticos completos${emAndamento ? ` · ${emAndamento} em andamento` : ''}. Toque em um Pokémon sem deck para começar o dele.</p>
      <div class="toolbar">
        <input id="themeDexSearchInput" class="field search" value="${esc(ui.deckDexQuery)}" placeholder="Buscar Pokémon por nome ou número"
          oninput="ui.deckDexQuery=this.value;document.getElementById('themeDexResults').innerHTML=renderThemeGridResults()">
      </div>
      <div id="themeDexResults">${renderThemeGridResults()}</div>`;
  }
  const oldEditor=renderDeckEditor;
  const oldDeleteDeck=deleteDeck;
  deleteDeck=function(id){
    if (!confirm('Excluir este deck? Esta ação não altera sua coleção de cartas.')) return;
    oldDeleteDeck(id);
  };
  renderDeckEditor=function(deck){
    const html=oldEditor(deck);
    const r=validate(deck);
    const temaSpan = deck.themePokemonId ? `<span>Temático: ${esc(pokemonMap.get(deck.themePokemonId)?.name || '')}</span>` : '';
    return html.replace('<div class="deck-actions">', `<div class="deck-analysis-strip">${temaSpan}<span>Formato: ${esc(deck.format||'Livre / Casual')}</span><span>Confiança: ${r.confidence.label}</span><span>Faltam: ${Math.max(0,deck.missingCards||0)}</span></div><div class="deck-actions"><button class="secondary-btn" onclick="testDeckHand('${esc(deck.id)}')">Testar mão</button>`)
      .replace('</section>', `<section class="deck-explanation"><h3>Como jogar</h3><p>${esc(deck.explanation||'Prepare um atacante, use busca e compra para manter o fluxo e preserve recursos para o próximo ataque.')}</p><small>${esc(r.confidence.reason)}</small></section></section>`);
  };
  renderDecks=function(){
    const decks=safeDecks(), selected=decks.find(d=>d.id===selectedDeckId);
    if(selected)return renderDeckEditor(selected);
    const mineView = `<p class="screen-subtitle">Monte, valide, teste e exporte decks de 60 cartas usando sua coleção real.</p>
      <button class="auto-deck-hero" onclick="openAutoBuilder()"><span>✨</span><div><strong>Montar deck automaticamente</strong><small>Escolha formato, objetivo e fonte das cartas</small></div></button>
      <button class="auto-deck-hero theme-deck-hero" onclick="openThematicDeckBuilder()"><span>🎯</span><div><strong>Criar deck temático</strong><small>Escolha um Pokémon: ele e a linha evolutiva viram os atacantes</small></div></button>
      ${lastCandidates.length?`<h3 class="section-title">Melhores sugestões</h3><div class="candidate-list">${lastCandidates.map(candidateCard).join('')}</div>`:''}
      <div class="deck-row"><input id="deckName" class="field" placeholder="Nome do novo deck"><button class="primary-btn" onclick="addDeck()">Criar vazio</button></div>
      <h3 class="section-title">Decks salvos</h3><div class="deck-lista">${decks.length
        // O cartão vem do app.js: capa, preço e retrospecto ficam iguais nas
        // duas telas de decks, em vez de existirem só numa delas.
        ?decks.map(deck=>typeof cartaoDeDeck==='function'
          ?cartaoDeDeck(deck)
          :`<button class="panel deck-panel" onclick="selectedDeckId='${esc(deck.id)}';render()"><div class="set-title-row"><span class="set-name">${esc(deck.name)}</span></div></button>`).join('')
        :'<div class="empty"><strong>Nenhum deck criado</strong>Use o montador automático ou crie um deck vazio.</div>'}</div>`;
    return `<section class="screen"><h2 class="screen-title">Decks</h2>
      <div class="decks-view-toggle">
        <button class="${ui.decksView==='dex'?'':'ativo'}" onclick="ui.decksView='mine';render()">Meus decks</button>
        <button class="${ui.decksView==='dex'?'ativo':''}" onclick="ui.decksView='dex';render()">Pokédex de decks</button>
      </div>
      ${ui.decksView==='dex' ? renderThemeGrid() : mineView}
    </section>`;
  };
  window.openAutoBuilder=openAutoBuilder;
  window.runAutoBuilder=runAutoBuilder;
  window.saveCandidate=saveCandidate;
  window.previewCandidate=previewCandidate;
  window.testDeckHand=testDeckHand;
  window.openThematicDeckBuilder=openThematicDeckBuilder;
  window.renderThemeDeckPickerResults=renderThemeDeckPickerResults;
  window.renderThemeGridResults=renderThemeGridResults;
  window.confirmThematicDeck=confirmThematicDeck;
  window.generateThematicDeck=generateThematicDeck;
  window.deckBuilderDiagnostics=()=>lastCandidates.map(deck=>({
    name:deck.name,
    energyPlan:deck.energyPlan,
    total:deckTotal(deck),
    split:deckBreakdown(deck),
    errors:validate(deck).errors,
    cards:Object.entries(deck.cards||{}).map(([id,quantity])=>{
      const card=cardMap.get(id);
      return {id,name:card?.name,quantity,class:deckCardClass(card),pokemonIds:pokemonIdsForCard(card),energyKinds:energyKinds(card),setId:card?.setId};
    })
  }));
})();
