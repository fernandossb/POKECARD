'use strict';

const TRAINER_CANONICAL_BASIC_ENERGIES = {
  grass: 'sve-001',
  fire: 'sve-002',
  water: 'sve-003',
  lightning: 'sve-004',
  psychic: 'sve-005',
  fighting: 'sve-006',
  darkness: 'sve-007',
  metal: 'sve-008'
};

const TRAINER_KNOWN_ENERGY_IDS = new Set([
  'sve-001', 'sve-002', 'sve-003', 'sve-004',
  'sve-005', 'sve-006', 'sve-007', 'sve-008',
  'sv01-258', 'swsh12.5-153', 'swsh12.5-155', 'swsh12.5-158'
]);

const DEFAULT_TRAINER_DECKS = [
  {
    id: 'lucario',
    name: 'Mega Lucario ex',
    strategy: 'Evolução e aceleração de Energia para o Banco.',
    cover: 'me01-077',
    format: 'TRAINING',
    preset: true,
    counts: {
      'me01-077': 3, 'me01-076': 4, 'me01-073': 2, 'me01-072': 3,
      'me01-075': 2, 'me01-074': 2, 'sv06.5-038': 1, 'sv06-141': 1,
      'me01-116': 4, 'me01-119': 4, 'sv09-149': 3, 'sv02-172': 2,
      'sv08-187': 1, 'sv08-177': 2, 'me01-124': 4, 'me01-131': 4,
      'sv06.5-061': 2, 'sv06-163': 1, 'me01-130': 1, 'sv10.5b-079': 2, 'sv01-258': 12
    }
  },
  {
    id: 'hariyama',
    name: 'Hariyama • Impacto',
    strategy: 'Troque o Ativo adversário ao evoluir e ataque com 210 de dano.',
    cover: 'me01-073',
    format: 'TRAINING',
    preset: true,
    counts: {
      'me01-072': 4, 'me01-073': 4, 'me01-074': 3, 'me01-075': 3, 'sv06.5-038': 1, 'sv06-141': 1,
      'me01-116': 4, 'me01-131': 4, 'me01-119': 4, 'sv09-149': 2, 'sv02-172': 2,
      'sv06.5-061': 3, 'me01-124': 4, 'me01-130': 2, 'sv10.5b-079': 2, 'sv08-187': 1, 'sv01-258': 16
    }
  },
  {
    id: 'solrock',
    name: 'Solrock & Lunatone',
    strategy: 'Ataque com uma Energia e compre cartas com Ciclo Lunar.',
    cover: 'me01-075',
    format: 'TRAINING',
    preset: true,
    counts: {
      'me01-075': 4, 'me01-074': 4, 'sv06.5-038': 1, 'sv06-141': 2,
      'me01-116': 4, 'me01-131': 4, 'me01-119': 4, 'sv09-149': 2, 'sv02-172': 3,
      'sv06.5-061': 4, 'me01-124': 4, 'me01-130': 3, 'sv10.5b-079': 2, 'sv08-187': 1, 'sv01-258': 18
    }
  },
  {
    id: 'ursaluna',
    name: 'Ursaluna ex • Virada',
    strategy: 'Prepare o Banco e aproveite o custo menor de Lua de Sangue no fim da partida.',
    cover: 'sv06-141',
    format: 'TRAINING',
    preset: true,
    counts: {
      'sv06-141': 3, 'me01-075': 4, 'me01-074': 4, 'sv06.5-038': 1,
      'me01-116': 4, 'me01-131': 4, 'me01-119': 4, 'sv09-149': 2, 'sv02-172': 3,
      'sv06.5-061': 3, 'me01-124': 3, 'me01-130': 3, 'sv10.5b-079': 3, 'sv08-187': 1, 'sv01-258': 18
    }
  },
  {
    id: 'academy-pikachu',
    name: 'Pikachu V • Academia 2022',
    strategy: 'Lista da Academia de Batalha 2022 · treino fora da rotação.',
    cover: 'swsh4-43',
    format: 'ACADEMY',
    preset: true,
    counts: {
      'swsh4-43': 1, 'swsh4-53': 4, 'swsh4-54': 3, 'swsh8-91': 4, 'swsh8-92': 3, 'swsh8-93': 2,
      'swsh1-74': 2, 'swsh1-76': 1, 'swsh8-102': 1, 'swsh8-109': 1, 'swsh1-164': 4, 'swsh1-177': 2,
      'swsh1-183': 2, 'swsh5-124': 1, 'swsh1-165': 4, 'swsh8-240': 3, 'swsh8-226': 2, 'swsh2-154': 1,
      'swsh2-167': 1, 'swsh12.5-155': 18
    }
  },
  {
    id: 'academy-cinderace',
    name: 'Cinderace V • Academia 2022',
    strategy: 'Lista da Academia de Batalha 2022 · treino fora da rotação.',
    cover: 'swsh8-43',
    format: 'ACADEMY',
    preset: true,
    counts: {
      'swsh8-43': 1, 'swsh8-46': 4, 'swsh8-49': 3, 'swsh8-29': 3, 'swsh8-30': 2, 'swsh6-23': 3,
      'swsh6-24': 2, 'swsh1-29': 1, 'swsh3.5-7': 2, 'swsh1-164': 4, 'swsh1-160': 2, 'swsh1-183': 2,
      'swsh1-175': 2, 'swsh1-177': 1, 'swsh1-165': 4, 'swsh8-240': 3, 'swsh8-226': 2, 'swsh2-167': 1,
      'swsh12.5-153': 18
    }
  },
  {
    id: 'academy-eevee',
    name: 'Eevee V • Academia 2022',
    strategy: 'Lista da Academia de Batalha 2022 · treino fora da rotação.',
    cover: 'swshp-SWSH065',
    format: 'ACADEMY',
    preset: true,
    counts: {
      'swshp-SWSH065': 1, 'swsh8-167': 2, 'swsh8-162': 3, 'swsh8-163': 2, 'swsh8-170': 3, 'swsh8-171': 2,
      'swsh3.5-35': 4, 'swsh3.5-36': 3, 'swshp-SWSH193': 2, 'swsh1-183': 2, 'swsh1-164': 4, 'swsh1-165': 4,
      'swsh8-240': 3, 'swsh8-226': 4, 'swsh2-167': 1, 'swsh3-165': 1, 'swsh8-228': 1, 'swsh12.5-158': 18
    }
  },
  {
    id: 'competitive-dragapult',
    name: 'Dragapult',
    strategy: 'Limitless · 5º Regional Nice 2026 · Yuya Kogai.',
    cover: 'sv06-130',
    format: 'STANDARD',
    preset: true,
    counts: {
      'sv06-128': 4, 'sv06-129': 4, 'sv06-130': 3, 'sv08.5-004': 2, 'sv09-120': 1, 'sv05-129': 1,
      'sv06-095': 2, 'sv06.5-038': 1, 'me03-062': 1,
      'me01-119': 4, 'sv07-133': 2, 'me03-084': 1, 'me03-076': 1, 'me01-114': 3,
      'me01-167': 4, 'me03-081': 4, 'me01-131': 3, 'me01-173': 3, 'me03-071': 4,
      'sv06-165': 1, 'me01-127': 2,
      'sve-002': 3, 'sve-005': 3, 'sve-007': 3
    }
  },
  {
    id: 'competitive-basic-box',
    name: 'Basic Box',
    strategy: 'Limitless · 1º Regional Nice 2026 · Luc Gantner.',
    cover: 'me01-104',
    format: 'STANDARD',
    preset: true,
    counts: {
      'me01-104': 4, 'me03-062': 3, 'sv06-025': 2, 'sv06-064': 2, 'sv08-076': 2,
      'me02.5-076': 2, 'sv05-123': 1, 'sv08-056': 1, 'sv05-081': 1, 'sv08-111': 1,
      'sv06.5-038': 1, 'sv05-025': 1,
      'sv07-133': 4, 'sv08-170': 2, 'me01-114': 2, 'sv05-145': 1,
      'me01-115': 4, 'me01-131': 4, 'me01-173': 2, 'sv06-165': 1, 'sv07-135': 1,
      'sv07-131': 3,
      'sve-001': 6, 'sve-005': 3, 'sve-004': 2, 'sve-006': 2, 'sve-003': 2
    }
  },
  {
    id: 'competitive-n-zoroark',
    name: "N's Zoroark",
    strategy: 'Limitless · 25º Regional Nice 2026 · Arthur Gibelin.',
    cover: 'sv09-098',
    format: 'STANDARD',
    preset: true,
    counts: {
      'me02.5-136': 4, 'sv09-098': 4, 'me02.5-155': 2, 'sv06-095': 2, 'sv09-116': 1,
      'sv09-096': 1, 'sv06.5-035': 1, 'sv06.5-039': 1, 'sv08.5-004': 1, 'me03-062': 1,
      'me01-119': 4, 'me01-114': 4, 'sv08-170': 2, 'sv08.5-096': 1, 'me03-076': 1,
      'sv06.5-059': 1, 'me01-167': 4, 'me01-131': 3, 'sv09-153': 3, 'me03-081': 2,
      'me01-173': 2, 'me04-082': 1, 'sv06-163': 1, 'sv06.5-055': 2,
      'sv09-152': 2, 'sv10-180': 2, 'sve-007': 7
    }
  },
  {
    id: 'competitive-slowking',
    name: 'Slowking',
    strategy: 'Limitless · 11º Regional Nice 2026 · Tomás Malaca.',
    cover: 'sv07-058',
    format: 'STANDARD',
    preset: true,
    counts: {
      'sv07-057': 4, 'sv07-058': 3, 'me01-104': 3, 'sv08-076': 2, 'sv09-063': 2,
      'sv06.5-047': 2, 'sv08-100': 1, 'sv07-055': 1, 'me03-062': 1, 'sv06.5-038': 1,
      'me02.5-076': 1,
      'me01-119': 4, 'sv05-145': 3, 'sv08-187': 1, 'me03-081': 4,
      'me02-094': 3, 'me01-173': 3, 'me01-131': 3, 'sv06-163': 1, 'me01-130': 1,
      'me04-082': 1, 'sv06-158': 1, 'sv06.5-054': 4,
      'me03-088': 4, 'sve-005': 4, 'sv06-166': 2
    }
  },
  {
    id: 'competitive-dhelmise',
    name: 'Dhelmise',
    strategy: 'Limitless · 2º Regional Nice 2026 · Maxime Launay.',
    cover: 'sv09-070',
    format: 'STANDARD',
    preset: true,
    counts: {
      'sv09-059': 4, 'sv09-060': 4, 'sv06-020': 4, 'sv06-022': 4, 'sv09-070': 4,
      'me02.5-076': 2, 'me01-087': 1, 'sv08-076': 1, 'sv06-141': 1,
      'sv05-147': 4, 'me01-119': 3, 'me05-078': 3, 'me01-114': 3, 'me03-079': 2,
      'me01-131': 4, 'me03-081': 3, 'me01-173': 3, 'me04-080': 2,
      'me03-088': 4, 'sve-005': 3, 'sv06-167': 1
    }
  },
  {
    id: 'competitive-alakazam-dudunsparce',
    name: 'Alakazam / Dudunsparce',
    strategy: 'Limitless · 21º Regional Nice 2026 · Luke Sullivan.',
    cover: 'sv06-082',
    format: 'STANDARD',
    preset: true,
    counts: {
      'sv06-080': 4, 'sv06-081': 4, 'sv06-082': 3, 'sv09-120': 3, 'sv05-129': 2,
      'sv06.5-040': 1, 'me02.5-076': 1, 'sv06.5-038': 1, 'sv10-010': 1,
      'me02-087': 4, 'sv10.5w-084': 3, 'me01-114': 2, 'sv06-155': 1, 'sv05-146': 1,
      'me01-167': 4, 'me03-081': 4, 'me01-125': 2, 'me02-094': 2, 'me01-173': 1,
      'me03-115': 1, 'me04-082': 1, 'sv06-150': 2, 'sv09-151': 1, 'me02-085': 4,
      'me03-088': 4, 'sve-005': 2, 'sv08-191': 1
    }
  },
  {
    id: 'competitive-dragapult-dusknoir',
    name: 'Dragapult / Dusknoir',
    strategy: 'Limitless · 31º Regional Nice 2026 · Tarran Flatt.',
    cover: 'sv06-130',
    format: 'STANDARD',
    preset: true,
    counts: {
      'sv06-128': 4, 'sv06-129': 4, 'sv06-130': 3, 'sv06.5-018': 2, 'sv06.5-019': 2,
      'sv06.5-020': 1, 'sv08.5-004': 2, 'sv06-095': 1, 'me02-014': 1, 'sv10-010': 1,
      'sv06.5-038': 1, 'me03-062': 1,
      'me01-119': 4, 'me01-114': 3, 'sv07-133': 2, 'me03-076': 1, 'me03-084': 1,
      'me03-081': 4, 'me01-167': 4, 'me01-131': 3, 'me01-173': 3,
      'me04-082': 1, 'sv06-165': 1, 'sv10-180': 2,
      'sve-005': 3, 'sve-002': 3, 'sve-007': 2
    }
  },
  {
    id: 'competitive-mega-excadrill',
    name: 'Mega Excadrill',
    strategy: 'Limitless · 44º Regional Louisville 2026 · Raymond Liu.',
    cover: 'me05-065',
    format: 'STANDARD',
    preset: true,
    counts: {
      'sv10.5b-045': 4, 'me05-065': 2, 'sv09-061': 4, 'sv09-062': 4, 'sv09-063': 1,
      'sv10.5b-067': 2,
      'sv10-176': 4, 'me01-119': 4, 'me01-114': 3, 'me03-076': 1, 'sv06-154': 1,
      'me01-167': 4, 'sv10.5b-084': 2, 'me04-109': 2, 'me01-131': 1,
      'me03-108': 1, 'me04-082': 1, 'me01-130': 1, 'sv05-152': 1, 'sv08-177': 1,
      'sve-008': 16
    }
  },
  {
    id: 'competitive-sharpedo-toxtricity',
    name: 'Sharpedo / Toxtricity',
    strategy: 'Limitless · 47º Regional Nice 2026 · Tapio Ikonen.',
    cover: 'me02-061',
    format: 'STANDARD',
    preset: true,
    counts: {
      'me02-060': 4, 'me02-061': 4, 'sv06.5-039': 3, 'me02-067': 2, 'me02-068': 2,
      'sv06-095': 2, 'sv06-131': 2, 'sv06.5-038': 1,
      'me01-114': 4, 'me01-119': 4, 'sv10.5w-083': 2, 'sv09-149': 1, 'sv10-176': 1,
      'me01-167': 4, 'me03-081': 4, 'me01-131': 4, 'sv06-165': 1,
      'sv06.5-055': 3, 'sv10-180': 2, 'sv08-177': 1, 'sve-007': 9
    }
  },
  {
    id: 'competitive-mega-lucario-hariyama',
    name: 'Mega Lucario / Hariyama',
    strategy: 'Limitless · 54º Regional Nice 2026 · Julian Patz.',
    cover: 'me01-077',
    format: 'STANDARD',
    preset: true,
    counts: {
      'me01-076': 3, 'me01-077': 3, 'me01-075': 3, 'me01-072': 2, 'me01-073': 2,
      'me01-074': 2, 'me03-003': 1, 'me03-062': 1,
      'me01-119': 4, 'me03-076': 2, 'me01-114': 2, 'sv09-146': 1, 'me01-132': 1,
      'me01-116': 4, 'me01-124': 4, 'me01-131': 4, 'me03-081': 3,
      'me01-130': 1, 'sv06-163': 1, 'sv10.5b-079': 2, 'sv08-177': 2, 'sv10-180': 1,
      'sve-006': 11
    }
  }
];

