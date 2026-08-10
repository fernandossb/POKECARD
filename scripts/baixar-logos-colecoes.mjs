/**
 * Baixa o logo de cada coleção e guarda dentro do aplicativo.
 *
 * A ideia é não depender da internet na hora de mostrar a tela: o logo já vem
 * junto no APK. Assim o cartão da coleção nunca aparece quebrado, mesmo sem
 * sinal ou se algum endereço mudar lá fora.
 *
 * Fonte: TCGdex — a mesma que o app já usa para o catálogo, então o nome do
 * arquivo é exatamente o `setId` que o app conhece. Em WebP cada logo fica em
 * torno de 22 KB; os ~218 sets somam algo perto de 5 MB.
 *
 * Uso:  node scripts/baixar-logos-colecoes.mjs
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const raiz = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const destino = path.join(raiz, 'app/src/main/assets/www/set-logos');
const indicePath = path.join(destino, 'index.json');
const API = 'https://api.tcgdex.net/v2/en';

async function pegarJson(url, tentativas = 3) {
  for (let i = 1; i <= tentativas; i += 1) {
    try {
      const resposta = await fetch(url, { headers: { 'user-agent': 'FicharioPokemonLogos/1.0' } });
      if (!resposta.ok) throw new Error(`HTTP ${resposta.status}`);
      return await resposta.json();
    } catch (erro) {
      if (i === tentativas) throw erro;
      await new Promise(r => setTimeout(r, 800 * i));
    }
  }
  return null;
}

fs.mkdirSync(destino, { recursive: true });

const sets = await pegarJson(`${API}/sets`);
if (!Array.isArray(sets) || !sets.length) throw new Error('Lista de coleções vazia');
console.log(`${sets.length} coleções encontradas.`);

const indice = {};
let baixados = 0;
let reaproveitados = 0;
let comSimbolo = 0;
let semArte = 0;
const faltando = [];

for (const resumo of sets) {
  const id = resumo?.id;
  if (!id) continue;
  const arquivo = path.join(destino, `${id}.webp`);

  // Já baixado numa execução anterior: não gasta rede de novo.
  if (fs.existsSync(arquivo) && fs.statSync(arquivo).size > 200) {
    indice[id] = `set-logos/${id}.webp`;
    reaproveitados += 1;
    continue;
  }

  // A própria lista já traz logo e símbolo, então não é preciso abrir cada
  // coleção uma a uma — o que economiza 218 requisições.
  // Nem toda coleção tem logo (kits de treinador e promos avulsas costumam
  // não ter); nesses casos o símbolo da expansão serve bem.
  const fontes = [resumo.logo, resumo.symbol].filter(Boolean);
  if (!fontes.length) {
    semArte += 1;
    faltando.push(`${id} (${resumo.name || 'sem nome'})`);
    continue;
  }

  let salvou = false;
  for (const base of fontes) {
    try {
      const imagem = await fetch(`${base}.webp`);
      if (!imagem.ok) throw new Error(`HTTP ${imagem.status}`);
      const bytes = Buffer.from(await imagem.arrayBuffer());
      if (bytes.length < 200) throw new Error('arquivo vazio');
      fs.writeFileSync(arquivo, bytes);
      indice[id] = `set-logos/${id}.webp`;
      if (base === resumo.logo) baixados += 1; else comSimbolo += 1;
      salvou = true;
      process.stdout.write(`\r${baixados + comSimbolo + reaproveitados} artes prontas`);
      break;
    } catch { /* tenta a próxima fonte */ }
  }
  if (!salvou) {
    semArte += 1;
    faltando.push(`${id} (${resumo.name || 'sem nome'})`);
  }
}

fs.writeFileSync(indicePath, JSON.stringify(indice));
// O app lê este arquivo direto, sem precisar de rede nem de fetch local.
fs.writeFileSync(
  path.join(destino, 'logos-data.js'),
  `/* Gerado por scripts/baixar-logos-colecoes.mjs. Não edite à mão. */\nwindow.__SET_LOGOS__=${JSON.stringify(indice)};\n`
);

const total = Object.keys(indice).length;
const tamanho = fs.readdirSync(destino)
  .filter(nome => nome.endsWith('.webp'))
  .reduce((soma, nome) => soma + fs.statSync(path.join(destino, nome)).size, 0);

console.log(`\n\nArtes disponíveis: ${total} de ${sets.length} coleções`);
console.log(`  logo baixado agora:  ${baixados}`);
console.log(`  símbolo (sem logo):  ${comSimbolo}`);
console.log(`  já existiam:         ${reaproveitados}`);
console.log(`  sem arte nenhuma:    ${semArte}`);
console.log(`Espaço ocupado: ${(tamanho / 1024 / 1024).toFixed(1)} MB`);
if (faltando.length) {
  console.log('\nColeções sem logo nem símbolo na fonte:');
  for (const nome of faltando) console.log(`  · ${nome}`);
  console.log('Essas continuam com o símbolo genérico no app — é limite da fonte, não erro.');
}
