package com.tcgtrainer.engine

/** No GameState, RNG, hidden prizes, opponent hand or deck order crosses this boundary. */
data class CpuInput(
    val observation: PlayerObservation, val phase: Phase, val turn: Int, val flags: TurnFlags,
    val choice: ChoiceRequest?, val selectableCards: List<CardInstance>,
    val actions: List<GameAction>, val previews: Map<GameAction.Attack, AttackPreview>,
    val stadium: CardInstance?
)

/** Controller adapter: reveals search results only while the rules permit that search. */
fun RulesEngine.cpuInput(state: GameState): CpuInput {
    val actor = actingPlayer(state)
    val own = state.players[actor]
    val choice = state.resolution?.choices?.firstOrNull()
    val selectable = if (state.phase == Phase.RESOLVE_CHOICE && choice != null)
        (own.hand + own.deck + own.discard).filter { it.id in choice.candidates || it.id in choice.visibleCards }.sortedBy { it.id }
    else emptyList()
    // Sorting prevents eligible deck search results from disclosing their order.
    val safeChoice = choice?.copy(candidates = choice.candidates.sorted(), visibleCards = choice.visibleCards.sorted())
    val actions = if (safeChoice != null && state.phase == Phase.RESOLVE_CHOICE) emptyList() else legalActions(state)
    return CpuInput(PlayerObservation.from(state, actor), state.phase, state.turn, own.flags, safeChoice,
        selectable, actions, actions.filterIsInstance<GameAction.Attack>().associateWith { checkNotNull(preview(state, it.attackId, it.targetId)) }, state.stadium)
}

/** Deterministic, public-information heuristic CPU. No fabricated hidden draws or random tie breaks. */
class CpuPlayer(private val catalog: CardCatalog = TrainerCatalog) {
    private val effects = EffectEngine(catalog)
    private fun d(card: CardInstance) = catalog.get(card.definitionId)

