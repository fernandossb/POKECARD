/**
 * Enriquece o catálogo local com dados do TCGdex que a listagem simples não
 * traz:
 *
 * - cartas: `category`, `trainerType`, `rarity`, `illustrator` (artista),
 *   `energyType` (Energia básica ou especial), `energyCost` (as cores de
 *   Energia que os ataques pedem, "WL"), `regulationMark` (a letra que
 *   decide a rotação do formato Padrão) e `nameEn` (o nome em inglês, só
 *   quando difere do português — é como as listas do PTCGL e do Limitless
 *   chegam);
 * - coleções: `tcgOnline`, a sigla do Pokémon TCG Live (OBF, PAF, SVI...),
 *   que é como qualquer lista de deck identifica a impressão;
 * - catálogo: `standardMarks`, as marcas de regulamentação que valem hoje no
 *   formato Padrão (a rotação).
 *
 * Sem `category`/`trainerType` o app só separa Pokémon / Energia /
 * "Treinador", misturando Item, Apoiador, Ferramenta e Estádio. Sem
 * `energyType` ele não sabe, com certeza, o que é Energia básica (a única sem
 * limite de cópias e legal em qualquer formato).
 *
 * Usa a API GraphQL, que devolve os dados em lote (500 por página) em vez de
 * uma requisição por carta. O argumento `filters` é obrigatório: sem ele a API
 * responde com erro. A sigla das coleções recentes (Scarlet & Violet e Mega
 * Evolução) só existe na API REST, uma coleção por vez, como "abreviação
 * oficial" — o GraphQL não expõe esse campo.
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
const REST_EN = 'https://api.tcgdex.net/v2/en';
const PAGE_SIZE = 500;
const CATEGORIES = ['Pokemon', 'Trainer', 'Energy'];
const HEADERS = { 'content-type': 'application/json', 'user-agent': 'FicharioPokemonCatalog/1.0' };

// Mesma normalização do app (acentos e pontuação fora, minúsculas).
const normalize = value => String(value ?? '')
  .normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase()
  .replace(/[^a-z0-9]+/g, ' ').trim();

async function comRetentativa(tarefa) {
  for (let attempt = 1; attempt <= 3; attempt += 1) {
    try {
      return await tarefa();
    } catch (error) {
      if (attempt === 3) throw error;
      await new Promise(resolve => setTimeout(resolve, 1500 * attempt));
    }
  }
  return null;
}

async function graphql(query) {
  return comRetentativa(async () => {
    const response = await fetch(ENDPOINT, { method: 'POST', headers: HEADERS, body: JSON.stringify({ query }) });
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    const payload = await response.json();
    if (payload.errors?.length) throw new Error(payload.errors[0]?.message || 'erro GraphQL');
    return payload.data;
  });
}

/* Custo de Energia dos ataques, em letras (G R W L P F D M Y), da cor mais
   pedida para a menos: Dragonite ex → "WL". Incolor não entra — qualquer
   Energia paga —, e o Pokémon que só pede Incolor (Snorlax, Larvitar) grava
   "C": sem isso, "não pede cor" e "ainda sem dados" ficariam iguais. É o que
   diz ao montador qual Energia básica levar: Dragão nem tem Energia própria,
   e o tipo da espécie engana (Arbok ex é Venenoso e ataca com Escuridão). */
const LETRA_DE_ENERGIA = {
  Grass: 'G', Fire: 'R', Water: 'W', Lightning: 'L', Psychic: 'P',
  Fighting: 'F', Darkness: 'D', Metal: 'M', Fairy: 'Y',
};
function custoDeEnergia(attacks) {
  const conta = new Map();
  for (const attack of attacks || []) {
    for (const tipo of attack?.cost || []) {
      const letra = LETRA_DE_ENERGIA[tipo];
      if (letra) conta.set(letra, (conta.get(letra) || 0) + 1);
    }
  }
  return [...conta].sort((a, b) => b[1] - a[1]).map(([letra]) => letra).join('') || 'C';
}

async function fetchCategory(category) {
  const found = new Map();
  // Teto de segurança: o catálogo inteiro tem ~23 mil cartas, então 200
  // páginas de 500 é folga larga e evita laço infinito se a API repetir dados.
  const MAX_PAGES = 200;
  const campos = 'id name trainerType energyType rarity illustrator regulationMark legal { standard }'
    + (category === 'Pokemon' ? ' attacks { cost }' : '');
  for (let page = 1; page <= MAX_PAGES; page += 1) {
    const data = await graphql(
      `{ cards(filters:{category:"${category}"}, pagination:{page:${page},itemsPerPage:${PAGE_SIZE}}) { ${campos} } }`
    );
    const cards = data?.cards || [];
    const before = found.size;
    for (const card of cards) {
      if (!card?.id) continue;
      found.set(card.id, {
        category,
        nameEn: String(card.name || '').trim() || null,
        trainerType: card.trainerType || null,
        energyType: card.energyType || null,
        rarity: card.rarity || null,
        illustrator: String(card.illustrator || '').trim() || null,
        regulationMark: String(card.regulationMark || '').trim().toUpperCase() || null,
        energyCost: category === 'Pokemon' ? custoDeEnergia(card.attacks) : null,
        standard: card.legal?.standard === true,
      });
    }
    process.stdout.write(`\r${category}: ${found.size} cartas`);
    // Para quando a página vem incompleta ou não traz nada novo.
    if (cards.length < PAGE_SIZE || found.size === before) break;
  }
  process.stdout.write('\n');
  return found;
}

