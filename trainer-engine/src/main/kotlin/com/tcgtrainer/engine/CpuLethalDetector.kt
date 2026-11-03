package com.tcgtrainer.engine

/** Checks proven terminal outcomes, including KOs from preparation effects.
 * The caller supplies a masked simulation after a deterministic legal transition.
 * Prize identities are irrelevant to mandatory collection; both players' owed prizes
 * are resolved by the rules, including simultaneous wins. Never speculate past a choice.
 */
internal class CpuLethalDetector(private val engine: RulesEngine, private val budget: CpuSearchBudget) {
    fun provenWin(initial: GameState, actor: Int): Boolean {
        var state = initial
        val maxCollections = initial.players.sumOf { it.prizes.size }
        for (step in 0..maxCollections) {
            budget.check()
            if (state.phase == Phase.GAME_OVER) return state.winner == actor
            if (state.phase != Phase.SELECT_PRIZES) return false
            val owner = engine.actingPlayer(state)
            val prize = state.players[owner].prizes.firstOrNull() ?: return false
            val result = engine.apply(state, GameAction.TakePrize(prize.id))
            if (result.legality != Legality.Legal || result.state.rng != state.rng) return false
            state = result.state
        }
        return false
    }
}
