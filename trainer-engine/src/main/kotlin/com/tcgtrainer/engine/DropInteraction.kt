package com.tcgtrainer.engine

/** The controller translates a spatial intent into ordinary, validated game actions. No coordinates or UI dependencies. */
sealed interface BoardTarget {
    val player: Int
    data class Active(override val player: Int) : BoardTarget
    data class Bench(override val player: Int) : BoardTarget
    data class Pokemon(override val player: Int, val pokemonId: String) : BoardTarget
    data class Activation(override val player: Int) : BoardTarget
    data class Stadium(override val player: Int) : BoardTarget
}
sealed interface DropDecision {
    data class Accepted(val actions: List<GameAction>, val label: String) : DropDecision
    data class Rejected(val reason: String) : DropDecision
}
class DropInteraction(private val rules: RulesEngine) {
    fun resolve(state: GameState, cardId: String, target: BoardTarget): DropDecision {
        if (target.player !in 0..1) return DropDecision.Rejected("Destino inválido.")
        val actor = rules.actingPlayer(state)
        val player = state.players[actor]
        val card = player.hand.find { it.id == cardId } ?: return DropDecision.Rejected("Esta carta não está na sua mão.")
        val d = rules.definition(card)
        val pokemonId = when (target) {
            is BoardTarget.Pokemon -> target.pokemonId
            is BoardTarget.Active -> state.players[target.player].active?.id
            else -> null
        }
        val first = when (d.category) {
            Category.ENERGY -> if (pokemonId != null) GameAction.AttachEnergy(cardId, pokemonId)
                else return DropDecision.Rejected("Solte a Energia sobre um dos seus Pokémon.")
            Category.POKEMON -> when {
                d.stage != Stage.BASIC && pokemonId != null -> GameAction.Evolve(cardId, pokemonId)
                d.stage == Stage.BASIC && target is BoardTarget.Active && target.player == actor -> GameAction.ChooseActive(cardId)
                d.stage == Stage.BASIC && target is BoardTarget.Bench && target.player == actor -> GameAction.PlayBasic(cardId)
                else -> return DropDecision.Rejected(if (d.stage == Stage.BASIC) "Solte o Básico no seu Banco (ou no Ativo durante a preparação)." else "Solte a evolução sobre o Pokémon que deseja evoluir.")
            }
            Category.TRAINER -> {
                if (target.player != actor && (d.trainerEffect as? TrainerEffect.Switch)?.opponent != true)
                    return DropDecision.Rejected("Ative este Treinador no seu lado da mesa.")
                GameAction.PlayTrainer(cardId)
            }
        }
        val legality = rules.validate(state, first)
        if (legality is Legality.Illegal) return DropDecision.Rejected(legality.reason)
        val actions = mutableListOf<GameAction>(first)
        // Dropping a Tool or switch effect directly onto a valid Pokémon supplies that explicit target.
        // Costs/searches never receive an automatic selection.
        if (d.category == Category.TRAINER && pokemonId != null) {
            val intermediate = rules.apply(state, first).state
            val choice = intermediate.resolution?.choices?.firstOrNull()
            if (choice?.kind in listOf(ChoiceKind.TOOL_TARGET, ChoiceKind.SWITCH_OWN, ChoiceKind.SWITCH_OPPONENT) && pokemonId in choice!!.candidates) {
                val follow = GameAction.ChooseCards(listOf(pokemonId))
                if (rules.validate(intermediate, follow) == Legality.Legal) actions += follow
            }
        }
        return DropDecision.Accepted(actions, when (first) {
            is GameAction.AttachEnergy -> "LIGAR ENERGIA"
            is GameAction.Evolve -> "EVOLUIR"
            is GameAction.ChooseActive -> "POKÉMON ATIVO"
            is GameAction.PlayBasic -> "COLOCAR NO BANCO"
            else -> if (d.trainerType == TrainerType.STADIUM) "JOGAR ESTÁDIO" else "ATIVAR ${d.name.uppercase()}"
        })
    }
}