    fun choose(input: CpuInput): GameAction {
        val o = input.observation
        val own = o.ownField
        val active = own.find { it.id == o.ownActiveId }
        val enemy = o.opponentField.find { it.id == o.opponentActiveId }
        input.previews.entries.firstOrNull { it.value.knockout && it.value.prizes >= o.ownPrizeCount && !it.value.uncertain }?.let { return it.key }
        fun card(id: String) = (o.ownHand + input.selectableCards + o.ownDiscard + own.flatMap { it.energies }).firstOrNull { it.id == id }
        fun pokemon(id: String) = (own + o.opponentField).first { it.id == id }
        fun hp(p: PokemonInstance): Int {
            val modifier = input.stadium?.let { d(it).trainerEffect as? TrainerEffect.HpModifier }
            return effects.effectiveHp(p) + if (modifier?.stage == d(p.top).stage) modifier.amount else 0
        }
        fun missing(p: PokemonInstance, a: AttackDefinition): Int {
            val energy = p.energies.map { d(it).type }.toMutableList()
            var discount = if (d(p.top).abilities.any { it.attackDiscountPerOpponentPrize }) 6-o.opponentPrizeCount else 0
            val cost = a.cost.filter { if (it == EnergyType.COLORLESS && discount > 0) { discount--; false } else true }
            var missing = 0
            cost.filter { it != EnergyType.COLORLESS }.forEach { if (!energy.remove(it)) missing++ }
            return missing + (cost.count { it == EnergyType.COLORLESS }-energy.size).coerceAtLeast(0)
        }
        fun damage(p: PokemonInstance, a: AttackDefinition, target: PokemonInstance): Int {
            if (a.effects.filterIsInstance<Effect.RequiresBenched>().any { req -> own.none { it.id != p.id && it.top.definitionId == req.definitionId } }) return 0
            val extra = a.effects.filterIsInstance<Effect.CoinDamage>().sumOf { it.coins*it.perHeads/2 } +
                if (input.flags.supporter) a.effects.filterIsInstance<Effect.SupporterBonus>().sumOf { it.damage } else 0
            return effects.damage(p, target, a.effects + Effect.Damage(extra), input.flags.damageBoosts[d(p.top).type] ?: 0,
                Effect.IgnoreWeaknessResistance in a.effects || (a.effects.any { it is Effect.TargetedDamage } && target.id != enemy?.id))
        }
        fun attackValue(p: PokemonInstance, target: PokemonInstance? = enemy): Double {
            if (target == null) return 0.0
            return d(p.top).attacks.filter { effects.supported(it) && missing(p, it) == 0 &&
                p.restrictions.none { r -> r.blockedTurn == input.turn && (r.attackId == null || r.attackId == it.id) } }.maxOfOrNull { a ->
                val damage = damage(p, a, target)
                val ko = damage >= hp(target)-target.damage
                val prizes = d(target.top).prizes
                damage.coerceAtMost((hp(target)-target.damage).coerceAtLeast(0))*.35 +
                    (if (ko && damage > 0) 200.0*prizes + if (prizes >= o.ownPrizeCount) 10000.0 else 0.0 else 0.0) -
                    (if (a.effects.filterIsInstance<Effect.Recoil>().sumOf { it.damage } >= hp(p)-p.damage) 100.0 else 0.0)
            } ?: 0.0
        }
        fun potential(p: PokemonInstance): Double = d(p.top).attacks.filter(effects::supported).maxOfOrNull {
            val damage = it.effects.filterIsInstance<Effect.Damage>().sumOf { e -> e.amount }
            damage.toDouble()/(missing(p, it)+1) + (if (p.id == active?.id) 15 else 0)
        } ?: 0.0
        fun keepValue(c: CardInstance): Double {
            val def = d(c)
            return when (def.category) {
                Category.ENERGY -> if (o.ownHand.count { d(it).category == Category.ENERGY } > 2) 8.0 else 65.0
                Category.POKEMON -> when {
                    own.any { def.canEvolveFrom(d(it.top)) } -> 110.0
                    def.stage != Stage.BASIC -> 12.0
                    own.none { it.top.definitionId == def.id } && own.any { pokemon ->
                        d(pokemon.top).attacks.any { attack -> attack.effects.any { it is Effect.RequiresBenched && it.definitionId == def.id } } ||
                            d(pokemon.top).abilities.any { it.requiresInPlay == def.id }
                    } -> 120.0
                    o.ownHand.any { d(it).canEvolveFrom(def) } -> 90.0
                    own.size < 3 -> 80.0
                    own.any { it.top.definitionId == def.id } -> 15.0
                    else -> 45.0
                }
                Category.TRAINER -> when (def.trainerEffect) {
                    is TrainerEffect.ShuffleHandDraw, is TrainerEffect.DrawTo -> if (o.ownHand.size < 5) 75.0 else 20.0
                    is TrainerEffect.DamageBoost -> 25.0
                    is TrainerEffect.HpModifier -> 0.0
                    else -> 40.0
                }
            }
        }
        fun setupValue(c: CardInstance): Double {
            val p = PokemonInstance(listOf(c), enteredPlayTurn = 0)
            val available = o.ownHand + own.map { it.top }
            val synergy = d(c).attacks.flatMap { it.effects }.filterIsInstance<Effect.RequiresBenched>()
                .count { requirement -> available.any { it.definitionId == requirement.definitionId } }*80
            val evolution = if (o.ownHand.any { d(it).canEvolveFrom(d(c)) }) 65 else 0
            val cheapAttack = d(c).attacks.minOfOrNull { it.cost.size } ?: 5
            return potential(p) + synergy + evolution + (4-cheapAttack)*12 - d(c).prizes*5
        }
        if (input.phase == Phase.RESOLVE_CHOICE) {
            val choice = checkNotNull(input.choice)
            fun value(id: String): Double = when (choice.kind) {
                ChoiceKind.DISCARD, ChoiceKind.ABILITY_ENERGY, ChoiceKind.DISCARD_ATTACK_ENERGY -> -keepValue(checkNotNull(card(id)))
                ChoiceKind.SEARCH, ChoiceKind.RECOVER, ChoiceKind.SONIA -> keepValue(checkNotNull(card(id)))
                ChoiceKind.SWITCH_OPPONENT, ChoiceKind.EVOLUTION_SWITCH -> active?.let { attackValue(it, pokemon(id)) } ?: 0.0
                ChoiceKind.SWITCH_OWN -> attackValue(pokemon(id)) - (active?.let(::attackValue) ?: 0.0) + potential(pokemon(id))*.05
                ChoiceKind.ENERGY_TARGET -> {
                    val p = pokemon(id)
                    val energy = checkNotNull(o.ownDiscard.find { it.id == choice.selectedEnergy })
                    potential(p.copy(energies = p.energies + energy))-potential(p) + if (p.energies.size < 3) 10.0 else -10.0
                }
                ChoiceKind.TOOL_TARGET -> if (id == active?.id) 50.0 else 15.0-pokemon(id).energies.size
                ChoiceKind.ENERGY_FROM_DISCARD -> 10.0
                ChoiceKind.ATTACH_DECK_ENERGY, ChoiceKind.RECYCLE_ENERGY -> 10.0
                ChoiceKind.HEAL_TARGET -> minOf(pokemon(id).damage, choice.amount).toDouble()
            }
            if (choice.kind == ChoiceKind.SONIA) {
                val groups = choice.candidates.groupBy { d(checkNotNull(card(it))).category }.values
                val best = groups.maxByOrNull { group -> group.sortedByDescending(::value).take(choice.max).sumOf(::value) }.orEmpty()
                return GameAction.ChooseCards(best.sortedByDescending(::value).take(choice.max))
            }
            val ranked = choice.candidates.sortedByDescending(::value)
            val desired = if (choice.kind == ChoiceKind.SWITCH_OWN || choice.kind == ChoiceKind.EVOLUTION_SWITCH)
                ranked.filter { value(it) > 0 }.size else ranked.size
            return GameAction.ChooseCards(ranked.take(desired.coerceIn(choice.min, choice.max.coerceAtMost(ranked.size))))
        }
        fun score(a: GameAction): Double = when (a) {
            is GameAction.ChooseStartingPlayer -> if (a.player == o.viewer) 10.0 else 0.0
            is GameAction.ChooseActive -> setupValue(checkNotNull(card(a.cardId)))
            is GameAction.MulliganDraw -> if (a.count <= minOf(7, (o.ownDeckCount-2).coerceAtLeast(0))) a.count.toDouble() else -1.0
            is GameAction.PlayBasic -> {
                val c = checkNotNull(card(a.cardId))
                if (own.size < 4 || keepValue(c) >= 100) 90.0+keepValue(c)*.1 else -5.0
            }
            is GameAction.Evolve -> 130.0 + potential(pokemon(a.pokemonId).copy(cards = pokemon(a.pokemonId).cards + checkNotNull(card(a.cardId))))*.1
            is GameAction.AttachEnergy -> {
                val p = pokemon(a.pokemonId); val after = p.copy(energies = p.energies + checkNotNull(card(a.cardId)))
                val improvement = attackValue(after)-attackValue(p)
                if (potential(after) <= potential(p)) -5.0 else 100.0 + improvement + (potential(after)-potential(p))*.5
            }
            is GameAction.Attack -> {
                val p = checkNotNull(input.previews[a])
                val attack = d(checkNotNull(active).top).attacks.first { it.id == a.attackId }
                val draw = attack.effects.filterIsInstance<Effect.Draw>().sumOf { it.count }
                val charge = attack.effects.filterIsInstance<Effect.EnergyFromDeck>().sumOf { it.count }
                val chargeValue = if (o.ownDeckCount > 0 && active.energies.size < (d(active.top).attacks.maxOfOrNull { it.cost.size } ?: 0)) charge*35.0 else 0.0
                val drawValue = if (o.ownDeckCount > draw+1) minOf(draw, (6-o.ownHand.size).coerceAtLeast(0))*10.0 else 0.0
                if (p.damage == 0) chargeValue+drawValue-1 else 10.0+p.damage.coerceAtMost(p.effectiveHp-p.currentDamage)*.35 + chargeValue +
                    (if (p.knockout) (if (p.uncertain) .5 else 1.0)*(200.0*p.prizes + if (p.prizes >= o.ownPrizeCount) 10000.0 else 0.0) else 0.0)
            }
            is GameAction.UseAbility -> if (o.ownDeckCount <= 1) -5.0 else 150.0-o.ownHand.size
            is GameAction.Retreat -> {
                val improvement = attackValue(pokemon(a.pokemonId)) - (active?.let(::attackValue) ?: 0.0)
                if (improvement > 5) 30.0+improvement-a.energyIds.size*10 else -5.0
            }
            is GameAction.Promote -> attackValue(pokemon(a.pokemonId)) + potential(pokemon(a.pokemonId))*.4
            is GameAction.PlayTrainer -> when (val effect = d(checkNotNull(card(a.cardId))).trainerEffect) {
                is TrainerEffect.Search -> if (o.ownDeckCount == 0 || o.ownHand.size > 11) -5.0 else 110.0-effect.discardCost*12
                is TrainerEffect.ShuffleHandDraw -> if (o.ownDeckCount <= 3 || o.ownHand.size > (if (o.ownPrizeCount == 6) effect.sixPrizeCount else effect.count)) -5.0 else 65.0-o.ownHand.size
                is TrainerEffect.DrawTo -> if (o.ownHand.size >= effect.size || o.ownDeckCount <= 2) -5.0 else 80.0-o.ownHand.size
                is TrainerEffect.Switch -> if (effect.opponent) {
                    val best = o.opponentField.drop(1).maxOfOrNull { active?.let { p -> attackValue(p, it) } ?: 0.0 } ?: 0.0
                    if (best > (active?.let(::attackValue) ?: 0.0)+10) 120.0+best else -5.0
                } else {
                    val gain = own.drop(1).maxOfOrNull(::attackValue)?.minus(active?.let(::attackValue) ?: 0.0) ?: 0.0
                    if (gain > 5) 115.0+gain else if (effect.drawTo > o.ownHand.size && o.ownDeckCount > 3 && own.size > 1) 35.0 else -5.0
                }
                is TrainerEffect.DamageBoost -> if (input.actions.any { it is GameAction.Attack } && d(checkNotNull(active).top).type == effect.type) 160.0 else -5.0
                TrainerEffect.Recover -> if (o.ownDiscard.any { d(it).category == Category.ENERGY || d(it).category == Category.POKEMON }) 45.0 else -5.0
                is TrainerEffect.RetreatReduction -> 50.0
                is TrainerEffect.HpModifier, null -> -5.0
                is TrainerEffect.DrawCards -> if (o.ownDeckCount > effect.count+1 && o.ownHand.size < 9) 100.0 else -5.0
                TrainerEffect.GreatBall, TrainerEffect.Sonia -> if (o.ownDeckCount > 0 && own.size < 5) 90.0 else -5.0
                TrainerEffect.CoinSwitch -> if (o.opponentField.size > 1) 20.0 else -5.0
                is TrainerEffect.HealPokemon -> own.filter { !effect.activeOnly || it.id == active?.id }.maxOfOrNull { minOf(it.damage, effect.amount).toDouble() } ?: -5.0
                is TrainerEffect.RecoverEnergy -> if (o.ownDiscard.any { d(it).category == Category.ENERGY }) 45.0 else -5.0
            }
            GameAction.FinishSetup, GameAction.FinishBonusSetup, GameAction.EndTurn -> 0.0
            is GameAction.TakePrize -> 0.0 // All facedown slots have equal value.
            is GameAction.ChooseCards -> error("Choices are ranked separately")
        }
        return checkNotNull(input.actions.maxByOrNull(::score)) { "CPU sem ação legal na fase ${input.phase}" }
    }
}
