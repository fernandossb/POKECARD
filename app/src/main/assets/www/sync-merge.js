/* Combina alterações feitas a partir da mesma revisão, sem descartar conflitos. */
(function (root) {
  'use strict';

  function iguais(a, b) {
    if (a === b) return true;
    if (!a || !b || typeof a !== 'object' || typeof b !== 'object') return false;
    if (Array.isArray(a) !== Array.isArray(b)) return false;
    const chavesA = Object.keys(a).sort();
    const chavesB = Object.keys(b).sort();
    return chavesA.length === chavesB.length && chavesA.every((chave, i) =>
      chave === chavesB[i] && iguais(a[chave], b[chave]));
  }

  function objeto(valor) {
    return valor !== null && typeof valor === 'object' && !Array.isArray(valor);
  }

  function arrayComIdsUnicos(valor) {
    if (!Array.isArray(valor)) return false;
    const ids = valor.map(item => objeto(item) && item.id != null ? String(item.id) : null);
    return ids.every(id => id !== null) && new Set(ids).size === ids.length;
  }

  function combinar(base, local, remoto) {
    const conflitos = [];
    function combinarArray(anterior, atual, nuvem, caminho) {
      if (![anterior, atual, nuvem].every(arrayComIdsUnicos)) return null;
      const basePorId = new Map(anterior.map(item => [String(item.id), item]));
      const localPorId = new Map(atual.map(item => [String(item.id), item]));
      const remotoPorId = new Map(nuvem.map(item => [String(item.id), item]));
      const todos = new Set([...basePorId.keys(), ...localPorId.keys(), ...remotoPorId.keys()]);
      const combinados = new Map();
      for (const id of todos) {
        const item = visitar(basePorId.get(id), localPorId.get(id), remotoPorId.get(id), [...caminho, id]);
        if (item !== undefined) combinados.set(id, item);
      }

      const baseIds = anterior.map(item => String(item.id));
      const ordemExistente = lista => lista.map(item => String(item.id))
        .filter(id => basePorId.has(id) && combinados.has(id));
      const ordemBase = baseIds.filter(id => combinados.has(id));
      const ordemLocal = ordemExistente(atual);
      const ordemRemota = ordemExistente(nuvem);
      const reordenouLocal = !iguais(ordemBase, ordemLocal);
      const reordenouRemoto = !iguais(ordemBase, ordemRemota);
      if (reordenouLocal && reordenouRemoto && !iguais(ordemLocal, ordemRemota)) {
        conflitos.push([...caminho, '$ordem']);
        return atual;
      }

      const preferida = reordenouLocal ? ordemLocal : reordenouRemoto ? ordemRemota : ordemBase;
      const ordem = [...preferida];
      for (const item of atual) {
        const id = String(item.id);
        if (!basePorId.has(id) && combinados.has(id) && !ordem.includes(id)) ordem.push(id);
      }
      for (const item of nuvem) {
        const id = String(item.id);
        if (!basePorId.has(id) && combinados.has(id) && !ordem.includes(id)) ordem.push(id);
      }
      return ordem.map(id => combinados.get(id)).filter(item => item !== undefined);
    }
    function visitar(anterior, atual, nuvem, caminho) {
      if (iguais(atual, nuvem)) return atual;
      if (iguais(anterior, atual)) return nuvem;
      if (iguais(anterior, nuvem)) return atual;
      if (objeto(anterior) && objeto(atual) && objeto(nuvem)) {
        const resultado = {};
        const chaves = new Set([...Object.keys(anterior), ...Object.keys(atual), ...Object.keys(nuvem)]);
        for (const chave of chaves) {
          const valor = visitar(anterior[chave], atual[chave], nuvem[chave], [...caminho, chave]);
          if (valor !== undefined) resultado[chave] = valor;
        }
        return resultado;
      }
      if (Array.isArray(anterior) && Array.isArray(atual) && Array.isArray(nuvem)) {
        const resultado = combinarArray(anterior, atual, nuvem, caminho);
        if (resultado !== null) return resultado;
      }
      conflitos.push(caminho);
      return atual;
    }
    return { dados: visitar(base, local, remoto, []), conflitos };
  }

  const api = { combinar };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  root.PokecardSyncMerge = api;
})(typeof globalThis !== 'undefined' ? globalThis : this);
