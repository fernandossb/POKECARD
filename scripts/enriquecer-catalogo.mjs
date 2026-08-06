/**
 * Enriquece o catálogo local com `category` e `trainerType` do TCGdex.
 *
 * Sem esses campos o app só consegue separar Pokémon / Energia / "Treinador",
 * misturando Item, Apoiador, Ferramenta e Estádio num grupo só — o que impede
 * qualquer análise real de balanceamento do deck.
 *
 * Usa a API GraphQL, que devolve os dados em lote (500 por página) em vez de
 * uma requisição por carta. O argumento `filters` é obrigatório: sem ele a API
 * responde com erro.
 *
 * Uso:  node scripts/enriquecer-catalogo.mjs
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const wwwData = path.join(root, 'app/src/main/assets/www/data');
const catalogPath = path.join(wwwData, 'catalog.json');
const catalogDataPath = path.join(wwwData, 'catalog-data.js');

const ENDPOINT = 'https://api.tcgdex.net/v2/graphql';
const PAGE_SIZE = 500;
const CATEGORIES = ['Pokemon', 'Trainer', 'Energy'];

async function graphql(query) {
  for (let attempt = 1; attempt <= 3; attempt += 1) {
    try {
      const response = await fetch(ENDPOINT, {
        method: 'POST',
        headers: { 'content-type': 'application/json', 'user-agent': 'FicharioPokemonCatalog/1.0' },
        body: JSON.stringify({ query }),
      });
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      const payload = await response.json();
      if (payload.errors?.length) throw new Error(payload.errors[0]?.message || 'erro GraphQL');
      return payload.data;
    } catch (error) {
      if (attempt === 3) throw error;
      await new Promise(resolve => setTimeout(resolve, 1500 * attempt));
    }
  }
  return null;
}

async function fetchCategory(category) {
  const found = new Map();
  // Teto de segurança: o catálogo inteiro tem ~23 mil cartas, então 200
  // páginas de 500 é folga larga e evita laço infinito se a API repetir dados.
  const MAX_PAGES = 200;
  for (let page = 1; page <= MAX_PAGES; page += 1) {
    const data = await graphql(
      `{ cards(filters:{category:"${category}"}, pagination:{page:${page},itemsPerPage:${PAGE_SIZE}}) { id trainerType } }`
    );
    const cards = data?.cards || [];
    const before = found.size;
    for (const card of cards) {
      if (!card?.id) continue;
      found.set(card.id, { category, trainerType: card.trainerType || null });
    }
    process.stdout.write(`\r${category}: ${found.size} cartas`);
    // Para quando a página vem incompleta ou não traz nada novo.
    if (cards.length < PAGE_SIZE || found.size === before) break;
  }
  process.stdout.write('\n');
  return found;
}

const catalog = JSON.parse(fs.readFileSync(catalogPath, 'utf8'));
const cards = catalog.cards || [];
if (!cards.length) throw new Error('catalog.json vazio ou inválido');

const index = new Map();
for (const category of CATEGORIES) {
  const found = await fetchCategory(category);
  for (const [id, value] of found) index.set(id, value);
}

let enriched = 0;
let missing = 0;
for (const card of cards) {
  const info = index.get(card.id);
  if (!info) { missing += 1; continue; }
  card.category = info.category;
  // trainerType só existe para cartas de Treinador; nas demais fica ausente.
  if (info.trainerType) card.trainerType = info.trainerType;
  else delete card.trainerType;
  enriched += 1;
}

catalog.enrichedAt = new Date().toISOString();
const serialized = JSON.stringify(catalog);
fs.writeFileSync(catalogPath, serialized);
fs.writeFileSync(catalogDataPath, `window.__CATALOG__=${serialized};`);

const byType = new Map();
for (const card of cards) {
  const key = card.category === 'Trainer' ? `Trainer/${card.trainerType || 'sem tipo'}` : (card.category || 'sem categoria');
  byType.set(key, (byType.get(key) || 0) + 1);
}
console.log(`\nCatálogo enriquecido: ${enriched} de ${cards.length} cartas (${missing} sem correspondência no TCGdex).`);
for (const [key, count] of [...byType].sort((a, b) => b[1] - a[1])) console.log(`  ${key}: ${count}`);
