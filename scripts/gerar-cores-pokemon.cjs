/* Gera app/src/main/assets/www/data/cores-pokemon.js: as duas cores do corpo de
   cada um dos 1.025 Pokémon, medidas no sprite que já vem no app (sprites/N.png).

   O tema do app usa a primeira (a que mais aparece no corpo) no fundo da tela e
   a segunda nos cartões e botões — o Venusaur fica com fundo verde e botões
   vermelhos, da flor.

   Como mede, por sprite:
   · ignora o transparente e o contorno (pixel muito escuro);
   · separa o que tem cor (croma >= 0,10) do que é cinza, branco ou creme claro;
   · monta um histograma de matiz (72 faixas de 5°), suavizado, pesando mais o
     pixel mais vivo;
   · cor 1 = o pico do histograma; cor 2 = o maior pico a pelo menos 50° da
     cor 1 que tenha ao menos 3% dos pixels coloridos;
   · Pokémon quase todo cinza ou branco (Onix, Absol...) fica com o cinza no
     fundo e a cor mais forte que ele tiver nos cartões.
   Sem segunda cor de verdade (Pokémon de uma cor só), grava -1 e o app usa a
   cor do tipo como segunda.

   Uso: node scripts/gerar-cores-pokemon.cjs  (não precisa de internet nem de
   pacote nenhum: o PNG é lido aqui mesmo, com o zlib do Node). */
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

const PASTA = path.join(__dirname, '..', 'app', 'src', 'main', 'assets', 'www');

function lerPng(arquivo) {
  const b = fs.readFileSync(arquivo);
  let pos = 8, largura = 0, altura = 0, bits = 0, tipo = 0;
  let paleta = null, transp = null;
  const dados = [];
  while (pos < b.length) {
    const tam = b.readUInt32BE(pos);
    const nome = b.toString('ascii', pos + 4, pos + 8);
    const corpo = b.subarray(pos + 8, pos + 8 + tam);
    if (nome === 'IHDR') {
      largura = corpo.readUInt32BE(0); altura = corpo.readUInt32BE(4); bits = corpo[8]; tipo = corpo[9];
      if (corpo[12]) throw new Error('PNG entrelaçado não suportado: ' + arquivo);
    } else if (nome === 'PLTE') paleta = corpo;
    else if (nome === 'tRNS') transp = corpo;
    else if (nome === 'IDAT') dados.push(corpo);
    else if (nome === 'IEND') break;
    pos += 12 + tam;
  }
  const bruto = zlib.inflateSync(Buffer.concat(dados));
  const canais = { 0: 1, 2: 3, 3: 1, 4: 2, 6: 4 }[tipo];
  const bpp = Math.max(1, (canais * bits) >> 3);
  const linha = Math.ceil((largura * canais * bits) / 8);
  const saida = Buffer.alloc(linha * altura);
  let anterior = Buffer.alloc(linha);
  for (let y = 0; y < altura; y++) {
    const filtro = bruto[y * (linha + 1)];
    const atual = Buffer.from(bruto.subarray(y * (linha + 1) + 1, (y + 1) * (linha + 1)));
    for (let x = 0; x < linha; x++) {
      const a = x >= bpp ? atual[x - bpp] : 0, c = anterior[x], d = x >= bpp ? anterior[x - bpp] : 0;
      let v = atual[x];
      if (filtro === 1) v += a;
      else if (filtro === 2) v += c;
      else if (filtro === 3) v += (a + c) >> 1;
      else if (filtro === 4) { const p = a + c - d, pa = Math.abs(p - a), pb = Math.abs(p - c), pc = Math.abs(p - d); v += pa <= pb && pa <= pc ? a : pb <= pc ? c : d; }
      atual[x] = v & 255;
    }
    atual.copy(saida, y * linha);
    anterior = atual;
  }
  const rgba = new Uint8Array(largura * altura * 4);
  for (let y = 0; y < altura; y++) {
    for (let x = 0; x < largura; x++) {
      const i = (y * largura + x) * 4;
      if (tipo === 3) {
        const porByte = 8 / bits;
        const byte = saida[y * linha + Math.floor(x / porByte)];
        const desl = (porByte - 1 - (x % porByte)) * bits;
        const idx = (byte >> desl) & ((1 << bits) - 1);
        rgba[i] = paleta[idx * 3]; rgba[i + 1] = paleta[idx * 3 + 1]; rgba[i + 2] = paleta[idx * 3 + 2];
        rgba[i + 3] = transp && idx < transp.length ? transp[idx] : 255;
      } else if (tipo === 6) {
        const o = y * linha + x * 4;
        rgba[i] = saida[o]; rgba[i + 1] = saida[o + 1]; rgba[i + 2] = saida[o + 2]; rgba[i + 3] = saida[o + 3];
      } else if (tipo === 2) {
        const o = y * linha + x * 3;
        rgba[i] = saida[o]; rgba[i + 1] = saida[o + 1]; rgba[i + 2] = saida[o + 2]; rgba[i + 3] = 255;
      } else throw new Error('tipo de PNG não suportado: ' + tipo);
    }
  }
  return rgba;
}

