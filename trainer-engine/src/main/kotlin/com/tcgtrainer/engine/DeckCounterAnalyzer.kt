package com.tcgtrainer.engine

data class ResourceExhaustion(
    val switchRemaining: Int,
    val bossRemaining: Int,
    val energyRemaining: Int,
    val recoveryRemaining: Int,
    val candyRemaining: Int,
    val boardLockOpportunity: Boolean,
    val lockTargetId: String?
)

object DeckCounterAnalyzer {

    fun analyzeDeck(state: GameState, actor: Int, catalog: CardCatalog): ResourceExhaustion {
        val opponent = 1 - actor
        val oppPlayer = state.players[opponent]
        val deckList = state.deckLists.getOrNull(opponent).orEmpty()

        if (deckList.isEmpty()) {
            return ResourceExhaustion(
                switchRemaining = 1,
                bossRemaining = 1,
                energyRemaining = 5,
                recoveryRemaining = 1,
                candyRemaining = 0,
                boardLockOpportunity = false,
                lockTargetId = null
            )
        }

        // Count totals in decklist
        var totalSwitch = 0
        var totalBoss = 0
        var totalEnergy = 0
        var totalRecovery = 0
        var totalCandy = 0

        for (cardId in deckList) {
            val def = catalog.get(cardId)
            val effect = def.trainerEffect
            if (effect is TrainerEffect.Switch) {
                if (effect.opponent) totalBoss++ else totalSwitch++
            }
            if (def.category == Category.ENERGY) totalEnergy++
            if (effect is TrainerEffect.RareCandy) totalCandy++
            if (effect is TrainerEffect.Recover || effect is TrainerEffect.RecoverPokemonAndBasicEnergy || effect is TrainerEffect.RecoverEnergy) {
                totalRecovery++
            }
        }

        // Subtract used / visible cards
        val usedCards = oppPlayer.discard + oppPlayer.hand + oppPlayer.pokemon().flatMap { it.cards + it.energies + it.tools }

        var usedSwitch = 0
        var usedBoss = 0
        var usedEnergy = 0
        var usedRecovery = 0
        var usedCandy = 0

        for (card in usedCards) {
            if (card.definitionId == "cpu-hidden-card") continue
            val def = catalog.get(card.definitionId)
            val effect = def.trainerEffect
            if (effect is TrainerEffect.Switch) {
                if (effect.opponent) usedBoss++ else usedSwitch++
            }
            if (def.category == Category.ENERGY) usedEnergy++
            if (effect is TrainerEffect.RareCandy) usedCandy++
            if (effect is TrainerEffect.Recover || effect is TrainerEffect.RecoverPokemonAndBasicEnergy || effect is TrainerEffect.RecoverEnergy) {
                usedRecovery++
            }
        }

        val switchRem = (totalSwitch - usedSwitch).coerceAtLeast(0)
        val bossRem = (totalBoss - usedBoss).coerceAtLeast(0)
        val energyRem = (totalEnergy - usedEnergy).coerceAtLeast(0)
        val recoveryRem = (totalRecovery - usedRecovery).coerceAtLeast(0)
        val candyRem = (totalCandy - usedCandy).coerceAtLeast(0)

        // Check Board Lock opportunity: if switchRemaining == 0 and bench has heavy retreat mon with 0-1 energy
        var lockTarget: String? = null
        var lockOpportunity = false

        if (switchRem == 0 && oppPlayer.bench.isNotEmpty()) {
            val heaviestBenched = oppPlayer.bench
                .filter { catalog.get(it.top.definitionId).retreatCost >= 2 && it.energies.size < catalog.get(it.top.definitionId).retreatCost }
                .maxByOrNull { catalog.get(it.top.definitionId).retreatCost - it.energies.size }

            if (heaviestBenched != null) {
                lockOpportunity = true
                lockTarget = heaviestBenched.id
            }
        }

        return ResourceExhaustion(
            switchRemaining = switchRem,
            bossRemaining = bossRem,
            energyRemaining = energyRem,
            recoveryRemaining = recoveryRem,
            candyRemaining = candyRem,
            boardLockOpportunity = lockOpportunity,
            lockTargetId = lockTarget
        )
    }

    fun calculateOuts(
        state: GameState,
        actor: Int,
        catalog: CardCatalog,
        predicate: (CardDefinition) -> Boolean
    ): Pair<Int, Double> {
        val opponent = 1 - actor
        val oppPlayer = state.players[opponent]
        val deckList = state.deckLists.getOrNull(opponent).orEmpty()
        if (deckList.isEmpty()) return 0 to 0.0

        val usedCards = (oppPlayer.discard + oppPlayer.hand + oppPlayer.pokemon().flatMap { it.cards + it.energies + it.tools })
            .filter { it.definitionId != "cpu-hidden-card" }
            .map { it.definitionId }

        val remainingInDeck = deckList.toMutableList()
        for (used in usedCards) {
            remainingInDeck.remove(used)
        }

        val outs = remainingInDeck.count { predicate(catalog.get(it)) }
        val deckSize = oppPlayer.deck.size.coerceAtLeast(1)
        val odds = (outs.toDouble() / deckSize).coerceIn(0.0, 1.0)
        return outs to odds
    }
}

