/* Service worker do app instalado no iPhone (e no Android, pelo navegador).

   Faz duas coisas: o app abre sem internet e abre rápido. No aplicativo
   Android (carregado de file://) este arquivo nem é registrado — só vale
   quando o app é servido por https, como na prévia publicada no GitHub Pages.

   Como o cache é usado:
   · A página (index.html): REDE PRIMEIRO, com a cópia guardada como reserva
     (e se a rede demorar mais de 4 s). Assim cada abertura já pega a versão
     nova; sem internet, abre a última.
   · Arquivos com "?v=" no endereço (app.js?v=213, styles.css?v=215...) e as
     pastas de imagens/fontes: CACHE PRIMEIRO. O número muda a cada versão, então
     um endereço guardado nunca está velho.
   · O resto (os dados em data/, o manifesto): REDE PRIMEIRO. São grandes, mas a
     conferência com o servidor é leve (o arquivo só baixa de novo se mudou).
   · O que vem de outros sites (preços, fotos das cartas, TCGdex) passa direto:
     o app tem os próprios caches para isso.

   O número de versão abaixo é carimbado na publicação (versão do app + código
   do commit). Cada publicação muda este arquivo, o navegador instala o novo
   service worker e o cache da versão anterior é apagado. */
const VERSAO = '__VERSAO__';
const CACHE = `pokecard-${VERSAO}`;
const BASE = new URL('./', self.location).pathname;
const PASTAS_FIXAS = ['sprites/', 'set-logos/', 'collection-images/', 'fonts/', 'icons/'];
// Sempre guardados na instalação, além do que o index.html carrega.
const EXTRAS = [
  'manifest.webmanifest', 'icons/icon-192.png', 'icons/icon-512.png', 'icons/apple-touch-icon.png',
  'pokebola-scanner.webp', 'gengar-wallpaper.webp', 'fonts/nunito-latin.woff2',
];

const esperar = ms => new Promise(resolve => setTimeout(resolve, ms));

self.addEventListener('install', evento => {
  evento.waitUntil((async () => {
    const cache = await caches.open(CACHE);
    // A página manda: tudo o que ela carrega (scripts, estilos, dados, imagens
    // do cabeçalho) entra no cache junto, no endereço exato com o "?v=".
    const pagina = await fetch('index.html', { cache: 'no-cache' });
    if (!pagina.ok) throw new Error(`index.html: HTTP ${pagina.status}`);
    const html = await pagina.clone().text();
    await cache.put('index.html', pagina);
    const enderecos = new Set(EXTRAS);
    for (const achado of html.matchAll(/(?:src|href)="([^"#]+)"/g)) {
      const endereco = achado[1];
      if (/^(?:[a-z]+:)?\/\//i.test(endereco) || /^(?:data|blob|javascript|mailto):/i.test(endereco)) continue;
      enderecos.add(endereco);
    }
    // Um arquivo que falhe não derruba a instalação: ele só fica para o
    // primeiro uso, com internet.
    await Promise.all([...enderecos].map(endereco =>
      cache.add(new Request(endereco, { cache: 'no-cache' })).catch(() => {})));
  })());
  self.skipWaiting();
});

self.addEventListener('activate', evento => {
  evento.waitUntil((async () => {
    for (const nome of await caches.keys()) {
      if (nome.startsWith('pokecard-') && nome !== CACHE) await caches.delete(nome);
    }
    await self.clients.claim();
  })());
});

async function guardar(cache, pedido, resposta) {
  // 206 (parcial) e respostas com erro não entram no cache.
  if (resposta && resposta.status === 200) await cache.put(pedido, resposta.clone());
  return resposta;
}

async function paginaPrimeiro(pedido) {
  const cache = await caches.open(CACHE);
  try {
    const resposta = await Promise.race([
      fetch(pedido),
      esperar(4000).then(() => { throw new Error('rede lenta'); }),
    ]);
    // A cópia guardada é sempre a de index.html, qualquer que seja o "?...".
    if (resposta.ok) cache.put('index.html', resposta.clone()).catch(() => {});
    return resposta;
  } catch (_) {
    return (await cache.match('index.html')) || Response.error();
  }
}

async function cachePrimeiro(pedido) {
  const cache = await caches.open(CACHE);
  const guardada = await cache.match(pedido);
  if (guardada) return guardada;
  return guardar(cache, pedido, await fetch(pedido));
}

async function redePrimeiro(pedido) {
  const cache = await caches.open(CACHE);
  try {
    const resposta = await Promise.race([
      fetch(pedido),
      esperar(4000).then(() => { throw new Error('rede lenta'); }),
    ]);
    return await guardar(cache, pedido, resposta);
  } catch (erro) {
    const guardada = await cache.match(pedido);
    if (guardada) return guardada;
    throw erro;
  }
}

self.addEventListener('fetch', evento => {
  const pedido = evento.request;
  if (pedido.method !== 'GET') return;
  const url = new URL(pedido.url);
  if (url.origin !== self.location.origin) return;
  if (url.pathname === `${BASE}sw.js`) return;

  if (pedido.mode === 'navigate') {
    evento.respondWith(paginaPrimeiro(pedido));
    return;
  }
  const relativo = url.pathname.startsWith(BASE) ? url.pathname.slice(BASE.length) : url.pathname;
  const fixo = /^\?v=/.test(url.search) || PASTAS_FIXAS.some(pasta => relativo.startsWith(pasta));
  evento.respondWith(fixo ? cachePrimeiro(pedido) : redePrimeiro(pedido));
});
