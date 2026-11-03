package com.tcgtrainer.engine

data class HandAnalysis(
    val threatScore: Double,
    val hasRareCandyCombo: Boolean,
    val hasLethalCombo: Boolean,
    val hasBoss: Boolean,
    val hasEnergy: Boolean,
    val hasSupporterDraw: Boolean,
    val isDeadHand: Boolean,
    val candidateCombos: List<String>
)

object PlayerHandAnalyzer {

    fun analyze(state: GameState, actor: Int, catalog: CardCatalog, oracleNextDraw: CardInstance? = null): HandAnalysis {
        val opponent = 1 - actor
        val player = state.players[opponent]
        val rawHand = player.hand + listOfNotNull(oracleNextDraw)
        val combos = mutableListOf<String>()

        val hasBoss = rawHand.any { card ->
            val def = catalog.get(card.definitionId)
            def.trainerEffect is TrainerEffect.Switch && (def.trainerEffect as TrainerEffect.Switch).opponent
        }
        if (hasBoss) combos += "Boss's Orders / Gust"

        val hasEnergy = rawHand.any { catalog.get(it.definitionId).category == Category.ENERGY }
        if (hasEnergy) combos += "Energy in hand"

        val hasRareCandy = rawHand.any {
            val def = catalog.get(it.definitionId)
            def.trainerEffect is TrainerEffect.RareCandy
        }
        val stage2Cards = rawHand.filter { catalog.get(it.definitionId).stage == Stage.STAGE_TWO }
        val ownBasics = player.pokemon().filter { catalog.get(it.top.definitionId).stage == Stage.BASIC }

        val hasRareCandyCombo = hasRareCandy && stage2Cards.any { s2 ->
            val s2Def = catalog.get(s2.definitionId)
            ownBasics.any { basic -> s2Def.canRareCandyEvolveFrom(catalog.get(basic.top.definitionId)) }
        }
        if (hasRareCandyCombo) combos += "Rare Candy Evolution combo"

        val hasSupporterDraw = rawHand.any {
            val def = catalog.get(it.definitionId)
            def.trainerType == TrainerType.SUPPORTER && (
                def.trainerEffect is TrainerEffect.DrawCards ||
                def.trainerEffect is TrainerEffect.ShuffleHandDraw ||
                def.trainerEffect is TrainerEffect.DrawTo
            )
        }

        // Calculate threat score (0 to 100)
        var score = 15.0
        if (hasEnergy) score += 20.0
        if (hasBoss && state.players[actor].bench.isNotEmpty()) score += 25.0
        if (hasRareCandyCombo) score += 30.0
        if (hasSupporterDraw && rawHand.size <= 4) score += 15.0

        // Check if hand enables lethal on the CPU's current field
        val cpuPrizesNeeded = state.players[opponent].prizes.size
        val hasAttackerWithEnergy = player.pokemon().any { p ->
            val pDef = catalog.get(p.top.definitionId)
            pDef.attacks.any { it.cost.size <= p.energies.size + (if (hasEnergy) 1 else 0) }
        }

        var hasLethalCombo = false
        if (hasAttackerWithEnergy) {
            val maxDamage = player.pokemon().maxOfOrNull { p ->
                val pDef = catalog.get(p.top.definitionId)
                pDef.attacks.filter { it.cost.size <= p.energies.size + (if (hasEnergy) 1 else 0) }
                    .maxOfOrNull { atk -> atk.effects.filterIsInstance<Effect.Damage>().maxOfOrNull { it.amount } ?: 0 } ?: 0
            } ?: 0

            val cpuActive = state.players[actor].active
            val cpuActiveHp = cpuActive?.let { catalog.get(it.top.definitionId).hp - it.damage } ?: 0
            val cpuActivePrizes = cpuActive?.let { catalog.get(it.top.definitionId).prizes } ?: 1

            if (maxDamage >= cpuActiveHp && cpuActivePrizes >= cpuPrizesNeeded) {
                hasLethalCombo = true
            }

            if (hasBoss && state.players[actor].bench.isNotEmpty()) {
                val lethalTarget = state.players[actor].bench.firstOrNull { b ->
                    val bHp = catalog.get(b.top.definitionId).hp - b.damage
                    val bPrizes = catalog.get(b.top.definitionId).prizes
                    maxDamage >= bHp && bPrizes >= cpuPrizesNeeded
                }
                if (lethalTarget != null) hasLethalCombo = true
            }
        }

        if (hasLethalCombo) {
            score = 100.0
            combos += "LETHAL COMBO"
        }

        val isDeadHand = !hasEnergy && !hasSupporterDraw && !hasRareCandy && !hasBoss && rawHand.size <= 3
        if (isDeadHand) score = 10.0

        return HandAnalysis(
            threatScore = score.coerceIn(0.0, 100.0),
            hasRareCandyCombo = hasRareCandyCombo,
            hasLethalCombo = hasLethalCombo,
            hasBoss = hasBoss,
            hasEnergy = hasEnergy,
            hasSupporterDraw = hasSupporterDraw,
            isDeadHand = isDeadHand,
            candidateCombos = combos
        )
    }
}

