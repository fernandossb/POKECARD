package com.tcgtrainer.engine

/** Bounded analysis of legal, known-card continuations. Never rolls an attack to select lucky futures. */
internal class DecisionReview(private val engine: RulesEngine) {
    internal data class Plan(val steps: List<String>, val value: Int, val damage: Int, val prizes: Int,
        val target: String, val certainKnockout: Boolean, val wins: Boolean, val uncertain: Boolean,
        val action: GameAction.Attack) {
        fun outcome(): String = when {
            wins -> "encerrar a partida neste turno ao obter os $prizes Prêmio(s) restantes"
            certainKnockout -> "nocautear $target e obter $prizes Prêmio(s)"
            else -> "causar cerca de $damage de dano em $target${if (uncertain) " (dependia de moedas ou outros resultados incertos)" else ""}"
        }
    }
    private data class Node(val state: GameState, val steps: List<String>)

    fun attack(state: GameState, action: GameAction.Attack, steps: List<String> = emptyList()): Plan? {
        val source = state.players[0].active ?: return null
        val target = state.players[1].pokemon().firstOrNull { it.id == action.targetId } ?: state.players[1].active ?: return null
        val preview = engine.preview(state, action.attackId, action.targetId) ?: return null
        val definition = engine.definition(source.top).attacks.firstOrNull { it.id == action.attackId }
        val damage = minOf(preview.damage, (preview.effectiveHp-preview.currentDamage).coerceAtLeast(0)).coerceAtLeast(0)
        val uncertain = preview.uncertain || SpecialCondition.CONFUSED in source.conditions || source.defendingAttackFailsOnTailsTurn == state.turn
        val ko = preview.knockout && !uncertain
        val prizes = if (ko) preview.prizes else 0
        val specialWin = definition?.effects?.contains(Effect.WinIfOnePrizeRemaining) == true && state.players[0].prizes.size == 1 && !uncertain
        val wins = (ko && prizes >= state.players[0].prizes.size) || specialWin
        val control = if (definition?.effects?.contains(Effect.LockOpponentItemsNextTurn) == true && state.players[1].hand.isNotEmpty()) 70 else 0
        val selfDamage = definition?.effects.orEmpty().sumOf { when(it) { is Effect.Recoil -> it.damage; is Effect.SelfDamage -> it.amount; else -> 0 } }
        val risk = if (selfDamage > 0 && selfDamage >= engine.effectiveHp(state, source)-source.damage) engine.definition(source.top).prizes*200 else 0
        val value = damage + prizes*400 + (if (ko) 200 else 0) + (if (wins) 20_000 else 0) + control - risk
        return Plan(steps + "usar ${definition?.name ?: action.attackId} de ${engine.definition(source.top).name} em ${engine.definition(target.top).name}",
            value, damage, if (specialWin) 1 else prizes, engine.definition(target.top).name, ko, wins, uncertain, action)
    }

    fun best(initial: GameState): Plan? {
        if (engine.actingPlayer(initial) != 0 || initial.phase !in listOf(Phase.MAIN, Phase.RESOLVE_CHOICE)) return null
        var frontier = listOf(Node(initial, emptyList()))
        var best: Plan? = null
        var examined = 0
        // Five steps permit Energy + Trainer + target choice + attack, without unbounded turn search.
        repeat(5) {
            val next = mutableListOf<Node>()
            for (node in frontier) {
                if (examined >= 160) break
                val state = node.state
                if (engine.actingPlayer(state) != 0) continue
                val actions = if (state.phase == Phase.RESOLVE_CHOICE) choiceActions(state) else engine.legalActions(state)
                for (action in actions.filterIsInstance<GameAction.Attack>()) {
                    val line = attack(state, action, node.steps) ?: continue
                    val incumbent = best
                    if (incumbent == null || line.value > incumbent.value || line.value == incumbent.value && line.steps.size < incumbent.steps.size) best = line
                }
                val preparations = actions.filter { it is GameAction.AttachEnergy || it is GameAction.PlayTrainer ||
                    it is GameAction.UseAbility || it is GameAction.Evolve || it is GameAction.Retreat ||
                    it is GameAction.PlayBasic || it is GameAction.ChooseCards }
                    .sortedByDescending { priority(it) }.take(28)
                for (action in preparations) {
                    if (++examined > 160) break
                    val transition = engine.apply(state, action)
                    if (transition.legality != Legality.Legal || !knownContinuation(state, transition.state, action)) continue
                    next += Node(transition.state, node.steps + label(state, action))
                }
            }
            frontier = next.sortedByDescending { node ->
                if (node.state.phase == Phase.RESOLVE_CHOICE) 80 else
                    engine.legalActions(node.state).filterIsInstance<GameAction.Attack>().maxOfOrNull { attack(node.state, it)?.value ?: 0 } ?: 0
            }.distinctBy { it.steps }.take(8)
            if (frontier.isEmpty()) return best
        }
        return best
    }

