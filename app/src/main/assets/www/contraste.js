/* Contraste automático de todo o texto do aplicativo.

   POR QUE ISTO EXISTE
   O app foi ganhando camadas de CSS ao longo do tempo, e várias delas fixam a
   cor da letra direto (branco, cinza-claro, verde). Isso funciona enquanto o
   fundo é escuro. Quando o usuário clareia o app pela barra de claridade — ou
   escolhe um Pokémon de tipo claro, como Elétrico ou Gelo — essas letras ficam
   claras sobre fundo claro e o texto some.

   Corrigir isso regra por regra no CSS não resolveria de verdade: são mais de
   três mil linhas em camadas empilhadas, e qualquer regra nova acrescentada
   depois voltaria a errar. Então em vez de tentar acertar cada cor na mão, o
   app CONFERE o resultado na tela.

   COMO FUNCIONA
   Depois de cada desenho de tela, este arquivo percorre os elementos que têm
   texto, descobre a cor real do fundo em que cada um está encostado — somando
   as transparências até achar algo sólido — e mede o contraste entre a letra e
   esse fundo. Quem estiver abaixo do mínimo legível recebe uma cor corrigida,
   mantendo o mesmo matiz e mudando só a claridade, para não descaracterizar o
   tema. Quem já está legível não é tocado.

   O mínimo segue a WCAG AA: 4,5:1 para texto normal e 3:1 para texto grande
   (24px, ou 19px em negrito), que é mais fácil de ler no mesmo contraste. */
