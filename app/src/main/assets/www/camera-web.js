/* Câmera do scanner no navegador — iPhone (e Android pelo navegador).

   No aplicativo Android, a câmera e a leitura do texto ficam no Java (CameraX +
   ML Kit) e o app só recebe o texto pronto: receiveScannerText(texto, acabamento),
   receiveScannerError(mensagem), receiveScannerGlare(%). Aqui o navegador faz
   as duas partes e entrega EXATAMENTE a mesma coisa, então todo o resto (achar
   a carta, confirmar, cadastrar, sessão de leitura) é o mesmo código.

   · A imagem vem de getUserMedia e aparece num <video> ATRÁS do app (a tela da
     câmera é transparente, como no Android: html.camera-ao-vivo).
   · O texto é lido pelo PaddleOCR (detector + leitor, em formato ONNX) no ONNX
     Runtime Web. Baixado na primeira vez (~22 MB, pasta ocr/, que só existe no
     site — scripts/baixar-ocr.mjs) e guardado no aparelho.
     Em 56 cartas de teste ele achou o número em 93% e o nome em 96%.
   · Só a carta é lida: o recorte é a moldura que o usuário encaixa na tela
     (o Android lê o quadro inteiro e confia que a carta o preenche).
   · A mesma ponte do Android: startLiveScanner, stopLiveScanner,
     pauseLiveScanner, resumeLiveScanner, lerDeNovoLogo.

   Precisa de https (o iPhone só libera a câmera assim) e de uma permissão. */
