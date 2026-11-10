/* Sincronização Android. Falhas de rede deixam a coleção no aparelho. */
(function () {
  'use strict';
  if (!window.Android?.syncAccount) return;

  const DATABASE = 'pokecard-sincronizacao';
  const STORE = 'revisoes';
  let conta = null;
  let metadata = null;
  let proprietarioLocal = null;
  let timer = null;
  let executando = false;
  let aplicando = false;
  let autorizarEnvioInicial = false;
  let autorizarRestauracaoInicial = false;
  let estadoAutorizadoParaRestauracao = null;
  let precisaEscolherColecao = false;
  let sequencia = 0;
  let alteracoesLocais = 0;
  let ultimaVerificacaoLocal = -1;
  const chamadas = new Map();
  let mensagem = 'Entre com sua conta Google para sincronizar.';

  function banco() {
    return new Promise((resolve, reject) => {
      const pedido = indexedDB.open(DATABASE, 1);
      pedido.onupgradeneeded = () => pedido.result.createObjectStore(STORE);
      pedido.onsuccess = () => resolve(pedido.result);
      pedido.onerror = () => reject(pedido.error);
    });
  }

  async function armazenar(uid, dados) {
    const db = await banco();
    return new Promise((resolve, reject) => {
      const tx = db.transaction(STORE, 'readwrite');
      tx.objectStore(STORE).put(dados, uid);
      tx.oncomplete = () => { db.close(); resolve(); };
      tx.onerror = () => { db.close(); reject(tx.error); };
      tx.onabort = () => { db.close(); reject(tx.error); };
    });
  }

  async function carregar(uid) {
    const db = await banco();
    return new Promise((resolve, reject) => {
      const tx = db.transaction(STORE, 'readonly');
      const pedido = tx.objectStore(STORE).get(uid);
      pedido.onsuccess = () => resolve(pedido.result || null);
      pedido.onerror = () => reject(pedido.error);
      tx.oncomplete = () => db.close();
    });
  }

  function solicitar(metodo, ...argumentos) {
    const id = String(++sequencia);
    return new Promise((resolve, reject) => {
      chamadas.set(id, { resolve, reject });
      try { window.Android[metodo](id, ...argumentos); }
      catch (error) { chamadas.delete(id); reject(error); }
    });
  }

  window.pokecardSyncReply = async function (id, resposta) {
    if (id === 'login' || id === 'logout') {
      if (resposta.error) { mensagem = resposta.error; notify(mensagem); return; }
      await definirConta(resposta);
      if (document.querySelector('.sync-conta')) openBackupPanel();
      return;
    }
    const chamada = chamadas.get(id);
    if (!chamada) return;
    chamadas.delete(id);
    if (resposta.error) chamada.reject(new Error(resposta.error));
    else chamada.resolve(resposta);
  };

  async function definirConta(dados) {
    conta = dados.uid ? dados : null;
    autorizarEnvioInicial = false;
    autorizarRestauracaoInicial = false;
    estadoAutorizadoParaRestauracao = null;
    precisaEscolherColecao = false;
    ultimaVerificacaoLocal = -1;
    proprietarioLocal = await carregar('__proprietario');
    metadata = conta ? await carregar(conta.uid) : null;
    mensagem = conta && proprietarioLocal && proprietarioLocal !== conta.uid
      ? 'Esta coleção está vinculada a outra conta. Entre com a conta original.'
      : conta ? 'Conectado. Aguardando sincronização.' : 'Entre com sua conta Google para sincronizar.';
    if (conta && (!proprietarioLocal || proprietarioLocal === conta.uid)) agendar();
  }

  function estadoAtual() {
    return JSON.parse(JSON.stringify(state));
  }

  function vazio(dados) {
    function semConteudo(valor) {
      if (valor == null || valor === false || valor === '') return true;
      if (Array.isArray(valor)) return valor.length === 0;
      if (typeof valor === 'object') return Object.keys(valor).length === 0;
      return false;
    }
    return !Object.keys(dados.entries || {}).length &&
      (!Array.isArray(dados.decks) || !dados.decks.length) &&
      Object.keys(dados).every(chave =>
        ['entries', 'decks', 'version', 'importedAt'].includes(chave) ||
        semConteudo(dados[chave]));
  }

  async function aplicar(dados) {
    if (!dados || typeof dados !== 'object' || !dados.entries || Array.isArray(dados.entries))
      throw new Error('A coleção recebida é inválida.');
    salvarBackupDeEmergencia();
    aplicando = true;
    try {
      const migrado = migrateState(dados);
      if (!migrado) throw new Error('A coleção recebida é inválida.');
      state = migrado;
      saveState();
      saveStateNow();
      // A revisão remota só pode virar base depois de uma gravação durável.
      // Se o IndexedDB falhar, a próxima tentativa deve voltar a comparar
      // contra a base anterior, preservando as alterações locais.
      await gravarColecaoNoBanco(textoParaGravar());
      render();
    } finally {
      aplicando = false;
    }
  }

  async function sincronizar() {
    if (!conta || !state || executando) return;
    const uid = conta.uid;
    if (proprietarioLocal && proprietarioLocal !== uid) {
      mensagem = 'Esta coleção está vinculada a outra conta. Entre com a conta original.';
      return;
    }
    executando = true;
    mensagem = 'Sincronizando…';
    try {
      const resposta = await solicitar('syncRead', metadata?.revisao || '');
      if (conta?.uid !== uid) return;
      if (resposta.unchanged && (!metadata || resposta.revision !== metadata.revisao))
        throw new Error('A revisão local não corresponde à nuvem.');
      if (resposta.unchanged && alteracoesLocais === ultimaVerificacaoLocal) {
        mensagem = 'Coleção sincronizada.';
        return;
      }
      const local = estadoAtual();
      let resultado = local;
      const revisao = resposta.revision || '';
      if (revisao && !resposta.unchanged) {
        const remoto = JSON.parse(resposta.data);
        if (!remoto || typeof remoto !== 'object' || !remoto.entries)
          throw new Error('A coleção na nuvem é inválida.');
        const baseRemota = JSON.parse(JSON.stringify(remoto));
        if (!metadata) {
          if (autorizarRestauracaoInicial && JSON.stringify(local) !== estadoAutorizadoParaRestauracao) {
            autorizarRestauracaoInicial = false;
            estadoAutorizadoParaRestauracao = null;
            mensagem = 'Os dados locais mudaram. Confirme novamente qual coleção usar.';
            return;
          }
          if (!vazio(local) && !autorizarRestauracaoInicial) {
            precisaEscolherColecao = true;
            mensagem = 'Há dados neste aparelho e na nuvem. Para usar a coleção da nuvem, toque em Usar coleção da nuvem.';
            return;
          }
          resultado = remoto;
        } else {
          const combinado = PokecardSyncMerge.combinar(metadata.base, local, remoto);
          if (combinado.conflitos.length) {
            mensagem = 'Alterações conflitantes. A coleção local foi preservada; exporte um backup.';
            notify(mensagem);
            return;
          }
          resultado = combinado.dados;
        }
        // Evita substituir uma edição feita durante a leitura assíncrona.
        if (JSON.stringify(local) !== JSON.stringify(estadoAtual())) { agendar(); return; }
        if (JSON.stringify(resultado) !== JSON.stringify(local)) await aplicar(resultado);
        if (conta?.uid !== uid) return;
        // A base não pode compartilhar objetos com o estado exibido: uma
        // edição posterior alteraria a referência usada para detectar mudanças.
        metadata = { revisao, base: baseRemota };
        await armazenar(uid, metadata);
        precisaEscolherColecao = false;
        autorizarRestauracaoInicial = false;
        estadoAutorizadoParaRestauracao = null;
        if (!proprietarioLocal) {
          await armazenar('__proprietario', uid);
          proprietarioLocal = uid;
        }
      } else if (!revisao && metadata?.revisao) {
        throw new Error('A revisão da nuvem não está disponível. A coleção local foi preservada.');
      } else if (!revisao && !autorizarEnvioInicial) {
        mensagem = 'Nenhuma coleção na nuvem. Ative a sincronização para enviar a coleção deste aparelho.';
        return;
      }

      const atual = estadoAtual();
      const alteracoesProcessadas = alteracoesLocais;
      const base = metadata?.base;
      const comparacao = base && PokecardSyncMerge.combinar(base, atual, base);
      if (!base || JSON.stringify(comparacao.dados) !== JSON.stringify(base)) {
        const escrita = await solicitar('syncWrite', revisao, JSON.stringify(atual));
        if (conta?.uid !== uid) return;
        metadata = { revisao: escrita.revision, base: atual };
        await armazenar(uid, metadata);
        if (!proprietarioLocal) {
          await armazenar('__proprietario', uid);
          proprietarioLocal = uid;
        }
        autorizarEnvioInicial = false;
      }
      if (alteracoesLocais === alteracoesProcessadas) ultimaVerificacaoLocal = alteracoesLocais;
      else agendar();
      mensagem = 'Coleção sincronizada.';
    } catch (error) {
      mensagem = 'Sincronização pendente. ' + (error.message || 'Tente novamente com internet.');
      if (/CONFLICT/.test(error.message || '')) agendar();
    } finally {
      executando = false;
    }
  }

  function agendar() {
    if (!conta || aplicando) return;
    clearTimeout(timer);
    timer = setTimeout(sincronizar, 1500);
  }

  window.pokecardSyncChanged = () => { alteracoesLocais++; agendar(); };
  window.pokecardSyncLogin = () => window.Android.syncLogin();
  window.pokecardSyncLogout = () => window.Android.syncLogout();
  window.pokecardSyncNow = () => { sincronizar().then(() => { notify(mensagem); openBackupPanel(); }); };
  window.pokecardSyncStart = () => {
    if (!confirm('Enviar a coleção deste aparelho para sua conta Google?')) return;
    autorizarEnvioInicial = true;
    window.pokecardSyncNow();
  };
  window.pokecardSyncRestore = () => {
    if (!confirm('Substituir os dados deste aparelho pela coleção da nuvem? Exporte um backup antes se quiser guardar os dados locais.')) return;
    autorizarRestauracaoInicial = true;
    estadoAutorizadoParaRestauracao = JSON.stringify(estadoAtual());
    window.pokecardSyncNow();
  };
  window.pokecardSyncPanel = () => `<div class="panel sync-conta">
    <strong>Conta e sincronização</strong>
    <p class="card-meta">Sincroniza coleção, decks e obras pessoais. Fotos locais de cartas não são sincronizadas.</p>
    <p class="card-meta">${esc(conta?.email || mensagem)}</p>
    ${conta ? `<p class="card-meta">${esc(mensagem)}</p>
      <button class="secondary-btn" onclick="pokecardSyncNow()">Sincronizar agora</button>
      ${!metadata ? '<button class="secondary-btn" onclick="pokecardSyncStart()">Ativar sincronização neste aparelho</button>' : ''}
      ${precisaEscolherColecao ? '<button class="secondary-btn" onclick="pokecardSyncRestore()">Usar coleção da nuvem</button>' : ''}
      <button class="secondary-btn" onclick="pokecardSyncLogout()">Sair da conta</button>`
      : '<button class="secondary-btn" onclick="pokecardSyncLogin()">Entrar com Google</button>'}
  </div>`;
  window.addEventListener('online', agendar);
  document.addEventListener('visibilitychange', () => { if (!document.hidden) agendar(); });
  setInterval(() => { if (!document.hidden) agendar(); }, 30000);
  solicitar('syncAccount').then(definirConta).catch(() => {});
})();
