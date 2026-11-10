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

  function combinar(base, local, remoto) {
    const conflitos = [];
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
      conflitos.push(caminho);
      return atual;
    }
    return { dados: visitar(base, local, remoto, []), conflitos };
  }

  const api = { combinar };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  root.PokecardSyncMerge = api;
})(typeof globalThis !== 'undefined' ? globalThis : this);
