/* Ícones do app e símbolos dos tipos.

   O app misturava emojis (que cada fabricante de celular desenha de um jeito),
   letras fazendo papel de ícone ("R$", "E") e uns poucos SVGs soltos. Aqui
   fica um conjunto só, desenhado no mesmo traço:

   - ícones de interface: traço de 1,8 px, cantos arredondados, herdam a cor
     do texto (currentColor) — então valem em qualquer tema e nível de claridade;
   - símbolos dos tipos (Fogo, Água...): um glifo branco (ou escuro, quando o
     fundo é claro) dentro de um disco na cor do tipo. As cores ficam no CSS
     (.tp-fogo, .tp-agua...), que também pinta os quadradinhos da Pokédex.

   Tudo vem de um "sprite" SVG escrito uma vez no começo da página; cada uso
   é só <svg><use href="#ic-olho"/></svg>, curto e barato — a Pokédex desenha
   180 quadradinhos por vez. Este arquivo carrega antes do app.js. */
(function () {
  'use strict';

  var R = Math.PI / 180;
  function n(v) { return Math.round(v * 100) / 100; }

  /* ---------- Geradores de formas ---------- */

  // Estrela de N pontas (rExt/rInt = raio das pontas/dos vales).
  function estrela(cx, cy, pontas, rExt, rInt, giro) {
    var d = '';
    for (var i = 0; i < pontas * 2; i++) {
      var r = i % 2 === 0 ? rExt : rInt;
      var a = (giro || -90) * R + i * Math.PI / pontas;
      d += (i ? 'L' : 'M') + n(cx + r * Math.cos(a)) + ' ' + n(cy + r * Math.sin(a));
    }
    return d + 'Z';
  }

  // Brilho de 4 pontas com os lados curvos para dentro.
  function brilho(cx, cy, r, fino) {
    var f = fino == null ? 0.22 : fino;
    var k = r * f;
    return 'M' + n(cx) + ' ' + n(cy - r)
      + 'Q' + n(cx + k) + ' ' + n(cy - k) + ' ' + n(cx + r) + ' ' + n(cy)
      + 'Q' + n(cx + k) + ' ' + n(cy + k) + ' ' + n(cx) + ' ' + n(cy + r)
      + 'Q' + n(cx - k) + ' ' + n(cy + k) + ' ' + n(cx - r) + ' ' + n(cy)
      + 'Q' + n(cx - k) + ' ' + n(cy - k) + ' ' + n(cx) + ' ' + n(cy - r) + 'Z';
  }

  // Engrenagem: contorno com dentes + furo no meio (preencher com evenodd).
  function engrenagem(cx, cy, dentes, rExt, rInt, rFuro) {
    var d = '';
    var passo = 360 / dentes;
    for (var i = 0; i < dentes; i++) {
      var base = i * passo - 90;
      var pts = [
        [rInt, base - passo * 0.30], [rExt, base - passo * 0.17],
        [rExt, base + passo * 0.17], [rInt, base + passo * 0.30]
      ];
      for (var j = 0; j < pts.length; j++) {
        var a = pts[j][1] * R;
        d += (i === 0 && j === 0 ? 'M' : 'L') + n(cx + pts[j][0] * Math.cos(a)) + ' ' + n(cy + pts[j][0] * Math.sin(a));
      }
    }
    d += 'Z';
    if (rFuro) {
      d += 'M' + n(cx + rFuro) + ' ' + n(cy)
        + 'a' + rFuro + ' ' + rFuro + ' 0 1 0 ' + n(-2 * rFuro) + ' 0'
        + 'a' + rFuro + ' ' + rFuro + ' 0 1 0 ' + n(2 * rFuro) + ' 0Z';
    }
    return d;
  }

  // Espiral aberta (traço).
  function espiral(cx, cy, voltas, rMax) {
    var d = '';
    var passos = Math.round(voltas * 28);
    for (var i = 0; i <= passos; i++) {
      var t = i / passos;
      var a = t * voltas * 2 * Math.PI - Math.PI / 2;
      var r = rMax * (0.12 + 0.88 * t);
      d += (i ? 'L' : 'M') + n(cx + r * Math.cos(a)) + ' ' + n(cy + r * Math.sin(a));
    }
    return d;
  }

  // Floco de neve: 6 braços com duas pontinhas cada (traço).
  function floco(cx, cy, r) {
    var d = '';
    for (var i = 0; i < 6; i++) {
      var a = (i * 60 - 90) * R;
      var x = cx + r * Math.cos(a), y = cy + r * Math.sin(a);
      d += 'M' + n(cx) + ' ' + n(cy) + 'L' + n(x) + ' ' + n(y);
      var bx = cx + r * 0.58 * Math.cos(a), by = cy + r * 0.58 * Math.sin(a);
      for (var lado = -1; lado <= 1; lado += 2) {
        var b = a + lado * 50 * R;
        d += 'M' + n(bx) + ' ' + n(by) + 'L' + n(bx + r * 0.3 * Math.cos(b)) + ' ' + n(by + r * 0.3 * Math.sin(b));
      }
    }
    return d;
  }

  /* ---------- Ícones de interface (traço, 24x24) ---------- */

  var T = 'fill="none" stroke="currentColor"'; // só para abreviar
  var P = function (d, extra) { return '<path d="' + d + '"' + (extra ? ' ' + extra : '') + '/>'; };
  var C = function (cx, cy, r, extra) { return '<circle cx="' + cx + '" cy="' + cy + '" r="' + r + '"' + (extra ? ' ' + extra : '') + '/>'; };
  var CHEIO = 'fill="currentColor" stroke="none"';

  var UI = {
    trofeu: P('M7.5 4h9v5.2a4.5 4.5 0 0 1-9 0z') + P('M7.5 6H5a1 1 0 0 0-1 1c0 2.3 1.600 3.900 3.700 4.200') + P('M16.500 6H19a1 1 0 0 1 1 1c0 2.300-1.600 3.900-3.700 4.200') + P('M12 13.800v3.500') + P('M8.500 20.500h7M9.500 17.300h5l.6 3.200H8.900z'),
    quadro: '<rect x="3.500" y="4.500" width="17" height="15" rx="2.600"/>' + C(9, 10, 1.600) + P('m4 17.500 4.800-4.800 3.700 3.700 2.800-2.800 4.700 4.700'),
    olho: P('M2.500 12S6 5.500 12 5.500 21.500 12 21.500 12 18 18.500 12 18.500 2.500 12 2.500 12z') + C(12, 12, 3),
    etiqueta: P('M3.500 12.600V5.500a2 2 0 0 1 2-2h7.100a2 2 0 0 1 1.400.6l7 7a2 2 0 0 1 0 2.800l-7.100 7.100a2 2 0 0 1-2.800 0l-7-7a2 2 0 0 1-.6-1.400z') + C(8.300, 8.300, 1.300, CHEIO),
    mochila: P('M9 7.500V6.500a3 3 0 0 1 6 0v1') + '<rect x="5" y="7.500" width="14" height="13" rx="3.600"/>' + P('M9 13.500h6v4.300a1 1 0 0 1-1 1h-4a1 1 0 0 1-1-1z') + P('M5.500 11.500h13'),
    engrenagem: '<path d="' + engrenagem(12, 12, 8, 9.600, 7.300, 0) + '"/>' + C(12, 12, 3),
    brilho: P(brilho(12, 12, 9.500, 0.2), 'fill="currentColor" stroke-width="1.2"') + P(brilho(19.500, 4.500, 2.600, 0.25), 'fill="currentColor" stroke="none"'),
    estrela: P(estrela(12, 12.500, 5, 9.500, 4.200), 'fill="currentColor" stroke-width="1.2"'),
    'estrela-vazia': P(estrela(12, 12.500, 5, 9.500, 4.200)),
    check: P('M4.500 12.500l5 5 10-11'),
    alerta: P('M12 3.800 2.800 19.800h18.400z') + P('M12 10v4.300') + C(12, 17, 0.6, CHEIO),
    coracao: P('M12 20.500S3.500 15.400 3.500 9.300A4.700 4.700 0 0 1 12 6.700a4.700 4.700 0 0 1 8.500 2.600c0 6.100-8.500 11.200-8.500 11.200z'),
    'coracao-cheio': P('M12 20.500S3.500 15.400 3.500 9.300A4.700 4.700 0 0 1 12 6.700a4.700 4.700 0 0 1 8.500 2.600c0 6.100-8.500 11.200-8.500 11.200z', 'fill="currentColor"'),
    lapis: P('M4 20l1-4.500L16.500 4a2 2 0 0 1 2.800 0l.7.700a2 2 0 0 1 0 2.800L8.500 19z') + P('M14.500 6l3.500 3.500'),
    raio: P('M13.500 2.500 5.500 13.500h5.800l-1 8 8.200-11.300h-5.800z', 'fill="currentColor" stroke-width="1.2"'),
    energia: C(12, 12, 9) + P('M13 6.500 8.500 12.800h3l-.7 4.700 4.700-6.500h-3z', 'fill="currentColor" stroke-width="1"'),
    pokebola: C(12, 12, 9) + P('M3 12h6.200M14.800 12H21') + C(12, 12, 2.600),
    diamante: P('M6.500 4h11l4 5.500L12 20.500 2.500 9.500z') + P('M2.500 9.500h19M9 4 7.500 9.500 12 20.500l4.500-11L15 4'),
    alvo: C(12, 12, 9) + C(12, 12, 5) + C(12, 12, 1.300, CHEIO),
    frasco: P('M9.500 3.500h5M10.500 3.500v5.500L5 18.300A2 2 0 0 0 6.700 21.300h10.600A2 2 0 0 0 19 18.300L13.500 9V3.500') + P('M7.500 15h9'),
    'seta-cima': P('M12 19V5M5.500 11.500 12 5l6.500 6.500'),
    camera: P('M4 8h3l1.500-2.500h7L17 8h3a1 1 0 0 1 1 1v9a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V9a1 1 0 0 1 1-1z') + C(12, 13, 3.500),
    cifrao: C(12, 12, 9) + P('M14.700 9.300C14.200 8.400 13.300 7.900 12 7.900c-1.600 0-2.700.8-2.700 2 0 2.900 5.600 1.600 5.600 4.300 0 1.200-1.200 2-2.800 2-1.300 0-2.300-.5-2.900-1.500') + P('M12 6.200v1.700M12 16.200v1.700'),
    paleta: P('M12 3a9 9 0 1 0 0 18c1.400 0 2.100-1 1.600-2.100-.5-1.100.1-2.400 1.400-2.400H17a4 4 0 0 0 4-4A9 9 0 0 0 12 3z') + C(7.600, 11.800, 1, CHEIO) + C(10, 7.600, 1, CHEIO) + C(14.800, 7.600, 1, CHEIO) + C(17.600, 11.400, 1, CHEIO),
    fechar: P('M6 6l12 12M18 6 6 18'),
    mais: C(5, 12, 1.500, CHEIO) + C(12, 12, 1.500, CHEIO) + C(19, 12, 1.500, CHEIO),
    cartas: '<rect x="4.500" y="5" width="11" height="15" rx="2"/>' + P('M8.500 3.500h8a3 3 0 0 1 3 3V16') + C(10, 11.500, 2) + P('M7.500 16.500h5'),
    lupa: C(10.500, 10.500, 6.500) + P('M15.500 15.500 21 21'),
    pastas: P('M3 7a2 2 0 0 1 2-2h4l2 2.500h8a2 2 0 0 1 2 2V18a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z') + P('M3 10.500h18'),
    bussola: C(12, 12, 9) + P('m15.400 8.600-2 4.800-4.800 2 2-4.800z'),
    dna: P('M7 3c0 5 10 5 10 9s-10 4-10 9') + P('M17 3c0 5-10 5-10 9s10 4 10 9') + P('M8.400 7.200h7.200M8.400 16.800h7.200'),
    medalha: C(12, 9, 5.500) + P('M8.500 13.600 7 21l5-2.600 5 2.600-1.500-7.400'),
    carimbo: P('M9.500 14V9.300a2.500 2.500 0 1 1 5 0V14') + P('M5 14h14a2 2 0 0 1 2 2v2H3v-2a2 2 0 0 1 2-2z') + P('M4 21h16'),
    globo: C(12, 12, 9) + P('M3 12h18') + P('M12 3c2.500 2.600 3.800 5.600 3.800 9S14.500 18.400 12 21c-2.500-2.600-3.800-5.600-3.800-9S9.500 5.600 12 3z'),
    escudo: P('M12 3 4.500 6v5.500c0 4.500 3.200 8 7.500 9.500 4.300-1.500 7.500-5 7.500-9.500V6z'),
    troca: P('M4 8h14l-3.500-3.500M20 16H6l3.500 3.500'),
    ampulheta: P('M6.500 3h11M6.500 21h11') + P('M8 3c0 4 2 6 4 9-2 3-4 5-4 9M16 3c0 4-2 6-4 9 2 3 4 5 4 9'),
    espadas: P('M5 5l11.500 11.500M19 5 7.500 16.500') + P('M14 18.500l3.500-3.500M6.500 15l3.500 3.500M3.500 20.500 6 18M20.500 20.500 18 18'),
    presente: '<rect x="3.500" y="8.500" width="17" height="4" rx="1"/>' + P('M5 12.500V20h14v-7.500M12 8.500V20') + P('M12 8.500C10.500 4 7 4 7 6.300c0 1.300 2.500 2.200 5 2.200 2.500 0 5-.9 5-2.200C17 4 13.500 4 12 8.500z'),
    cadeado: '<rect x="5" y="10.500" width="14" height="10" rx="2.500"/>' + P('M8 10.500V8a4 4 0 0 1 8 0v2.500') + C(12, 15.500, 1.200, CHEIO),
    circulo: C(12, 12, 6, CHEIO),
    losango: P('M12 4.500 19.500 12 12 19.500 4.500 12z', 'fill="currentColor" stroke-width="1.2"'),
    estrelas2: P(estrela(8, 13, 5, 6, 2.700), 'fill="currentColor" stroke-width="1"') + P(estrela(16.500, 10, 5, 6, 2.700), 'fill="currentColor" stroke-width="1"'),
    arcoiris: P('M3.500 18.500a8.500 8.500 0 0 1 17 0') + P('M7 18.500a5 5 0 0 1 10 0') + P('M10.500 18.500a1.500 1.500 0 0 1 3 0'),
    carta: '<rect x="5.500" y="3" width="13" height="18" rx="2.200"/>' + P('M9 15.500h6M9 18h3.500') + C(12, 9.500, 2.500),
    download: P('M12 4v11m0 0-4-4m4 4 4-4M5 19.500h14'),
    filtro: P('M4 6.500h16M7 12h10M10 17.500h4'),
    mapa: P('m3.500 6.500 5.500-2 6 2 5.500-2v13l-5.500 2-6-2-5.500 2z') + P('M9 4.500v13M15 6.500v13'),
    bau: P('M4 11V8a3 3 0 0 1 3-3h10a3 3 0 0 1 3 3v3') + '<rect x="3.500" y="11" width="17" height="9" rx="2"/>' + P('M3.500 14.500h17') + '<rect x="10.500" y="13" width="3" height="3.600" rx=".8"/>',
    relogio: C(12, 12, 9) + P('M12 7v5l3.300 2'),
    // Acabamentos das versões da carta (no lugar de ◻ ◧ ❶).
    'v-normal': '<rect x="5.500" y="5.500" width="13" height="13" rx="3.200"/>',
    'v-reverse': '<rect x="5.500" y="5.500" width="13" height="13" rx="3.200"/>' + P('M8.700 5.500H12v13H8.700a3.200 3.200 0 0 1-3.200-3.200V8.700a3.200 3.200 0 0 1 3.200-3.200z', 'fill="currentColor" stroke="none"'),
    'v-primeira': C(12, 12, 8.200) + P('M10.200 10.200 12.400 8.600V15.700')
  };

  /* ---------- Símbolos dos tipos (glifo dentro do disco, 24x24) ---------- */

  var F = 'fill="currentColor"';
  var EO = 'fill="currentColor" fill-rule="evenodd"';
  // Linhas de entalhe: usam as cores do próprio disco (definidas no CSS do tipo).
  var LINHA = 'fill="none" stroke-linecap="round" stroke-linejoin="round" style="stroke:var(--tp-b,#222);stroke-width:1"';
  var LINHA_CLARA = 'fill="none" stroke-linecap="round" style="stroke:var(--tp-a,#fff);stroke-width:1.100"';

  var TIPOS = {
    normal: C(12, 12, 6.800, 'fill="none" stroke="currentColor" stroke-width="2.600"') + C(12, 12, 2.600, F),
    fogo: P('M12 2.600c.4 2.800-.8 4.500-2.300 6.200C8 10.700 6.400 12.500 6.400 15.200A5.600 5.600 0 0 0 12 21a5.600 5.600 0 0 0 5.600-5.800c0-2.400-1-3.900-2-5.100-.3 1.400-1 2.300-2 2.700.5-3.700-.4-7.600-1.600-10.200z' + 'M12 18.900c-1.600 0-2.700-1.100-2.700-2.600 0-1.100.7-1.900 1.500-2.700.4-.4.800-.9 1-1.500 1.100 1 2.800 2.200 2.800 4.200 0 1.500-1.100 2.600-2.600 2.600z', EO),
    agua: P('M12 2.600c3.800 4.300 6.400 7.500 6.400 11.100A6.400 6.400 0 0 1 12 20.200a6.400 6.400 0 0 1-6.400-6.500c0-3.600 2.600-6.800 6.400-11.100z' + 'M8.300 14.200a3.700 3.700 0 0 0 2.600 3.500.6.6 0 0 0 .3-1.100 2.600 2.600 0 0 1-1.700-2.500.6.6 0 0 0-1.200.1z', EO),
    eletrico: P('M13.700 2 5.300 13.300h5.400L9.700 22l9-12.200h-5.500z', 'fill="currentColor" stroke="currentColor" stroke-width=".8" stroke-linejoin="round"'),
    planta: P('M20.300 3.800C10.600 3.400 4.600 8 4.600 14.300c0 1.700.4 3.100 1.200 4.200L4.100 20.200l1.300 1 1.900-1.900c1.100.7 2.400 1.100 3.900 1.100 6 0 9.600-5.200 9.100-16.600z' + 'M7.600 17.300c.9-3.600 3.700-6.900 8.400-9.100-3.700 3-6.100 5.900-7.400 9.700z', EO),
    gelo: P(floco(12, 12, 9.200), 'fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"'),
    // Punho: quatro dedos + palma em branco; as linhas entre os dedos e o
    // polegar usam a cor escura do disco (--tp-b), como um entalhe.
    lutador: '<rect x="5" y="6.600" width="3.600" height="7.600" rx="1.800" ' + F + '/>'
      + '<rect x="8.400" y="5.400" width="3.600" height="8.600" rx="1.800" ' + F + '/>'
      + '<rect x="11.900" y="5.400" width="3.600" height="8.600" rx="1.800" ' + F + '/>'
      + '<rect x="15.300" y="6.600" width="3.600" height="7.600" rx="1.800" ' + F + '/>'
      + '<rect x="5.600" y="11" width="12.800" height="10" rx="3.400" ' + F + '/>'
      + P('M8.500 7.800v4.600M12 6.600v5.800M15.400 7.800v4.600', LINHA)
      + P('M5.600 14.600h8.500a1.900 1.900 0 0 1 0 3.800H9.200', LINHA),
    venenoso: P('M12 3c-4.400 0-7.500 3-7.500 7.200 0 2.500 1.200 4.300 3 5.400V19c0 .9.700 1.500 1.500 1.500h6c.8 0 1.500-.6 1.500-1.500v-3.400c1.800-1.100 3-2.900 3-5.400C19.500 6 16.400 3 12 3z' + 'M9.300 9.900a1.800 1.800 0 1 0 0 3.600 1.800 1.800 0 0 0 0-3.600z' + 'M14.700 9.900a1.800 1.800 0 1 0 0 3.600 1.800 1.800 0 0 0 0-3.600z' + 'M12 13.600l1 1.900h-2z', EO),
    terrestre: P('M2.800 19.800c1.400-5.700 5-9.600 9.200-9.600s7.800 3.900 9.200 9.600z', F) + C(8.200, 6.400, 1.500, F) + C(13.200, 4.600, 1.900, F) + C(17.800, 7.400, 1.300, F),
    // Ave de asas abertas.
    voador: P('M2.500 9.200c3.300-1.700 6.800-.4 9.500 3.600 2.700-4 6.200-5.300 9.500-3.600-2.600.6-4.500 2.200-5.800 4.800-.9 1.800-1.800 3.600-3.700 6-1.900-2.400-2.800-4.200-3.700-6-1.300-2.600-3.200-4.200-5.800-4.800z', 'fill="currentColor" stroke="currentColor" stroke-width="1.400" stroke-linejoin="round"'),
    psiquico: P(espiral(12, 12, 2.25, 8.400), 'fill="none" stroke="currentColor" stroke-width="2.300" stroke-linecap="round" stroke-linejoin="round"'),
    inseto: '<ellipse cx="12" cy="14.200" rx="4.700" ry="6" ' + F + '/>' + C(12, 6.600, 2.400, F) + P('M10.600 5 9.200 2.800M13.400 5l1.400-2.200M7.500 11.500 4 10M7.300 15 3.500 15.500M8 18.800 5 21M16.500 11.500 20 10M16.700 15l3.800.5M16 18.800l3 2.200', 'fill="none" stroke="currentColor" stroke-width="1.700" stroke-linecap="round"'),
    // Duas pedras angulosas, com as facetas riscadas na cor clara do disco (--tp-a).
    pedra: P('M3 19.800 5.600 10.400 10.800 6 16.200 9.600l1 10.200z', 'fill="currentColor" stroke="currentColor" stroke-width="1.200" stroke-linejoin="round"')
      + P('M13.600 19.800l1.400-5.400 3.800-2.600 3 3.600-.6 4.400z', 'fill="currentColor" stroke="currentColor" stroke-width="1.200" stroke-linejoin="round"')
      + P('M5.600 10.400l5.300 3.700 5.300-4.500M10.900 14.100v5.700', LINHA_CLARA),
    fantasma: P('M12 2.800c-4.200 0-7 3-7 7.200V21.200l2.400-2.100 2.300 2.100 2.300-2.100 2.300 2.100 2.300-2.100L19 21.200V10c0-4.200-2.800-7.200-7-7.200z' + 'M8.500 9.200a1.400 1.900 0 1 0 2.800 0 1.400 1.900 0 1 0-2.800 0z' + 'M12.700 9.200a1.400 1.900 0 1 0 2.800 0 1.400 1.900 0 1 0-2.800 0z', EO),
    // Olho de dragão: amêndoa com pupila em fenda.
    dragao: P('M2.300 12c3-4.900 6.600-7.300 9.700-7.300s6.700 2.400 9.700 7.300c-3 4.900-6.600 7.300-9.700 7.300S5.300 16.900 2.300 12z' + 'M12 6.800c-1.200 1.600-1.800 3.400-1.800 5.200s.6 3.600 1.800 5.200c1.200-1.600 1.800-3.400 1.800-5.200S13.200 8.400 12 6.800z', EO),
    sombrio: P('M20.300 14.800A9 9 0 0 1 9.200 3.700a9 9 0 1 0 11.100 11.100z', 'fill="currentColor" stroke="currentColor" stroke-width="1" stroke-linejoin="round"'),
    metalico: P(engrenagem(12, 12, 8, 9.800, 7.200, 2.800), EO),
    fada: P(brilho(11, 13, 9.300, 0.2), F) + P(brilho(19.400, 5, 3.200, 0.28), F),
    incolor: P(estrela(12, 12.500, 5, 9.600, 4.100), 'fill="currentColor" stroke="currentColor" stroke-width="1" stroke-linejoin="round"')
  };

  /* ---------- Sprite ---------- */

  var partes = [];
  Object.keys(UI).forEach(function (nome) {
    partes.push('<symbol id="ic-' + nome + '" viewBox="0 0 24 24">' + UI[nome] + '</symbol>');
  });
  Object.keys(TIPOS).forEach(function (nome) {
    partes.push('<symbol id="tp-' + nome + '" viewBox="0 0 24 24">' + TIPOS[nome] + '</symbol>');
  });

  function instalarSprite() {
    if (document.getElementById('sprite-icones') || !document.body) return;
    var caixa = document.createElement('div');
    caixa.innerHTML = '<svg id="sprite-icones" xmlns="http://www.w3.org/2000/svg" width="0" height="0"'
      + ' style="position:absolute;width:0;height:0;overflow:hidden" aria-hidden="true" focusable="false">'
      + partes.join('') + '</svg>';
    document.body.insertBefore(caixa.firstChild, document.body.firstChild);
  }
  instalarSprite();
  if (!document.getElementById('sprite-icones')) document.addEventListener('DOMContentLoaded', instalarSprite);

  /* ---------- Uso ---------- */

  /** Ícone de interface: <svg><use/></svg> que herda cor e tamanho do texto. */
  window.icone = function (nome, classe) {
    if (!UI[nome]) nome = 'carta';
    return '<svg class="ic ic-' + nome + (classe ? ' ' + classe : '') + '" viewBox="0 0 24 24" aria-hidden="true" focusable="false"><use href="#ic-' + nome + '"/></svg>';
  };

  // Nome do tipo (português da Pokédex ou inglês do jogo de cartas) -> apelido.
  var APELIDOS = {
    normal: 'normal', fogo: 'fogo', agua: 'agua', eletrico: 'eletrico', planta: 'planta', gelo: 'gelo',
    lutador: 'lutador', venenoso: 'venenoso', terrestre: 'terrestre', voador: 'voador', psiquico: 'psiquico',
    inseto: 'inseto', pedra: 'pedra', fantasma: 'fantasma', dragao: 'dragao', sombrio: 'sombrio',
    metalico: 'metalico', fada: 'fada',
    grass: 'planta', fire: 'fogo', water: 'agua', lightning: 'eletrico', psychic: 'psiquico',
    fighting: 'lutador', darkness: 'sombrio', metal: 'metalico', fairy: 'fada', dragon: 'dragao',
    colorless: 'incolor', incolor: 'incolor'
  };
  var ROTULOS = {
    normal: 'Normal', fogo: 'Fogo', agua: 'Água', eletrico: 'Elétrico', planta: 'Planta', gelo: 'Gelo',
    lutador: 'Lutador', venenoso: 'Venenoso', terrestre: 'Terrestre', voador: 'Voador', psiquico: 'Psíquico',
    inseto: 'Inseto', pedra: 'Pedra', fantasma: 'Fantasma', dragao: 'Dragão', sombrio: 'Sombrio',
    metalico: 'Metálico', fada: 'Fada', incolor: 'Incolor'
  };

  /** 'Água' / 'water' / 'Fogo' -> 'agua' / 'fogo'; '' se não conhece. */
  window.apelidoDoTipo = function (tipo) {
    var chave = String(tipo == null ? '' : tipo).toLowerCase()
      .normalize('NFD').replace(/[̀-ͯ]/g, '').trim();
    return APELIDOS[chave] || '';
  };
  window.rotuloDoTipo = function (tipo) { return ROTULOS[window.apelidoDoTipo(tipo)] || String(tipo || ''); };

  /** O disco do tipo: glifo na cor do tipo. tamanho em px (padrão 20). */
  window.simboloDoTipo = function (tipo, tamanho) {
    var apelido = window.apelidoDoTipo(tipo);
    if (!apelido) return '';
    var estilo = tamanho ? ' style="--tp-tam:' + Number(tamanho) + 'px"' : '';
    return '<span class="tp-disco tp-' + apelido + '"' + estilo + ' aria-hidden="true"><svg viewBox="0 0 24 24" focusable="false"><use href="#tp-' + apelido + '"/></svg></span>';
  };

  window.__ICONES__ = { ui: Object.keys(UI), tipos: Object.keys(TIPOS) };
})();