    internal fun knownContinuation(before: GameState, after: GameState, action: GameAction): Boolean {
        if (after.turn != before.turn || after.currentPlayer != 0 || after.phase == Phase.GAME_OVER) return false
        // Shuffles, coin outcomes and random effects cannot justify a guaranteed counterfactual.
        if (before.rng != after.rng) return false
        // Revealing a deck top is new information, even when no random number was consumed.
        val deckIds = before.players.flatMap { it.deck }.map { it.id }.toSet()
        if (after.resolution?.choices.orEmpty().any { choice -> choice.visibleCards.any { it in deckIds } || choice.candidates.any { it in deckIds } }) return false
        val known = before.players[0].hand + before.players[0].discard + before.players[0].pokemon().flatMap { it.cards + it.energies + it.tools }
        val selected = (action as? GameAction.ChooseCards)?.cardIds.orEmpty()
        if (after.players[0].hand.any { card -> known.none { it.id == card.id } && card.id !in selected }) return false
        return after.phase in listOf(Phase.MAIN, Phase.RESOLVE_CHOICE)
    }

    private fun choiceActions(state: GameState): List<GameAction> {
        val choice = state.resolution?.choices?.firstOrNull() ?: return emptyList()
        // Do not base a plan on the identity of still-hidden opponent cards or deck-top peeks.
        val forbidden = state.players[1].hand.map { it.id }.toSet() + state.players[1].deck.map { it.id }
        if (choice.candidates.any { it in forbidden }) return emptyList()
        val actions = mutableListOf<GameAction>()
        // Review already searches continuations; avoid nesting the complete master search at every choice node.
        runCatching { CpuPlayer(engine.catalogForReview(), CpuStrategy.HEURISTIC).choose(engine.cpuInput(state)) }.getOrNull()?.let(actions::add)
        if (choice.min == 0) actions += GameAction.ChooseCards(emptyList())
        if (choice.min <= 1 && choice.max >= 1) choice.candidates.take(20).forEach { actions += GameAction.ChooseCards(listOf(it)) }
        return actions.distinct().filter { engine.validate(state, it) == Legality.Legal }
    }

    private fun priority(action: GameAction): Int = when(action) {
        is GameAction.AttachEnergy -> 100
        is GameAction.Evolve -> 95
        is GameAction.UseAbility -> 90
        is GameAction.PlayTrainer -> 85
        is GameAction.ChooseCards -> 100
        is GameAction.Retreat -> 80
        else -> 20
    }

    fun label(state: GameState, action: GameAction): String {
        fun name(id: String): String = state.players.flatMap { it.hand + it.discard + it.deck + it.pokemon().flatMap { p -> p.cards + p.energies + p.tools } }
            .firstOrNull { it.id == id }?.let { engine.definition(it).name } ?: id
        fun pokemon(id: String) = state.players.flatMap { it.pokemon() }.firstOrNull { it.id == id }?.let { engine.definition(it.top).name } ?: id
        return when(action) {
            is GameAction.AttachEnergy -> "ligar ${name(action.cardId)} a ${pokemon(action.pokemonId)}"
            is GameAction.PlayTrainer -> "jogar ${name(action.cardId)}"
            is GameAction.Evolve -> "evoluir ${pokemon(action.pokemonId)} para ${name(action.cardId)}"
            is GameAction.Retreat -> "recuar e promover ${pokemon(action.pokemonId)}"
            is GameAction.PlayBasic -> "colocar ${name(action.cardId)} no Banco"
            is GameAction.UseAbility -> "usar ${state.players[0].pokemon().firstOrNull { it.id == action.pokemonId }?.let { engine.definition(it.top).abilities.firstOrNull { a -> a.id == action.abilityId }?.name } ?: "a habilidade"} de ${pokemon(action.pokemonId)}"
            is GameAction.ChooseCards -> if (state.resolution?.choices?.firstOrNull()?.kind == ChoiceKind.TOOL_TARGET && action.cardIds.size == 1)
                "ligar ${state.resolution.toolCard?.let { engine.definition(it).name } ?: "a Ferramenta"} a ${pokemon(action.cardIds.single())}"
                else if (action.cardIds.isEmpty()) "concluir a escolha opcional" else "escolher ${action.cardIds.joinToString { id -> state.players.flatMap { it.pokemon() }.firstOrNull { it.id == id }?.let { engine.definition(it.top).name } ?: name(id) }}"
            is GameAction.Attack -> attack(state, action)?.steps?.lastOrNull() ?: "atacar"
            GameAction.EndTurn -> "encerrar o turno"
            else -> "resolver a jogada"
        }
    }
}

private fun RulesEngine.catalogForReview(): CardCatalog = object : CardCatalog {
    override fun get(id: String): CardDefinition = definition(CardInstance("review-$id", id))
}