/* Sigla PTCGL de cada coleção. O GraphQL traz `tcgOnline` das coleções
   antigas; as recentes só têm a "abreviação oficial" na API REST. */
async function fetchSiglas(setIds) {
  const siglas = new Map();
  const data = await graphql('{ sets(filters:{}, pagination:{page:1,itemsPerPage:1000}) { id tcgOnline } }');
  for (const set of data?.sets || []) {
    if (set?.id && set.tcgOnline) siglas.set(set.id, String(set.tcgOnline).trim().toUpperCase());
  }
  const faltando = setIds.filter(id => !siglas.has(id));
  const LOTE = 6;
  for (let i = 0; i < faltando.length; i += LOTE) {
    await Promise.all(faltando.slice(i, i + LOTE).map(async id => {
      try {
        const detalhe = await comRetentativa(async () => {
          const response = await fetch(`${REST_EN}/sets/${encodeURIComponent(id)}`, { headers: HEADERS });
          if (response.status === 404) return null;
          if (!response.ok) throw new Error(`HTTP ${response.status}`);
          return response.json();
        });
        const sigla = String(detalhe?.tcgOnline || detalhe?.abbreviation?.official || '').trim().toUpperCase();
        if (sigla) siglas.set(id, sigla);
      } catch (_) { /* coleção sem versão em inglês: fica sem sigla */ }
    }));
    process.stdout.write(`\rSiglas: ${Math.min(i + LOTE, faltando.length)} de ${faltando.length} coleções consultadas`);
  }
  process.stdout.write('\n');
  return siglas;
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
  // energyType: "Normal" (básica) ou "Special" — só nas Energias.
  if (info.energyType) card.energyType = info.energyType;
  else delete card.energyType;
  // "None" é como a fonte marca carta sem raridade impressa.
  if (info.rarity && info.rarity !== 'None') card.rarity = info.rarity;
  // Carta sem artista conhecido fica sem o campo, em vez de guardar vazio.
  if (info.illustrator) card.illustrator = info.illustrator;
  if (info.regulationMark) card.regulationMark = info.regulationMark;
  // Só nos Pokémon: as cores que os ataques pedem, ou "C" (só Incolor).
  if (info.energyCost) card.energyCost = info.energyCost;
  else delete card.energyCost;
  // O nome em inglês só é guardado quando é diferente do nome do catálogo:
  // "Charizard ex" é igual nas duas línguas, "Ordens do Chefe" não.
  if (info.nameEn && normalize(info.nameEn) !== normalize(card.name)) card.nameEn = info.nameEn;
  else delete card.nameEn;
  enriched += 1;
}

/* Rotação: as marcas de regulamentação que valem no Padrão hoje. A fonte
   decide a legalidade pela marca impressa (G saiu em 2026; H, I e J valem),
   então basta guardar as letras — o app aplica a cada carta pela marca dela,
   e a Treinadores reimpressos pelo nome. */
const marcasDoPadrao = new Set();
for (const info of index.values()) if (info.standard && info.regulationMark) marcasDoPadrao.add(info.regulationMark);
catalog.standardMarks = [...marcasDoPadrao].sort();

const siglas = await fetchSiglas((catalog.sets || []).map(set => set.id).filter(Boolean));
let comSigla = 0;
for (const set of catalog.sets || []) {
  const sigla = siglas.get(set.id);
  if (sigla) { set.tcgOnline = sigla; comSigla += 1; }
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
const artistas = new Set(cards.map(card => card.illustrator).filter(Boolean));
console.log(`Com artista: ${cards.filter(card => card.illustrator).length} cartas, ${artistas.size} artistas diferentes.`);
console.log(`Com marca de regulamentação: ${cards.filter(card => card.regulationMark).length} cartas.`);
console.log(`Marcas no formato Padrão: ${catalog.standardMarks.join(', ') || 'nenhuma'}.`);
console.log(`Energias básicas: ${cards.filter(card => card.energyType === 'Normal').length} · especiais: ${cards.filter(card => card.energyType === 'Special').length}.`);
console.log(`Pokémon com custo de ataque: ${cards.filter(card => card.energyCost).length} (só Incolor: ${cards.filter(card => card.energyCost === 'C').length}).`);
console.log(`Com nome em inglês diferente: ${cards.filter(card => card.nameEn).length} cartas.`);
console.log(`Coleções com sigla PTCGL: ${comSigla} de ${(catalog.sets || []).length}.`);
for (const [key, count] of [...byType].sort((a, b) => b[1] - a[1])) console.log(`  ${key}: ${count}`);