function hsl(r, g, b) {
  r /= 255; g /= 255; b /= 255;
  const max = Math.max(r, g, b), min = Math.min(r, g, b), l = (max + min) / 2;
  let h = 0, s = 0;
  if (max !== min) {
    const d = max - min;
    s = l > 0.5 ? d / (2 - max - min) : d / (max + min);
    h = max === r ? (g - b) / d + (g < b ? 6 : 0) : max === g ? (b - r) / d + 2 : (r - g) / d + 4;
    h *= 60;
  }
  return [h, s * 100, l * 100];
}

const distancia = (a, b) => { const d = Math.abs(a - b) % 360; return d > 180 ? 360 - d : d; };

// Média de um grupo de pixels em torno de um matiz (média circular do matiz).
function media(pixels, matiz, raio) {
  let sx = 0, sy = 0, ss = 0, sl = 0, n = 0;
  for (const p of pixels) {
    if (distancia(p.h, matiz) > raio) continue;
    sx += Math.cos(p.h * Math.PI / 180) * p.w; sy += Math.sin(p.h * Math.PI / 180) * p.w;
    ss += p.s * p.w; sl += p.l * p.w; n += p.w;
  }
  if (!n) return null;
  const h = (Math.atan2(sy, sx) * 180 / Math.PI + 360) % 360;
  return { h: Math.round(h), s: Math.round(ss / n), l: Math.round(sl / n), peso: n };
}

function medir(rgba) {
  const coloridos = [], neutros = [];
  for (let i = 0; i < rgba.length; i += 4) {
    if (rgba[i + 3] < 128) continue;
    const r = rgba[i], g = rgba[i + 1], b = rgba[i + 2];
    const max = Math.max(r, g, b) / 255, min = Math.min(r, g, b) / 255;
    if (max < 0.2) continue; // contorno
    const [h, s, l] = hsl(r, g, b);
    if (max - min < 0.10) neutros.push({ h, s, l, w: 1 });
    else coloridos.push({ h, s, l, w: 1 + (max - min) });
  }
  const FAIXAS = 72;
  const hist = new Array(FAIXAS).fill(0);
  let total = 0;
  for (const p of coloridos) { hist[Math.floor(p.h / 5) % FAIXAS] += p.w; total += p.w; }
  const suave = hist.map((_, i) => [-2, -1, 0, 1, 2].reduce((soma, d) => soma + hist[(i + d + FAIXAS) % FAIXAS] * (d === 0 ? 1 : Math.abs(d) === 1 ? 0.7 : 0.35), 0));
  const picos = suave.map((v, i) => ({ v, h: i * 5 + 2.5 })).sort((a, b) => b.v - a.v);

  let cor1 = picos.length && total ? media(coloridos, picos[0].h, 22) : null;
  let cor2 = null;
  if (cor1) {
    // Detalhe pequeno também vale (as bochechas do Pikachu, a flor do
    // Venusaur): basta 1,5% dos pixels coloridos e 40° de distância da cor 1.
    for (const pico of picos) {
      if (distancia(pico.h, cor1.h) < 40) continue;
      const c = media(coloridos, pico.h, 18);
      if (c && c.peso >= total * 0.015 && distancia(c.h, cor1.h) >= 38) { cor2 = c; break; }
    }
  }
  const lNeutro = neutros.length ? Math.round(neutros.reduce((soma, p) => soma + p.l, 0) / neutros.length) : 0;
  // Quase todo cinza/branco: o cinza vai para o fundo, a cor mais forte para os cartões.
  if (neutros.length > coloridos.length * 1.3) {
    const neutro = { h: cor1 ? cor1.h : 0, s: 6, l: lNeutro };
    cor2 = cor1 || null;
    cor1 = neutro;
  } else if (!cor2 && neutros.length >= (coloridos.length + neutros.length) * 0.12) {
    // Uma cor só, mas com bastante branco/cinza/creme (barriga, asas): esse
    // tom neutro vira a segunda cor.
    cor2 = { h: cor1 ? cor1.h : 0, s: 6, l: lNeutro };
  }
  if (!cor1) cor1 = { h: 0, s: 0, l: 60 };
  return [cor1.h, cor1.s, cor1.l].concat(cor2 ? [cor2.h, cor2.s, cor2.l] : [-1, 0, 0]);
}

const resultado = {};
for (let id = 1; id <= 1025; id++) {
  resultado[id] = medir(lerPng(path.join(PASTA, 'sprites', id + '.png')));
}
const cabecalho = `/* As duas cores do corpo de cada Pokémon: [matiz, saturação, claridade] da
   cor 1 (a que mais aparece — vai no fundo da tela) e da cor 2 (vai nos
   cartões e botões). Matiz -1 na cor 2 = Pokémon de uma cor só: o app usa a
   cor do tipo. Gerado por scripts/gerar-cores-pokemon.cjs a partir de
   sprites/N.png — rode de novo se os sprites mudarem. */
`;
fs.writeFileSync(path.join(PASTA, 'data', 'cores-pokemon.js'),
  cabecalho + 'window.__CORES_POKEMON__ = ' + JSON.stringify(resultado) + ';\n');
const semSegunda = Object.values(resultado).filter(c => c[3] < 0).length;
console.log('ok:', Object.keys(resultado).length, 'Pokémon;', semSegunda, 'sem segunda cor');
for (const id of [1, 3, 4, 6, 7, 9, 25, 94, 95, 133, 143, 150, 249, 359, 384, 445, 448, 658, 887]) console.log(id, resultado[id].join(','));
