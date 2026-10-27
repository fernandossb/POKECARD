package com.tcgtrainer.engine

object TrainingReview {
    private data class Finding(val score: Int, val text: String)
    private data class AttackLine(
        val action: GameAction.Attack,
        val attackName: String,
        val targetName: String,
        val damage: Int,
        val remainingHp: Int,
        val knockout: Boolean,
        val prizes: Int,
        val value: Int
    )

    fun describe(state: GameState): String {
        val ownRecords = state.history.filter { it.player == 0 && it.turn > 0 }
        val attacks = ownRecords.count { it.action is GameAction.Attack }
        val strengths = mutableListOf<Finding>()
        val errors = mutableListOf<Finding>()
        var replayed = false

        if (state.deckLists.size == 2) runCatching {
            val engine = RulesEngine(TrainerCatalog)
            var replay = engine.newGame(state.seed, state.deckLists, state.mode)
            for (record in state.history) {
                val before = replay
                val transition = engine.apply(before, record.action)
                check(transition.legality == Legality.Legal)
                val after = transition.state

                if (record.player == 0 && record.turn > 0) {
                    when (val action = record.action) {
                        is GameAction.Attack -> reviewAttack(engine, before, after, record, action, strengths, errors)
                        GameAction.EndTurn -> reviewEndTurn(engine, before, record, errors)
                        is GameAction.AttachEnergy -> reviewEnergyAttachment(engine, before, after, record, action, strengths)
                        is GameAction.PlayTrainer -> reviewTrainer(engine, before, record, action, strengths)
                        is GameAction.Evolve -> reviewEvolution(engine, before, after, record, action, strengths)
                        else -> Unit
                    }
                }
                replay = after
            }
            replayed = true
        }.onFailure {
            strengths.clear()
            errors.clear()
        }

        val principalPositive = strengths.maxByOrNull { it.score }
        val principalError = errors.maxByOrNull { it.score }
        return buildString {
            appendLine("RESUMO DO TREINO")
            appendLine(state.winReason ?: "Partida encerrada.")
            appendLine("Você realizou $attacks ataque(s).")
            appendLine("\nPONTO POSITIVO PRINCIPAL")
            appendLine(principalPositive?.text ?: "Não encontrei no histórico uma jogada decisiva que dê para confirmar como ponto forte.")
            appendLine("\nPRINCIPAL ERRO OU OPORTUNIDADE PERDIDA")
            appendLine(principalError?.text ?: "Não encontrei uma oportunidade tática clara que possa ser apontada como erro.")
            if (!replayed) appendLine("\nNão foi possível reconstruir todas as decisões da partida; por isso, a análise foi limitada.")
            appendLine("\nA análise considera o estado visível do jogo e as jogadas legais daquele turno; não presume informações ocultas da mão adversária.")
            if (state.deckLists.firstOrNull()?.size != 60) appendLine("Deck incompleto: as chances de compra diferem das de uma lista final de 60 cartas.")
        }
    }

