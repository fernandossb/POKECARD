/* O app no navegador — iPhone (e Android) pela Tela de Início.

   O aplicativo Android conversa com o sistema por uma ponte (window.Android):
   avisos, salvar arquivo, importar backup, câmera. No navegador essa ponte não
   existe, então este arquivo faz o equivalente com o que o navegador oferece.
   Quando a ponte existe (aplicativo Android), nada aqui muda o comportamento.

   O que há aqui:
   · service worker (abrir sem internet) — só em https, nunca no APK;
   · avisos na tela no lugar do "toast" do Android;
   · entregar arquivo: Compartilhar do iPhone (Arquivos, iCloud, WhatsApp...) ou
     download, no lugar do "Salvar como" do Android;
   · importar backup por seletor de arquivo;
   · convite para instalar no iPhone, com o cuidado com o armazenamento;
   · "Atualizar o app", já que no iPhone não há puxar-para-atualizar;
   · a Pokébola do meio da barra vira "Buscar carta" (a câmera é só do Android).

   NO_NAVEGADOR (app.js) é verdadeiro quando não há ponte com o Android. */
(function () {
  'use strict';

  var CHAVE_INSTALAR = 'pokecard-instalar-dispensado';
  var DIAS_SEM_CONVITE = 14;

  function ehIOS() {
    var ua = navigator.userAgent || '';
    // iPad novo se apresenta como Mac, mas tem tela de toque.
    return /iPad|iPhone|iPod/.test(ua) || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);
  }

  // Aberto pelo ícone da Tela de Início (e não numa aba do Safari).
  function appInstalado() {
    return navigator.standalone === true
      || (window.matchMedia && window.matchMedia('(display-mode: standalone)').matches);
  }

  window.ehIOS = ehIOS;
  window.appInstalado = appInstalado;

  /* ---------- Avisos na tela ---------- */

  var tempoDoAviso = 0;
  window.mostrarAvisoNaTela = function (mensagem) {
    var texto = String(mensagem == null ? '' : mensagem);
    if (!texto || !document.body) return;
    var el = document.getElementById('aviso-na-tela');
    if (!el) {
      el = document.createElement('div');
      el.id = 'aviso-na-tela';
      el.setAttribute('role', 'status');
      el.setAttribute('aria-live', 'polite');
      document.body.appendChild(el);
    }
    el.textContent = texto;
    // Reinicia a animação quando um aviso chega em cima de outro.
    el.classList.remove('visivel');
    void el.offsetWidth;
    el.classList.add('visivel');
    clearTimeout(tempoDoAviso);
    tempoDoAviso = setTimeout(function () { el.classList.remove('visivel'); }, 3200 + Math.min(3500, texto.length * 22));
  };

  /* ---------- Service worker e atualização ---------- */

  var recarregarAoAtualizar = false;
  var haVersaoNova = false;

  function registrarServiceWorker() {
    if (!('serviceWorker' in navigator) || !/^https?:$/.test(location.protocol)) return;
    var tinhaControle = Boolean(navigator.serviceWorker.controller);
    navigator.serviceWorker.addEventListener('controllerchange', function () {
      if (recarregarAoAtualizar) { location.reload(); return; }
      // Primeira instalação: o service worker só assumiu o app, nada mudou.
      if (!tinhaControle) { tinhaControle = true; return; }
      haVersaoNova = true;
      window.mostrarAvisoNaTela('Nova versão do app pronta. Feche e abra de novo para usar.');
    });
    window.addEventListener('load', function () {
      navigator.serviceWorker.register('sw.js').catch(function () {});
    });
  }

  // No iPhone instalado não há puxar-para-atualizar nem botão de recarregar.
  window.atualizarAppInstalado = function () {
    if (haVersaoNova || !('serviceWorker' in navigator) || !/^https?:$/.test(location.protocol)) {
      location.reload();
      return;
    }
    window.mostrarAvisoNaTela('Procurando atualização...');
    navigator.serviceWorker.getRegistration().then(function (registro) {
      if (!registro) { location.reload(); return null; }
      return registro.update().then(function () {
        if (registro.installing || registro.waiting) {
          recarregarAoAtualizar = true;
          window.mostrarAvisoNaTela('Baixando a nova versão...');
          // Se o service worker novo já estava pronto, ninguém dispara
          // "controllerchange": recarrega por conta própria.
          setTimeout(function () { if (recarregarAoAtualizar) location.reload(); }, 9000);
        } else {
          window.mostrarAvisoNaTela('Você já está na versão mais recente.');
        }
      });
    }).catch(function () {
      window.mostrarAvisoNaTela('Sem conexão para procurar atualização.');
    });
  };

  /* ---------- Entregar um arquivo ---------- */

  function baixar(blob, nome) {
    var link = document.createElement('a');
    var endereco = URL.createObjectURL(blob);
    link.href = endereco;
    link.download = nome;
    link.rel = 'noopener';
    document.body.appendChild(link);
    link.click();
    link.remove();
    setTimeout(function () { URL.revokeObjectURL(endereco); }, 60000);
    return 'baixado';
  }

  function arquivoParaCompartilhar(blob, nome, mime) {
    try { return new File([blob], nome, { type: mime }); } catch (_) { return null; }
  }

  function podeCompartilhar(arquivo) {
    try { return Boolean(arquivo && navigator.share && navigator.canShare && navigator.canShare({ files: [arquivo] })); }
    catch (_) { return false; }
  }

  /* Compartilhar do iPhone: Salvar em Arquivos, iCloud Drive, AirDrop, WhatsApp.
     O iPhone só deixa abrir o Compartilhar logo depois de um toque. Se o app
     passou tempo demais preparando o arquivo, ele recusa — aí aparece um
     painel com um botão, e o toque nele já vale. Sem Compartilhar (computador),
     baixa o arquivo.
     Devolve: 'compartilhado', 'baixado', 'cancelado' ou 'painel' (o botão do
     painel termina o serviço; aoConcluir roda quando der certo). */
  window.entregarArquivo = function (nome, mime, dados, aoConcluir) {
    var blob = dados instanceof Blob ? dados : new Blob([dados], { type: mime });
    var arquivo = arquivoParaCompartilhar(blob, nome, mime);
    var concluir = function (situacao) { if (typeof aoConcluir === 'function') aoConcluir(situacao); return situacao; };
    if (!podeCompartilhar(arquivo)) return Promise.resolve(concluir(baixar(blob, nome)));
    return navigator.share({ files: [arquivo], title: nome }).then(function () {
      return concluir('compartilhado');
    }).catch(function (erro) {
      if (erro && erro.name === 'AbortError') return 'cancelado';
      if (erro && erro.name === 'NotAllowedError') { painelDoArquivo(nome, blob, arquivo, concluir); return 'painel'; }
      return concluir(baixar(blob, nome));
    });
  };

  function painelDoArquivo(nome, blob, arquivo, concluir) {
    if (typeof showModal !== 'function') { concluir(baixar(blob, nome)); return; }
    var tamanho = blob.size < 1048576 ? Math.max(1, Math.round(blob.size / 1024)) + ' KB' : (blob.size / 1048576).toFixed(1).replace('.', ',') + ' MB';
    window.__arquivoPronto = { nome: nome, blob: blob, arquivo: arquivo, concluir: concluir };
    showModal('<button class="modal-close" onclick="closeModal()" aria-label="Fechar">×</button>'
      + '<h2>Arquivo pronto</h2>'
      + '<div class="panel"><strong>' + String(nome).replace(/[<>&]/g, '') + '</strong><p class="card-meta">' + tamanho + '</p></div>'
      + '<div class="backup-actions"><button class="primary-btn" onclick="compartilharArquivoPronto()">Salvar ou compartilhar</button>'
      + '<button class="secondary-btn" onclick="baixarArquivoPronto()">Baixar</button></div>');
  }

  // Aqui o toque é do usuário, na hora: o Compartilhar abre.
  window.compartilharArquivoPronto = function () {
    var pronto = window.__arquivoPronto;
    if (!pronto) return;
    navigator.share({ files: [pronto.arquivo], title: pronto.nome }).then(function () {
      pronto.concluir('compartilhado');
      if (typeof closeModal === 'function') closeModal();
    }).catch(function (erro) {
      if (erro && erro.name === 'AbortError') return;
      pronto.concluir(baixar(pronto.blob, pronto.nome));
    });
  };

  window.baixarArquivoPronto = function () {
    var pronto = window.__arquivoPronto;
    if (!pronto) return;
    pronto.concluir(baixar(pronto.blob, pronto.nome));
    if (typeof closeModal === 'function') closeModal();
  };

  /* ---------- Importar backup ---------- */

  window.escolherBackupNoNavegador = function () {
    var campo = document.createElement('input');
    campo.type = 'file';
    campo.accept = '.json,application/json';
    campo.style.display = 'none';
    campo.addEventListener('change', function () {
      var arquivo = campo.files && campo.files[0];
      campo.remove();
      if (!arquivo) return;
      var leitor = new FileReader();
      leitor.onload = function () {
        if (typeof receiveImportedBackup === 'function') receiveImportedBackup(String(leitor.result));
      };
      leitor.onerror = function () { window.mostrarAvisoNaTela('Não consegui ler o arquivo.'); };
      leitor.readAsText(arquivo);
    });
    document.body.appendChild(campo);
    campo.click();
  };

  /* ---------- Buscar carta (no lugar da câmera) ---------- */

  window.abrirBuscaDeCartas = function () {
    if (typeof ui !== 'undefined') { ui.cardSet = 'all'; ui.cardFilter = 'all'; ui.cardQuery = ''; }
    if (typeof setTab === 'function') setTab('cards');
    setTimeout(function () {
      var campo = document.getElementById('cardSearchInput');
      if (campo) campo.focus();
    }, 400);
  };

  /* ---------- Convite para instalar no iPhone ----------

     Duas coisas que quem usa o app precisa saber ANTES de cadastrar cartas:
     1. o app instalado tem armazenamento próprio, separado do Safari — o que
        for cadastrado numa aba do Safari não aparece no ícone da Tela de Início
        (só indo por backup);
     2. o Safari apaga os dados de um site que fica 7 dias sem ser aberto. O
        app instalado não sofre isso. */
  function convitePendente() {
    try {
      var quando = Number(localStorage.getItem(CHAVE_INSTALAR)) || 0;
      return !quando || Date.now() - quando > DIAS_SEM_CONVITE * 86400000;
    } catch (_) { return true; }
  }

  window.avisoDeInstalarHtml = function () {
    if (typeof NO_NAVEGADOR === 'undefined' || !NO_NAVEGADOR) return '';
    if (!ehIOS() || appInstalado() || !convitePendente()) return '';
    var compartilhar = '<svg class="instalar-icone" viewBox="0 0 24 24" aria-hidden="true"><path d="M12 15V3m0 0L8 7m4-4 4 4M6 11H5a1 1 0 0 0-1 1v8a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1v-8a1 1 0 0 0-1-1h-1" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg>';
    return '<section class="aviso-instalar" aria-label="Instalar o app no iPhone">'
      + '<strong>Instale o POKECARD no seu iPhone</strong>'
      + '<ol>'
      + '<li>Toque em ' + compartilhar + ' <b>Compartilhar</b>, na barra do Safari.</li>'
      + '<li>Escolha <b>Adicionar à Tela de Início</b>.</li>'
      + '<li>Abra o app pelo ícone novo.</li>'
      + '</ol>'
      + '<small><b>Faça isso antes de cadastrar cartas.</b> O app instalado guarda a coleção separado do Safari, e o Safari apaga os dados de um site que fica 7 dias sem abrir.</small>'
      + '<button type="button" onclick="dispensarAvisoDeInstalar()">Entendi</button>'
      + '</section>';
  };

  window.dispensarAvisoDeInstalar = function () {
    try { localStorage.setItem(CHAVE_INSTALAR, String(Date.now())); } catch (_) {}
    if (typeof renderKeepingScroll === 'function') renderKeepingScroll();
  };

  registrarServiceWorker();
})();