function mapCardIdForTrainer(cardId) {
  if (!cardId || TRAINER_KNOWN_ENERGY_IDS.has(cardId)) return cardId;
  if (typeof cardMap !== 'undefined' && typeof ehEnergiaBasica === 'function' && typeof tipoDaEnergiaBasica === 'function') {
    const card = cardMap.get(cardId);
    if (card && ehEnergiaBasica(card)) {
      const tipo = tipoDaEnergiaBasica(card);
      if (tipo && TRAINER_CANONICAL_BASIC_ENERGIES[tipo]) {
        return TRAINER_CANONICAL_BASIC_ENERGIES[tipo];
      }
    }
  }
  return cardId;
}

function obterDecksIniciaisDoTrainer() {
  try {
    if (window.Android?.getInitialTrainerDecks) {
      const raw = window.Android.getInitialTrainerDecks();
      const parsed = JSON.parse(raw || '{}');
      if (Array.isArray(parsed.decks) && parsed.decks.length) {
        return parsed.decks;
      }
    }
  } catch (_) {}
  return DEFAULT_TRAINER_DECKS;
}

function sincronizarDecksDoTrainer() {
  if (!state) return false;
  state.decks = Array.isArray(state.decks) ? state.decks : [];
  if (!state.decks.length) {
    state.migratedTrainerDecks = [];
  }
  const migratedSet = new Set(Array.isArray(state.migratedTrainerDecks) ? state.migratedTrainerDecks : []);
  const existingIds = new Set(state.decks.map(d => d?.id).filter(Boolean));
  const initialDecks = obterDecksIniciaisDoTrainer();
  let changed = false;

  for (const td of initialDecks) {
    if (!td?.id || !td?.name || !td?.counts) continue;
    if (existingIds.has(td.id)) {
      if (!migratedSet.has(td.id)) {
        migratedSet.add(td.id);
        changed = true;
      }
      continue;
    }
    if (migratedSet.has(td.id)) continue;

    const cards = {};
    for (const [cid, qty] of Object.entries(td.counts)) {
      const n = Math.trunc(Number(qty) || 0);
      if (cid && n > 0) cards[cid] = n;
    }
    const now = new Date().toISOString();
    state.decks.push({
      id: String(td.id),
      name: String(td.name),
      cards,
      capa: String(td.cover || Object.keys(cards)[0] || ''),
      format: td.format === 'STANDARD' ? 'standard' : 'casual',
      trainerFormat: String(td.format || 'TRAINING'),
      explanation: String(td.strategy || 'Deck salvo para jogar no TCG Trainer.'),
      planejando: true,
      tcgTrainer: true,
      trainerPreset: Boolean(td.preset),
      createdAt: now,
      updatedAt: now
    });
    existingIds.add(td.id);
    migratedSet.add(td.id);
    changed = true;
  }

  state.migratedTrainerDecks = [...migratedSet];
  if (changed && typeof saveState === 'function') {
    saveState();
  }
  syncDecksToTrainerNative();
  return changed;
}

