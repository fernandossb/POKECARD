import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const catalogPath = path.join(root, 'app/src/main/assets/www/data/catalog.json');
const pokedexPath = path.join(root, 'app/src/main/assets/www/data/pokedex.json');

const normalize = value => String(value ?? '')
  .normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase()
  .replace(/[^a-z0-9]+/g, ' ').trim();

const catalog = JSON.parse(fs.readFileSync(catalogPath, 'utf8'));
const pokedex = JSON.parse(fs.readFileSync(pokedexPath, 'utf8'));
const names = pokedex
  .map(item => ({ id: item.id, normalized: normalize(item.name) }))
  .filter(item => item.normalized)
  .sort((a, b) => b.normalized.length - a.normalized.length);

let indexed = 0;
for (const card of catalog.cards || []) {
  if (Array.isArray(card.pokemonIds)) continue;
  const cardName = normalize(card.name);
  const found = [];
  for (const item of names) {
    const pokemonName = item.normalized;
    if (cardName === pokemonName
      || cardName.startsWith(`${pokemonName} `)
      || cardName.includes(` ${pokemonName} `)) {
      found.push(item.id);
    }
  }
  card.pokemonIds = [...new Set(found)];
  indexed++;
}

fs.writeFileSync(catalogPath, JSON.stringify(catalog));
console.log(`Catálogo otimizado: ${indexed} cartas indexadas; ${catalog.cards?.length || 0} cartas no total.`);
