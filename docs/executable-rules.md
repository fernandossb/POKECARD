# Regras executáveis — 5.98.0

Cobertura verificada em 23/10/2026: 457 impressões executáveis, das quais 422 elegíveis no Padrão do snapshot. O catálogo combinado contém 3.097 impressões. Isso **não** significa que todas as cartas da rotação estejam implementadas.

O compilador usa uma gramática fechada para Pokémon Básicos sem habilidades ou regras especiais: dano numérico, compra de cartas, moedas multiplicativas, dano no próprio atacante, cura e impedimento de atacar no próximo turno. Todos os ataques precisam ser reconhecidos. Texto adicional, dano variável desconhecido, tipo não suportado ou regra especial impedem a liberação da carta inteira. Evoluções e habilidades continuam restritas aos mapeamentos explícitos existentes.

Treinadores e Energias reaproveitam uma implementação apenas quando categoria, nome, subtipo e texto são idênticos, incluindo a distinção ACE SPEC. As Energias Especiais não recebem a exceção de quantidade das Energias Básicas. Reimpressões adicionais usam arte remota.

O editor mostra todo o catálogo por padrão e permite planejar cartas não possuídas. A CPU sorteia entre decks completos e executáveis, respeitando o formato Padrão quando selecionado. O botão de atualizações verifica as releases oficiais do PokeCard; novas regras chegam em uma atualização assinada do aplicativo, não pela execução de texto baixado.

Validação: testes de rejeição de cláusulas desconhecidas, dano variável, preservação dos efeitos, cura limitada ao dano existente e distinção de Energias; suíte existente de regras, CPU, replay e decks. `CatalogRuleCompilerTest` gera `trainer-engine/build/rules-coverage.txt` com a lista efetivamente executável.

Fontes: [anúncio oficial de rotação 2026](https://www.pokemon.com/us/pokemon-news/2026-pokemon-tcg-standard-format-rotation-announcement), [manual oficial](https://www.pokemon.com/static-assets/content-assets/cms2/pdf/trading-card-game/rulebook/par_rulebook_en.pdf), textos individuais do TCGdex preservados em `library/catalog.json`.

Pendente: conversão e testes das demais habilidades, condições especiais, efeitos condicionais, energias especiais e interações entre cartas. O inventário de textos não constitui validação dessas regras.
