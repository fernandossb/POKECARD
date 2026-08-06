const fs = require('fs');
const vm = require('vm');
const assert = require('assert');

const source = fs.readFileSync('app/src/main/assets/www/app.js', 'utf8');

const start = source.indexOf('const LIGA_EXPORT_HEADERS');
const end = source.indexOf('function openLigaExportPanel(');
assert(start >= 0 && end > start, 'Não foi possível localizar o bloco de exportação da Liga Pokémon');
const block = source.slice(start, end);

// Mocks mínimos: só o que buildLigaExportCsv precisa para rodar fora do app completo.
// ligaCardNumber, finishPriceLabel e finishKind são as mesmas funções reais do app.js
// (extraídas abaixo), não reimplementações — o objetivo é testar só o formato do CSV.
const helpersStart = source.indexOf('function ligaNumberPart(');
const helpersEnd = source.indexOf('async function ligaSetCode(');
assert(helpersStart >= 0 && helpersEnd > helpersStart, 'Não foi possível localizar ligaCardNumber');
const numberHelpers = source.slice(helpersStart, helpersEnd);

const finishStart = source.indexOf('const PRICE_FINISHES =');
const finishEnd = source.indexOf('const CARD_FINISH_DEFINITIONS');
const finishBlock = source.slice(finishStart, finishEnd);

const context = {
  normalize: value => String(value || '').normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase(),
  hasFiniteNumber: value => value !== null && value !== undefined && value !== '' && Number.isFinite(Number(value)),
  effectiveVariantPrice: (cardId, variant) => hasFiniteNumberLike(variant?.manualEstimatedValue)
    ? { brl: Number(variant.manualEstimatedValue) }
    : (hasFiniteNumberLike(variant?.automaticEstimatedValue) ? { brl: Number(variant.automaticEstimatedValue) } : null),
};
function hasFiniteNumberLike(value) { return value !== null && value !== undefined && value !== '' && Number.isFinite(Number(value)); }
context.hasFiniteNumberLike = hasFiniteNumberLike;

vm.createContext(context);
// LIGA_EXPORT_HEADERS é `const`: diferente de `function`, uma const de topo não vira
// propriedade do objeto de contexto automaticamente, por isso o espelhamento manual abaixo.
vm.runInContext(finishBlock + numberHelpers + block + '\nthis.LIGA_EXPORT_HEADERS = LIGA_EXPORT_HEADERS;', context);

// --- Cabeçalho ---
assert.deepStrictEqual(context.LIGA_EXPORT_HEADERS[0], 'Tipo');
assert(context.LIGA_EXPORT_HEADERS.includes('Preço unitário (R$)'));

// --- Linha simples: venda, sem graduação, sem variação artística ---
const cardA = { id: 'swsh6-57', name: 'Gengar', setId: 'swsh6', setName: 'Reinado Arrepiante', localId: '57', number: '57/198' };
const variantA = {
  finish: 'holo', language: 'pt-br', condition: 'Near Mint', edition: 'unlimited', distribution: 'unstamped',
  artVariant: 'standard', gradingCompany: 'Não graduada', grade: '', manualEstimatedValue: 45.5, notes: '',
  isForSale: true, isForTrade: false, isWishlist: false,
};
const rowA = { cardId: cardA.id, card: cardA, variant: variantA, quantity: 2, tipo: 'Venda' };

const csvSingle = context.buildLigaExportCsv([rowA], new Map([['swsh6', 'REA']]));
const linesSingle = csvSingle.replace(/^\uFEFF/, '').split('\r\n').filter(Boolean);
assert.strictEqual(linesSingle.length, 2, 'deve ter cabeçalho + 1 linha');
const colsA = linesSingle[1].split(';');
assert.strictEqual(colsA[0], 'Venda');
assert.strictEqual(colsA[1], 'Gengar');
assert.strictEqual(colsA[2], 'Reinado Arrepiante');
assert.strictEqual(colsA[3], 'REA');
assert.strictEqual(colsA[4], '057/198');
assert.strictEqual(colsA[6], 'holo');
assert.strictEqual(colsA[10], '', 'artVariant standard não deve aparecer');
assert.strictEqual(colsA[11], '', 'sem graduadora não deve aparecer');
assert.strictEqual(colsA[12], '2');
assert.strictEqual(colsA[13], '45,50', 'preço deve usar vírgula decimal, sem símbolo de moeda');

// --- Linha com graduação, nome com ; e aspas (precisa escapar), venda+troca ---
const cardB = { id: 'base1-4', name: 'Charizard; "Base Set"', setId: 'base1', setName: 'Base Set', localId: '4', number: '4/102' };
const variantB = {
  finish: 'normal', language: 'en', condition: 'Near Mint', edition: 'firstEdition', distribution: 'unstamped',
  artVariant: 'standard', gradingCompany: 'PSA', grade: '9', automaticEstimatedValue: 1999.9, notes: 'Canto levemente batido',
  isForSale: true, isForTrade: true, isWishlist: false,
};
const rowB = { cardId: cardB.id, card: cardB, variant: variantB, quantity: 1, tipo: 'Venda + Troca' };

const csvTwo = context.buildLigaExportCsv([rowA, rowB], new Map());
const linesTwo = csvTwo.replace(/^\uFEFF/, '').split('\r\n').filter(Boolean);
assert.strictEqual(linesTwo.length, 3);
// Parse manual simples de CSV para o teste (suficiente para aspas balanceadas de uma linha)
function parseCsvLine(line) {
  const out = []; let cur = ''; let inQuotes = false;
  for (let i = 0; i < line.length; i++) {
    const ch = line[i];
    if (inQuotes) {
      if (ch === '"' && line[i + 1] === '"') { cur += '"'; i++; }
      else if (ch === '"') inQuotes = false;
      else cur += ch;
    } else if (ch === '"') inQuotes = true;
    else if (ch === ';') { out.push(cur); cur = ''; }
    else cur += ch;
  }
  out.push(cur);
  return out;
}
const fieldsB = parseCsvLine(linesTwo[2]);
assert.strictEqual(fieldsB[0], 'Venda + Troca');
assert.strictEqual(fieldsB[1], 'Charizard; "Base Set"', 'nome com ; e aspas deve sobreviver ao round-trip');
assert.strictEqual(fieldsB[3], '', 'sem código Liga resolvido deve ficar em branco, não inventado');
assert.strictEqual(fieldsB[4], '004/102');
assert.strictEqual(fieldsB[8], 'firstEdition', 'Edição');
assert.strictEqual(fieldsB[9], 'unstamped', 'Distribuição');
assert.strictEqual(fieldsB[10], '', 'Variação artística é representada pelo ID da carta');
assert.strictEqual(fieldsB[11], 'PSA 9', 'Graduação (empresa + nota)');
assert.strictEqual(fieldsB[12], '1', 'Quantidade');
assert.strictEqual(fieldsB[13], '1999,90', 'Preço com vírgula decimal, sem separador de milhar');
assert.strictEqual(fieldsB[14], 'Canto levemente batido', 'Observações');

console.log('Exportação Liga Pokémon: cabeçalho, formato de preço, escaping de CSV e graduação aprovados.');
