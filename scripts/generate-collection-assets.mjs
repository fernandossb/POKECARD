import fs from 'node:fs/promises';
import path from 'node:path';
import process from 'node:process';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const sharp = require('sharp');

const root = path.resolve(import.meta.dirname, '..');
const www = path.join(root, 'app', 'src', 'main', 'assets', 'www');
const catalogPath = path.join(www, 'data', 'catalog.json');
const outputDir = path.join(www, 'collection-images');
const metadataPath = path.join(www, 'data', 'collection-metadata.js');
const catalog = JSON.parse(await fs.readFile(catalogPath, 'utf8'));

await fs.mkdir(outputDir, { recursive: true });

const manualDates = {
  'swsh9.5tg': '2022-02-25',
  'swsh10.5tg': '2022-05-27',
  'swsh11.5tg': '2022-09-09',
  'swsh12.5tg': '2022-11-11',
  'swsh12.5gg': '2023-01-20',
  'cel25cc': '2021-10-08',
  'swsh4.5sv': '2021-02-19',
  'sve': '2023-03-31',
  'mee': '2025-09-26',
  'mep': '2025-09-26',
};

const setCards = new Map();
for (const card of catalog.cards || []) {
  if (!setCards.has(card.setId)) setCards.set(card.setId, []);
  setCards.get(card.setId).push(card);
}

async function fetchSetDate(id) {
  for (const locale of ['en', 'pt-br', 'ja']) {
    try {
      const response = await fetch(`https://api.tcgdex.net/v2/${locale}/sets/${encodeURIComponent(id)}`);
      if (!response.ok) continue;
      const value = await response.json();
      if (/^\d{4}-\d{2}-\d{2}$/.test(String(value.releaseDate || ''))) return value.releaseDate;
    } catch {}
  }
  return manualDates[id.toLowerCase()] || manualDates[id] || null;
}

async function resolveDates() {
  const result = {};
  let cursor = 0;
  const workers = Array.from({ length: 10 }, async () => {
    while (cursor < catalog.sets.length) {
      const set = catalog.sets[cursor++];
      const date = await fetchSetDate(set.id);
      if (!date) throw new Error(`Data ausente para ${set.id} (${set.name})`);
      result[set.id] = date;
      process.stdout.write(`data ${set.id}: ${date}\n`);
    }
  });
  await Promise.all(workers);
  return result;
}

function hash(value) {
  let result = 2166136261;
  for (const char of value) {
    result ^= char.charCodeAt(0);
    result = Math.imul(result, 16777619);
  }
  return result >>> 0;
}

function representativePokemon(set) {
  const ids = [];
  for (const card of setCards.get(set.id) || []) {
    for (const id of card.pokemonIds || []) {
      const numeric = Number(id);
      if (numeric > 0 && numeric <= 1025 && !ids.includes(numeric)) ids.push(numeric);
      if (ids.length === 3) return ids;
    }
  }
  const fallback = (hash(set.id) % 1025) + 1;
  return [fallback];
}

async function existingSprite(ids) {
  for (const id of ids) {
    const candidate = path.join(www, 'sprites', `${id}.png`);
    try {
      await fs.access(candidate);
      return candidate;
    } catch {}
  }
  return path.join(www, 'sprites', '25.png');
}

async function createBackground(set, index) {
  const seed = hash(set.id);
  const hue1 = seed % 360;
  const hue2 = (hue1 + 55 + (index % 7) * 13) % 360;
  const pokemon = representativePokemon(set);
  const sprite = await existingSprite(pokemon);
  const spriteBuffer = await sharp(sprite)
    .resize(260, 260, { fit: 'contain', withoutEnlargement: false })
    .png()
    .toBuffer();
  const svg = Buffer.from(`
    <svg width="480" height="300" xmlns="http://www.w3.org/2000/svg">
      <defs>
        <linearGradient id="g" x1="0" y1="0" x2="1" y2="1">
          <stop stop-color="hsl(${hue1} 65% 34%)"/>
          <stop offset="1" stop-color="hsl(${hue2} 72% 16%)"/>
        </linearGradient>
        <pattern id="p" width="44" height="44" patternUnits="userSpaceOnUse" patternTransform="rotate(25)">
          <circle cx="8" cy="8" r="4" fill="white" opacity=".08"/>
          <path d="M0 30h44" stroke="white" opacity=".055" stroke-width="8"/>
        </pattern>
        <radialGradient id="r">
          <stop stop-color="white" stop-opacity=".30"/>
          <stop offset="1" stop-color="white" stop-opacity="0"/>
        </radialGradient>
      </defs>
      <rect width="480" height="300" fill="url(#g)"/>
      <rect width="480" height="300" fill="url(#p)"/>
      <circle cx="370" cy="142" r="165" fill="url(#r)"/>
      <circle cx="370" cy="142" r="105" fill="none" stroke="white" stroke-opacity=".12" stroke-width="14"/>
      <path d="M265 142h210" stroke="white" stroke-opacity=".12" stroke-width="14"/>
      <circle cx="370" cy="142" r="28" fill="none" stroke="white" stroke-opacity=".16" stroke-width="12"/>
    </svg>`);
  await sharp(svg)
    .composite([{ input: spriteBuffer, left: 220, top: 25 }])
    .webp({ quality: 74, effort: 5 })
    .toFile(path.join(outputDir, `${set.id}.webp`));
  return pokemon[0];
}

const dates = await resolveDates();
const metadata = {};
for (let index = 0; index < catalog.sets.length; index++) {
  const set = catalog.sets[index];
  const pokemonId = await createBackground(set, index);
  metadata[set.id] = {
    releaseDate: dates[set.id],
    image: `collection-images/${set.id}.webp`,
    pokemonId,
  };
  process.stdout.write(`imagem ${set.id}\n`);
}

const ordered = Object.fromEntries(Object.entries(metadata).sort(([a], [b]) => a.localeCompare(b)));
await fs.writeFile(
  metadataPath,
  `/* Gerado localmente: datas exatas por ID e imagens offline das coleções. */\nwindow.__COLLECTION_METADATA__ = ${JSON.stringify(ordered, null, 2)};\n`,
  'utf8',
);
process.stdout.write(`Concluído: ${Object.keys(ordered).length} coleções.\n`);