    private fun reviewAttack(
        engine: RulesEngine,
        before: GameState,
        after: GameState,
        record: ActionRecord,
        action: GameAction.Attack,
        strengths: MutableList<Finding>,
        errors: MutableList<Finding>
    ) {
        val attacker = before.players[0].active ?: return
        val opponent = before.players[1]
        val target = opponent.pokemon().firstOrNull { it.id == action.targetId } ?: opponent.active ?: return
        val attack = engine.definition(attacker.top).attacks.firstOrNull { it.id == action.attackId }
        val preview = engine.preview(before, action.attackId, action.targetId) ?: return
        val targetName = engine.definition(target.top).name
        val attackName = attack?.name ?: "ataque"
        val prizeDelta = (after.prizesOwed[0] - before.prizesOwed[0]).coerceAtLeast(0)
        val knockedOut = record.events.any { it.type == "PokemonKnockedOut" && it.message.contains(targetName, ignoreCase = true) } || prizeDelta > 0
        val appliedDamage = record.events.firstOrNull { it.type == "AttackDeclared" }?.amount ?: preview.damage
        val hpLeft = (engine.effectiveHp(before, target) - target.damage).coerceAtLeast(0)
        val damageToTarget = minOf(appliedDamage.coerceAtLeast(0), hpLeft)
        val remainingHp = if (knockedOut) 0 else (hpLeft - damageToTarget).coerceAtLeast(0)
        val prizeCount = if (knockedOut) maxOf(preview.prizes, prizeDelta, engine.definition(target.top).prizes) else 0
        val statusApplied = record.events.any { it.type == "SpecialCondition" && it.message.contains(targetName, ignoreCase = true) } ||
            after.players[1].pokemon().firstOrNull { it.id == target.id }?.conditions.orEmpty().any { it !in target.conditions }
        val actualValue = tacticalValue(damageToTarget, knockedOut, prizeCount) + if (statusApplied) 50 else 0

        val alternatives = bestAttackLines(engine, before)
        val best = alternatives.maxByOrNull { it.value }
        if (knockedOut) {
            strengths += Finding(1_000 + prizeCount * 100 + damageToTarget,
                "T${record.turn}: ${engine.definition(attacker.top).name} usou $attackName e nocauteou $targetName, rendendo $prizeCount Prêmio(s).")
        } else if (best?.action == action && damageToTarget >= 40) {
            strengths += Finding(200 + actualValue,
                "T${record.turn}: $attackName foi sua melhor opção de ataque e aplicou $damageToTarget de dano em $targetName, deixando-o com $remainingHp HP.")
        } else if (statusApplied && best?.action == action) {
            val condition = record.events.firstOrNull { it.type == "SpecialCondition" }?.message.orEmpty()
            strengths += Finding(180 + actualValue, "T${record.turn}: $attackName aplicou uma condição especial útil em $targetName. $condition")
        }

        val stronger = best?.takeIf { it.action != action && it.value >= actualValue + 90 }
        if (stronger != null && !knockedOut) {
            val reason = if (stronger.knockout) "podia nocautear ${stronger.targetName} e render ${stronger.prizes} Prêmio(s)"
                else "podia causar ${stronger.damage} de dano a ${stronger.targetName}"
            errors += Finding((stronger.value - actualValue) + if (stronger.knockout) 400 else 0,
                "T${record.turn}: escolheu $attackName em $targetName, mas ${stronger.attackName} era a linha de maior impacto: $reason. A jogada escolhida aplicou $damageToTarget de dano.")
        }
    }

    private fun reviewEndTurn(engine: RulesEngine, state: GameState, record: ActionRecord, errors: MutableList<Finding>) {
        val bestDirectAttack = bestAttackLines(engine, state).maxByOrNull { it.value }
        if (bestDirectAttack != null && (bestDirectAttack.knockout || bestDirectAttack.damage >= 100)) {
            val outcome = if (bestDirectAttack.knockout) "nocauteava ${bestDirectAttack.targetName} e rendia ${bestDirectAttack.prizes} Prêmio(s)"
                else causation(bestDirectAttack)
            errors += Finding(if (bestDirectAttack.knockout) 1_000 + bestDirectAttack.prizes * 100 else 300 + bestDirectAttack.value,
                "T${record.turn}: encerrou o turno sem atacar, embora ${bestDirectAttack.attackName} pudesse $outcome.")
            return
        }

        // Look for a concrete missed setup: a still-legal manual Energy attachment that unlocked a high-value attack.
        val legal = engine.legalActions(state)
        val setupLine = legal.filterIsInstance<GameAction.AttachEnergy>().asSequence().mapNotNull { attach ->
            val attached = engine.apply(state, attach).takeIf { it.legality == Legality.Legal }?.state ?: return@mapNotNull null
            bestAttackLines(engine, attached).maxByOrNull { it.value }?.let { attach to it }
        }.filter { (_, line) -> line.knockout || line.damage >= 100 }
            .maxByOrNull { (_, line) -> line.value }
        if (setupLine != null) {
            val (attach, line) = setupLine
            val energyName = state.players[0].hand.firstOrNull { it.id == attach.cardId }?.let(engine::definition)?.name ?: "Energia"
            val outcome = if (line.knockout) "e poderia nocautear ${line.targetName} por ${line.prizes} Prêmio(s)"
                else "e liberaria ${line.attackName}, com ${line.damage} de dano previsto em ${line.targetName}"
            errors += Finding(250 + line.value,
                "T${record.turn}: encerrou sem atacar e sem usar a ligação manual de Energia. Ligar $energyName a ${linePokemonName(engine, state, attach.pokemonId)} ainda era legal $outcome.")
        }
    }

    private fun reviewEnergyAttachment(
        engine: RulesEngine,
        before: GameState,
        after: GameState,
        record: ActionRecord,
        action: GameAction.AttachEnergy,
        strengths: MutableList<Finding>
    ) {
        val beforeAttacks = bestAttackLines(engine, before).associateBy { it.action.attackId to it.action.targetId }
        val newlyEnabled = bestAttackLines(engine, after).filter { line ->
            val old = beforeAttacks[line.action.attackId to line.action.targetId]
            (old == null || old.damage == 0) && (line.knockout || line.damage >= 70)
        }.maxByOrNull { it.value } ?: return
        val pokemon = after.players[0].pokemon().firstOrNull { it.id == action.pokemonId } ?: return
        val targetOutcome = if (newlyEnabled.knockout) "nocauteava ${newlyEnabled.targetName}"
            else causation(newlyEnabled)
        strengths += Finding(260 + newlyEnabled.value,
            "T${record.turn}: a Energia ligada a ${engine.definition(pokemon.top).name} habilitou ${newlyEnabled.attackName}, que podia $targetOutcome.")
    }

