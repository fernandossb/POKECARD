package com.tcgtrainer.engine

object TrainingReview {
    fun describe(state: GameState): String {
        val own = state.history.filter { it.player == 0 && it.turn > 0 }
        val turns = own.groupBy { it.turn }
        val attacks = own.count { it.action is GameAction.Attack }
        val quiet = turns.filterValues { actions -> actions.any { it.action is GameAction.EndTurn } && actions.none { it.action is GameAction.Attack } }.keys
        val observations = mutableListOf<String>()
        if (state.deckLists.size == 2) runCatching {
            val engine = RulesEngine(TrainerCatalog)
            var replay = engine.newGame(state.seed, state.deckLists, state.mode)
            for (record in state.history) {
                if (record.player == 0 && record.action is GameAction.EndTurn) {
                    val available = engine.legalActions(replay).filterIsInstance<GameAction.Attack>()
                    if (available.isNotEmpty()) observations += "T${record.turn} · Oportunidade de melhoria: encerrou com ${available.size} ataque(s) legal(is) disponível(is). Avalie o custo de deixar de atacar; preservar recursos também pode justificar a escolha."
                }
                if (record.player == 0 && record.action is GameAction.Attack) {
                    val action = record.action
                    val preview = engine.preview(replay, action.attackId, action.targetId)
                    if (preview != null && preview.damage > 0) observations += "T${record.turn} · Ponto positivo: preparou e executou um ataque com previsão de ${preview.damage} de dano."
                }
                val next = engine.apply(replay, record.action)
                check(next.legality == Legality.Legal)
                replay = next.state
            }
        }.onFailure { observations.clear() }
        return buildString {
            appendLine("RESUMO DO TREINO")
            appendLine(state.winReason ?: "Partida encerrada.")
            appendLine("Você realizou $attacks ataque(s) em ${turns.size} turno(s) registrados.")
            appendLine("\nPONTOS PARA ESTUDAR")
            if (attacks > 0) appendLine("Você colocou seus atacantes em ação. Revise a sequência de preparação e os recursos gastos antes de cada ataque.")
            if (quiet.isNotEmpty()) appendLine("Turnos encerrados sem atacar: ${quiet.joinToString()}. Confira se faltou energia, evolução ou acesso a cartas. Passar pode ter sido necessário; este registro não prova um erro.")
            appendLine("\nDECISÕES BOAS E PONTOS A MELHORAR")
            appendLine(observations.take(16).joinToString("\n").ifEmpty { "Não há decisões suficientes para apontar oportunidades específicas." })
            appendLine("Avaliação didática: um ataque disponível não é necessariamente a melhor escolha. O resumo não usa a mão oculta do adversário para julgar suas decisões.")
            if (state.deckLists.firstOrNull()?.size != 60) appendLine("Deck incompleto: as chances de compra diferem das de uma lista final de 60 cartas.")
        }
    }
}
