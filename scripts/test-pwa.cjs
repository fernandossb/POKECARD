// App instalável (iPhone): o que o iPhone e o service worker precisam achar.
// Um endereço quebrado no index.html não derruba nada no Android, mas no
// iPhone o service worker simplesmente deixa o arquivo fora do cache — e o app
// perde o modo sem internet sem avisar. Este teste pega isso antes.
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const assert = require('assert');

const www = path.join(__dirname, '..', 'app', 'src', 'main', 'assets', 'www');
const ler = arquivo => fs.readFileSync(path.join(www, arquivo), 'utf8');
const existe = arquivo => fs.existsSync(path.join(www, arquivo));

const html = ler('index.html');

// 1. Tudo o que o index.html carrega existe (é o que o service worker guarda).
const enderecos = [...html.matchAll(/(?:src|href)="([^"#]+)"/g)].map(m => m[1])
  .filter(e => !/^(?:[a-z]+:)?\/\//i.test(e) && !/^(?:data|blob|javascript|mailto):/i.test(e));
assert(enderecos.length >= 15, 'index.html deveria carregar scripts, estilos e ícones');
for (const endereco of enderecos) {
  const arquivo = endereco.split('?')[0];
  assert(existe(arquivo), `index.html aponta para um arquivo que não existe: ${endereco}`);
}
for (const obrigatorio of ['manifest.webmanifest', 'icons/apple-touch-icon.png', 'pwa.js']) {
  assert(enderecos.some(e => e.split('?')[0] === obrigatorio), `index.html precisa referenciar ${obrigatorio}`);
}

// 2. Marcas do iPhone e margens da tela.
assert(/viewport-fit=cover/.test(html), 'viewport-fit=cover é o que libera env(safe-area-inset-*)');
assert(/apple-mobile-web-app-capable"\s+content="yes"/.test(html), 'Sem isso o iPhone abre o app com a barra do Safari');
assert(/apple-mobile-web-app-title/.test(html));
// A marca "sem-android" precisa existir ANTES do CSS: senão a tela pisca com as margens erradas.
const marca = html.indexOf("classList.add('sem-android')");
assert(marca > 0 && marca < html.indexOf('styles.css'), 'A classe sem-android tem de ser posta antes do styles.css');

// 3. Manifesto e ícones.
const manifesto = JSON.parse(ler('manifest.webmanifest'));
assert.strictEqual(manifesto.display, 'standalone');
assert(manifesto.name && manifesto.short_name && manifesto.start_url && manifesto.scope);
assert(existe(manifesto.start_url.replace(/^\.\//, '')), 'start_url precisa existir');
function dimensoesPng(arquivo) {
  const bytes = fs.readFileSync(path.join(www, arquivo));
  assert.strictEqual(bytes.toString('latin1', 1, 4), 'PNG', `${arquivo} não é PNG`);
  return `${bytes.readUInt32BE(16)}x${bytes.readUInt32BE(20)}`;
}
assert(manifesto.icons.length >= 2);
for (const icone of manifesto.icons) {
  assert(existe(icone.src), `Ícone do manifesto não existe: ${icone.src}`);
  assert.strictEqual(dimensoesPng(icone.src), icone.sizes, `Tamanho declarado de ${icone.src} não confere`);
}
assert(manifesto.icons.some(i => i.sizes === '512x512'), 'Falta o ícone de 512 px');
assert.strictEqual(dimensoesPng('icons/apple-touch-icon.png'), '180x180', 'O ícone do iPhone tem de ser 180x180');

// 4. Service worker: compila, e traz o marcador que a publicação carimba.
const sw = ler('sw.js');
assert(/const VERSAO = '__VERSAO__';/.test(sw), "sw.js precisa de: const VERSAO = '__VERSAO__'; (o robô troca na publicação)");
new vm.Script(sw, { filename: 'sw.js' });
new vm.Script(ler('pwa.js'), { filename: 'pwa.js' });
const robo = fs.readFileSync(path.join(__dirname, '..', '.github', 'workflows', 'publicar-previa.yml'), 'utf8');
assert(/__VERSAO__/.test(robo) && /sw\.js/.test(robo), 'A publicação da prévia precisa carimbar a versão no sw.js');

// 5. O service worker não pode ser registrado no aplicativo Android (file://).
assert(/\^https\?:\$\/\.test\(location\.protocol\)/.test(ler('pwa.js')), 'O registro precisa checar que o endereço é http(s)');

// 6. Câmera do navegador: o módulo existe, compila e está na página; o leitor de
// texto (pasta ocr/, fora do Git) é baixado pela publicação e guardado à parte.
const camera = ler('camera-web.js');
new vm.Script(camera, { filename: 'camera-web.js' });
assert(enderecos.some(e => e.split('?')[0] === 'camera-web.js'), 'index.html precisa carregar camera-web.js');
assert(html.indexOf('camera-web.js') > html.indexOf('app.js'), 'camera-web.js deve vir depois do app.js (usa avisarNaCamera)');
for (const ponte of ['startLiveScanner', 'stopLiveScanner', 'pauseLiveScanner', 'resumeLiveScanner', 'lerDeNovoLogo']) {
  assert(new RegExp(ponte + '\\s*:').test(camera), `CameraWeb precisa expor ${ponte} (mesma ponte do Android)`);
}
assert(!/tesseract/i.test(camera), 'camera-web.js ainda cita o Tesseract (o leitor agora é o PaddleOCR)');
assert(fs.existsSync(path.join(__dirname, 'baixar-ocr.mjs')), 'scripts/baixar-ocr.mjs sumiu');
assert(/baixar-ocr\.mjs/.test(robo) && /setup-node/.test(robo), 'A publicação da prévia precisa baixar o leitor de texto (ocr/)');
assert(/ocr\//.test(fs.readFileSync(path.join(__dirname, '..', '.gitignore'), 'utf8')), 'ocr/ (30 MB) não pode ir para o Git');
assert(!enderecos.some(e => e.startsWith('ocr/')), 'index.html não deve carregar ocr/ direto: só a câmera pede, quando abre');
const sws = ler('sw.js');
assert(/PASTA_OCR\s*=\s*'ocr\/'/.test(sws) && /CACHE_OCR/.test(sws), 'sw.js precisa guardar a pasta ocr/ num cache próprio (não é refeito a cada versão)');

console.log(`App instalável aprovado: ${enderecos.length} arquivos do index.html existem, manifesto e ícones conferem, service worker carimbável, câmera web conectada.`);