function buildTrainerDecksPayload() {
  const decks = Array.isArray(state?.decks) ? state.decks : [];
  const out = [];
  for (const deck of decks) {
    if (!deck?.id || !deck?.name) continue;
    const counts = {};
    let total = 0;
    for (const [rawId, rawQty] of Object.entries(deck.cards || {})) {
      const qty = Math.trunc(Number(rawQty) || 0);
      if (!rawId || qty <= 0) continue;
      const mappedId = mapCardIdForTrainer(rawId);
      counts[mappedId] = Math.min(60, (counts[mappedId] || 0) + qty);
      total += qty;
    }
    if (total <= 0) continue;
    const coverCard = typeof cartaDeCapa === 'function' ? cartaDeCapa(deck) : null;
    const cover = String(deck.capa || coverCard?.id || Object.keys(counts)[0] || 'me01-077');
    const trainerFormat = deck.trainerFormat
      || (String(deck.id).startsWith('academy-') ? 'ACADEMY' : deck.format === 'standard' ? 'STANDARD' : 'TRAINING');
    out.push({
      id: String(deck.id),
      name: String(deck.name),
      strategy: String(deck.explanation || 'Deck salvo na aba Decks do PokeCard.'),
      cover,
      format: trainerFormat,
      counts
    });
  }
  return out;
}

