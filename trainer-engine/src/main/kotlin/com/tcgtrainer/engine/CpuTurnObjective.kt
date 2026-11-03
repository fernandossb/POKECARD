package com.tcgtrainer.engine

enum class CpuTurnObjective {
    WIN_NOW,
    KNOCKOUT_TARGET,
    PREPARE_ATTACK,
    FIND_MISSING_ENERGY,
    FIND_ATTACKER,
    EVOLVE_ATTACKER,
    DRAW_REQUIRED_CARDS,
    PREPARE_BACKUP_ATTACKER,
    DISRUPT_OPPONENT,
    DEFEND_AGAINST_LETHAL,
    EXECUTE_ATTACK,
    TAKE_PRIZES,
    GUST_TARGET,
    HAND_DISRUPTION,
    BUILD_ATTACKER,
    SETUP_ENGINE,
    PASSIVE_DEVELOPMENT;
}

data class CpuTurnPlan(
    val objective: CpuTurnObjective,
    val plannedSequence: List<GameAction>,
    val targetPokemonId: String? = null,
    val chosenAttackerId: String? = null,
    val initialTurn: Int = -1,
    val actor: Int = 1,
    var stepIndex: Int = 0,
    val searchCardsUsed: MutableList<String> = mutableListOf(),
    val drawCardsUsed: MutableList<String> = mutableListOf(),
    var energyAttached: Boolean = false,
    var executedAttack: GameAction.Attack? = null,
    var attackDamageDealt: Int = 0,
    var attackName: String? = null,
    var targetName: String? = null,
    var endTurnReason: String? = null,
    var noAttackReason: String? = null,
    var bestAttackScore: Double? = null,
    var bestNoAttackScore: Double? = null,
    var lethalThreatDescription: String? = null
) {
    val turn: Int get() = initialTurn
    val sequence: List<GameAction> get() = plannedSequence

    fun isComplete(): Boolean = stepIndex >= plannedSequence.size
    fun nextAction(): GameAction? = plannedSequence.getOrNull(stepIndex)
    fun advance(): GameAction? = plannedSequence.getOrNull(stepIndex++)

    fun nextAction(state: GameState, engine: RulesEngine): GameAction? {
        while (stepIndex < plannedSequence.size) {
            val action = plannedSequence[stepIndex]
            if (engine.validate(state, action) == Legality.Legal) {
                stepIndex++
                return action
            }
            stepIndex++
        }
        return null
    }

    fun isCompleted(state: GameState, actor: Int): Boolean = when (objective) {
        CpuTurnObjective.WIN_NOW, CpuTurnObjective.TAKE_PRIZES -> state.phase == Phase.GAME_OVER && state.winner == actor
        CpuTurnObjective.KNOCKOUT_TARGET -> targetPokemonId != null && state.players[1 - actor].pokemon().none { it.id == targetPokemonId }
        CpuTurnObjective.DEFEND_AGAINST_LETHAL -> true
        CpuTurnObjective.PREPARE_ATTACK, CpuTurnObjective.BUILD_ATTACKER -> chosenAttackerId != null && state.players[actor].pokemon().any { it.id == chosenAttackerId && it.energies.isNotEmpty() }
        CpuTurnObjective.EXECUTE_ATTACK -> executedAttack != null || state.turn > initialTurn
        CpuTurnObjective.DISRUPT_OPPONENT, CpuTurnObjective.HAND_DISRUPTION, CpuTurnObjective.GUST_TARGET -> true
        CpuTurnObjective.FIND_ATTACKER -> chosenAttackerId != null || (targetPokemonId != null && state.players[actor].hand.any { it.definitionId == targetPokemonId })
        CpuTurnObjective.EVOLVE_ATTACKER, CpuTurnObjective.SETUP_ENGINE -> chosenAttackerId != null && state.players[actor].pokemon().any { it.id == chosenAttackerId && it.cards.size > 1 }
        CpuTurnObjective.FIND_MISSING_ENERGY -> energyAttached
        CpuTurnObjective.DRAW_REQUIRED_CARDS -> drawCardsUsed.isNotEmpty()
        CpuTurnObjective.PREPARE_BACKUP_ATTACKER -> state.players[actor].bench.any { it.energies.isNotEmpty() }
        CpuTurnObjective.PASSIVE_DEVELOPMENT -> true
    }
}

