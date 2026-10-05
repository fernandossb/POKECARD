// Traz o leitor de texto da câmera do navegador (iPhone) para app/src/main/assets/www/ocr/.
//
// O PaddleOCR (modelos PP-OCRv3 em formato ONNX, rodando no ONNX Runtime Web) lê
// o nome, os ataques e a numeração da carta. São uns 32 MB, e só o navegador usa:
// no Android quem lê é o ML Kit, de dentro do aplicativo. Por isso a pasta ocr/
// NÃO vai para o Git nem para o APK — este script roda na publicação do site
// (publicar-previa.yml) e, à mão, para testar localmente:
//
//   node scripts/baixar-ocr.mjs
//
// Tudo é fixado por versão e conferido por checksum (SHA-256), para a publicação
// sair sempre igual e nada trocar por baixo.
//
//   ocr/ort.wasm.min.js        ONNX Runtime Web 1.17.3 (só o backend WebAssembly)
//   ocr/ort-wasm-simd.wasm     o motor; o aparelho baixa só um dos dois
//   ocr/ort-wasm.wasm          (o segundo é para iPhone com iOS antes do 16.4, sem SIMD)
//   ocr/modelos/det.onnx       detector de texto  (PP-OCRv3, inglês)
//   ocr/modelos/rec.onnx       leitor de texto    (PP-OCRv3, inglês)
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ORT = { pacote: 'onnxruntime-web', versao: '1.17.3' };
const ORT_ARQUIVOS = {
  'ort.wasm.min.js': '654435ead2d823e56d5893ec53be49f6ddb7e827096561aae63501071470cf23',
  'ort-wasm-simd.wasm': '6783fcd6647ce1b426a527c31412a9c40894552faa609e9c931b3239a3438071',
  'ort-wasm.wasm': '969afd00c79afead28a90b6acd01bd64abe4b5ac8bc9650c3dee3aefc6e0cea6',
};
// SWHL/RapidOCR no Hugging Face, no commit que foi testado.
const COMMIT_DOS_MODELOS = '1cfba2e90fc938db55889873735088de210cc173';
const MODELOS = {
  'det.onnx': { caminho: 'PP-OCRv4/en_PP-OCRv3_det_infer.onnx', sha256: 'f139598bc2af4e4b6fe98dec11574e30edfdd91fc94ac1425c18ace3bd5a866b' },
  'rec.onnx': { caminho: 'PP-OCRv3/en_PP-OCRv3_rec_infer.onnx', sha256: 'ef7abd8bd3629ae57ea2c28b425c1bd258a871b93fd2fe7c433946ade9b5d9ea' },
};
const VERSAO_DO_CONJUNTO = JSON.stringify({ ort: ORT.versao, modelos: COMMIT_DOS_MODELOS });

const raiz = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const destino = path.join(raiz, 'app', 'src', 'main', 'assets', 'www', 'ocr');
const marca = path.join(destino, 'versoes.json');
const sha256 = bytes => createHash('sha256').update(bytes).digest('hex');
const todos = [...Object.keys(ORT_ARQUIVOS), ...Object.keys(MODELOS).map(n => `modelos/${n}`)];

function jaEstaPronto() {
  try {
    if (!fs.existsSync(marca) || fs.readFileSync(marca, 'utf8') !== VERSAO_DO_CONJUNTO) return false;
    return todos.every(arquivo => fs.existsSync(path.join(destino, arquivo)));
  } catch (_) { return false; }
}

if (jaEstaPronto()) {
  console.log('ocr/ já está na versão certa.');
  process.exit(0);
}

function conferir(nome, bytes, esperado) {
  const obtido = sha256(bytes);
  if (obtido !== esperado) throw new Error(`${nome}: checksum diferente do esperado (${obtido}). A fonte mudou ou o download veio corrompido.`);
}

const temporaria = fs.mkdtempSync(path.join(os.tmpdir(), 'pokecard-ocr-'));
try {
  // 1. ONNX Runtime Web, pelo npm.
  fs.writeFileSync(path.join(temporaria, 'package.json'), '{"name":"ocr","private":true}');
  execFileSync('npm', ['install', '--no-audit', '--no-fund', '--silent', `${ORT.pacote}@${ORT.versao}`], {
    cwd: temporaria, stdio: 'inherit', shell: process.platform === 'win32',
  });
  fs.rmSync(destino, { recursive: true, force: true });
  fs.mkdirSync(path.join(destino, 'modelos'), { recursive: true });
  for (const [arquivo, esperado] of Object.entries(ORT_ARQUIVOS)) {
    const bytes = fs.readFileSync(path.join(temporaria, 'node_modules', ORT.pacote, 'dist', arquivo));
    conferir(arquivo, bytes, esperado);
    fs.writeFileSync(path.join(destino, arquivo), bytes);
  }

  // 2. Os modelos, do Hugging Face (endereço fixo pelo commit).
  for (const [nome, { caminho, sha256: esperado }] of Object.entries(MODELOS)) {
    const url = `https://huggingface.co/SWHL/RapidOCR/resolve/${COMMIT_DOS_MODELOS}/${caminho}`;
    let resposta;
    for (let tentativa = 1; tentativa <= 3; tentativa++) {
      try {
        resposta = await fetch(url);
        if (resposta.ok) break;
        throw new Error(`HTTP ${resposta.status}`);
      } catch (erro) {
        if (tentativa === 3) throw new Error(`Não consegui baixar ${nome}: ${erro.message}`);
        await new Promise(r => setTimeout(r, 1500 * tentativa));
      }
    }
    const bytes = Buffer.from(await resposta.arrayBuffer());
    conferir(nome, bytes, esperado);
    fs.writeFileSync(path.join(destino, 'modelos', nome), bytes);
  }

  fs.writeFileSync(marca, VERSAO_DO_CONJUNTO);
  const bytes = fs.readdirSync(destino, { recursive: true }).reduce((soma, arquivo) => {
    const caminho = path.join(destino, arquivo);
    return soma + (fs.statSync(caminho).isFile() ? fs.statSync(caminho).size : 0);
  }, 0);
  console.log(`ocr/ pronto: ${(bytes / 1048576).toFixed(1)} MB em ${destino}`);
} finally {
  fs.rmSync(temporaria, { recursive: true, force: true });
}