function syncDecksToTrainerNative() {
  try {
    if (!window.Android?.syncTrainerDecks) return;
    const payload = {
      version: 1,
      decks: buildTrainerDecksPayload()
    };
    window.Android.syncTrainerDecks(JSON.stringify(payload));
  } catch (_) {}
}

function openTcgTrainer(preferredDeckId = '') {
  if (!state || !window.Android?.openTcgTrainer) {
    window.alert('O TCG Trainer está disponível no aplicativo Android atualizado do PokeCard.');
    return;
  }
  const counts = {};
  for (const id of Object.keys(state.entries || {})) {
    const quantity = quantityFor(id);
    if (Number.isSafeInteger(quantity) && quantity > 0 && quantity <= 1000000) {
      counts[id] = (counts[id] || 0) + quantity;
      const mapped = mapCardIdForTrainer(id);
      if (mapped !== id) {
        counts[mapped] = Math.min(1000000, (counts[mapped] || 0) + quantity);
      }
    }
  }
  const chosenId = preferredDeckId || (typeof selectedDeckId === 'string' && selectedDeckId ? selectedDeckId : '');
  window.Android.openTcgTrainer(JSON.stringify({
    version: 1,
    counts,
    decks: buildTrainerDecksPayload(),
    selectedDeckId: chosenId
  }));
}
