import { writeFile } from 'node:fs/promises';

const api = 'https://api.tcgdex.net/v2/en';
async function get(path, base = api) {
  for (let attempt = 0; attempt < 3; attempt++) {
    try {
      const response = await fetch(base + path, { signal: AbortSignal.timeout(15000) });
      if (!response.ok) throw new Error(`${path}: HTTP ${response.status}`);
      return await response.json();
    } catch (error) {
      if (attempt === 2) throw error;
    }
  }
}

const set = await get('/sets/me06');
const portuguese = await get('/sets/me06', 'https://api.tcgdex.net/v2/pt');
const namesPt = new Map(portuguese.cards.map(({ id, name }) => [id, name]));
if (set.releaseDate !== '2026-11-06' || set.cards.length !== 141 ||
    new Set(set.cards.map(card => card.id)).size !== 141) {
  throw new Error('Unexpected Delta Reign checklist');
}

const cards = [];
// TCGdex omits the evolution source on this set, and labels Pokémon ex as Basic.
const evolutions = {
  Masquerain: 'Surskit', Cacturne: 'Cacnea', Vespiquen: 'Combee', 'Mega Golisopod ex': 'Wimpod',
  Floragato: 'Sprigatito', 'Meowscarada ex': 'Floragato', Dolliv: 'Smoliv', Arboliva: 'Dolliv',
  Arcanine: 'Growlithe', Magmortar: 'Magmar', Jellicent: 'Frillish', Electivire: 'Electabuzz',
  Jolteon: 'Eevee', Pawmo: 'Pawmi', Pawmot: 'Pawmo', Kilowattrel: 'Wattrel', Hypno: 'Drowzee',
  'Mega Golurk ex': 'Golett', Sandslash: 'Sandshrew', Steelix: 'Onix', Nidorina: 'Nidoran♀',
  Nidoqueen: 'Nidorina', Ariados: 'Spinarak', Sharpedo: 'Carvanha', Liepard: 'Purrloin',
  'Zoroark ex': 'Zorua', 'Mega Malamar ex': 'Inkay', 'Hakamo-o': 'Jangmo-o', 'Kommo-o': 'Hakamo-o',
  Altaria: 'Swablu', Fletchinder: 'Fletchling', 'Talonflame ex': 'Fletchinder',
};
const secondStages = new Set(['Meowscarada ex', 'Arboliva', 'Pawmot', 'Nidoqueen', 'Kommo-o', 'Talonflame ex']);
const symbols = { Grass: 'G', Fire: 'R', Water: 'W', Lightning: 'L', Psychic: 'P',
  Fighting: 'F', Darkness: 'D', Metal: 'M', Dragon: 'N', Colorless: 'C' };
function normalizeEffects(entries) {
  return entries?.map(entry => ({
    ...entry,
    ...(entry.effect ? { effect: entry.effect.replace(
      /<span class="energy-symbol (\w+)"[^>]*>[^<]*<\/span>/g,
      (original, type) => symbols[type] ? `{${symbols[type]}}` : original)
      .replace(/<\/?(?:em|strong|p)>/g, ' ')
      .replace(/\s+/g, ' ').replace(/\s+([.,)])/g, '$1').trim() } : {}),
  }));
}
for (let i = 0; i < set.cards.length; i += 12) {
  cards.push(...await Promise.all(set.cards.slice(i, i + 12).map(async ({ id }) => {
    const { pricing, thirdParty, updated, variants_detailed, ...card } = await get('/cards/' + id);
    if (card.id !== id || card.set?.id !== set.id) throw new Error(`Unexpected card: ${id}`);
    if (card.category === 'Pokemon' && evolutions[card.name]) {
      card.stage = secondStages.has(card.name) ? 'Stage2' : 'Stage1';
      card.evolveFrom = evolutions[card.name];
    }
    if (card.category === 'Pokemon' && card.stage !== 'Basic' && !card.evolveFrom) {
      throw new Error(`Missing evolution source: ${id}`);
    }
    card.attacks = normalizeEffects(card.attacks);
    card.abilities = normalizeEffects(card.abilities);
    return { ...card, localizedName: namesPt.get(id), setCode: 'DLR', setReleaseDate: set.releaseDate };
  })));
}
cards.sort((a, b) => Number(a.localId) - Number(b.localId));
await writeFile(new URL('../trainer-engine/src/main/resources/library/delta-reign.json', import.meta.url),
  `{"source":${JSON.stringify(api + '/sets/me06')},"cards":[\n${cards.map(card => JSON.stringify(card)).join(',\n')}\n]}\n`, 'utf8');