(function () {
  'use strict';

  var ALVO_NORMAL = 4.5;
  var ALVO_GRANDE = 3;
  // Guarda a cor aplicada por nós; a presença dela significa "já corrigido".
  var MARCA = 'data-contraste';
  // Guarda a cor que o elemento tinha ANTES da primeira correção. É dela que
  // toda avaliação parte, para a correção não se avaliar a si mesma.
  var COR_BASE = 'data-cor-base';

  // As duas caixas de primeiro nível da tela. `#modal` fica fora de `#app`,
  // por isso são duas. Nada de raízes aninhadas: cada elemento é visitado
  // uma vez só por varredura.
  var RAIZES = ['#app', '#modal'];
  var IGNORAR = { SCRIPT: 1, STYLE: 1, SVG: 1, PATH: 1, IMG: 1, CANVAS: 1, BR: 1, HR: 1 };

  // ---------- cores ----------

  function parseCor(texto) {
    if (!texto) return null;
    var m = String(texto).match(/rgba?\(\s*([\d.]+)[,\s]+([\d.]+)[,\s]+([\d.]+)(?:[,/\s]+([\d.]+))?/i);
    if (!m) return null;
    return {
      r: Number(m[1]), g: Number(m[2]), b: Number(m[3]),
      a: m[4] === undefined ? 1 : Number(m[4])
    };
  }

  /* Um botão com degradê não tem cor de fundo sólida: o navegador devolve
     "transparent" e a média das paradas do degradê é a melhor aproximação do
     que o olho enxerga por baixo da letra. */
  function corDoGradiente(backgroundImage) {
    if (!backgroundImage || backgroundImage === 'none' || backgroundImage.indexOf('gradient') < 0) return null;
    var achados = backgroundImage.match(/rgba?\([^)]+\)/gi);
    if (!achados || !achados.length) return null;
    var soma = { r: 0, g: 0, b: 0, a: 0 }, total = 0;
    for (var i = 0; i < achados.length; i++) {
      var c = parseCor(achados[i]);
      if (!c || c.a === 0) continue;
      soma.r += c.r; soma.g += c.g; soma.b += c.b; soma.a += c.a;
      total++;
    }
    if (!total) return null;
    return { r: soma.r / total, g: soma.g / total, b: soma.b / total, a: soma.a / total };
  }

  // Sobrepõe uma cor semitransparente sobre outra já resolvida.
  function sobrepor(frente, fundo) {
    var a = frente.a;
    return {
      r: frente.r * a + fundo.r * (1 - a),
      g: frente.g * a + fundo.g * (1 - a),
      b: frente.b * a + fundo.b * (1 - a),
      a: 1
    };
  }

  function canal(v) {
    var x = v / 255;
    return x <= 0.03928 ? x / 12.92 : Math.pow((x + 0.055) / 1.055, 2.4);
  }

  function luminancia(cor) {
    return 0.2126 * canal(cor.r) + 0.7152 * canal(cor.g) + 0.0722 * canal(cor.b);
  }

  function contraste(a, b) {
    var A = luminancia(a), B = luminancia(b);
    return (Math.max(A, B) + 0.05) / (Math.min(A, B) + 0.05);
  }

  function rgbParaHsl(c) {
    var r = c.r / 255, g = c.g / 255, b = c.b / 255;
    var max = Math.max(r, g, b), min = Math.min(r, g, b);
    var l = (max + min) / 2, h = 0, s = 0;
    if (max !== min) {
      var d = max - min;
      s = l > 0.5 ? d / (2 - max - min) : d / (max + min);
      if (max === r) h = ((g - b) / d + (g < b ? 6 : 0));
      else if (max === g) h = (b - r) / d + 2;
      else h = (r - g) / d + 4;
      h *= 60;
    }
    return { h: h, s: s * 100, l: l * 100 };
  }

  function hslParaRgb(h, s, l) {
    s = Math.max(0, Math.min(100, s)) / 100;
    l = Math.max(0, Math.min(100, l)) / 100;
    var c = (1 - Math.abs(2 * l - 1)) * s;
    var x = c * (1 - Math.abs(((h / 60) % 2) - 1));
    var m = l - c / 2;
    var r = 0, g = 0, b = 0;
    if (h < 60) { r = c; g = x; }
    else if (h < 120) { r = x; g = c; }
    else if (h < 180) { g = c; b = x; }
    else if (h < 240) { g = x; b = c; }
    else if (h < 300) { r = x; b = c; }
    else { r = c; b = x; }
    return {
      r: Math.round((r + m) * 255),
      g: Math.round((g + m) * 255),
      b: Math.round((b + m) * 255),
      a: 1
    };
  }

  function paraCss(c) {
    return 'rgb(' + Math.round(c.r) + ',' + Math.round(c.g) + ',' + Math.round(c.b) + ')';
  }

  /* Acha uma cor legível mantendo o matiz original. Clareia se o fundo é
     escuro, escurece se o fundo é claro. Se nem o extremo do matiz alcançar o
     contraste pedido, cai para branco ou preto puro — o que render mais. */
  function corLegivel(atual, fundo, alvo) {
    var hsl = rgbParaHsl(atual);
    var subir = luminancia(fundo) < 0.5;

    for (var passo = 0; passo <= 100; passo += 2) {
      var l = subir ? hsl.l + passo : hsl.l - passo;
      if (l < 0 || l > 100) break;
      var cor = hslParaRgb(hsl.h, hsl.s, l);
      if (contraste(cor, fundo) >= alvo) return cor;
    }

    var branco = { r: 255, g: 255, b: 255, a: 1 };
    var preto = { r: 20, g: 20, b: 24, a: 1 };
    return contraste(branco, fundo) >= contraste(preto, fundo) ? branco : preto;
  }

  // ---------- leitura da tela ----------

  /* Elementos irmãos compartilham os mesmos ancestrais. Sem guardar o
     resultado, o fundo do mesmo cartão seria recalculado uma vez por linha da
     lista. O cache vale por varredura e é jogado fora no fim. */
  var cacheFundo = null;

  function fundoDaPagina() {
    var raizCor = parseCor(getComputedStyle(document.documentElement).backgroundColor);
    if (raizCor && raizCor.a >= 0.999) return raizCor;
    var corpo = parseCor(getComputedStyle(document.body).backgroundColor);
    if (corpo && corpo.a >= 0.999) return corpo;
    return { r: 12, g: 14, b: 18, a: 1 };
  }

  /* Sobe pelos elementos somando as camadas até achar algo opaco. É assim que
     descobrimos a cor que está de fato atrás da letra, e não só a do cartão
     mais próximo — que muitas vezes é transparente. */
  function fundoEfetivo(el) {
    if (!el || el.nodeType !== 1) return fundoDaPagina();
    if (cacheFundo && cacheFundo.has(el)) return cacheFundo.get(el);

    var cs = getComputedStyle(el);
    var cor = parseCor(cs.backgroundColor);
    if (!cor || cor.a === 0) cor = corDoGradiente(cs.backgroundImage);

    var resolvida;
    if (cor && cor.a >= 0.999) {
      resolvida = cor;
    } else {
      var atras = el.parentElement ? fundoEfetivo(el.parentElement) : fundoDaPagina();
      resolvida = cor && cor.a > 0 ? sobrepor(cor, atras) : atras;
    }

    if (cacheFundo) cacheFundo.set(el, resolvida);
    return resolvida;
  }

  function temTextoProprio(el) {
    var tag = el.tagName;
    if (tag === 'INPUT' || tag === 'SELECT' || tag === 'TEXTAREA') return true;
    for (var i = 0; i < el.childNodes.length; i++) {
      var n = el.childNodes[i];
      if (n.nodeType === 3 && n.nodeValue && n.nodeValue.trim()) return true;
    }
    return false;
  }

  function alvoDoTexto(cs) {
    var tamanho = parseFloat(cs.fontSize) || 16;
    var peso = parseInt(cs.fontWeight, 10) || 400;
    var grande = tamanho >= 24 || (tamanho >= 18.66 && peso >= 700);
    return grande ? ALVO_GRANDE : ALVO_NORMAL;
  }

  // ---------- correção ----------

  /* FASE 1 — só leitura. Devolve o que precisa mudar, sem mexer em nada.
     Nenhuma escrita pode acontecer aqui: cada alteração de estilo obrigaria o
     navegador a recalcular o layout inteiro na próxima medição, e numa tela
     com 600 itens isso trava o aplicativo por segundos. */
  function avaliar(el) {
    /* Elemento que não ocupa espaço na tela não precisa de correção — e
       atrapalha. Consultar `display` do próprio elemento não basta: o filho de
       um cabeçalho escondido continua dizendo "block", porque quem está
       escondido é o pai. Medir a caixa é o único jeito confiável, e ainda
       poupa trabalho: telas com listas grandes têm muita coisa fora de vista. */
    if (!el.getClientRects().length) return null;

    var cs = getComputedStyle(el);
    if (cs.visibility === 'hidden' || Number(cs.opacity) === 0) return null;

    /* A conta parte SEMPRE da cor original, guardada na primeira correção,
       nunca da cor que nós mesmos aplicamos.

       Ler a cor corrigida era um vai-e-vem: o elemento passava no teste
       justamente por causa da correção, a correção era retirada por parecer
       desnecessária, a cor voltava a ser ilegível e na varredura seguinte tudo
       se repetia. Em telas que se redesenham várias vezes — como o cadastro da
       carta, que recarrega preço e imagem — isso aparecia como texto piscando. */
    var corTexto = parseCor(el.getAttribute(COR_BASE) || cs.color);
    if (!corTexto) return null;

    // O fundo onde a letra encosta: se a caixa tem cor própria (campo, botão,
    // etiqueta) é ela; se é transparente, o que estiver atrás.
    var fundo = fundoEfetivo(el);

    // Letra semitransparente enxerga o fundo por trás dela.
    var efetiva = corTexto.a >= 0.999 ? corTexto : sobrepor(corTexto, fundo);
    var alvo = alvoDoTexto(cs);

    if (contraste(efetiva, fundo) >= alvo) {
      // A cor de origem já é legível aqui: se havia correção, ela sobrava.
      return el.hasAttribute(MARCA) ? { el: el, limpar: true } : null;
    }

    var nova = paraCss(corLegivel(efetiva, fundo, alvo));
    // Já está exatamente nesta cor: nada a escrever, e a varredura seguinte
    // também não vai encontrar trabalho — o estado estabiliza.
    if (el.getAttribute(MARCA) === nova) return null;
    return { el: el, cor: nova, base: el.getAttribute(COR_BASE) || cs.color };
  }

  // FASE 2 — só escrita, depois de todas as medições terem sido feitas.
  function aplicar(tarefa) {
    var el = tarefa.el;
    if (tarefa.limpar) return desfazer(el);
    // A marca guarda a cor aplicada; assim a próxima varredura reconhece que
    // não há nada a mudar em vez de reescrever o mesmo valor.
    if (!el.hasAttribute(COR_BASE)) el.setAttribute(COR_BASE, tarefa.base);
    el.style.setProperty('color', tarefa.cor, 'important');
    el.setAttribute(MARCA, tarefa.cor);
    // O texto de dica dos campos acompanha a cor corrigida do campo.
    var tag = el.tagName;
    if (tag === 'INPUT' || tag === 'TEXTAREA') el.style.setProperty('--ph-cor', tarefa.cor);
  }

  function desfazer(el) {
    el.style.removeProperty('color');
    el.style.removeProperty('--ph-cor');
    el.removeAttribute(MARCA);
    el.removeAttribute(COR_BASE);
  }

  function limparCorrecoes() {
    var marcados = document.querySelectorAll('[' + MARCA + ']');
    for (var i = 0; i < marcados.length; i++) desfazer(marcados[i]);
  }

  function varrer() {
    var tarefas = [];
    cacheFundo = window.Map ? new Map() : null;
    try {
      for (var r = 0; r < RAIZES.length; r++) {
        var raiz = document.querySelector(RAIZES[r]);
        if (!raiz) continue;
        var todos = raiz.querySelectorAll('*');
        for (var i = 0; i < todos.length; i++) {
          var el = todos[i];
          if (IGNORAR[String(el.tagName).toUpperCase()]) continue;
          if (!temTextoProprio(el)) continue;
          var tarefa = avaliar(el);
          if (tarefa) tarefas.push(tarefa);
        }
      }
    } finally {
      cacheFundo = null;
    }
    for (var t = 0; t < tarefas.length; t++) aplicar(tarefas[t]);
  }

  // ---------- quando rodar ----------

  var agendado = false;

  function agendar() {
    if (agendado) return;
    agendado = true;

    var jaRodou = false;
    var rodar = function () {
      if (jaRodou) return;
      jaRodou = true;
      agendado = false;
      try { varrer(); } catch (e) {}
    };

    /* Dois gatilhos de propósito, e vale o que chegar primeiro.
       O quadro de animação espera a tela assentar antes de medir — medir no
       meio do desenho leria cores que ainda vão mudar. Só que ele NÃO dispara
       com o aplicativo em segundo plano. Sem o relógio de reserva, uma tela
       desenhada com o app minimizado deixaria a varredura pendente para
       sempre, e o corretor pararia de funcionar de vez. */
    if (window.requestAnimationFrame) requestAnimationFrame(function () { setTimeout(rodar, 0); });
    setTimeout(rodar, 60);
  }

  function reavaliarTudo() {
    // Tema novo: as correções velhas foram calculadas contra o fundo antigo.
    limparCorrecoes();
    agendar();
  }

  function observar() {
    if (!window.MutationObserver) return;
    // Só mudanças de conteúdo. Atributos ficam de fora de propósito: as
    // correções mexem no style e reagendariam a varredura em laço infinito.
    var observador = new MutationObserver(agendar);
    var alvos = ['#app', '#modal'];
    for (var i = 0; i < alvos.length; i++) {
      var el = document.querySelector(alvos[i]);
      if (el) observador.observe(el, { childList: true, subtree: true });
    }
  }

  function iniciar() {
    observar();
    agendar();
  }

  window.addEventListener('tema-aplicado', reavaliarTudo);
  window.addEventListener('resize', agendar);
  // Exposto para o app pedir uma conferência depois de desenhos manuais.
  window.conferirContraste = agendar;

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', iniciar);
  } else {
    iniciar();
  }
})();