(function () {
  'use strict';

  var INTERVALO_MS = 1800;     // espaço mínimo entre duas entregas (igual ao Android)
  var RETENTATIVA_MS = 600;    // depois de uma leitura que não fechou
  var TEXTO_MINIMO = 12;       // texto mais curto que isto é reflexo ou borda
  var LARGURA_DA_CARTA = 960;  // px da carta recortada (a moldura de um iPhone dá ~930)
  var REFLEXO_AVISAR = 0.04;   // fração do miolo branco estourado
  var FECHAR_LEITOR_EM_MS = 60000;
  var MAX_LINHAS = 48;         // no máximo isto de trechos de texto por carta

  // Os caracteres que o leitor conhece (en_dict.txt do PaddleOCR). No CTC o 0 é
  // "nada", e o último é o espaço.
  var CARACTERES = [''].concat('0123456789:;<=>?@ABCDEFGHIJKLMNOPQRSTUVWXYZ[\\]^_`abcdefghijklmnopqrstuvwxyz{|}~!"#$%&\'()*+,-./ '.split('')).concat([' ']);
  var TAMANHOS = { wasm: 10551547, wasmSemSimd: 9726745, det: 2423224, rec: 8967018 };

  var estado = {
    ativa: false, pausada: false, ocupada: false, finish: 'comum', geracao: 0,
    video: null, stream: null, ultimaEntrega: 0, ultimoAvisoDeReflexo: 0, temporizador: 0, fechandoOcr: 0,
    ultimaLeituraMs: 0,
  };
  var config = { faixas: [0.26, 0.80] };   // [fim da faixa de cima, início da faixa de baixo], em fração da altura
  var leitor = { det: null, rec: null, promessa: null };
  var cartaCanvas = null;
  var trabalho = null;      // canvas e buffers reaproveitados entre quadros

  function disponivel() {
    return Boolean(navigator.mediaDevices && navigator.mediaDevices.getUserMedia && window.isSecureContext);
  }

  function dica(mensagem) {
    if (typeof avisarNaCamera === 'function') avisarNaCamera(mensagem);
  }

  /* ---------- O leitor de texto: baixar e preparar ---------- */

  function carregarScript(endereco) {
    return new Promise(function (resolve, reject) {
      if (window.ort) { resolve(); return; }
      var tag = document.createElement('script');
      tag.src = endereco;
      tag.onload = function () { resolve(); };
      tag.onerror = function () { reject(new Error('Não consegui baixar o leitor de cartas. Confira a internet.')); };
      document.head.appendChild(tag);
    });
  }

  // O aparelho aguenta instruções SIMD? (iOS 16.4 em diante.) Escolhe qual motor baixar.
  function temSimd() {
    try {
      return WebAssembly.validate(new Uint8Array([0, 97, 115, 109, 1, 0, 0, 0, 1, 5, 1, 96, 0, 1, 123, 3, 2, 1, 0, 10, 10, 1, 8, 0, 65, 0, 253, 15, 253, 98, 11]));
    } catch (_) { return false; }
  }

  // Baixa um arquivo mostrando o andamento. Se já estiver no cache do aparelho, é instantâneo.
  async function baixar(endereco, tamanhoEsperado, aoAndar) {
    var resposta = await fetch(endereco);
    if (!resposta.ok) throw new Error('Não consegui baixar o leitor de cartas (HTTP ' + resposta.status + ').');
    var leitorDeCorpo = resposta.body && resposta.body.getReader ? resposta.body.getReader() : null;
    if (!leitorDeCorpo) {
      var inteiro = new Uint8Array(await resposta.arrayBuffer());
      aoAndar(inteiro.length);
      return inteiro;
    }
    var pedacos = [], recebido = 0;
    for (;;) {
      var passo = await leitorDeCorpo.read();
      if (passo.done) break;
      pedacos.push(passo.value);
      recebido += passo.value.length;
      aoAndar(passo.value.length);
    }
    var bytes = new Uint8Array(recebido);
    var posicao = 0;
    pedacos.forEach(function (p) { bytes.set(p, posicao); posicao += p.length; });
    return bytes;
  }

  function prepararLeitor() {
    clearTimeout(estado.fechandoOcr);
    if (leitor.promessa) return leitor.promessa;
    leitor.promessa = (async function () {
      var base = new URL('ocr/', document.baseURI).href;
      var simd = temSimd();
      var total = (simd ? TAMANHOS.wasm : TAMANHOS.wasmSemSimd) + TAMANHOS.det + TAMANHOS.rec;
      var baixado = 0, ultimoPct = -1;
      var andar = function (bytes) {
        baixado += bytes;
        var pct = Math.min(99, Math.round(100 * baixado / total));
        if (pct !== ultimoPct) { ultimoPct = pct; dica('Preparando o leitor de cartas… ' + pct + '% (só na primeira vez)'); }
      };
      dica('Preparando o leitor de cartas… (só na primeira vez)');
      await carregarScript(base + 'ort.wasm.min.js');
      var ort = window.ort;
      // Sem threads: elas pedem cabeçalhos especiais que o site não tem — e o leitor é leve.
      ort.env.wasm.numThreads = 1;
      ort.env.wasm.proxy = false;
      ort.env.logLevel = 'error';
      // O motor é baixado aqui, com o andamento à vista, e entregue pronto ao ONNX
      // Runtime por um endereço local (blob:), para ele não baixar os 10 MB de novo.
      var arquivoDoMotor = simd ? 'ort-wasm-simd.wasm' : 'ort-wasm.wasm';
      var bytesDoMotor = await baixar(base + arquivoDoMotor, simd ? TAMANHOS.wasm : TAMANHOS.wasmSemSimd, andar);
      var caminhos = {};
      caminhos[arquivoDoMotor] = URL.createObjectURL(new Blob([bytesDoMotor], { type: 'application/wasm' }));
      ort.env.wasm.wasmPaths = caminhos;
      var bytesDet = await baixar(base + 'modelos/det.onnx', TAMANHOS.det, andar);
      var bytesRec = await baixar(base + 'modelos/rec.onnx', TAMANHOS.rec, andar);
      dica('Quase pronto…');
      var opcoes = { executionProviders: ['wasm'], graphOptimizationLevel: 'all' };
      leitor.det = await ort.InferenceSession.create(bytesDet, opcoes);
      leitor.rec = await ort.InferenceSession.create(bytesRec, opcoes);
      dica('Encaixe a carta dentro da moldura');
      return leitor;
    })();
    leitor.promessa.catch(function () { leitor.promessa = null; });
    return leitor.promessa;
  }

  function soltarLeitor() {
    var det = leitor.det, rec = leitor.rec;
    leitor.det = leitor.rec = leitor.promessa = null;
    [det, rec].forEach(function (sessao) { if (sessao && sessao.release) { try { sessao.release(); } catch (_) {} } });
    trabalho = null;
  }

  /* ---------- Imagem: recorte da moldura ---------- */

  /* A carta como a pessoa a encaixou na moldura. O <video> cobre a tela
     inteira com object-fit: cover; a moldura é um retângulo de CSS por cima —
     converte-se um no outro. */
  function recortarCarta() {
    var v = estado.video;
    if (!v || !v.videoWidth || !v.videoHeight) return null;
    var vw = v.videoWidth, vh = v.videoHeight;
    var r = v.getBoundingClientRect();
    if (!r.width || !r.height) return null;
    var s = Math.max(r.width / vw, r.height / vh);
    var ox = (r.width - vw * s) / 2, oy = (r.height - vh * s) / 2;
    var m = document.querySelector('.camera-moldura');
    var x0, y0, x1, y1;
    if (m) {
      var b = m.getBoundingClientRect();
      x0 = b.left; y0 = b.top; x1 = b.right; y1 = b.bottom;
    } else {
      // Sem a moldura na tela: a mesma, centralizada.
      var largura = Math.min(0.86 * r.width, 380), altura = largura * 88 / 63;
      x0 = r.left + (r.width - largura) / 2; y0 = r.top + (r.height - altura) / 2; x1 = x0 + largura; y1 = y0 + altura;
    }
    var sx = Math.max(0, (x0 - r.left - ox) / s), sy = Math.max(0, (y0 - r.top - oy) / s);
    var sw = Math.min(vw - sx, (x1 - x0) / s), sh = Math.min(vh - sy, (y1 - y0) / s);
    if (sw < 40 || sh < 40) return null;
    var cw = Math.min(LARGURA_DA_CARTA, Math.round(sw));
    var ch = Math.round(cw * sh / sw);
    if (!cartaCanvas) cartaCanvas = document.createElement('canvas');
    if (cartaCanvas.width !== cw || cartaCanvas.height !== ch) { cartaCanvas.width = cw; cartaCanvas.height = ch; }
    var ctx = cartaCanvas.getContext('2d', { willReadFrequently: true });
    ctx.drawImage(v, sx, sy, sw, sh, 0, 0, cw, ch);
    return cartaCanvas;
  }

  /* Reflexo: fração do miolo da carta em branco estourado (como o Android).
     Só avisa; o iPhone não deixa baixar a exposição pelo navegador. */
  function medirReflexo(carta) {
    var w = carta.width, h = carta.height;
    var x0 = Math.floor(w / 6), y0 = Math.floor(h / 6);
    var dados = carta.getContext('2d', { willReadFrequently: true }).getImageData(x0, y0, w - 2 * x0, h - 2 * y0);
    var px = dados.data, total = 0, estourados = 0;
    for (var i = 0; i < px.length; i += 4 * 8) {
      total++;
      if (((px[i] * 77 + px[i + 1] * 150 + px[i + 2] * 29) >> 8) >= 250) estourados++;
    }
    if (!total) return;
    var fracao = estourados / total;
    var agora = Date.now();
    if (fracao >= REFLEXO_AVISAR && agora - estado.ultimoAvisoDeReflexo > 3000) {
      estado.ultimoAvisoDeReflexo = agora;
      if (typeof window.receiveScannerGlare === 'function') window.receiveScannerGlare(Math.round(fracao * 100));
    }
  }

  /* ---------- O leitor de texto: detectar e ler ---------- */

  function areaDeTrabalho() {
    if (!trabalho) {
      trabalho = {
        det: document.createElement('canvas'),
        rec: document.createElement('canvas'),
      };
      trabalho.detCtx = trabalho.det.getContext('2d', { willReadFrequently: true });
      trabalho.recCtx = trabalho.rec.getContext('2d', { willReadFrequently: true });
    }
    return trabalho;
  }

  /* Onde há texto: o detector devolve, para cada ponto da imagem, a chance de
     ele ser texto (mapa de probabilidades). Os pontos acima de 0,3 que se
     tocam formam um trecho de texto; cada trecho vira um retângulo, alargado
     um pouco porque o mapa encolhe as letras (o "unclip" do PaddleOCR). */
  async function detectar(carta) {
    var w = carta.width, h = carta.height;
    var escala = Math.min(1, 960 / Math.max(w, h));
    var rw = Math.max(32, Math.round(w * escala / 32) * 32), rh = Math.max(32, Math.round(h * escala / 32) * 32);
    var area = areaDeTrabalho();
    area.det.width = rw; area.det.height = rh;
    area.detCtx.drawImage(carta, 0, 0, rw, rh);
    var px = area.detCtx.getImageData(0, 0, rw, rh).data;
    var plano = rw * rh;
    var entrada = new Float32Array(3 * plano);
    // O PaddleOCR espera a ordem BGR, normalizada com a média e o desvio do ImageNet.
    for (var p = 0, i = 0; p < plano; p++, i += 4) {
      entrada[p] = (px[i + 2] / 255 - 0.485) / 0.229;
      entrada[plano + p] = (px[i + 1] / 255 - 0.456) / 0.224;
      entrada[2 * plano + p] = (px[i] / 255 - 0.406) / 0.225;
    }
    var ort = window.ort;
    var feeds = {};
    feeds[leitor.det.inputNames[0]] = new ort.Tensor('float32', entrada, [1, 3, rh, rw]);
    var saida = await leitor.det.run(feeds);
    var prob = saida[leitor.det.outputNames[0]].data;
    var LIMIAR = 0.3, NOTA_MINIMA = 0.5, UNCLIP = 1.6;
    var rotulo = new Uint8Array(plano);
    var pilha = new Int32Array(plano);
    var caixas = [];
    for (var inicio = 0; inicio < plano; inicio++) {
      if (prob[inicio] <= LIMIAR || rotulo[inicio]) continue;
      var topo = 0;
      pilha[topo++] = inicio; rotulo[inicio] = 1;
      var x0 = rw, y0 = rh, x1 = 0, y1 = 0, soma = 0, qtd = 0;
      while (topo) {
        var q = pilha[--topo];
        var qy = (q / rw) | 0, qx = q - qy * rw;
        if (qx < x0) x0 = qx; if (qx > x1) x1 = qx;
        if (qy < y0) y0 = qy; if (qy > y1) y1 = qy;
        soma += prob[q]; qtd++;
        if (qx > 0 && prob[q - 1] > LIMIAR && !rotulo[q - 1]) { rotulo[q - 1] = 1; pilha[topo++] = q - 1; }
        if (qx < rw - 1 && prob[q + 1] > LIMIAR && !rotulo[q + 1]) { rotulo[q + 1] = 1; pilha[topo++] = q + 1; }
        if (qy > 0 && prob[q - rw] > LIMIAR && !rotulo[q - rw]) { rotulo[q - rw] = 1; pilha[topo++] = q - rw; }
        if (qy < rh - 1 && prob[q + rw] > LIMIAR && !rotulo[q + rw]) { rotulo[q + rw] = 1; pilha[topo++] = q + rw; }
      }
      var bw = x1 - x0 + 1, bh = y1 - y0 + 1;
      if (Math.min(bw, bh) < 3 || soma / qtd < NOTA_MINIMA) continue;
      var d = (bw * bh * UNCLIP) / (2 * (bw + bh));
      caixas.push({
        x0: Math.max(0, x0 - d) * w / rw, y0: Math.max(0, y0 - d) * h / rh,
        x1: Math.min(rw, x1 + d) * w / rw, y1: Math.min(rh, y1 + d) * h / rh,
      });
    }
    return caixas;
  }

  // Lê o texto de um retângulo da carta: altura 48 px, largura proporcional, decodificação CTC.
  async function lerTrecho(carta, caixa) {
    var left = Math.max(0, Math.floor(caixa.x0)), top = Math.max(0, Math.floor(caixa.y0));
    var width = Math.min(carta.width - left, Math.ceil(caixa.x1 - caixa.x0));
    var height = Math.min(carta.height - top, Math.ceil(caixa.y1 - caixa.y0));
    if (width < 12 || height < 8) return '';
    var alto = 48;
    var largo = Math.max(16, Math.min(1600, Math.ceil(alto * width / height)));
    var area = areaDeTrabalho();
    area.rec.width = largo; area.rec.height = alto;
    area.recCtx.imageSmoothingEnabled = true;
    area.recCtx.imageSmoothingQuality = 'high';
    area.recCtx.drawImage(carta, left, top, width, height, 0, 0, largo, alto);
    var px = area.recCtx.getImageData(0, 0, largo, alto).data;
    var plano = largo * alto;
    var entrada = new Float32Array(3 * plano);
    for (var p = 0, i = 0; p < plano; p++, i += 4) {
      entrada[p] = (px[i + 2] / 255 - 0.5) / 0.5;
      entrada[plano + p] = (px[i + 1] / 255 - 0.5) / 0.5;
      entrada[2 * plano + p] = (px[i] / 255 - 0.5) / 0.5;
    }
    var ort = window.ort;
    var feeds = {};
    feeds[leitor.rec.inputNames[0]] = new ort.Tensor('float32', entrada, [1, 3, alto, largo]);
    var saida = await leitor.rec.run(feeds);
    var t = saida[leitor.rec.outputNames[0]];
    var passos = t.dims[1], classes = t.dims[2], dados = t.data;
    var texto = '', anterior = -1;
    for (var s = 0; s < passos; s++) {
      var melhor = 0, valor = -Infinity, base = s * classes;
      for (var c = 0; c < classes; c++) { var v = dados[base + c]; if (v > valor) { valor = v; melhor = c; } }
      if (melhor !== 0 && melhor !== anterior) texto += CARACTERES[melhor] || '';
      anterior = melhor;
    }
    return texto.trim();
  }

  function montarTexto(linhas, alturaDaCarta) {
    // O formato do Android: a carta inteira, depois as faixas do rodapé, separadas
    // pelos marcadores que o app procura (é de lá que ele tira a numeração).
    var corpo = linhas.map(function (l) { return l.texto; }).join('\n');
    var rodape = linhas.filter(function (l) { return (l.y0 + l.y1) / 2 > 0.86 * alturaDaCarta; })
      .map(function (l) { return l.texto; }).join('\n');
    return corpo + '\n[FAIXA INFERIOR AMPLIADA]\n' + rodape + '\n[NUMERO AMPLIADO]\n' + rodape;
  }

  async function lerCarta(carta) {
    var caixas = await detectar(carta);
    // O que identifica a carta está no alto (nome, evolução) e no pé (numeração,
    // ilustrador, símbolo da coleção). Os ataques, no miolo, tomam a maior parte
    // do tempo de leitura e não ajudam: só as duas faixas são lidas.
    if (config.faixas) {
      caixas = caixas.filter(function (c) {
        var meio = (c.y0 + c.y1) / 2 / carta.height;
        return meio <= config.faixas[0] || meio >= config.faixas[1];
      });
    }
    // De cima para baixo; na mesma altura, da esquerda para a direita.
    caixas.sort(function (a, b) { return ((a.y0 + a.y1) - (b.y0 + b.y1)) || (a.x0 - b.x0); });
    var linhas = [];
    for (var i = 0; i < caixas.length && linhas.length < MAX_LINHAS; i++) {
      var texto = await lerTrecho(carta, caixas[i]);
      if (texto) linhas.push({ texto: texto, y0: caixas[i].y0, y1: caixas[i].y1 });
    }
    return montarTexto(linhas, carta.height);
  }

  /* ---------- O ciclo de leitura ---------- */

  function agendar(ms, minha) {
    clearTimeout(estado.temporizador);
    estado.temporizador = setTimeout(function () { ciclo(minha); }, ms);
  }

  async function ciclo(minha) {
    if (!estado.ativa || minha !== estado.geracao) return;
    var agora = Date.now();
    var pronta = estado.video && estado.video.videoWidth && leitor.det && leitor.rec;
    if (estado.pausada || estado.ocupada || !pronta || document.hidden || agora - estado.ultimaEntrega < INTERVALO_MS) {
      agendar(250, minha);
      return;
    }
    estado.ocupada = true;
    try {
      var carta = recortarCarta();
      if (carta) {
        medirReflexo(carta);
        var t0 = Date.now();
        var texto = await lerCarta(carta);
        estado.ultimaLeituraMs = Date.now() - t0;
        // A câmera pode ter fechado, ou o app pausado para a pergunta "é esta carta?".
        if (estado.ativa && minha === estado.geracao && !estado.pausada && texto.trim().length >= TEXTO_MINIMO) {
          estado.ultimaEntrega = Date.now();
          if (typeof window.receiveScannerText === 'function') window.receiveScannerText(texto, estado.finish);
        }
      }
    } catch (erro) {
      if (window.console) console.warn('POKECARD: leitura da câmera falhou —', erro && erro.message);
    } finally {
      estado.ocupada = false;
      agendar(60, minha);
    }
  }

  /* ---------- Abrir e fechar a câmera ---------- */

  function criarVideo() {
    if (estado.video) return estado.video;
    var v = document.createElement('video');
    v.id = 'camera-web';
    v.setAttribute('playsinline', '');
    v.setAttribute('webkit-playsinline', '');
    v.muted = true;
    v.autoplay = true;
    document.body.insertBefore(v, document.body.firstChild);
    estado.video = v;
    return v;
  }

  function mensagemDoErro(erro) {
    var nome = erro && erro.name;
    if (nome === 'NotAllowedError' || nome === 'SecurityError') return 'Permissão de câmera negada. Libere a câmera para este app nos ajustes do aparelho.';
    if (nome === 'NotFoundError' || nome === 'OverconstrainedError') return 'Não encontrei uma câmera neste aparelho.';
    if (nome === 'NotReadableError') return 'A câmera está em uso por outro app. Feche-o e tente de novo.';
    return (erro && erro.message) || 'Não foi possível abrir a câmera.';
  }

  // Sem imagem não há o que fazer na tela do scanner (permissão negada, sem internet
  // para baixar o leitor, sem câmera): o app fecha a tela e deixa o aviso à vista.
  function falhar(mensagem) {
    parar();
    document.documentElement.classList.remove('camera-ao-vivo');
    if (typeof window.cameraWebFalhou === 'function') window.cameraWebFalhou(mensagem);
    else if (typeof window.receiveScannerError === 'function') window.receiveScannerError(mensagem);
  }

  function abrirFluxo(minha) {
    return navigator.mediaDevices.getUserMedia({
      audio: false,
      video: { facingMode: { ideal: 'environment' }, width: { ideal: 1920 }, height: { ideal: 1080 } },
    }).then(function (stream) {
      if (!estado.ativa || minha !== estado.geracao) { stream.getTracks().forEach(function (t) { t.stop(); }); return; }
      estado.stream = stream;
      var faixa = stream.getVideoTracks()[0];
      // Foco contínuo, onde o navegador deixa (Android; o iPhone já foca sozinho).
      try {
        var caps = faixa && faixa.getCapabilities ? faixa.getCapabilities() : {};
        if (caps.focusMode && caps.focusMode.indexOf('continuous') >= 0) faixa.applyConstraints({ advanced: [{ focusMode: 'continuous' }] });
      } catch (_) {}
      var v = criarVideo();
      v.srcObject = stream;
      var tocar = v.play();
      if (tocar && tocar.catch) tocar.catch(function () {});
    });
  }

  function iniciar(finish) {
    estado.finish = finish || 'comum';
    estado.pausada = false;
    // Seguro de chamar repetido, como no Android: só garante que a câmera está aberta.
    if (estado.ativa) return;
    if (!disponivel()) {
      falhar('A câmera só abre por uma conexão segura (https). Abra o app pelo endereço do site.');
      return;
    }
    estado.ativa = true;
    estado.ocupada = false;
    estado.ultimaEntrega = 0;
    var minha = ++estado.geracao;
    criarVideo();
    // A câmera e o leitor sobem juntos: o aviso do leitor aparece enquanto a imagem já roda.
    abrirFluxo(minha).catch(function (erro) {
      if (estado.ativa && minha === estado.geracao) falhar(mensagemDoErro(erro));
    });
    prepararLeitor().catch(function (erro) {
      if (window.console) console.warn('POKECARD: leitor de cartas não preparou —', erro && erro.message);
      if (estado.ativa && minha === estado.geracao) falhar('Não consegui preparar o leitor de cartas. Confira a internet e tente de novo.');
    });
    agendar(300, minha);
  }

  function parar() {
    estado.ativa = false;
    estado.geracao++;
    clearTimeout(estado.temporizador);
    if (estado.stream) {
      estado.stream.getTracks().forEach(function (t) { try { t.stop(); } catch (_) {} });
      estado.stream = null;
    }
    if (estado.video) {
      try { estado.video.pause(); } catch (_) {}
      estado.video.srcObject = null;
      estado.video.remove();
      estado.video = null;
    }
    estado.ocupada = false;
    cartaCanvas = null;
    // O leitor fica um minuto na memória (abrir o scanner de novo é imediato) e sai.
    clearTimeout(estado.fechandoOcr);
    estado.fechandoOcr = setTimeout(soltarLeitor, FECHAR_LEITOR_EM_MS);
  }

  // O iPhone desliga a câmera quando o app vai para segundo plano: ao voltar, reabre.
  document.addEventListener('visibilitychange', function () {
    if (document.hidden || !estado.ativa) return;
    var faixa = estado.stream && estado.stream.getVideoTracks()[0];
    if (faixa && faixa.readyState === 'live') {
      if (estado.video && estado.video.paused) { var p = estado.video.play(); if (p && p.catch) p.catch(function () {}); }
      return;
    }
    var minha = estado.geracao;
    abrirFluxo(minha).catch(function (erro) {
      if (estado.ativa && minha === estado.geracao) falhar(mensagemDoErro(erro));
    });
  });

  window.CameraWeb = {
    disponivel: disponivel,
    startLiveScanner: iniciar,
    stopLiveScanner: parar,
    // Com o painel "é esta carta?" aberto a câmera segue ligada, mas não entrega leitura.
    pauseLiveScanner: function () { estado.pausada = true; },
    // Uma leitura leva ~2 s aqui; a folga a mais dá tempo de trocar a carta antes de ela ser lida de novo.
    resumeLiveScanner: function () { estado.pausada = false; estado.ultimaEntrega = Date.now() + 1500; },
    // A leitura parecia carta mas não fechou: tenta de novo logo, sem esperar o intervalo.
    lerDeNovoLogo: function () { estado.ultimaEntrega = Date.now() - INTERVALO_MS + RETENTATIVA_MS; },
    // Para os testes.
    _estado: estado, _lerCarta: lerCarta, _prepararLeitor: prepararLeitor, _recortarCarta: recortarCarta,
    _detectar: detectar, _lerTrecho: lerTrecho, _config: config,
  };
})();
