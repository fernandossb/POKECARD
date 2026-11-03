package com.tcgtrainer.engine

/** Conditional exposure to gust, not a prediction that the opponent holds or will draw gust. */
internal class CpuBenchSafety(private val engine: RulesEngine, catalog: CardCatalog) {
    private val forecast = CpuForecast(catalog)

    fun exposesFinalPrizes(state: GameState, owner: Int, target: PokemonInstance): Boolean {
        val opponent = 1-owner
        if (state.players[opponent].prizes.size !in 1..engine.definition(target.top).prizes) return false
        val source = state.players[opponent].active ?: return false
        if (SpecialCondition.CONFUSED in source.conditions || source.defendingAttackFailsOnTailsTurn == state.turn+1) return false
        val hypothetical = state.copy(currentPlayer = opponent, turn = state.turn+1, phase = Phase.MAIN, resolution = null,
            players = state.players.mapIndexed { index, player -> if (index == owner)
                player.copy(active = target, bench = player.pokemon().filter { it.id != target.id })
            else player.copy(flags = TurnFlags()) })
        // Never inspect actual hidden cards, even when called with a controller state in a test.
        val frame = CpuTurnSearch.publicPosition(hypothetical, owner)?.state ?: return false
        return engine.availableAttacks(frame, opponent, source).any { attack ->
            val action = GameAction.Attack(attack.id)
            engine.validate(frame, action) == Legality.Legal && forecast.preview(frame, action)?.let {
                it.knockout && !it.uncertain && it.prizes >= frame.players[opponent].prizes.size
            } == true
        }
    }
}