    private fun reviewTrainer(
        engine: RulesEngine,
        before: GameState,
        record: ActionRecord,
        action: GameAction.PlayTrainer,
        strengths: MutableList<Finding>
    ) {
        val card = before.players[0].hand.firstOrNull { it.id == action.cardId } ?: return
        val cardName = engine.definition(card).name
        val ownDraws = record.events.filter { it.type == "CardsDrawn" && it.message.startsWith("Comprou") }.sumOf { it.amount ?: 0 }
        if (ownDraws >= 2) strengths += Finding(320 + ownDraws,
            "T${record.turn}: $cardName gerou $ownDraws cartas para a sua mão, aumentando as opções ainda neste turno.")
        val healing = record.events.filter { it.type == "DamageHealed" }.sumOf { it.amount ?: 0 }
        if (healing >= 40) strengths += Finding(240 + healing,
            "T${record.turn}: $cardName curou $healing de dano dos seus Pokémon.")
        val attached = record.events.filter { it.type.contains("Energy", ignoreCase = true) && it.type.contains("Attach", ignoreCase = true) }
            .sumOf { it.amount ?: 0 }
        if (attached >= 2) strengths += Finding(230 + attached,
            "T${record.turn}: $cardName acelerou a preparação, ligando $attached Energias.")
    }

    private fun reviewEvolution(
        engine: RulesEngine,
        before: GameState,
        after: GameState,
        record: ActionRecord,
        action: GameAction.Evolve,
        strengths: MutableList<Finding>
    ) {
        val evolved = after.players[0].pokemon().firstOrNull { pokemon -> pokemon.cards.any { it.id == action.cardId } } ?: return
        val newlyEnabled = bestAttackLines(engine, after).filter { line ->
            bestAttackLines(engine, before).none { old -> old.action.attackId == line.action.attackId && old.damage > 0 } &&
                (line.knockout || line.damage >= 70)
        }.maxByOrNull { it.value } ?: return
        val targetOutcome = if (newlyEnabled.knockout) "ameaçava nocautear ${newlyEnabled.targetName}"
            else causation(newlyEnabled)
        strengths += Finding(280 + newlyEnabled.value,
            "T${record.turn}: evoluir para ${engine.definition(evolved.top).name} liberou ${newlyEnabled.attackName}, que $targetOutcome.")
    }

    private fun bestAttackLines(engine: RulesEngine, state: GameState): List<AttackLine> {
        if (state.currentPlayer != 0 || state.phase != Phase.MAIN) return emptyList()
        val attacker = state.players[0].active ?: return emptyList()
        val opponent = state.players[1]
        return engine.legalActions(state).filterIsInstance<GameAction.Attack>().mapNotNull { action ->
            val target = opponent.pokemon().firstOrNull { it.id == action.targetId } ?: opponent.active ?: return@mapNotNull null
            val preview = engine.preview(state, action.attackId, action.targetId) ?: return@mapNotNull null
            val damage = minOf(preview.damage.coerceAtLeast(0), (preview.effectiveHp - preview.currentDamage).coerceAtLeast(0))
            val isKnockout = preview.knockout && preview.damage > 0
            val prizes = if (isKnockout) maxOf(preview.prizes, engine.definition(target.top).prizes) else 0
            val name = engine.definition(attacker.top).attacks.firstOrNull { it.id == action.attackId }?.name ?: "ataque"
            AttackLine(action, name, engine.definition(target.top).name, damage,
                (preview.effectiveHp - preview.currentDamage - damage).coerceAtLeast(0), isKnockout, prizes,
                tacticalValue(damage, isKnockout, prizes))
        }
    }

    private fun tacticalValue(damage: Int, knockout: Boolean, prizes: Int): Int =
        if (knockout) 300 + prizes * 160 + damage / 3 else damage

    private fun causation(line: AttackLine): String =
        "causar ${line.damage} de dano em ${line.targetName}, deixando-o com ${line.remainingHp} HP"

    private fun linePokemonName(engine: RulesEngine, state: GameState, pokemonId: String): String =
        state.players[0].pokemon().firstOrNull { it.id == pokemonId }?.let { engine.definition(it.top).name } ?: "o Pokémon escolhido"
}
