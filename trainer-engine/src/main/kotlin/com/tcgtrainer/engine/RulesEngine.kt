package com.tcgtrainer.engine

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

class RulesEngine(val catalog: CardCatalog = TestCatalog) {
    val effects = EffectEngine(catalog)
    private val content by lazy { ContentEngine(this) }
    fun definition(card: CardInstance) = catalog.get(card.definitionId)
    private fun abilityNames(card: CardInstance): List<String> {
        val compiled = definition(card).abilities.map { it.name }
        if (compiled.isNotEmpty()) return compiled
        val raw = CatalogRuleCompiler.rows[card.definitionId] ?: return emptyList()
        return raw["abilities"]?.jsonArray.orEmpty().mapIndexedNotNull { index, item ->
            CatalogRuleCompiler.compileAbility(card.definitionId, index, item.jsonObject)?.name
        }
    }
    private fun copyAttackKey(pokemonId: String, attackId: String) = "$pokemonId::copy::$attackId"
    private fun copiedAttackOptions(state: GameState, player: Int): List<Triple<String, PokemonInstance, AttackDefinition>> =
        state.players[player].bench.filter { definition(it.top).name.startsWith("N's ") }.flatMap { pokemon ->
            definition(pokemon.top).attacks.filter { Effect.CopyBenchedNsAttack !in it.effects && effects.supported(it) }
                .map { attack -> Triple(copyAttackKey(pokemon.id, attack.id), pokemon, attack) }
        }
    private fun attackFor(state: GameState, active: PokemonInstance, attackId: String): AttackDefinition? {
        val printed = definition(active.top).attacks.find { it.id == attackId } ?: return null
        val optionalEnergyBonus = printed.effects.filterIsInstance<Effect.OptionalReturnWaterEnergyDamageBonus>().firstOrNull()
        if (optionalEnergyBonus != null) {
            val effects = printed.effects.filterNot { it is Effect.OptionalReturnWaterEnergyDamageBonus } +
                if (state.pendingOptionalEnergyAttackId == attackId && state.pendingOptionalEnergyBonus > 0)
                    listOf(Effect.Damage(optionalEnergyBonus.bonus)) else emptyList()
            return printed.copy(effects = effects)
        }
        if (state.pendingCopiedAttackId == attackId && state.pendingCopiedAttack != null)
            return printed.copy(effects = state.pendingCopiedAttack.effects, text = state.pendingCopiedAttack.text)
        if (Effect.CopyBenchedNsAttack in printed.effects) {
            val copied = copiedAttackOptions(state, state.currentPlayer).firstOrNull()?.third ?: return printed
            return printed.copy(effects = copied.effects, text = copied.text)
        }
        return printed
    }

    fun newGame(seed: Long, decks: List<List<String>> = listOf(TestCatalog.deck(), TestCatalog.deck()), mode: Mode = Mode.TRAINING): GameState {
        require(decks.size == 2 && decks.all { if (mode == Mode.TRAINING) it.size in 14..60 else it.size == 60 }) { "São necessários dois decks de 60 cartas." }
        require(decks.all { deck -> deck.any { isBasic(catalog.get(it)) } }) { "Deck sem Pokémon Básico." }
        val rng = SeededRng(RngState(seed))
        val starter = rng.nextInt(2)
        val players = decks.mapIndexed { player, deck ->
            var cards = deck.mapIndexed { index, id -> CardInstance("$player-$index", id) }
            var mulligans = 0
            while (true) {
                cards = rng.shuffle(cards)
                if (cards.take(7).any { isBasic(definition(it)) }) break
                mulligans++
                check(mulligans < 10000) { "Limite de segurança de mulligans excedido." }
            }
            PlayerState(deck = cards.drop(7), hand = cards.take(7), mulligans = mulligans)
        }.toMutableList()
        for (i in 0..1) players[i] = players[i].copy(bonusDraws = (players[1-i].mulligans - players[i].mulligans).coerceAtLeast(0))
        return GameState("game-$seed", seed, rng.state, players, starter, starter, phase = Phase.CHOOSE_STARTER, setupPlayer = starter, mode = mode,
            catalogVersion = if (decks.flatten().any { !it.startsWith("TEST") }) "lucario-v1" else "test-v1")
    }

    private fun isBasic(card: CardDefinition) = card.category == Category.POKEMON && card.stage == Stage.BASIC
    fun actingPlayer(state: GameState): Int = when {
        state.turn == 0 -> state.setupPlayer
        state.resolution?.choices?.firstOrNull()?.kind == ChoiceKind.OPPONENT_SHUFFLES_OWN_HAND -> 1-state.currentPlayer
        else -> state.currentPlayer
    }
    private fun reason(text: String) = Legality.Illegal(text)
    private fun pokemon(state: GameState, id: String) = state.players[actingPlayer(state)].pokemon().find { it.id == id }
    fun validate(state: GameState, action: GameAction): Legality {
        if (state.phase == Phase.GAME_OVER) return reason("A partida terminou.")
        val p = state.players[actingPlayer(state)]
        fun hand(id: String) = p.hand.find { it.id == id }
        val allowed = when (state.phase) {
            Phase.CHOOSE_STARTER -> action is GameAction.ChooseStartingPlayer
            Phase.SELECT_ACTIVE -> action is GameAction.ChooseActive
            Phase.SELECT_BENCH -> action is GameAction.PlayBasic || action == GameAction.FinishSetup
            Phase.MULLIGAN_BONUS -> action is GameAction.MulliganDraw
            Phase.BONUS_BENCH -> action is GameAction.PlayBasic || action == GameAction.FinishBonusSetup
            Phase.MAIN -> action is GameAction.PlayBasic || action is GameAction.AttachEnergy || action is GameAction.Evolve || action is GameAction.Attack || action is GameAction.Retreat || action == GameAction.EndTurn || action is GameAction.PlayTrainer || action is GameAction.UseAbility
            Phase.RESOLVE_CHOICE -> action is GameAction.ChooseCards
            Phase.SELECT_PRIZES -> action is GameAction.TakePrize
            Phase.PROMOTE -> action is GameAction.Promote
            Phase.GAME_OVER -> false
        }
        if (!allowed) return reason("Ação indisponível na fase ${state.phase}.")
        return when (action) {
            is GameAction.PlayTrainer, is GameAction.UseAbility, is GameAction.ChooseCards -> content.validate(state, action)
            is GameAction.ChooseStartingPlayer -> if (action.player in 0..1) Legality.Legal else reason("Jogador inválido.")
            is GameAction.ChooseActive -> if (hand(action.cardId)?.let { isBasic(definition(it)) } == true) Legality.Legal else reason("Escolha um Pokémon Básico da mão.")
            is GameAction.PlayBasic -> when {
                state.phase == Phase.BONUS_BENCH && action.cardId !in state.bonusCardIds -> reason("Somente Básicos comprados pelo bônus de mulligan podem entrar nesta etapa.")
                p.bench.size >= 5 -> reason("O Banco já tem 5 Pokémon.")
                hand(action.cardId)?.let { isBasic(definition(it)) } != true -> reason("Somente Pokémon Básico da mão pode ir ao Banco.")
                else -> Legality.Legal
            }
            GameAction.FinishSetup -> Legality.Legal
            GameAction.FinishBonusSetup -> Legality.Legal
            is GameAction.MulliganDraw -> if (action.count in 0..minOf(p.bonusDraws, p.deck.size)) Legality.Legal else reason("Quantidade de compras de mulligan inválida.")
            is GameAction.AttachEnergy -> when {
                p.flags.manualEnergy -> reason("Você já ligou uma Energia manual neste turno.")
                hand(action.cardId)?.let { definition(it).category } != Category.ENERGY -> reason("Escolha uma Energia da mão.")
                pokemon(state, action.pokemonId) == null -> reason("Escolha um Pokémon seu em jogo.")
                else -> Legality.Legal
            }
            is GameAction.Evolve -> {
                val target = pokemon(state, action.pokemonId) ?: return reason("Pokémon fora de jogo.")
                val evolution = hand(action.cardId)?.let(::definition) ?: return reason("Carta fora da mão.")
                val canEvolveImmediately = target.id == state.players[state.currentPlayer].active?.id && definition(target.top).abilities.any { it.mayEvolveFirstTurnIfActive }
                when {
                    state.turn <= p.evolutionLockedUntilTurn -> reason("O adversário impediu evoluções neste turno.")
                    state.turn <= 2 && !canEvolveImmediately -> reason("Não é permitido evoluir no primeiro turno de cada jogador.")
                    target.enteredPlayTurn == state.turn && !canEvolveImmediately -> reason("Este Pokémon entrou em jogo neste turno.")
                    target.lastEvolutionTurn == state.turn -> reason("Este Pokémon já evoluiu neste turno.")
                    !evolution.canEvolveFrom(definition(target.top)) -> reason("Evolução incompatível.")
                    else -> Legality.Legal
                }
            }
            is GameAction.Attack -> {
                val active = p.active ?: return reason("Não há Pokémon Ativo.")
                val attack = attackFor(state, active, action.attackId) ?: return reason("Ataque inexistente.")
                val printedAttack = definition(active.top).attacks.first { it.id == action.attackId }
                val rocketRequirement = definition(active.top).abilities.maxOfOrNull { it.requiresTeamRocketPokemonCount } ?: 0
                val rocketCount = p.pokemon().count { definition(it.top).name.startsWith("Team Rocket's ") }
                when {
                    state.turn == 1 && !(state.currentPlayer == state.startingPlayer && Effect.MayAttackFirstTurnIfStarting in attack.effects) -> reason("Quem começa não pode atacar no primeiro turno.")
                    Effect.RestrictIfSecondPlayerFirstTurn in attack.effects && state.turn == 2 && state.currentPlayer != state.startingPlayer -> reason("Quem começa em segundo lugar não pode usar este ataque no seu primeiro turno.")
                    SpecialCondition.PARALYZED in active.conditions || SpecialCondition.ASLEEP in active.conditions -> reason("Este Pokémon não pode atacar enquanto está Paralisado ou Adormecido.")
                    Effect.CopyBenchedNsAttack in printedAttack.effects && copiedAttackOptions(state, state.currentPlayer).isEmpty() -> reason("Não há ataques suportados de Pokémon de N no seu Banco para copiar.")
                    rocketRequirement > rocketCount -> reason("Este Pokémon só pode atacar com pelo menos $rocketRequirement Pokémon da Equipe Rocket em jogo.")
                    !effects.supported(attack) -> reason("Efeito ainda não suportado; ataque bloqueado.")
                    active.restrictions.any { it.blockedTurn == state.turn && (it.attackId == null || it.attackId == action.attackId) } -> reason("Este Pokémon não pode usar este ataque neste turno devido ao seu ataque anterior.")
                    action.attackId in active.attackIdsUsedWhileActive -> reason("Este Pokémon já usou este ataque enquanto está no Ativo; troque-o para o Banco para liberar o ataque novamente.")
                    attack.effects.any { it is Effect.DiscardEnergyThenBenchDamage } && state.players[1-state.currentPlayer].bench.none { it.id == action.targetId } -> reason("Escolha um Pokémon do Banco adversário como alvo.")
                    attack.effects.filterIsInstance<Effect.TargetedDamage>().any { it.benchedOnly } && state.players[1-state.currentPlayer].bench.none { it.id == action.targetId } -> reason("Escolha um Pokémon do Banco adversário como alvo.")
                    attack.effects.any { it is Effect.TargetedDamage } && state.players[1-state.currentPlayer].pokemon().none { it.id == action.targetId } -> reason("Escolha um Pokémon adversário como alvo.")
                    state.pendingOptionalEnergyAttackId == action.attackId -> Legality.Legal
                    else -> energyCost(active, attackCost(state, active, attack))
                }
            }
            is GameAction.Retreat -> {
                val active = p.active ?: return reason("Sem Ativo.")
                when {
                    p.flags.retreat -> reason("Você já recuou neste turno.")
                    active.retreatBlockedTurn == state.turn -> reason("Este Pokémon não pode recuar durante este turno.")
                    SpecialCondition.PARALYZED in active.conditions || SpecialCondition.ASLEEP in active.conditions -> reason("Este Pokémon não pode recuar enquanto está Paralisado ou Adormecido.")
                    p.bench.none { it.id == action.pokemonId } -> reason("Escolha um Pokémon do Banco.")
                    action.energyIds.size != retreatCost(active, p.pokemon(), state.players[1-state.currentPlayer].pokemon()) -> reason("Selecione exatamente o custo de recuo em Energias.")
                    action.energyIds.distinct().size != action.energyIds.size || !active.energies.map { it.id }.containsAll(action.energyIds) -> reason("Energias de recuo inválidas.")
                    else -> Legality.Legal
                }
            }
            is GameAction.TakePrize -> if (p.prizes.any { it.id == action.cardId }) Legality.Legal else reason("Prêmio indisponível.")
            is GameAction.Promote -> if (p.bench.any { it.id == action.pokemonId }) Legality.Legal else reason("Escolha um Pokémon do Banco.")
            GameAction.EndTurn -> Legality.Legal
        }
    }

    private fun energyCost(pokemon: PokemonInstance, cost: List<EnergyType>): Legality {
        val available = pokemon.energies.map { definition(it).type }.toMutableList()
        for (type in cost.filter { it != EnergyType.COLORLESS }) {
            if (!available.remove(type)) return reason("Falta Energia do tipo ${type.name}.")
        }
        val missing = cost.count { it == EnergyType.COLORLESS } - available.size
        return if (missing > 0) reason("Falta $missing Energia para o custo incolor.") else Legality.Legal
    }

    fun retreatCost(pokemon: PokemonInstance, friendlyPokemon: List<PokemonInstance> = listOf(pokemon), opponentPokemon: List<PokemonInstance> = emptyList()): Int {
        val free = friendlyPokemon.any { source ->
            definition(source.top).abilities.any { ability ->
                val type = ability.freeRetreatWithAttachedEnergyType
                type != null && pokemon.energies.any { definition(it).type == type }
            }
        } || (pokemon.energies.isEmpty() && definition(pokemon.top).abilities.any { it.freeRetreatWithNoEnergy })
        val base = if (free) 0 else definition(pokemon.top).retreatCost
        val increase = if (definition(pokemon.top).stage != Stage.BASIC) opponentPokemon.sumOf { source ->
            definition(source.top).abilities.sumOf { it.opponentActiveEvolutionRetreatCostBonus }
        } else 0
        return (base + increase - ((pokemon.tool?.let(::definition)?.trainerEffect as? TrainerEffect.RetreatReduction)?.amount ?: 0)).coerceAtLeast(0)
    }
    fun attackCost(state: GameState, pokemon: PokemonInstance, attack: AttackDefinition): List<EnergyType> {
        val teraAlternative = definition(pokemon.top).abilities.firstOrNull {
            it.alternativeAttackName == attack.name && it.requiresTeraInPlay &&
                state.players[state.currentPlayer].pokemon().any { inPlay -> definition(inPlay.top).isTera }
        }
        if (teraAlternative != null) return teraAlternative.alternativeAttackCost
        val discount = (if (definition(pokemon.top).abilities.any { it.attackDiscountPerOpponentPrize }) 6-state.players[1-state.currentPlayer].prizes.size else 0) +
            if (definition(pokemon.top).abilities.any { it.attackDiscountPerKofuDiscard }) state.players[state.currentPlayer].discard.count { definition(it).name == "Kofu" } else 0
        var remaining = discount
        return attack.cost.filter { if (it == EnergyType.COLORLESS && remaining > 0) { remaining--; false } else true }
    }
    fun preview(state: GameState, attackId: String, targetId: String? = null, rolledHeads: Int? = null, effectDamage: Int = 0,
        forceIgnoreWeaknessResistance: Boolean = false): AttackPreview? {
        val attacker = state.players[state.currentPlayer].active ?: return null
        val opponent = state.players[1-state.currentPlayer]
        val rawDefender = if (targetId != null) opponent.pokemon().find { it.id == targetId } ?: return null else opponent.active ?: return null
        val opponentNWeaknessChanged = state.players[state.currentPlayer].pokemon().any { pokemon ->
            definition(pokemon.top).abilities.any { it.changeOpponentNsPokemonWeaknessToPsychic }
        } && definition(rawDefender.top).name.startsWith("N's ")
        val defender = if (opponentNWeaknessChanged) rawDefender.copy(weaknessOverride = EnergyType.PSYCHIC) else rawDefender
        val attack = attackFor(state, attacker, attackId) ?: return null
        if (Effect.PullSelectedBenchBeforeDamage in attack.effects && targetId != null) {
            val pulled = opponent.bench.find { it.id == targetId } ?: return null
            val oldActive = opponent.active ?: return null
            val switched = state.copy(players = state.players.mapIndexed { index, player ->
                if (index != 1-state.currentPlayer) player else player.copy(active = pulled,
                    bench = player.bench.filterNot { it.id == targetId } + oldActive)
            })
            return preview(switched, attackId, null, rolledHeads, effectDamage, forceIgnoreWeaknessResistance)
        }
        if (!effects.supported(attack)) return null
        val requirement = attack.effects.filterIsInstance<Effect.RequiresBenched>().firstOrNull()
        val exactHandSize = attack.effects.filterIsInstance<Effect.RequiresExactHandSize>().firstOrNull()
        val undamagedActiveRequirement = Effect.RequiresUndamagedOpponentActive in attack.effects
        val hasRequirement = (requirement == null || state.players[state.currentPlayer].bench.any { it.top.definitionId == requirement.definitionId }) &&
            (exactHandSize == null || state.players[state.currentPlayer].hand.size == exactHandSize.size) &&
            (!undamagedActiveRequirement || defender.id == opponent.active?.id && defender.damage == 0)
        val boost = if (defender.id == opponent.active?.id) state.players[state.currentPlayer].flags.damageBoosts[definition(attacker.top).type] ?: 0 else 0
        val coin = attack.effects.filterIsInstance<Effect.CoinDamage>().firstOrNull()
        val benchEnergyEffect = attack.effects.filterIsInstance<Effect.DiscardBenchEnergyDamage>().firstOrNull()
        val availableBenchEnergies = state.players[state.currentPlayer].bench.sumOf { it.energies.size }
        val benchEnergyBonus = benchEnergyEffect?.let { minOf(it.max, availableBenchEnergies) * it.perEnergyDamage } ?: 0
        val coinFlipCount = coin?.let { when {
            it.untilTails -> 20
            it.perEnergyBothActive -> attacker.energies.size + (opponent.active?.energies?.size ?: 0)
            it.perEnergy -> attacker.energies.size
            else -> it.coins
        } } ?: 0
        val expectedHeads = coin?.let { when {
            rolledHeads != null -> rolledHeads
            it.untilTails -> 1
            else -> coinFlipCount / 2
        } } ?: 0
        val extra = (coin?.let { effect ->
            if (effect.minimumHeads > 0) if (rolledHeads != null && rolledHeads >= effect.minimumHeads) effect.perHeads else 0
            else expectedHeads*effect.perHeads
        } ?: 0) +
            if (state.players[state.currentPlayer].flags.supporter) attack.effects.filterIsInstance<Effect.SupporterBonus>().sumOf { it.damage } else 0
        val resolvedEffects = attack.effects + (if (extra > 0) listOf(Effect.Damage(extra)) else emptyList()) +
            (if (effectDamage > 0) listOf(Effect.Damage(effectDamage)) else emptyList())
        val damage = if (!hasRequirement || Effect.RequiresStadium in attack.effects && state.stadium == null) 0 else effects.damage(attacker, defender, resolvedEffects, boost,
            forceIgnoreWeaknessResistance || attack.effects.contains(Effect.IgnoreWeaknessResistance) || defender.id != opponent.active?.id,
            ownBenchCount = state.players[state.currentPlayer].bench.size, opponentBenchCount = opponent.bench.size,
            ownInPlayCount = state.players[state.currentPlayer].pokemon().size,
            ignoreOpponentActiveEffects = Effect.IgnoreOpponentActiveEffects in attack.effects,
            ignoreResistance = Effect.IgnoreResistance in attack.effects, ignoreWeakness = Effect.IgnoreWeakness in attack.effects, turn = state.turn,
            ownHandCount = state.players[state.currentPlayer].hand.size,
            opponentActiveIsEx = state.players[1-state.currentPlayer].active?.let { definition(it.top).name.endsWith(" ex") } == true,
            targetIsOpponentActive = defender.id == state.players[1-state.currentPlayer].active?.id,
            opponentActiveEnergyCount = state.players[1-state.currentPlayer].active?.energies?.size ?: 0,
            opponentHasSpecialCondition = state.players[1-state.currentPlayer].active?.conditions?.isNotEmpty() == true,
            opponentActiveDamage = state.players[1-state.currentPlayer].active?.damage ?: 0,
            opponentHandCount = state.players[1-state.currentPlayer].hand.size,
            opponentHandEnergyCount = state.players[1-state.currentPlayer].hand.count { definition(it).category == Category.ENERGY },
            ownToolCount = state.players[state.currentPlayer].pokemon().count { it.tool != null },
            ownDamagedPokemonCount = state.players[state.currentPlayer].pokemon().count { it.damage > 0 },
            ownDamagedBenchedPokemonCount = state.players[state.currentPlayer].bench.count { it.damage > 0 },
            ownEnergyCounts = state.players[state.currentPlayer].pokemon().flatMap { it.energies }
                .mapNotNull { energy -> definition(energy).type?.let { it to energy } }.groupBy({ it.first }, { it.second }).mapValues { it.value.size },
            ownPokemonNames = state.players[state.currentPlayer].pokemon().map { definition(it.top).name },
            ownBenchPokemonNames = state.players[state.currentPlayer].bench.map { definition(it.top).name },
            ownBenchPokemonTypes = state.players[state.currentPlayer].bench.map { definition(it.top).type },
            ownBenchPokemonDetails = state.players[state.currentPlayer].bench.map { definition(it.top).name to it.damage },
            ownBenchPokemonTypeDetails = state.players[state.currentPlayer].bench.map { definition(it.top).type to it.damage },
            ownKnockedOutByAttackLastTurn = state.players[state.currentPlayer].lastKnockedOutByAttackDamageTurn == state.turn-1,
            friendlyPokemon = state.players[1-state.currentPlayer].pokemon(),
            friendlyBenchPokemon = state.players[1-state.currentPlayer].bench,
            opponentDamageCounterCount = state.players[1-state.currentPlayer].pokemon().sumOf { it.damage / 10 },
            opponentActiveRetreatCost = if (defender.id == opponent.active?.id)
                retreatCost(defender, opponent.pokemon(), state.players[state.currentPlayer].pokemon()) else 0,
            ownDiscardEnergyCount = state.players[state.currentPlayer].discard.count { definition(it).category == Category.ENERGY },
            ownBasicDiscardEnergyCounts = state.players[state.currentPlayer].discard.filter { definition(it).category == Category.ENERGY && definition(it).basicEnergy }
                .groupBy({ definition(it).type }, { it }).mapValues { it.value.size },
            ownDiscardPokemonAbilityNames = state.players[state.currentPlayer].discard.flatMap(::abilityNames),
            ownSpecialEnergyCount = state.players[state.currentPlayer].pokemon().flatMap { it.energies }
                .count { definition(it).category == Category.ENERGY && !definition(it).basicEnergy },
            opponentPrizeCardsRemaining = opponent.prizes.size,
            allPokemonToolCount = state.players.flatMap { it.pokemon() }.count { it.tool != null },
            attackerPokemon = state.players[state.currentPlayer].pokemon(),
            ownBenchWithEnergyCounts = EnergyType.entries.associateWith { type ->
                state.players[state.currentPlayer].bench.count { pokemon -> pokemon.energies.any { definition(it).type == type } }
            })
        val hp = effects.effectiveHp(defender, state)
        val survivalHp = if (defender.damage == 0) definition(defender.top).abilities.maxOfOrNull { it.surviveAttackKnockoutAtHp } ?: 0 else 0
        val remaining = if (survivalHp > 0 && damage >= hp) survivalHp else (hp - defender.damage - damage).coerceAtLeast(0)
        val topDeckEnergy = attack.effects.filterIsInstance<Effect.DiscardTopDeckEnergyDamage>().firstOrNull()
        val uncertain = coin != null && rolledHeads == null || benchEnergyBonus > 0 || topDeckEnergy != null
        val minimum = if (coin != null && rolledHeads == null) preview(state, attackId, targetId, 0)!!.damage else damage
        val coinMaximum = if (coin != null && rolledHeads == null) preview(state, attackId, targetId, coinFlipCount)!!.damage else damage
        val defenderWeakness = defender.weaknessOverride ?: definition(defender.top).weakness
        val bonusAfterWeakness = if (attack.effects.contains(Effect.IgnoreWeaknessResistance) || defender.id != opponent.active?.id) benchEnergyBonus
            else if (!(defender.weaknessSuppressedTurn >= 0 && defender.weaknessSuppressedTurn == state.turn) && defenderWeakness == definition(attacker.top).type) benchEnergyBonus * 2 else benchEnergyBonus
        val topDeckMaxBonus = topDeckEnergy?.let { effect ->
            val raw = minOf(effect.count, state.players[state.currentPlayer].deck.size) * effect.damagePerEnergy
            val weakness = !forceIgnoreWeaknessResistance && !attack.effects.contains(Effect.IgnoreWeaknessResistance) && !attack.effects.contains(Effect.IgnoreWeakness) &&
                defender.id == opponent.active?.id && defender.weaknessSuppressedTurn != state.turn &&
                (defender.weaknessOverride ?: definition(defender.top).weakness) == definition(attacker.top).type
            raw * if (weakness) 2 else 1
        } ?: 0
        val maximum = coinMaximum + bonusAfterWeakness + topDeckMaxBonus
        return AttackPreview(damage, hp, defender.damage, remaining, remaining == 0, if (remaining == 0) definition(defender.top).prizes else 0,
            uncertain, minimum, maximum)
    }

    fun apply(state: GameState, action: GameAction): Transition {
        val legality = validate(state, action)
        if (legality is Legality.Illegal) return Transition(state, legality)
        val actor = actingPlayer(state)
        if (action is GameAction.Attack) {
            val active = state.players[actor].active!!
            val printed = definition(active.top).attacks.first { it.id == action.attackId }
            val optionalWaterEnergy = printed.effects.filterIsInstance<Effect.OptionalReturnWaterEnergyDamageBonus>().firstOrNull()
            if (optionalWaterEnergy != null && state.pendingOptionalEnergyAttackId != action.attackId) {
                val energies = active.energies.filter { definition(it).category == Category.ENERGY && definition(it).type == EnergyType.WATER }
                if (energies.isNotEmpty()) {
                    val request = ChoiceRequest(ChoiceKind.OPTIONAL_ATTACK_ENERGY_BONUS,
                        "Ninja Spinner: você pode devolver 1 Energia {W} do atacante à mão para causar ${optionalWaterEnergy.bonus} a mais.",
                        energies.map { it.id }, 0, 1)
                    return Transition(state.copy(phase = Phase.RESOLVE_CHOICE, resolution = Resolution(listOf(request)),
                        afterResolution = ResolutionEnd.MAIN), Legality.Legal,
                        listOf(GameEvent("OptionalAttackCostChoice", "Escolha se devolve uma Energia {W} antes do dano.")))
                }
            }
            if (Effect.CopyBenchedNsAttack in printed.effects && state.pendingCopiedAttackId != action.attackId) {
                val options = copiedAttackOptions(state, actor)
                val candidates = options.map { it.first }
                val labels = options.associate { (key, pokemon, attack) ->
                    key to "${definition(pokemon.top).name} — ${attack.name} (${attack.cost.joinToString(" + ") { it.name }})"
                }
                val request = ChoiceRequest(ChoiceKind.ATTACK_COPY_BENCHED_N,
                    "Night Joker: escolha um ataque de um Pokémon de N no seu Banco.", candidates, 1, 1, optionLabels = labels)
                val event = GameEvent("AttackCopyChoiceOpened", "Escolha um ataque de N no Banco antes de resolver Night Joker.")
                return Transition(state.copy(phase = Phase.RESOLVE_CHOICE, resolution = Resolution(listOf(request)),
                    afterResolution = ResolutionEnd.MAIN,
                    history = state.history + ActionRecord(actor, state.turn, action, listOf(event))), Legality.Legal, listOf(event))
            }
        }
        if (action is GameAction.ChooseCards && state.resolution?.choices?.firstOrNull()?.kind == ChoiceKind.OPTIONAL_ATTACK_ENERGY_BONUS) {
            val active = state.players[actor].active
                ?: return Transition(state, Legality.Illegal("O atacante não está mais no Campo."))
            val attackId = definition(active.top).attacks.firstOrNull { it.effects.any { effect -> effect is Effect.OptionalReturnWaterEnergyDamageBonus } }?.id
                ?: return Transition(state, Legality.Illegal("Ninja Spinner não está mais disponível."))
            val bonus = definition(active.top).attacks.first { it.id == attackId }.effects
                .filterIsInstance<Effect.OptionalReturnWaterEnergyDamageBonus>().single().bonus
            val selected = action.cardIds.singleOrNull()?.let { id -> active.energies.firstOrNull { it.id == id } }
            if (action.cardIds.isNotEmpty() && (selected == null || definition(selected).type != EnergyType.WATER || definition(selected).category != Category.ENERGY))
                return Transition(state, Legality.Illegal("Escolha uma Energia {W} ligada ao atacante, ou nenhuma."))
            var prepared = state.copy(phase = Phase.MAIN, resolution = null,
                pendingOptionalEnergyAttackId = attackId,
                pendingOptionalEnergyBonus = if (selected != null) bonus else 0)
            val choiceEvents = mutableListOf<GameEvent>()
            if (selected != null) {
                prepared = prepared.copy(players = prepared.players.mapIndexed { index, player ->
                    if (index != actor) player else player.copy(
                        active = player.active?.copy(energies = player.active!!.energies - selected),
                        hand = player.hand + selected)
                })
                choiceEvents += GameEvent("OptionalWaterEnergyReturned", "Devolveu uma Energia {W} à mão; Ninja Spinner causa $bonus de dano adicional.", bonus)
            } else choiceEvents += GameEvent("OptionalWaterEnergyKept", "Manteve as Energias ligadas; Ninja Spinner causa o dano base.")
            prepared = prepared.copy(history = prepared.history + ActionRecord(actor, state.turn, action, choiceEvents))
            val resolved = apply(prepared, GameAction.Attack(attackId))
            return Transition(resolved.state, resolved.legality, choiceEvents + resolved.events)
        }
        if (action is GameAction.ChooseCards && state.resolution?.choices?.firstOrNull()?.kind == ChoiceKind.ATTACK_COPY_BENCHED_N) {
            val key = action.cardIds.single()
            val option = copiedAttackOptions(state, actor).firstOrNull { it.first == key }
                ?: return Transition(state, Legality.Illegal("O ataque selecionado deixou de estar disponível."))
            val attackId = state.players[actor].active?.let { active ->
                definition(active.top).attacks.firstOrNull { Effect.CopyBenchedNsAttack in it.effects }?.id
            } ?: return Transition(state, Legality.Illegal("Night Joker não está mais disponível."))
            val prepared = state.copy(phase = Phase.MAIN, resolution = null,
                pendingCopiedAttackId = attackId, pendingCopiedAttack = option.third)
            return apply(prepared, GameAction.Attack(attackId))
        }
        var next = state
        val events = mutableListOf<GameEvent>()
        fun emit(type: String, message: String, amount: Int? = null) { events += GameEvent(type, message, amount) }
        fun update(index: Int, transform: (PlayerState) -> PlayerState) { next = next.copy(players = next.players.mapIndexed { i, p -> if (i == index) transform(p) else p }) }
        fun updatePokemon(id: String, transform: (PokemonInstance) -> PokemonInstance) = update(actor) { p ->
            p.copy(active = p.active?.let { if (it.id == id) transform(it) else it }, bench = p.bench.map { if (it.id == id) transform(it) else it })
        }
        fun win(player: Int, why: String) { next = next.copy(winner = player, winReason = why, phase = Phase.GAME_OVER); emit("GameWon", why) }
        fun startTurn(player: Int) {
            next = next.copy(currentPlayer = player, turn = next.turn + 1, phase = Phase.MAIN, resumePlayer = null)
            val p = next.players[player]
            if (p.deck.isEmpty()) win(1-player, "O adversário não conseguiu comprar no início do turno.")
            else {
                update(player) { it.copy(deck = it.deck.drop(1), hand = it.hand + it.deck.first(), flags = TurnFlags()) }
                emit("TurnStarted", "Turno ${next.turn} — jogador ${player+1}; comprou 1 carta.")
            }
        }
        val p = state.players[actor]
        when (action) {
            is GameAction.PlayTrainer, is GameAction.UseAbility, is GameAction.ChooseCards -> {
                val resolved = content.apply(next, action)
                next = resolved.state; events += resolved.events
            }
            is GameAction.ChooseStartingPlayer -> {
                next = next.copy(startingPlayer = action.player, currentPlayer = action.player, setupPlayer = 0, phase = Phase.SELECT_ACTIVE)
                emit("StartingPlayerChosen", "Vencedor da moeda: jogador ${actor+1}; começa: jogador ${action.player+1}.")
            }
            is GameAction.ChooseActive -> {
                val card = p.hand.first { it.id == action.cardId }
                update(actor) { it.copy(hand = it.hand - card, active = PokemonInstance(listOf(card), enteredPlayTurn = 0)) }
                next = next.copy(phase = Phase.SELECT_BENCH)
                emit("ActiveSelected", "Jogador ${actor+1}: ${definition(card).name} no Ativo.")
            }
            is GameAction.PlayBasic -> {
                val card = p.hand.first { it.id == action.cardId }
                update(actor) { it.copy(hand = it.hand - card, bench = it.bench + PokemonInstance(listOf(card), enteredPlayTurn = state.turn)) }
                emit("BasicPlayed", "${definition(card).name} entrou no Banco.")
                val onBenchSwitch = definition(card).abilities.any { it.onBenchSwitchAndMoveEnergy }
                val onBenchAbility = definition(card).abilities.firstOrNull { it.onBenchAttachBasicEnergyMax > 0 }
                val eligibleEnergy = onBenchAbility?.let { ability -> next.players[actor].hand.filter {
                    definition(it).category == Category.ENERGY && definition(it).basicEnergy && definition(it).type == ability.onBenchAttachBasicEnergyType
                } }.orEmpty()
                if (state.phase == Phase.MAIN && onBenchSwitch) {
                    next = next.copy(phase = Phase.RESOLVE_CHOICE, afterResolution = ResolutionEnd.MAIN,
                        resolution = Resolution(listOf(ChoiceRequest(ChoiceKind.ON_BENCH_SWITCH_AND_MOVE_ENERGY,
                            "Você pode trocar o Pokémon que acabou de entrar no Banco com seu Ativo.", listOf(card.id), 0, 1))))
                } else if (state.phase == Phase.MAIN && onBenchAbility != null && eligibleEnergy.isNotEmpty()) {
                    next = next.copy(phase = Phase.RESOLVE_CHOICE, afterResolution = ResolutionEnd.MAIN, resolution = Resolution(listOf(ChoiceRequest(
                        ChoiceKind.ABILITY_ATTACH_MULTIPLE_BASIC_ENERGY,
                        "${onBenchAbility.name}: você pode ligar até ${onBenchAbility.onBenchAttachBasicEnergyMax} Energia(s) Básica(s) da mão a este Pokémon.",
                        eligibleEnergy.map { it.id }, 0, minOf(onBenchAbility.onBenchAttachBasicEnergyMax, eligibleEnergy.size), sourcePokemonId = card.id))))
                } else if (state.phase == Phase.MAIN && definition(card).abilities.any { it.onBenchDiscardStadium } && next.stadium != null) {
                    val stadium = next.stadium!!
                    val owner = next.stadiumOwner ?: actor
                    update(owner) { it.copy(discard = it.discard + stadium) }
                    next = next.copy(stadium = null, stadiumOwner = null)
                    emit("OnBenchStadiumDiscarded", "${definition(card).name} descartou o Estádio em jogo.")
                } else if (state.phase == Phase.MAIN && definition(card).abilities.any { it.onBenchDiscardOpponentDeckTop } && next.players[1-actor].deck.isNotEmpty()) {
                    val top = next.players[1-actor].deck.first()
                    next = next.copy(phase = Phase.RESOLVE_CHOICE, afterResolution = ResolutionEnd.MAIN,
                        resolution = Resolution(listOf(ChoiceRequest(ChoiceKind.ON_BENCH_DISCARD_OPPONENT_DECK_TOP,
                            "Você pode descartar a carta do topo do deck do adversário.", listOf(top.id), 0, 1, sourcePokemonId = card.id))))
                }
            }
            GameAction.FinishSetup -> {
                // Prizes are placed only after the player has selected the initial field.
                update(actor) { it.copy(prizes = it.deck.take(6), prizePositions = it.deck.take(6).map { card -> card.id }, deck = it.deck.drop(6)) }
                val finished = next.setupFinished + actor
                next = next.copy(setupFinished = finished)
                if (finished.size == 1) next = next.copy(setupPlayer = 1, phase = Phase.SELECT_ACTIVE)
                else next = next.copy(setupPlayer = 0, phase = Phase.MULLIGAN_BONUS)
                emit("SetupCompleted", "Jogador ${actor+1}: 6 Prêmios separados.")
            }
            is GameAction.MulliganDraw -> {
                update(actor) { it.copy(hand = it.hand + it.deck.take(action.count), deck = it.deck.drop(action.count), bonusDraws = 0) }
                emit("MulliganBonus", "Jogador ${actor+1} comprou ${action.count} carta(s) por mulligan.")
                next = next.copy(phase = Phase.BONUS_BENCH, bonusCardIds = p.deck.take(action.count).map { it.id })
            }
            GameAction.FinishBonusSetup -> {
                next = next.copy(bonusCardIds = emptyList())
                if (actor == 0) next = next.copy(setupPlayer = 1, phase = Phase.MULLIGAN_BONUS) else startTurn(state.startingPlayer)
            }
            is GameAction.AttachEnergy -> {
                val card = p.hand.first { it.id == action.cardId }
                updatePokemon(action.pokemonId) { it.copy(energies = it.energies + card) }
                update(actor) { it.copy(hand = it.hand - card, flags = it.flags.copy(manualEnergy = true)) }
                emit("ManualEnergyAttached", "${definition(card).name} ligada a ${action.pokemonId}.")
            }
            is GameAction.Evolve -> {
                val card = p.hand.first { it.id == action.cardId }
                updatePokemon(action.pokemonId) { it.copy(cards = it.cards + card, lastEvolutionTurn = state.turn, restrictions = emptyList(), conditions = emptySet(), defendingAttackFailsOnTailsTurn = -1, attackImmunityTurn = -1, incomingDamageReduction = 0, damageReductionTurn = -1, incomingDamageReductionEvolutionOnly = false, outgoingDamageReduction = 0, outgoingDamageReductionTurn = -1, preventBasicNonColorlessDamageTurn = -1, preventBasicPokemonDamageTurn = -1) }
                update(actor) { it.copy(hand = it.hand - card) }
                emit("PokemonEvolved", "Evoluiu para ${definition(card).name}; dano e Energias preservados.")
                definition(card).abilities.filter { it.onEvolveDraw > 0 }.forEach { ability ->
                    val count = minOf(ability.onEvolveDraw, next.players[actor].deck.size)
                    update(actor) { it.copy(hand = it.hand + it.deck.take(count), deck = it.deck.drop(count)) }
                    emit("CardsDrawn", "Comprou $count carta(s) ao evoluir com ${ability.name}.")
                }
                if (definition(card).abilities.any { it.onEvolveHealAllEvolutionAndDiscardEnergy }) {
                    val healedPokemon = next.players[actor].pokemon().filter {
                        definition(it.top).stage != Stage.BASIC && it.damage > 0
                    }
                    if (healedPokemon.isNotEmpty()) {
                        val discardedEnergy = healedPokemon.flatMap { it.energies }
                        healedPokemon.forEach { pokemon -> updatePokemon(pokemon.id) { it.copy(damage = 0, energies = emptyList()) } }
                        update(actor) { it.copy(discard = it.discard + discardedEnergy) }
                        emit("EvolutionTeamHealed", "Curou ${healedPokemon.size} Pokémon Evoluído(s) e descartou ${discardedEnergy.size} Energia(s) com ${definition(card).abilities.first { it.onEvolveHealAllEvolutionAndDiscardEnergy }.name}.", healedPokemon.size)
                    }
                }
                definition(card).abilities.filter { it.onEvolveShuffleRandomOpponentHandPerHeads > 0 }.forEach { ability ->
                    val rng = SeededRng(next.rng)
                    val flips = List(ability.onEvolveShuffleRandomOpponentHandPerHeads) { rng.nextInt(2) == 0 }
                    emit("CoinFlipped", flips.joinToString { if (it) "CARA — ${ability.name}" else "COROA — ${ability.name}" })
                    val opponent = 1-actor
                    val hand = next.players[opponent].hand.toMutableList()
                    val revealed = mutableListOf<CardInstance>()
                    repeat(minOf(flips.count { it }, hand.size)) {
                        revealed += hand.removeAt(rng.nextInt(hand.size))
                    }
                    if (revealed.isNotEmpty()) {
                        val deck = rng.shuffle(next.players[opponent].deck + revealed)
                        next = next.copy(players = next.players.mapIndexed { owner, player ->
                            if (owner == opponent) player.copy(hand = hand, deck = deck) else player
                        })
                        emit("OpponentHandCardsShuffled", "O adversário revelou ${revealed.joinToString { definition(it).name }} e embaralhou ${revealed.size} carta(s) da mão no deck.", revealed.size)
                        emit("DeckShuffled", "Deck adversário embaralhado após ${ability.name}.")
                    }
                    next = next.copy(rng = rng.state)
                }
                val metalAttach = definition(card).abilities.firstOrNull { it.onEvolveAttachMetalFromDiscardMax > 0 }
                val metalEnergy = next.players[actor].discard.filter { definition(it).category == Category.ENERGY && definition(it).basicEnergy && definition(it).type == EnergyType.METAL }
                val metalPokemon = next.players[actor].pokemon().filter { definition(it.top).type == EnergyType.METAL }
                if (metalAttach != null && metalEnergy.isNotEmpty() && metalPokemon.isNotEmpty()) {
                    next = next.copy(phase = Phase.RESOLVE_CHOICE, afterResolution = ResolutionEnd.MAIN, resolution = Resolution(listOf(ChoiceRequest(
                        ChoiceKind.EVOLUTION_METAL_ENERGY, "${metalAttach.name}: você pode escolher até ${metalAttach.onEvolveAttachMetalFromDiscardMax} Energias Metal Básicas do descarte.",
                        metalEnergy.map { it.id }, 0, minOf(metalAttach.onEvolveAttachMetalFromDiscardMax, metalEnergy.size)))))
                } else {
                    val namedRecovery = definition(card).abilities.firstOrNull { it.onEvolveRecoverNamedTrainerFromDiscard != null }
                    val eligible = namedRecovery?.let { ability -> next.players[actor].discard.filter {
                        definition(it).category == Category.TRAINER && definition(it).name == ability.onEvolveRecoverNamedTrainerFromDiscard
                    } }.orEmpty()
                    if (namedRecovery != null && eligible.isNotEmpty()) {
                        next = next.copy(phase = Phase.RESOLVE_CHOICE, afterResolution = ResolutionEnd.MAIN, resolution = Resolution(listOf(ChoiceRequest(
                            ChoiceKind.EVOLUTION_RECOVER_NAMED_TRAINER,
                            "${namedRecovery.name}: você pode recuperar até ${namedRecovery.onEvolveRecoverNamedTrainerMax} ${namedRecovery.onEvolveRecoverNamedTrainerFromDiscard}(s) do descarte.",
                            eligible.map { it.id }, 0, minOf(namedRecovery.onEvolveRecoverNamedTrainerMax, eligible.size), visibleCards = eligible.map { it.id }))))
                    }
                }
                if (next.phase != Phase.RESOLVE_CHOICE && definition(card).abilities.any { it.onEvolveConfuseOpponentActive }) {
                    val enemyActive = next.players[1-actor].active
                    if (enemyActive != null && definition(enemyActive.top).abilities.none { it.preventOpponentEffects }) {
                        next = next.copy(phase = Phase.RESOLVE_CHOICE, afterResolution = ResolutionEnd.MAIN,
                            resolution = Resolution(listOf(ChoiceRequest(ChoiceKind.EVOLUTION_CONFUSE_ACTIVE,
                                "Usar ${definition(card).abilities.first { it.onEvolveConfuseOpponentActive }.name} e Confundir o Pokémon Ativo adversário?",
                                listOf(enemyActive.id), 0, 1))))
                    }
                }
                if (next.phase != Phase.RESOLVE_CHOICE && definition(card).abilities.any { it.onEvolveSwitch } && next.players[1-actor].bench.isNotEmpty()) {
                    next = next.copy(phase = Phase.RESOLVE_CHOICE, afterResolution = ResolutionEnd.MAIN, resolution = Resolution(listOf(ChoiceRequest(
                        ChoiceKind.EVOLUTION_SWITCH, "Usar habilidade da evolução? Escolha um Pokémon do Banco adversário ou pule.", next.players[1-actor].bench.map { it.id }, 0, 1))))
                }
            }
            is GameAction.Retreat -> {
                val active = p.active!!
                val target = p.bench.first { it.id == action.pokemonId }
                val paid = active.energies.filter { it.id in action.energyIds }
                update(actor) { it.copy(active = target.copy(enteredActiveTurn = state.turn, conditions = emptySet(), attackImmunityTurn = -1, incomingDamageReduction = 0, damageReductionTurn = -1, incomingDamageReductionEvolutionOnly = false, outgoingDamageReduction = 0, outgoingDamageReductionTurn = -1, preventBasicNonColorlessDamageTurn = -1, preventBasicPokemonDamageTurn = -1), bench = it.bench - target + active.copy(energies = active.energies - paid.toSet(), restrictions = emptyList(), attackIdsUsedWhileActive = emptyList(), conditions = emptySet(), attackImmunityTurn = -1, incomingDamageReduction = 0, damageReductionTurn = -1, incomingDamageReductionEvolutionOnly = false, outgoingDamageReduction = 0, outgoingDamageReductionTurn = -1, preventBasicNonColorlessDamageTurn = -1, preventBasicPokemonDamageTurn = -1), discard = it.discard + paid, flags = it.flags.copy(retreat = true)) }
                emit("Retreated", "Recuou descartando ${paid.size} Energia(s).")
            }
            is GameAction.Attack -> {
                val attack = attackFor(state, p.active!!, action.attackId)!!
                if (state.pendingCopiedAttackId == action.attackId) next = next.copy(pendingCopiedAttackId = null, pendingCopiedAttack = null)
                if (Effect.CannotRepeatAttackUntilLeavingActive in attack.effects) {
                    updatePokemon(p.active.id) { it.copy(attackIdsUsedWhileActive = it.attackIdsUsedWhileActive + action.attackId) }
                }
                val preventedByCoinFlip = if (p.active!!.defendingAttackFailsOnTailsTurn == state.turn) {
                    val rng = SeededRng(next.rng)
                    val tails = rng.nextInt(2) != 0
                    next = next.copy(rng = rng.state)
                    updatePokemon(p.active.id) { it.copy(defendingAttackFailsOnTailsTurn = -1) }
                    emit("CoinFlipped", if (tails) "COROA — o ataque do Pokémon Defensor não acontece." else "CARA — o ataque do Pokémon Defensor acontece.")
                    tails
                } else false
                val confusionFailed = if (!preventedByCoinFlip && SpecialCondition.CONFUSED in p.active!!.conditions) {
                    val rng = SeededRng(next.rng); val failed = rng.nextInt(2) != 0
                    next = next.copy(rng = rng.state); emit("CoinFlipped", if (failed) "COROA — confusão" else "CARA — confusão")
                    failed
                } else false
                val requiredHandSize = attack.effects.filterIsInstance<Effect.RequiresExactHandSize>().firstOrNull()?.size
                val requiresUndamagedOpponent = Effect.RequiresUndamagedOpponentActive in attack.effects
                val attackConditionFails = requiredHandSize != null && p.hand.size != requiredHandSize ||
                    requiresUndamagedOpponent && (next.players[1-actor].active?.damage != 0)
                val coinMiss = if (!confusionFailed && !attackConditionFails && Effect.CoinMiss in attack.effects) {
                    val rng = SeededRng(next.rng); val missed = rng.nextInt(2) != 0
                    next = next.copy(rng = rng.state); emit("CoinFlipped", if (missed) "COROA" else "CARA")
                    missed
                } else false
                if (preventedByCoinFlip || confusionFailed || coinMiss || attackConditionFails) {
                    if (preventedByCoinFlip) emit("AttackStoppedByCoin", "A moeda caiu em coroa; o ataque não aconteceu.")
                    else if (confusionFailed) {
                        update(actor) { player -> player.copy(active = player.active?.copy(damage = player.active!!.damage + 30)) }
                        emit("ConfusionDamage", "O ataque falhou por confusão; o atacante recebeu 30 de dano.", 30)
                    } else if (coinMiss) emit("AttackMissed", "A moeda caiu em coroa; o ataque não causou dano nem aplicou efeitos.")
                    else emit("AttackEffectFailed", "Condição da mão não atendida; o ataque não causou dano nem aplicou efeitos.")
                    val settled = settle(next, ResolutionEnd.ATTACK); next = settled.state; events += settled.events
                } else {
                val coin = attack.effects.filterIsInstance<Effect.CoinDamage>().firstOrNull()
                val knockoutHeads = if (Effect.CoinKnockOutBasic in attack.effects) {
                    val rng = SeededRng(next.rng); val result = rng.nextInt(2) == 0
                    next = next.copy(rng = rng.state); emit("CoinFlipped", if (result) "CARA" else "COROA")
                    if (result) 1 else 0
                } else null
                val heads = coin?.let { effect ->
                    val rng = SeededRng(next.rng)
                    val results = mutableListOf<Boolean>()
                    when {
                        effect.untilTails -> {
                            while (results.size < 1000) {
                                val head = rng.nextInt(2) == 0
                                results += head
                                if (!head) break
                            }
                        }
                        effect.perEnergyBothActive -> repeat(next.players[actor].active!!.energies.size + (next.players[1-actor].active?.energies?.size ?: 0)) { results += rng.nextInt(2) == 0 }
                        effect.perEnergy -> repeat(next.players[actor].active!!.energies.size) { results += rng.nextInt(2) == 0 }
                        else -> repeat(effect.coins) { results += rng.nextInt(2) == 0 }
                    }
                    next = next.copy(rng = rng.state)
                    emit("CoinFlipped", results.joinToString { if (it) "CARA" else "COROA" })
                    results.count { it }
                } ?: knockoutHeads
                val opponent = 1-actor
                val coinDamageEachPokemon = attack.effects.filterIsInstance<Effect.CoinDamageEachOpponentPokemon>().firstOrNull()
                val coinDamageTargets = if (coinDamageEachPokemon != null) {
                    val targets = next.players[opponent].pokemon()
                    val rng = SeededRng(next.rng)
                    val results = targets.map { target -> target to (rng.nextInt(2) == 0) }
                    next = next.copy(rng = rng.state)
                    if (results.isNotEmpty()) emit("CoinFlipped", results.joinToString { (target, heads) ->
                        "${definition(target.top).name}: ${if (heads) "CARA" else "COROA"}"
                    })
                    results.filter { it.second && it.first.attackImmunityTurn != state.turn &&
                        definition(it.first.top).abilities.none { ability -> ability.preventOpponentEffects } }
                        .map { it.first.id }
                } else emptyList()
                val toolTarget = next.players[opponent].active
                if (Effect.DiscardDefendingTools in attack.effects && toolTarget?.tool != null && toolTarget.attackImmunityTurn != state.turn) {
                    val tool = toolTarget.tool
                    update(opponent) { it.copy(active = it.active?.let { active -> if (active.id == toolTarget.id) active.copy(tool = null) else active }, discard = it.discard + tool) }
                    emit("DefendingToolsDiscarded", "Descartou ${definition(tool).name} do Pokémon Ativo adversário.")
                }
                val topDeckEffect = attack.effects.filterIsInstance<Effect.DiscardTopDeckEnergyDamage>().firstOrNull()
                val topDeckEffectDamage = if (topDeckEffect != null) {
                    val discarded = next.players[actor].deck.take(topDeckEffect.count)
                    val basicEnergyCount = discarded.count { definition(it).category == Category.ENERGY &&
                        definition(it).basicEnergy && definition(it).type == topDeckEffect.type }
                    update(actor) { it.copy(deck = it.deck.drop(discarded.size), discard = it.discard + discarded) }
                    emit("TopDeckDiscarded", "Descartou ${discarded.size} carta(s) do topo do deck; ${basicEnergyCount} Energia(s) ${topDeckEffect.type} Básica(s) geraram dano.", basicEnergyCount)
                    basicEnergyCount * topDeckEffect.damagePerEnergy
                } else 0
                if (Effect.DiscardOwnDeckTop in attack.effects && next.players[actor].deck.isNotEmpty()) {
                    val discarded = next.players[actor].deck.first()
                    update(actor) { it.copy(deck = it.deck.drop(1), discard = it.discard + discarded) }
                    emit("OwnDeckDiscarded", "Descartou a carta do topo do próprio deck: ${definition(discarded).name}.")
                }
                val pullSelected = Effect.PullSelectedBenchBeforeDamage in attack.effects
                if (pullSelected) {
                    val pulledId = action.targetId ?: error("É necessário selecionar o Pokémon do Banco")
                    val opponentState = next.players[opponent]
                    val pulled = opponentState.bench.first { it.id == pulledId }
                    val oldActive = opponentState.active!!
                    update(opponent) { it.copy(active = pulled, bench = it.bench.filterNot { target -> target.id == pulledId } + oldActive) }
                    emit("OpponentPokemonPulled", "${definition(pulled.top).name} entrou no Ativo antes do dano.")
                }
                val extraBenchTarget = attack.effects.any { it is Effect.DiscardEnergyThenBenchDamage || it is Effect.OptionalShuffleEnergyThenBenchDamage }
                val primaryTargetId = if (extraBenchTarget || pullSelected) null else action.targetId
                val preview = preview(next, action.attackId, primaryTargetId, heads, topDeckEffectDamage)!!
                val discardCoin = attack.effects.any { it is Effect.DiscardDefendingEnergy && it.onHeads } && coin == null
                val discardHeads = if (discardCoin) {
                    val rng = SeededRng(next.rng); val result = rng.nextInt(2) == 0
                    next = next.copy(rng = rng.state); emit("CoinFlipped", if (result) "CARA" else "COROA")
                    result
                } else false
                val statusCoin = attack.effects.any { it is Effect.StatusCondition && it.onHeads } && coin == null
                val statusHeads = if (coin != null && attack.effects.any { it is Effect.StatusCondition && it.onHeads }) {
                    (heads ?: 0) >= (attack.effects.filterIsInstance<Effect.StatusCondition>().filter { it.onHeads }.maxOfOrNull { it.minimumHeads } ?: 1)
                } else if (statusCoin) {
                    val rng = SeededRng(next.rng); val result = rng.nextInt(2) == 0
                    next = next.copy(rng = rng.state); emit("CoinFlipped", if (result) "CARA" else "COROA")
                    result
                } else false
                val immunityHeads = if (Effect.PreventAttackDamageNextTurnOnHeads in attack.effects) {
                    val rng = SeededRng(next.rng); val result = rng.nextInt(2) == 0
                    next = next.copy(rng = rng.state); emit("CoinFlipped", if (result) "CARA — proteção" else "COROA — proteção")
                    result
                } else false
                val defender = primaryTargetId?.let { id -> next.players[opponent].pokemon().first { it.id == id } } ?: next.players[opponent].active!!
                val defenderEffectsPrevented = defender.attackImmunityTurn == state.turn ||
                    definition(defender.top).abilities.any { it.preventOpponentEffects }
                if (attack.effects.any { it is Effect.OpponentHandEnergyCountDamage } || Effect.RevealOpponentHand in attack.effects) {
                    val revealed = next.players[opponent].hand.joinToString(", ") { definition(it).name }
                    emit("OpponentHandRevealed", "Mão adversária revelada: ${revealed.ifEmpty { "vazia" }}.")
                }
                emit("AttackDeclared", "${attack.name}: ${preview.damage} de dano em ${definition(defender.top).name}.", preview.damage)
                val survivalHp = if (defender.damage == 0) definition(defender.top).abilities.maxOfOrNull { it.surviveAttackKnockoutAtHp } ?: 0 else 0
                val headsSurvivalHp = definition(defender.top).abilities.maxOfOrNull { it.surviveAttackKnockoutOnHeadsAtHp } ?: 0
                val damageShield = definition(defender.top).abilities.firstOrNull { ability ->
                    ability.coinFlipPreventAttackDamage && (ability.coinFlipPreventAttackDamageEnergyType == null ||
                        defender.energies.any { definition(it).type == ability.coinFlipPreventAttackDamageEnergyType })
                }
                val shieldHeads = if (preview.damage > 0 && damageShield != null) {
                    val rng = SeededRng(next.rng); val result = rng.nextInt(2) == 0
                    next = next.copy(rng = rng.state); emit("CoinFlipped", if (result) "CARA — ${damageShield.name}: dano prevenido" else "COROA — ${damageShield.name}")
                    result
                } else false
                val wouldBeKnockedOutByDamage = !shieldHeads && preview.damage > 0 && defender.damage < preview.effectiveHp && defender.damage + preview.damage >= preview.effectiveHp
                val survivalHeads = if (headsSurvivalHp > 0 && wouldBeKnockedOutByDamage && !defenderEffectsPrevented) {
                    val rng = SeededRng(next.rng); val result = rng.nextInt(2) == 0
                    next = next.copy(rng = rng.state); emit("CoinFlipped", if (result) "CARA — Durable Body" else "COROA — Durable Body")
                    result
                } else false
                val damageAfterAttack = when {
                    shieldHeads -> defender.damage
                    survivalHeads -> (preview.effectiveHp - headsSurvivalHp).coerceAtLeast(0)
                    survivalHp > 0 && preview.damage >= preview.effectiveHp -> (preview.effectiveHp - survivalHp).coerceAtLeast(0)
                    else -> defender.damage + preview.damage
                }
                val appliedDamage = damageAfterAttack - defender.damage
                val attackDamageKnockout = wouldBeKnockedOutByDamage && !survivalHeads && damageAfterAttack >= preview.effectiveHp
                if (attackDamageKnockout) next = next.copy(pendingAttackDamageKnockoutIds = next.pendingAttackDamageKnockoutIds + defender.id)
                update(opponent) { it.copy(active = it.active?.let { pokemon -> if (pokemon.id == defender.id) pokemon.copy(damage = damageAfterAttack) else pokemon },
                    bench = it.bench.map { pokemon -> if (pokemon.id == defender.id) pokemon.copy(damage = damageAfterAttack) else pokemon }) }
                if (!defenderEffectsPrevented) attack.effects.filterIsInstance<Effect.DelayedDefendingDamageCounters>().forEach { effect ->
                    updatePokemon(defender.id) { it.copy(delayedDamageCounters = it.delayedDamageCounters + effect.counters,
                        delayedDamageTurn = state.turn + 1) }
                    emit("DelayedDamageScheduled", "${effect.counters} contadores de dano serão aplicados ao Pokémon Defensor no fim do próximo turno adversário.", effect.counters * 10)
                }
                if (!defenderEffectsPrevented) attack.effects.filterIsInstance<Effect.RetaliateDamageCountersNextTurn>().forEach { effect ->
                    updatePokemon(p.active.id) { it.copy(retaliateDamageCounters = effect.counters,
                        retaliateDamageCountersTurn = state.turn + 1) }
                    emit("RetaliationScheduled", "Se for atingido no próximo turno adversário, aplicará ${effect.counters} contadores de dano ao atacante.", effect.counters * 10)
                }
                if (appliedDamage > 0 && Effect.HealSelfByDamageDealt in attack.effects) {
                    updatePokemon(p.active.id) { it.copy(damage = (it.damage - preview.damage).coerceAtLeast(0)) }
                    emit("DamageHealed", "Curou o atacante em até ${preview.damage} de dano pelo ataque.", preview.damage)
                }
                val remainingHpAfterDamage = (preview.effectiveHp - damageAfterAttack).coerceAtLeast(0)
                emit("DamageDealt", "HP restante: $remainingHpAfterDamage.")
                if (damageAfterAttack < preview.effectiveHp && wouldBeKnockedOutByDamage)
                    emit("KnockoutPrevented", "${definition(defender.top).name} sobreviveu ao ataque e ficou com $remainingHpAfterDamage HP.", appliedDamage)
                if (!defenderEffectsPrevented && knockoutHeads == 1) {
                    val active = next.players[opponent].active
                    if (active != null && definition(active.top).stage == Stage.BASIC) {
                        val hp = effects.effectiveHp(active, next)
                        update(opponent) { player -> player.copy(active = player.active?.let { if (it.id == active.id) it.copy(damage = maxOf(it.damage, hp)) else it }) }
                        emit("AttackEffectKnockout", "Nocauteou o Pokémon Básico Ativo adversário pelo efeito do ataque.")
                    }
                }
                if (Effect.KnockOutBothActive in attack.effects) {
                    for (owner in 0..1) {
                        if (owner == opponent && defenderEffectsPrevented) continue
                        val active = next.players[owner].active ?: continue
                        val hp = effects.effectiveHp(active, next)
                        update(owner) { player -> player.copy(active = player.active?.let { if (it.id == active.id) it.copy(damage = maxOf(it.damage, hp)) else it }) }
                    }
                    emit("AttackEffectKnockout", "O ataque Nocauteou ambos os Pokémon Ativos por efeito.")
                }
                val retaliation = if (appliedDamage > 0 && defender.id == state.players[opponent].active?.id)
                    definition(defender.top).abilities.sumOf { it.retaliateDamageCounters * 10 } +
                        if (defender.retaliateDamageCountersTurn == state.turn) defender.retaliateDamageCounters * 10 else 0
                else 0
                if (retaliation > 0) {
                    update(actor) { player -> player.copy(
                        active = player.active?.copy(damage = player.active!!.damage + retaliation),
                        bench = player.bench.map { if (it.id == p.active!!.id) it.copy(damage = it.damage + retaliation) else it }
                    ) }
                    emit("CounterattackDamage", "${definition(defender.top).name} revidou com $retaliation de dano, mesmo que seja Nocauteado.", retaliation)
                }
                if (appliedDamage > 0 && defender.id == state.players[opponent].active?.id) {
                    val retaliatedConditions = definition(defender.top).abilities.mapNotNull { it.retaliateCondition }.toSet()
                    if (retaliatedConditions.isNotEmpty()) {
                        updatePokemon(p.active.id) { it.copy(conditions = it.conditions + retaliatedConditions) }
                        emit("AttackConditionRetaliation", "${definition(defender.top).name} aplicou condição(ões) especial(is) ao atacante.")
                    }
                }
                if (!defenderEffectsPrevented && Effect.PreventRetreatNextTurn in attack.effects) {
                    update(opponent) { player -> player.copy(
                        active = player.active?.let { target -> if (target.id == defender.id) target.copy(retreatBlockedTurn = state.turn + 1) else target },
                        bench = player.bench.map { target -> if (target.id == defender.id) target.copy(retreatBlockedTurn = state.turn + 1) else target }
                    ) }
                    emit("RetreatBlocked", "${definition(defender.top).name} não pode recuar no próximo turno.")
                }
                if (!defenderEffectsPrevented && Effect.PreventDefendingAttackNextTurn in attack.effects) {
                    update(opponent) { player -> player.copy(
                        active = player.active?.let { target -> if (target.id == defender.id) target.copy(restrictions = target.restrictions + AttackRestriction(null, state.turn + 1)) else target },
                        bench = player.bench.map { target -> if (target.id == defender.id) target.copy(restrictions = target.restrictions + AttackRestriction(null, state.turn + 1)) else target }
                    ) }
                    emit("AttackBlocked", "${definition(defender.top).name} não pode atacar no próximo turno.")
                }
                if (!defenderEffectsPrevented && Effect.DefendingAttackFailsOnTailsNextTurn in attack.effects) {
                    update(opponent) { player -> player.copy(
                        active = player.active?.let { target -> if (target.id == defender.id) target.copy(defendingAttackFailsOnTailsTurn = state.turn + 1) else target },
                        bench = player.bench.map { target -> if (target.id == defender.id) target.copy(defendingAttackFailsOnTailsTurn = state.turn + 1) else target }
                    ) }
                    emit("AttackCoinLock", "No próximo turno, ${definition(defender.top).name} deverá lançar uma moeda antes de atacar.")
                }
                attack.effects.filterIsInstance<Effect.SelfDamage>().forEach { effect ->
                    val ownActive = next.players[actor].active!!
                    val hp = effects.effectiveHp(ownActive, next)
                    if (effect.amount > 0 && ownActive.damage < hp && ownActive.damage+effect.amount >= hp)
                        next = next.copy(pendingAttackDamageKnockoutIds = next.pendingAttackDamageKnockoutIds + ownActive.id)
                    updatePokemon(p.active.id) { it.copy(damage = it.damage + effect.amount) }
                    emit("SelfDamage", "O atacante recebeu ${effect.amount} de dano.", effect.amount)
                }
                attack.effects.filterIsInstance<Effect.OwnBenchDamage>().forEach { effect ->
                    val targets = next.players[actor].bench
                    targets.filter { it.damage < effects.effectiveHp(it, next) && it.damage + effect.amount >= effects.effectiveHp(it, next) }
                        .forEach { next = next.copy(pendingAttackDamageKnockoutIds = next.pendingAttackDamageKnockoutIds + it.id) }
                    update(actor) { player -> player.copy(bench = player.bench.map { it.copy(damage = it.damage + effect.amount) }) }
                    if (targets.isNotEmpty()) emit("OwnBenchDamage", "Seus Pokémon do Banco receberam ${effect.amount} de dano cada.", effect.amount * targets.size)
                }
                attack.effects.filterIsInstance<Effect.StatusCondition>().forEach { effect ->
                    val opponentTypeRequirement = attack.effects.filterIsInstance<Effect.OpponentActiveTypeCondition>().firstOrNull()?.type
                    if ((effect.onSelf || !defenderEffectsPrevented) && (!effect.onHeads || (if (coin != null) (heads ?: 0) >= effect.minimumHeads else statusHeads)) &&
                        (effect.onSelf || opponentTypeRequirement == null || definition(defender.top).type == opponentTypeRequirement)) {
                        val exclusive = setOf(SpecialCondition.ASLEEP, SpecialCondition.PARALYZED, SpecialCondition.CONFUSED)
                        fun applyCondition(target: PokemonInstance) = target.copy(conditions =
                            (if (effect.condition in exclusive) target.conditions - exclusive else target.conditions) + effect.condition)
                        val owner = if (effect.onSelf) actor else opponent
                        val targetId = if (effect.onSelf) p.active!!.id else defender.id
                        update(owner) { player -> player.copy(
                            active = player.active?.let { target -> if (target.id == targetId) applyCondition(target) else target },
                            bench = player.bench.map { target -> if (target.id == targetId) applyCondition(target) else target }
                        ) }
                        emit("SpecialCondition", "${definition(if (effect.onSelf) p.active!!.top else defender.top).name}: ${effect.condition}.")
                    }
                }
                if (Effect.RecoverSelfFromSpecialConditions in attack.effects) {
                    updatePokemon(p.active.id) { it.copy(conditions = emptySet()) }
                    emit("SpecialConditionsRecovered", "${definition(p.active.top).name} se recuperou de todas as Condições Especiais.")
                }
                if (Effect.NoWeaknessNextTurn in attack.effects) {
                    updatePokemon(p.active.id) { it.copy(weaknessSuppressedTurn = state.turn + 1) }
                    emit("WeaknessRemovedNextTurn", "${definition(p.active.top).name} não terá Fraqueza durante o próximo turno adversário.")
                }
                attack.effects.filterIsInstance<Effect.ReduceEvolutionDamageNextTurn>().forEach { effect ->
                    updatePokemon(p.active.id) { it.copy(incomingDamageReduction = effect.amount, damageReductionTurn = state.turn + 1, incomingDamageReductionEvolutionOnly = true) }
                    emit("EvolutionDamageReducedNextTurn", "O atacante receberá ${effect.amount} de dano a menos de ataques de Pokémon Evoluídos no próximo turno.")
                }
                attack.effects.filterIsInstance<Effect.ReduceDamageNextTurn>().forEach { effect ->
                    updatePokemon(p.active.id) { it.copy(incomingDamageReduction = effect.amount, damageReductionTurn = state.turn + 1, incomingDamageReductionEvolutionOnly = false) }
                    emit("DamageReducedNextTurn", "O atacante receberá ${effect.amount} de dano a menos no próximo turno adversário.")
                }
                if (!defenderEffectsPrevented) attack.effects.filterIsInstance<Effect.ReduceDefendingAttackDamageNextTurn>().forEach { effect ->
                    update(opponent) { player -> player.copy(
                        active = player.active?.let { if (it.id == defender.id) it.copy(outgoingDamageReduction = effect.amount, outgoingDamageReductionTurn = state.turn + 1) else it },
                        bench = player.bench.map { if (it.id == defender.id) it.copy(outgoingDamageReduction = effect.amount, outgoingDamageReductionTurn = state.turn + 1) else it }
                    ) }
                    emit("AttackDamageReducedNextTurn", "Os ataques de ${definition(defender.top).name} causarão ${effect.amount} de dano a menos no próximo turno.")
                }
                attack.effects.filterIsInstance<Effect.DiscardDefendingEnergy>().forEach { effect ->
                    val qualifyingHeads = if (coin != null) (heads ?: 0) > 0 else discardHeads
                    if (!defenderEffectsPrevented && (!effect.onHeads || qualifyingHeads)) {
                        val target = next.players[opponent].pokemon().first { it.id == defender.id }
                        val discarded = target.energies.filter { energy ->
                            (effect.type == null || definition(energy).type == effect.type) &&
                                (!effect.specialOnly || definition(energy).category == Category.ENERGY && !definition(energy).basicEnergy)
                        }.take(if (effect.perHeads) effect.count * (heads ?: if (discardHeads) 1 else 0) else effect.count)
                        if (discarded.isNotEmpty()) {
                            update(opponent) { player -> player.copy(
                                active = player.active?.let { if (it.id == defender.id) it.copy(energies = it.energies - discarded.toSet()) else it },
                                bench = player.bench.map { if (it.id == defender.id) it.copy(energies = it.energies - discarded.toSet()) else it },
                                discard = player.discard + discarded
                            ) }
                            emit("DefendingEnergyDiscarded", "Descartou ${discarded.size} Energia(s) do Defensor.")
                        }
                    }
                }
                if (Effect.DiscardAllOpponentSpecialEnergy in attack.effects) {
                    val field = next.players[opponent].pokemon().filter { pokemon ->
                        definition(pokemon.top).abilities.none { it.preventOpponentEffects }
                    }
                    val discarded = field.flatMap { pokemon -> pokemon.energies.filter { energy ->
                        definition(energy).category == Category.ENERGY && !definition(energy).basicEnergy
                    } }
                    if (discarded.isNotEmpty()) {
                        update(opponent) { player -> player.copy(
                            active = player.active?.copy(energies = player.active.energies - discarded.toSet()),
                            bench = player.bench.map { it.copy(energies = it.energies - discarded.toSet()) },
                            discard = player.discard + discarded
                        ) }
                        emit("OpponentSpecialEnergyDiscarded", "Descartou ${discarded.size} Energia(s) Especial(is) do campo adversário.")
                    }
                }
                if (immunityHeads) {
                    updatePokemon(p.active!!.id) { it.copy(attackImmunityTurn = state.turn + 1) }
                    emit("AttackImmunityNextTurn", "O atacante impedirá dano e efeitos de ataques no próximo turno adversário.")
                }
                if (Effect.PreventBasicNonColorlessDamageNextTurn in attack.effects) {
                    updatePokemon(p.active!!.id) { it.copy(preventBasicNonColorlessDamageTurn = state.turn + 1) }
                    emit("BasicNonColorlessDamagePrevented", "O Ativo impedirá dano de ataques de Pokémon Básicos não Incolores no próximo turno adversário.")
                }
                if (Effect.PreventBasicPokemonDamageNextTurn in attack.effects) {
                    updatePokemon(p.active!!.id) { it.copy(preventBasicPokemonDamageTurn = state.turn + 1) }
                    emit("BasicPokemonDamagePrevented", "O Ativo impedirá dano de ataques de Pokémon Básicos no próximo turno adversário.")
                }
                attack.effects.filterIsInstance<Effect.DiscardRandomOpponentHand>().forEach { effect ->
                    val hand = next.players[opponent].hand
                    val count = minOf(effect.count, hand.size)
                    if (count > 0) {
                        val rng = SeededRng(next.rng)
                        val remaining = hand.toMutableList()
                        val discarded = buildList { repeat(count) { add(remaining.removeAt(rng.nextInt(remaining.size))) } }
                        next = next.copy(rng = rng.state)
                        update(opponent) { it.copy(hand = it.hand - discarded.toSet(), discard = it.discard + discarded) }
                        emit("OpponentCardsDiscarded", "O adversário descartou $count carta(s) aleatória(s) da mão.", count)
                    }
                }
                attack.effects.filterIsInstance<Effect.ShuffleRandomOpponentHand>().forEach { effect ->
                    val opponentHand = next.players[opponent].hand
                    val count = minOf(effect.count, opponentHand.size)
                    if (count > 0) {
                        val rng = SeededRng(next.rng)
                        val remaining = opponentHand.toMutableList()
                        val selected = buildList { repeat(count) { add(remaining.removeAt(rng.nextInt(remaining.size))) } }
                        val shuffledDeck = rng.shuffle(next.players[opponent].deck + selected)
                        next = next.copy(rng = rng.state)
                        update(opponent) { it.copy(hand = it.hand - selected.toSet(), deck = shuffledDeck) }
                        val names = selected.joinToString { definition(it).canonicalName }
                        emit("OpponentCardsShuffledIntoDeck", "O adversário revelou $names e embaralhou ${selected.size} carta(s) no deck.", selected.size)
                    }
                }
                attack.effects.filterIsInstance<Effect.DiscardOpponentDeck>().forEach { effect ->
                    val discarded = next.players[opponent].deck.take(effect.count)
                    update(opponent) { it.copy(deck = it.deck.drop(discarded.size), discard = it.discard + discarded) }
                    emit("OpponentDeckDiscarded", "Descartou ${discarded.size} carta(s) do topo do deck adversário.", discarded.size)
                }
                attack.effects.filterIsInstance<Effect.Heal>().forEach { heal ->
                    val healed = minOf(heal.amount, next.players[actor].active!!.damage)
                    updatePokemon(p.active.id) { it.copy(damage = it.damage - healed) }
                    emit("PokemonHealed", "Curou $healed de dano do atacante.", healed)
                }
                attack.effects.filterIsInstance<Effect.HealEachOwnPokemon>().forEach { heal ->
                    next.players[actor].pokemon().filter { it.damage > 0 }.forEach { pokemon ->
                        val healed = minOf(heal.amount, pokemon.damage)
                        updatePokemon(pokemon.id) { it.copy(damage = it.damage - healed) }
                        emit("PokemonHealed", "Curou $healed de dano de ${definition(pokemon.top).name}.", healed)
                    }
                }
                attack.effects.filterIsInstance<Effect.Recoil>().forEach { recoil ->
                    val ownActive = next.players[actor].active!!
                    val hp = effects.effectiveHp(ownActive, next)
                    if (recoil.damage > 0 && ownActive.damage < hp && ownActive.damage+recoil.damage >= hp)
                        next = next.copy(pendingAttackDamageKnockoutIds = next.pendingAttackDamageKnockoutIds + ownActive.id)
                    updatePokemon(p.active.id) { it.copy(damage = it.damage + recoil.damage) }
                    emit("RecoilDamage", "${recoil.damage} de dano no próprio atacante.")
                }
                attack.effects.filterIsInstance<Effect.RestrictNextTurn>().forEach { restriction -> updatePokemon(p.active.id) { it.copy(restrictions = it.restrictions + AttackRestriction(restriction.attackId, state.turn + 2)) } }
                attack.effects.filterIsInstance<Effect.RestrictNamedAttackNextTurn>().forEach { restriction ->
                    val restrictedId = definition(p.active.top).attacks.firstOrNull { it.name == restriction.attackName }?.id
                        ?: error("Ataque restringido ${restriction.attackName} não existe no catálogo.")
                    updatePokemon(p.active.id) { it.copy(restrictions = it.restrictions + AttackRestriction(restrictedId, state.turn + 2)) }
                }
                if (Effect.LockOpponentItemsNextTurn in attack.effects) update(1-actor) { it.copy(itemPlayLockedUntilTurn = state.turn + 1) }
                if (!defenderEffectsPrevented && Effect.LockOpponentEvolutionNextTurn in attack.effects) update(opponent) { it.copy(evolutionLockedUntilTurn = state.turn + 1) }
                attack.effects.filterIsInstance<Effect.RetaliateDamageCountersNextTurn>().forEach { effect ->
                    updatePokemon(p.active.id) { it.copy(retaliateDamageCounters = effect.counters, retaliateDamageCountersTurn = state.turn + 1) }
                }
                attack.effects.filterIsInstance<Effect.Draw>().forEach { draw ->
                    val n = minOf(draw.count, next.players[actor].deck.size)
                    update(actor) { it.copy(hand = it.hand + it.deck.take(n), deck = it.deck.drop(n)) }
                    emit("CardsDrawn", "Comprou $n carta(s) pelo ataque.")
                }
                attack.effects.filterIsInstance<Effect.EachPlayerDraw>().forEach { draw ->
                    for (playerIndex in 0..1) {
                        val n = minOf(draw.count, next.players[playerIndex].deck.size)
                        update(playerIndex) { it.copy(hand = it.hand + it.deck.take(n), deck = it.deck.drop(n)) }
                        emit("CardsDrawn", if (playerIndex == actor) "Comprou $n carta(s) pelo ataque." else "Adversário comprou $n carta(s) pelo ataque.", n)
                    }
                }
                attack.effects.filterIsInstance<Effect.ShuffleHandDraw>().forEach { effect ->
                    val hand = next.players[actor].hand
                    val rng = SeededRng(next.rng)
                    val shuffled = rng.shuffle(next.players[actor].deck + hand)
                    val count = minOf(effect.count, shuffled.size)
                    update(actor) { it.copy(hand = shuffled.take(count), deck = shuffled.drop(count)) }
                    next = next.copy(rng = rng.state)
                    emit("HandShuffledAndDrawn", "Embaralhou a mão no deck e comprou $count carta(s).", count)
                }
                attack.effects.filterIsInstance<Effect.DiscardHandDraw>().forEach { effect ->
                    val discarded = next.players[actor].hand
                    val count = minOf(effect.count, next.players[actor].deck.size)
                    update(actor) { it.copy(hand = it.deck.take(count), deck = it.deck.drop(count), discard = it.discard + discarded) }
                    emit("HandDiscardedAndDrawn", "Descartou ${discarded.size} carta(s) da mão e comprou $count.", count)
                }
                attack.effects.filterIsInstance<Effect.DrawToHand>().forEach { draw ->
                    val player = next.players[actor]
                    val n = minOf((draw.size-player.hand.size).coerceAtLeast(0), player.deck.size)
                    update(actor) { it.copy(hand = it.hand + it.deck.take(n), deck = it.deck.drop(n)) }
                    emit("CardsDrawn", "Comprou $n carta(s) até ter ${minOf(draw.size, player.hand.size+n)} na mão.")
                }
                if (Effect.DiscardStadium in attack.effects && next.stadium != null) {
                    val stadium = next.stadium!!
                    val owner = next.stadiumOwner ?: actor
                    update(owner) { it.copy(discard = it.discard + stadium) }
                    next = next.copy(stadium = null, stadiumOwner = null)
                    emit("StadiumDiscarded", "Descartou ${definition(stadium).name} do campo pelo ataque.")
                }
                if (Effect.ReturnSelfAndAttachmentsToHand in attack.effects) {
                    val returning = next.players[actor].active
                    if (returning != null && returning.damage < effects.effectiveHp(returning, next)) {
                        val cards = returning.cards + returning.energies + listOfNotNull(returning.tool)
                        update(actor) { it.copy(active = null, hand = it.hand + cards) }
                        emit("PokemonReturnedToHand", "Devolveu ${definition(returning.top).name} e todas as cartas ligadas à mão.", cards.size)
                    }
                }
                if (next.pendingOptionalEnergyAttackId == action.attackId)
                    next = next.copy(pendingOptionalEnergyAttackId = null, pendingOptionalEnergyBonus = 0)
                val choices = mutableListOf<ChoiceRequest>()
                if (Effect.ChooseOpponentAttackToRestrict in attack.effects && !defenderEffectsPrevented) {
                    val opponentAttacks = next.players[opponent].active?.let { definition(it.top).attacks }.orEmpty()
                    if (opponentAttacks.isNotEmpty()) choices += ChoiceRequest(ChoiceKind.ATTACK_RESTRICT_OPPONENT_ATTACK,
                        "Escolha 1 ataque do Pokémon Ativo adversário para bloquear no próximo turno.",
                        opponentAttacks.map { it.id }, 1, 1)
                }
                attack.effects.filterIsInstance<Effect.DiscardHandEnergyDamageBonus>().forEach { effect ->
                    val eligible = next.players[actor].hand.filter { definition(it).category == Category.ENERGY }
                    if (eligible.isNotEmpty()) choices += ChoiceRequest(ChoiceKind.ATTACK_HAND_ENERGY_DISCARD,
                        "Você pode descartar até ${effect.max} Energias da mão para aumentar o dano.",
                        eligible.map { it.id }, 0, minOf(effect.max, eligible.size), amount = effect.perEnergy)
                }
                attack.effects.filterIsInstance<Effect.DiscardOwnEnergyDamageBonus>().forEach { effect ->
                    val eligible = (if (effect.attackerOnly) listOfNotNull(next.players[actor].active) else next.players[actor].pokemon())
                        .flatMap { it.energies }.filter { energy ->
                            val def = definition(energy)
                            def.category == Category.ENERGY && (effect.type == null || def.type == effect.type) && (!effect.basicOnly || def.basicEnergy)
                        }
                    if (eligible.isNotEmpty()) choices += ChoiceRequest(ChoiceKind.ATTACK_OWN_ENERGY_DISCARD,
                        "Você pode descartar até ${minOf(effect.max, eligible.size)} Energia(s) dos seus Pokémon para aumentar o dano.",
                        eligible.map { it.id }, 0, minOf(effect.max, eligible.size), amount = effect.perEnergy)
                }
                attack.effects.filterIsInstance<Effect.MultiTargetDamage>().forEach { effect ->
                    val candidates = next.players[opponent].pokemon().filter { it.attackImmunityTurn != state.turn &&
                        definition(it.top).abilities.none { ability -> ability.preventOpponentEffects } }
                    if (effect.allowRepeat) {
                        if (candidates.isNotEmpty()) choices += ChoiceRequest(ChoiceKind.ATTACK_MULTI_DAMAGE_SINGLE_TARGET,
                            "Escolha 1 Pokémon adversário para receber ${effect.amount} de dano (${effect.targets} escolhas no total).",
                            candidates.map { it.id }, 1, 1, remaining = effect.targets, amount = effect.amount)
                    } else {
                        val count = minOf(effect.targets, candidates.size)
                        if (count > 0) choices += ChoiceRequest(ChoiceKind.ATTACK_MULTI_DAMAGE_TARGETS,
                            "Escolha $count Pokémon adversário para receber ${effect.amount} de dano cada.",
                            candidates.map { it.id }, count, count, amount = effect.amount)
                    }
                }
                if (coinDamageEachPokemon != null && coinDamageTargets.isNotEmpty()) {
                    choices += ChoiceRequest(ChoiceKind.ATTACK_MULTI_DAMAGE_TARGETS,
                        "Causará ${coinDamageEachPokemon.amount} de dano a cada Pokémon adversário que saiu cara.",
                        coinDamageTargets, coinDamageTargets.size, coinDamageTargets.size, amount = coinDamageEachPokemon.amount)
                }
                listOf(TrainerType.SUPPORTER to Effect.SearchSupporterToHand, TrainerType.ITEM to Effect.SearchItemToHand).forEach { (type, effect) ->
                    if (effect in attack.effects) {
                        val eligible = next.players[actor].deck.filter { definition(it).category == Category.TRAINER && definition(it).trainerType == type }
                        if (eligible.isNotEmpty()) choices += ChoiceRequest(ChoiceKind.ATTACK_ANY_CARD_SEARCH,
                            "Revele e escolha um ${if (type == TrainerType.SUPPORTER) "Apoiador" else "Item"} do seu deck para pôr na mão.",
                            eligible.map { it.id }, 1, 1, visibleCards = eligible.map { it.id })
                    }
                }
                if (Effect.AttachAnyBasicEnergyFromHand in attack.effects || Effect.AttachOneBasicEnergyFromHand in attack.effects) {
                    val eligible = next.players[actor].hand.filter { definition(it).category == Category.ENERGY && definition(it).basicEnergy }
                    if (eligible.isNotEmpty()) {
                        val repeatable = Effect.AttachAnyBasicEnergyFromHand in attack.effects
                        choices += ChoiceRequest(ChoiceKind.ATTACK_HAND_ENERGY,
                            if (repeatable) "Você pode ligar Energias Básicas da mão aos seus Pokémon, uma por vez." else "Escolha uma Energia Básica da mão para ligar a um dos seus Pokémon.",
                            eligible.map { it.id }, if (repeatable) 0 else 1, 1, remaining = if (repeatable) 1 else 0)
                    }
                }
                if (!defenderEffectsPrevented && knockoutHeads == 0 && Effect.CoinKnockOutBasic in attack.effects) {
                    val targets = next.players[opponent].bench.filter { definition(it.top).stage == Stage.BASIC && it.attackImmunityTurn != state.turn && definition(it.top).abilities.none { ability -> ability.preventOpponentEffects } }
                    if (targets.isNotEmpty()) choices += ChoiceRequest(ChoiceKind.ATTACK_KNOCKOUT_BASIC_BENCH,
                        "Escolha um Pokémon Básico do Banco adversário para Nocautear.", targets.map { it.id }, 1, 1)
                }
                if (!defenderEffectsPrevented && Effect.MoveOpponentDamageCounters in attack.effects) {
                    val field = next.players[opponent].pokemon().filter { definition(it.top).abilities.none { ability -> ability.preventOpponentEffects } }
                    val sources = field.filter { source -> source.damage >= 10 && field.any { it.id != source.id } }
                    if (sources.isNotEmpty()) choices += ChoiceRequest(ChoiceKind.ATTACK_MOVE_DAMAGE_SOURCE,
                        "Você pode mover contadores de dano entre Pokémon adversários.", sources.map { it.id }, 0, 1)
                }
                if (!defenderEffectsPrevented && Effect.MoveOwnBenchDamageCountersToOpponent in attack.effects) {
                    val sources = next.players[actor].bench.filter { it.damage >= 10 }
                    val targets = next.players[opponent].pokemon().filter { definition(it.top).abilities.none { ability -> ability.preventOpponentEffects } }
                    if (sources.isNotEmpty() && targets.isNotEmpty()) choices += ChoiceRequest(ChoiceKind.ATTACK_MOVE_OWN_DAMAGE_SOURCE,
                        "Escolha um Pokémon do seu Banco para mover todos os contadores de dano.", sources.map { it.id }, 1, 1)
                }
                if (!defenderEffectsPrevented && Effect.MoveEnergyBetweenOpponentPokemon in attack.effects) {
                    val field = next.players[opponent].pokemon().filter { definition(it.top).abilities.none { ability -> ability.preventOpponentEffects } }
                    val energies = field.flatMap { it.energies }
                    if (energies.isNotEmpty() && field.size >= 2) choices += ChoiceRequest(ChoiceKind.ATTACK_OPPONENT_ENERGY,
                        "Escolha uma Energia ligada ao Pokémon adversário que será movida.", energies.map { it.id }, 1, 1)
                }
                attack.effects.filterIsInstance<Effect.BenchDamage>().forEach { effect ->
                    val bench = next.players[opponent].bench.filter { it.attackImmunityTurn != state.turn &&
                        (!effect.requiresDamageCounters || it.damage > 0) }
                    if (bench.isNotEmpty()) choices += ChoiceRequest(ChoiceKind.ATTACK_DAMAGE_TARGET,
                        "Escolha um Pokémon do Banco adversário para receber ${effect.amount} de dano.", bench.map { it.id }, 1, 1, amount = effect.amount)
                }
                attack.effects.filterIsInstance<Effect.DiscardEnergyThenBenchDamage>().forEach { effect ->
                    val eligible = next.players[actor].active!!.energies
                    val count = minOf(effect.count, eligible.size)
                    if (count > 0) choices += ChoiceRequest(ChoiceKind.DISCARD_ATTACK_ENERGY,
                        "Descarte $count Energia(s) do atacante.", eligible.map { it.id }, count, count,
                        amount = effect.amount)
                }
                attack.effects.filterIsInstance<Effect.OptionalShuffleEnergyThenBenchDamage>().forEach { effect ->
                    val eligible = next.players[actor].active!!.energies
                    val targets = next.players[opponent].bench.filter { it.attackImmunityTurn != state.turn &&
                        definition(it.top).abilities.none { ability -> ability.preventOpponentEffects } }
                    if (eligible.size >= effect.count && targets.isNotEmpty()) choices += ChoiceRequest(
                        ChoiceKind.ATTACK_OPTIONAL_SHUFFLE_ENERGY,
                        "Opcional: embaralhe ${effect.count} Energias deste Pokémon no deck para causar ${effect.amount} de dano a um Pokémon do Banco adversário.",
                        eligible.map { it.id }, 0, effect.count, amount = effect.amount)
                }
                attack.effects.filterIsInstance<Effect.BenchDamageCounters>().forEach { effect ->
                    val bench = next.players[opponent].bench.filter { it.attackImmunityTurn != state.turn && definition(it.top).abilities.none { ability -> ability.preventOpponentEffects } }
                    if (bench.isNotEmpty()) choices += ChoiceRequest(ChoiceKind.ATTACK_DAMAGE_COUNTER_TARGET,
                        if (effect.distribute) "Escolha um Pokémon do Banco adversário para receber 1 dos ${effect.count} contadores de dano."
                        else "Escolha um Pokémon do Banco adversário para receber ${effect.count} contadores de dano.",
                        bench.map { it.id }, 1, 1, remaining = if (effect.distribute) effect.count else 0,
                        amount = if (effect.distribute) 10 else effect.count * 10)
                }
                if (Effect.PlaceDamageCounter in attack.effects) {
                    val targets = next.players[opponent].pokemon().filter { it.attackImmunityTurn != state.turn && definition(it.top).abilities.none { ability -> ability.preventOpponentEffects } }
                    if (targets.isNotEmpty()) choices += ChoiceRequest(ChoiceKind.ATTACK_DAMAGE_COUNTER_TARGET,
                        "Escolha 1 Pokémon adversário para receber 1 contador de dano.", targets.map { it.id }, 1, 1, amount = 10)
                }
                attack.effects.filterIsInstance<Effect.MoveEnergyToBench>().forEach { effect ->
                    val energies = next.players[actor].active!!.energies
                    val count = minOf(effect.count, energies.size, next.players[actor].bench.size)
                    if (count > 0) choices += ChoiceRequest(ChoiceKind.ATTACK_ENERGY,
                        "Escolha uma Energia do atacante para mover para ${count} Pokémon do Banco.", energies.map { it.id }, 1, 1, remaining = count)
                }
                if (Effect.MoveAllEnergyToBench in attack.effects && next.players[actor].active!!.energies.isNotEmpty() && next.players[actor].bench.isNotEmpty())
                    choices += ChoiceRequest(ChoiceKind.ATTACK_MOVE_ALL_ENERGY_TARGET,
                        "Escolha um Pokémon do seu Banco para receber todas as Energias do Ativo.", next.players[actor].bench.map { it.id }, 1, 1)
                if (Effect.ReturnAttachedEnergyToHand in attack.effects) {
                    val energies = next.players[actor].active!!.energies
                    if (energies.isNotEmpty()) choices += ChoiceRequest(ChoiceKind.ATTACK_ENERGY_TO_HAND,
                        "Escolha uma Energia do seu Pokémon Ativo para devolver à mão.", energies.map { it.id }, 1, 1)
                }
                attack.effects.filterIsInstance<Effect.DiscardBenchEnergyDamage>().forEach { effect ->
                    val energies = next.players[actor].bench.flatMap { it.energies }
                    val maxDiscard = minOf(effect.max, energies.size)
                    val weaknessMultiplier = if (Effect.IgnoreWeaknessResistance in attack.effects) 1 else {
                        val defender = next.players[opponent].active
                        val defenderType = defender?.let { definition(it.top).weakness }
                        val weaknessSuppressed = defender?.let { it.weaknessSuppressedTurn >= 0 && it.weaknessSuppressedTurn == state.turn } == true
                        if (!weaknessSuppressed && defenderType == definition(p.active!!.top).type) 2 else 1
                    }
                    if (maxDiscard > 0) choices += ChoiceRequest(ChoiceKind.ATTACK_BENCH_ENERGY_DISCARD,
                        "Você pode descartar até $maxDiscard Energia(s) do seu Banco para aumentar o dano.",
                        energies.map { it.id }, 0, maxDiscard, amount = effect.perEnergyDamage, damageMultiplier = weaknessMultiplier)
                }
                attack.effects.filterIsInstance<Effect.DiscardAttackEnergy>().forEach { effect ->
                    val eligible = next.players[actor].active!!.energies.filter { effect.type == null || definition(it).type == effect.type }
                    if (effect.all) {
                        updatePokemon(p.active.id) { it.copy(energies = it.energies - eligible.toSet()) }
                        update(actor) { it.copy(discard = it.discard + eligible) }
                        emit("AttackEnergyDiscarded", "Descartou ${eligible.size} Energia(s) do atacante.")
                    } else if (eligible.isNotEmpty()) {
                        val n = minOf(effect.count, eligible.size)
                        choices += ChoiceRequest(ChoiceKind.DISCARD_ATTACK_ENERGY, "Descarte $n Energia(s) do atacante.", eligible.map { it.id }, n, n)
                    }
                }
                if (attack.effects.filterIsInstance<Effect.MultiTargetDamage>().any { it.discardBeforeDamage } && choices.any { it.kind == ChoiceKind.DISCARD_ATTACK_ENERGY }) {
                    val discardChoices = choices.filter { it.kind == ChoiceKind.DISCARD_ATTACK_ENERGY }
                    choices.removeAll { it.kind == ChoiceKind.DISCARD_ATTACK_ENERGY }
                    choices.addAll(0, discardChoices)
                }
                attack.effects.filterIsInstance<Effect.EnergyFromDeck>().forEach { effect ->
                    val eligible = next.players[actor].deck.filter { definition(it).category == Category.ENERGY && definition(it).basicEnergy && (effect.type == null || definition(it).type == effect.type) }
                    choices += ChoiceRequest(ChoiceKind.ATTACH_DECK_ENERGY, "Buscar até ${effect.count} Energia(s) e ligar ao atacante.", eligible.map { it.id }, 0, minOf(effect.count, eligible.size))
                }
                attack.effects.filterIsInstance<Effect.AttachBasicEnergyFromDeckAny>().forEach { effect ->
                    val eligible = next.players[actor].deck.filter { definition(it).category == Category.ENERGY && definition(it).basicEnergy && (effect.type == null || definition(it).type == effect.type) }
                    val targetAvailable = if (effect.benchedOnly) next.players[actor].bench.isNotEmpty() else next.players[actor].pokemon().isNotEmpty()
                    val candidates = if (targetAvailable) eligible else emptyList()
                    choices += ChoiceRequest(ChoiceKind.ATTACK_DECK_BASIC_ENERGY_ANY,
                        "Você pode buscar até ${effect.max} Energia(s) Básica(s) e ligar aos seus Pokémon.", candidates.map { it.id }, 0, minOf(1, effect.max, candidates.size), remaining = effect.max, energyType = effect.type, benchedOnly = effect.benchedOnly)
                }
                attack.effects.filterIsInstance<Effect.AttachBasicEnergyFromDiscardAny>().forEach { effect ->
                    val eligible = next.players[actor].discard.filter { definition(it).category == Category.ENERGY && definition(it).basicEnergy && (effect.type == null || definition(it).type == effect.type) }
                    if (next.players[actor].bench.isNotEmpty() && eligible.isNotEmpty()) choices += ChoiceRequest(
                        ChoiceKind.ABILITY_DISCARD_ENERGY_FROM_DISCARD,
                        "Você pode ligar até ${minOf(effect.max, eligible.size)} Energia(s) Básica(s) do descarte aos Pokémon do Banco.",
                        eligible.map { it.id }, 0, 1, remaining = minOf(effect.max, eligible.size), energyType = effect.type, benchedOnly = true)
                }
                attack.effects.filterIsInstance<Effect.AttachBasicEnergyToEachBenchedPokemon>().forEach { effect ->
                    val eligible = next.players[actor].deck.filter { definition(it).category == Category.ENERGY && definition(it).basicEnergy && definition(it).type == effect.type }
                    val count = minOf(next.players[actor].bench.size, eligible.size)
                    if (count > 0) choices += ChoiceRequest(ChoiceKind.ATTACK_DECK_BASIC_ENERGY_ANY,
                        "Busque até $count Energia(s) Básica(s) de ${effect.type}, uma para cada Pokémon do Banco.",
                        eligible.map { it.id }, 1, 1, remaining = count, energyType = effect.type,
                        benchedOnly = true, eachBenchedPokemon = true)
                }
                if (Effect.ShuffleOwnBenchedPokemonIntoDeck in attack.effects && next.players[actor].bench.isNotEmpty()) {
                    choices += ChoiceRequest(ChoiceKind.ATTACK_SHUFFLE_OWN_BENCH,
                        "Escolha 1 Pokémon do seu Banco e todas as cartas ligadas para embaralhar no deck.",
                        next.players[actor].bench.map { it.id }, 1, 1)
                }
                if (Effect.DevolveOpponentActive in attack.effects && !defenderEffectsPrevented) {
                    next.players[opponent].active?.takeIf { it.cards.size > 1 && it.damage < effects.effectiveHp(it, next) }?.let { evolved ->
                        choices += ChoiceRequest(ChoiceKind.ATTACK_DEVOLVE_OPPONENT_ACTIVE,
                            "Devolva o Pokémon Ativo adversário e devolva sua evolução à mão.", listOf(evolved.id), 1, 1)
                    }
                }
                attack.effects.filterIsInstance<Effect.SearchPokemonToHand>().forEach { effect ->
                    val eligible = next.players[actor].deck.filter { definition(it).category == Category.POKEMON }
                    choices += ChoiceRequest(ChoiceKind.ATTACK_POKEMON_SEARCH,
                        "Você pode buscar um Pokémon no deck e colocá-lo na mão.", eligible.map { it.id }, 0, minOf(effect.max, eligible.size), visibleCards = eligible.map { it.id })
                }
                attack.effects.filterIsInstance<Effect.SearchPokemonTypeAndStadiumToHand>().forEach { effect ->
                    val eligible = next.players[actor].deck.filter { card ->
                        val def = definition(card)
                        (def.category == Category.POKEMON && def.type == effect.type) ||
                            (def.category == Category.TRAINER && def.trainerType == TrainerType.STADIUM)
                    }
                    if (eligible.isNotEmpty()) choices += ChoiceRequest(ChoiceKind.ATTACK_ANY_CARD_SEARCH,
                        "Busque até ${effect.max} em qualquer combinação de Pokémon ${effect.type} e Estádios.",
                        eligible.map { it.id }, 0, minOf(effect.max, eligible.size), visibleCards = eligible.map { it.id })
                }
                if (Effect.SearchAnyCardToHand in attack.effects && next.players[actor].deck.isNotEmpty()) {
                    val eligible = next.players[actor].deck
                    choices += ChoiceRequest(ChoiceKind.ATTACK_ANY_CARD_SEARCH,
                        "Você pode buscar qualquer carta no deck e colocá-la na mão.", eligible.map { it.id }, 0, 1,
                        visibleCards = eligible.map { it.id })
                }
                if (Effect.SearchEvolutionForSelfToEvolve in attack.effects) {
                    val currentActive = next.players[actor].active!!
                    val evolutions = next.players[actor].deck.filter { definition(it).canEvolveFrom(definition(currentActive.top)) }
                    if (evolutions.isNotEmpty()) choices += ChoiceRequest(ChoiceKind.ATTACK_EVOLVE_SELF,
                        "Escolha uma evolução do seu deck para evoluir este Pokémon Ativo.", evolutions.map { it.id }, 1, 1,
                        visibleCards = evolutions.map { it.id })
                }
                attack.effects.filterIsInstance<Effect.SearchBasicEnergyToHand>().forEach { effect ->
                    val eligible = next.players[actor].deck.filter { definition(it).category == Category.ENERGY && definition(it).basicEnergy }
                    if (eligible.isNotEmpty()) choices += ChoiceRequest(ChoiceKind.SEARCH,
                        "Revele e escolha até ${minOf(effect.max, eligible.size)} Energias Básicas para sua mão.",
                        eligible.map { it.id }, 0, minOf(effect.max, eligible.size), visibleCards = eligible.map { it.id })
                }
                attack.effects.filterIsInstance<Effect.OpponentChoosesHandShuffle>().forEach { effect ->
                    val hand = next.players[opponent].hand
                    val count = minOf(effect.count, hand.size)
                    if (count > 0) choices += ChoiceRequest(ChoiceKind.OPPONENT_SHUFFLES_OWN_HAND,
                        "Escolha $count carta(s) da sua mão para embaralhar no seu deck.", hand.map { it.id }, count, count,
                        visibleCards = hand.map { it.id })
                }
                attack.effects.filterIsInstance<Effect.HealOwnPokemon>().forEach { effect ->
                    val damaged = next.players[actor].pokemon().filter { it.damage > 0 }
                    if (damaged.isNotEmpty()) choices += ChoiceRequest(ChoiceKind.ATTACK_HEAL_TARGET,
                        "Escolha 1 dos seus Pokémon para curar até ${effect.amount} de dano.", damaged.map { it.id }, 1, 1, amount = effect.amount)
                }
                attack.effects.filterIsInstance<Effect.SwitchOwn>().forEach { effect ->
                    val bench = next.players[actor].bench
                    if (bench.isNotEmpty()) choices += ChoiceRequest(ChoiceKind.SWITCH_OWN,
                        if (effect.optional) "Você pode trocar seu Ativo por um Pokémon do Banco." else "Troque seu Ativo por um Pokémon do Banco.",
                        bench.map { it.id }, if (effect.optional) 0 else 1, 1)
                }
                if (!defenderEffectsPrevented && (Effect.SwitchOpponent in attack.effects || Effect.PullOpponentBench in attack.effects)) {
                    val opponentBench = next.players[1-actor].bench
                    if (opponentBench.isNotEmpty()) choices += ChoiceRequest(ChoiceKind.SWITCH_OPPONENT,
                        "Seu oponente escolhe o Pokémon que entra no Ativo.", opponentBench.map { it.id }, 1, 1)
                }
                attack.effects.filterIsInstance<Effect.BasicPokemonToBench>().forEach { effect ->
                    val freeSlots = (5-next.players[actor].bench.size).coerceAtLeast(0)
                    val basics = next.players[actor].deck.filter { definition(it).category == Category.POKEMON && definition(it).stage == Stage.BASIC }
                    val maxCards = minOf(effect.count, freeSlots, basics.size)
                    if (maxCards > 0) choices += ChoiceRequest(ChoiceKind.ATTACK_BENCH_POKEMON,
                        "Escolha até $maxCards Pokémon Básico(s) para colocar no Banco.", basics.map { it.id }, 0, maxCards)
                }
                attack.effects.filterIsInstance<Effect.NamedPokemonToBench>().forEach { effect ->
                    val capacity = (5 - next.players[actor].bench.size).coerceAtLeast(0)
                    val eligible = next.players[actor].deck.filter { definition(it).category == Category.POKEMON && definition(it).name == effect.name }
                    val maxCards = minOf(effect.count, capacity, eligible.size)
                    if (maxCards > 0) choices += ChoiceRequest(ChoiceKind.ATTACK_NAMED_POKEMON_TO_BENCH,
                        "Revele e coloque até $maxCards ${effect.name} do deck no Banco.", eligible.map { it.id }, 0, maxCards,
                        visibleCards = eligible.map { it.id }, targetNamePrefix = effect.name)
                }
                attack.effects.filterIsInstance<Effect.RecoverNamedPokemonToBench>().forEach { effect ->
                    val freeSlots = (5-next.players[actor].bench.size).coerceAtLeast(0)
                    val candidates = next.players[actor].discard.filter { definition(it).category == Category.POKEMON && definition(it).name == effect.name && definition(it).stage == Stage.BASIC }
                    val maxCards = minOf(effect.count, freeSlots, candidates.size)
                    if (maxCards > 0) choices += ChoiceRequest(ChoiceKind.ATTACK_RECOVER_POKEMON,
                        "Escolha até $maxCards ${effect.name} do descarte para colocar no Banco.", candidates.map { it.id }, 0, maxCards)
                }
                if (Effect.SearchRotomToBench in attack.effects) {
                    val freeSlots = (5-next.players[actor].bench.size).coerceAtLeast(0)
                    val candidates = next.players[actor].deck.filter {
                        definition(it).category == Category.POKEMON && definition(it).name.contains("Rotom", ignoreCase = true)
                    }
                    val maxCards = minOf(freeSlots, candidates.size)
                    if (maxCards > 0) choices += ChoiceRequest(ChoiceKind.ATTACK_ROTOM_SEARCH,
                        "Você pode buscar qualquer número de Pokémon com Rotom no nome para colocar no Banco.",
                        candidates.map { it.id }, 0, maxCards)
                }
                if (Effect.RecoverSupporterFromDiscard in attack.effects) {
                    val supporters = next.players[actor].discard.filter {
                        definition(it).category == Category.TRAINER && definition(it).trainerType == TrainerType.SUPPORTER
                    }
                    if (supporters.isNotEmpty()) choices += ChoiceRequest(ChoiceKind.RECOVER,
                        "Escolha um Apoiador do descarte para recuperar.", supporters.map { it.id }, 1, 1,
                        visibleCards = supporters.map { it.id })
                }
                attack.effects.filterIsInstance<Effect.SearchPokemonMatchingAttachedBasicEnergy>().forEach { effect ->
                    val types = next.players[actor].active!!.energies.filter { definition(it).basicEnergy }.map { definition(it).type }.toSet()
                    val eligible = next.players[actor].deck.filter { definition(it).category == Category.POKEMON && definition(it).type in types }
                    val maxCards = minOf(effect.max, eligible.size)
                    if (maxCards > 0) choices += ChoiceRequest(ChoiceKind.ATTACK_MATCHING_ENERGY_SEARCH,
                        "Revele e escolha até $maxCards Pokémon do tipo de uma Energia Básica ligada ao atacante.",
                        eligible.map { it.id }, 0, maxCards, visibleCards = eligible.map { it.id })
                }
                val acceleration = attack.effects.filterIsInstance<Effect.AccelerateEnergy>().firstOrNull()
                if (choices.isNotEmpty()) next = next.copy(phase = Phase.RESOLVE_CHOICE, afterResolution = ResolutionEnd.ATTACK,
                    resolution = Resolution(choices, shuffleAfter = attack.effects.any { it is Effect.EnergyFromDeck || it is Effect.AttachBasicEnergyFromDeckAny || it is Effect.AttachBasicEnergyToEachBenchedPokemon || it is Effect.SearchPokemonToHand || it is Effect.SearchPokemonTypeAndStadiumToHand || it == Effect.SearchAnyCardToHand || it == Effect.SearchSupporterToHand || it == Effect.SearchItemToHand || it is Effect.BasicPokemonToBench || it is Effect.NamedPokemonToBench || it == Effect.SearchRotomToBench || it is Effect.SearchPokemonMatchingAttachedBasicEnergy || it is Effect.SearchBasicEnergyToHand || it == Effect.SearchEvolutionForSelfToEvolve }))
                else if (acceleration != null && p.bench.isNotEmpty()) {
                    val eligible = next.players[actor].discard.filter { definition(it).category == Category.ENERGY && definition(it).type == acceleration.type }.map { it.id }
                    if (eligible.isNotEmpty()) next = next.copy(phase = Phase.RESOLVE_CHOICE, afterResolution = ResolutionEnd.ATTACK,
                        resolution = Resolution(listOf(ChoiceRequest(ChoiceKind.ENERGY_FROM_DISCARD, "Escolha uma Energia do descarte ou conclua o efeito.", eligible, 0, 1, acceleration.max, energyType = acceleration.type)),
                            shuffleAfter = Effect.SearchRotomToBench in attack.effects))
                    else {
                        if (Effect.SearchRotomToBench in attack.effects) {
                            val rng = SeededRng(next.rng)
                            update(actor) { it.copy(deck = rng.shuffle(it.deck)) }
                            next = next.copy(rng = rng.state)
                            emit("DeckShuffled", "Deck embaralhado após Roto Call.")
                        }
                        val settled = settle(next, ResolutionEnd.ATTACK); next = settled.state; events += settled.events
                    }
                } else {
                    if (Effect.SearchRotomToBench in attack.effects) {
                        val rng = SeededRng(next.rng)
                        update(actor) { it.copy(deck = rng.shuffle(it.deck)) }
                        next = next.copy(rng = rng.state)
                        emit("DeckShuffled", "Deck embaralhado após Roto Call.")
                    }
                    val settled = settle(next, ResolutionEnd.ATTACK); next = settled.state; events += settled.events
                }
                }
            }
            is GameAction.TakePrize -> {
                val card = p.prizes.first { it.id == action.cardId }
                update(actor) { it.copy(prizes = it.prizes - card, hand = it.hand + card) }
                next = next.copy(pendingPrizes = next.pendingPrizes - 1)
                emit("PrizeTaken", "Jogador ${actor+1} retirou um Prêmio.")
                val owed = next.prizesOwed.toMutableList(); owed[actor] = (owed[actor]-1).coerceAtLeast(0); next = next.copy(prizesOwed = owed)
                if (next.pendingPrizes == 0) { val resumed = resumeAfterPrizes(next); next = resumed.state; events += resumed.events }
            }
            is GameAction.Promote -> {
                val target = p.bench.first { it.id == action.pokemonId }
                update(actor) { it.copy(active = target.copy(enteredActiveTurn = state.turn), bench = it.bench - target) }
                emit("PokemonPromoted", "${definition(target.top).name} foi promovido ao Ativo.")
                val remaining = state.promotionQueue.filter { it != actor }
                if (remaining.isNotEmpty()) next = next.copy(promotionQueue = remaining, currentPlayer = remaining.first())
                else if (state.afterResolution == ResolutionEnd.MAIN) next = next.copy(phase = Phase.MAIN, currentPlayer = state.resumePlayer!!, resumePlayer = null, promotionQueue = emptyList())
                else if (state.afterResolution == ResolutionEnd.ATTACK) {
                    val resumed = resumeAfterPrizes(next.copy(promotionQueue = emptyList()))
                    next = resumed.state; events += resumed.events
                } else { val resumed = nextTurn(next.copy(promotionQueue = emptyList()), state.resumePlayer!!); next = resumed.state; events += resumed.events }
            }
            GameAction.EndTurn -> { val ended = nextTurn(next, 1-actor); next = ended.state; events += ended.events }
        }
        next = next.copy(history = state.history + ActionRecord(actor, state.turn, action, events.toList()))
        return Transition(next, Legality.Legal, events)
    }

    internal fun nextTurn(state: GameState, player: Int): Transition {
        val events = mutableListOf<GameEvent>()
        val rng = SeededRng(state.rng)
        val froslassTriggers = state.players.flatMap { it.pokemon() }.sumOf { pokemon ->
            definition(pokemon.top).abilities.maxOfOrNull { it.checkupDamageCountersPerPokemonWithAbilityExceptFroslass } ?: 0
        }
        val checked = state.copy(players = state.players.mapIndexed { owner, value ->
            fun applyDelayedDamage(pokemon: PokemonInstance): PokemonInstance {
                val clearedCoinLockTurn = if (pokemon.defendingAttackFailsOnTailsTurn in 0..state.turn) -1 else pokemon.defendingAttackFailsOnTailsTurn
                if (pokemon.delayedDamageTurn != state.turn) return pokemon.copy(defendingAttackFailsOnTailsTurn = clearedCoinLockTurn)
                val amount = pokemon.delayedDamageCounters * 10
                if (amount > 0) events += GameEvent("DelayedDamageApplied", "${definition(pokemon.top).name} recebeu $amount de dano do efeito adiado.", amount)
                return pokemon.copy(damage = pokemon.damage + amount, delayedDamageCounters = 0, delayedDamageTurn = -1,
                    defendingAttackFailsOnTailsTurn = clearedCoinLockTurn)
            }
            fun checkupDamage(pokemon: PokemonInstance): Int = if (froslassTriggers > 0 &&
                definition(pokemon.top).name != "Froslass" && definition(pokemon.top).abilities.isNotEmpty()) froslassTriggers * 10 else 0
            val rawActive = value.active ?: run {
                return@mapIndexed value.copy(bench = value.bench.map { raw ->
                    val pokemon = applyDelayedDamage(raw)
                    pokemon.copy(damage = pokemon.damage + checkupDamage(pokemon))
                })
            }
            val active = applyDelayedDamage(rawActive)
            var conditions = active.conditions
            val poison = if (SpecialCondition.POISONED in conditions) 10 else 0
            val burn = if (SpecialCondition.BURNED in conditions) 20 else 0
            val abilityDamage = checkupDamage(active)
            if (abilityDamage > 0) events += GameEvent("AbilityCheckupDamage", "Pokémon com Habilidade receberam contadores de dano por Calafrio Maldito.", abilityDamage)
            if (poison + burn > 0) events += GameEvent("StatusDamage", "${definition(active.top).name} recebeu ${poison+burn} de dano por Condições Especiais.", poison+burn)
            if (burn > 0 && rng.nextInt(2) == 0) { conditions = conditions - SpecialCondition.BURNED; events += GameEvent("StatusRecovered", "${definition(active.top).name} se recuperou da Queimadura.") }
            if (owner == state.currentPlayer) conditions = conditions - SpecialCondition.PARALYZED
            if (owner == player && SpecialCondition.ASLEEP in conditions) {
                if (rng.nextInt(2) == 0) { conditions = conditions - SpecialCondition.ASLEEP; events += GameEvent("StatusRecovered", "${definition(active.top).name} acordou.") }
                else events += GameEvent("StatusRemains", "${definition(active.top).name} continua Adormecido.")
            }
            value.copy(active = active.copy(damage = active.damage + poison + burn + abilityDamage, conditions = conditions),
                bench = value.bench.map { raw ->
                    val pokemon = applyDelayedDamage(raw)
                    pokemon.copy(damage = pokemon.damage + checkupDamage(pokemon))
                })
        })
        val next = checked.copy(rng = rng.state, currentPlayer = player, turn = state.turn + 1, phase = Phase.MAIN,
            resumePlayer = null, resolution = null, promotionQueue = emptyList(), prizesOwed = listOf(0, 0), afterResolution = ResolutionEnd.MAIN,
            extraAttackAvailableFor = null, extraAttackUsedThisTurn = false)
        val p = next.players[player]
        if (p.deck.isEmpty()) return Transition(next.copy(phase = Phase.GAME_OVER, winner = 1-player,
            winReason = "O jogador ${player+1} não conseguiu comprar no início do turno."), Legality.Legal,
            events + GameEvent("GameWon", "Derrota por falta de compra obrigatória."))
        val drawn = next.copy(players = next.players.mapIndexed { i, value -> if (i != player) value else
            value.copy(hand = value.hand + value.deck.first(), deck = value.deck.drop(1), flags = TurnFlags()) })
        events += GameEvent("TurnStarted", "Turno ${next.turn} — jogador ${player+1}; comprou 1 carta.")
        val settled = settle(drawn, ResolutionEnd.MAIN)
        return settled.copy(events = events + settled.events)
    }

    internal fun settle(state: GameState, end: ResolutionEnd): Transition {
        val actor = state.currentPlayer
        val opponentActive = state.players[1-actor].active
        val opponentActiveKnockedOut = opponentActive != null && opponentActive.damage >= effects.effectiveHp(opponentActive, state)
        val festivalBonusAttack = end == ResolutionEnd.ATTACK && opponentActiveKnockedOut && !state.extraAttackUsedThisTurn &&
            state.stadium?.let { definition(it).name == "Festival Grounds" } == true &&
            state.players[actor].active?.let { attacker -> attacker.damage < effects.effectiveHp(attacker, state) &&
                definition(attacker.top).abilities.any { it.festivalGroundsMayAttackAgain } } == true
        val owed = mutableListOf(0, 0)
        val events = mutableListOf<GameEvent>()
        val players = state.players.mapIndexed { owner, player ->
            val knockedOut = player.pokemon().filter { it.damage >= effects.effectiveHp(it, state) }
            knockedOut.forEach { pokemon ->
                val attackKo = end == ResolutionEnd.ATTACK && pokemon.id in state.pendingAttackDamageKnockoutIds
                val attackerIsEx = state.players[1-owner].active?.let { definition(it.top).name.endsWith(" ex") } == true
                val shadowyConcealment = attackKo && definition(pokemon.top).type == EnergyType.DARKNESS && attackerIsEx &&
                    player.pokemon().any { field -> definition(field.top).abilities.any { ability ->
                        ability.reducePrizeForDarknessKnockoutByEx && (!ability.requirePecharuntExForPrizeReduction ||
                            player.pokemon().any { definition(it.top).name == "Pecharunt ex" })
                    } }
                val prizeCount = (definition(pokemon.top).prizes - if (shadowyConcealment) 1 else 0).coerceAtLeast(0)
                owed[1-owner] += prizeCount
                events += if (shadowyConcealment) GameEvent("PokemonKnockedOut",
                    "${definition(pokemon.top).name} foi nocauteado; $prizeCount Prêmio(s) após Shadowy Concealment.", prizeCount)
                else GameEvent("PokemonKnockedOut", "${definition(pokemon.top).name} foi nocauteado; ${definition(pokemon.top).prizes} Prêmio(s).")
            }
            val ids = knockedOut.map { it.id }.toSet()
            player.copy(active = player.active?.takeUnless { it.id in ids }, bench = player.bench.filterNot { it.id in ids },
                discard = player.discard + knockedOut.flatMap { it.cards + it.energies + listOfNotNull(it.tool) },
                lastKnockedOutTurn = if (knockedOut.isNotEmpty()) state.turn else player.lastKnockedOutTurn,
                lastKnockedOutByAttackDamageTurn = if (end == ResolutionEnd.ATTACK && knockedOut.any { it.id in state.pendingAttackDamageKnockoutIds })
                    state.turn else player.lastKnockedOutByAttackDamageTurn)
        }
        var next = state.copy(players = players, prizesOwed = owed.mapIndexed { i, n -> minOf(n, players[i].prizes.size) },
            afterResolution = end, resolution = null, resumePlayer = if (end == ResolutionEnd.ATTACK) 1-actor else actor,
            pendingAttackDamageKnockoutIds = emptySet(),
            extraAttackAvailableFor = if (festivalBonusAttack) actor else state.extraAttackAvailableFor,
            extraAttackUsedThisTurn = state.extraAttackUsedThisTurn || festivalBonusAttack)
        val conditions = (0..1).map { i -> (if (players[1-i].pokemon().isEmpty()) 1 else 0) + (if (owed[i] >= players[i].prizes.size && owed[i] > 0) 1 else 0) }
        val noPokemonWinner = (0..1).firstOrNull { players[1-it].pokemon().isEmpty() && conditions[it] > conditions[1-it] }
        if (noPokemonWinner != null) return Transition(next.copy(phase = Phase.GAME_OVER, winner = noPokemonWinner,
            winReason = "O adversário ficou sem Pokémon em jogo."), Legality.Legal, events + GameEvent("GameWon", "Vitória por condições de nocaute."))
        val prizePlayer = listOf(actor, 1-actor).firstOrNull { next.prizesOwed[it] > 0 }
        if (prizePlayer != null) {
            next = next.copy(phase = Phase.SELECT_PRIZES, currentPlayer = prizePlayer, pendingPrizes = next.prizesOwed[prizePlayer])
            return Transition(next, Legality.Legal, events + GameEvent("PrizeSelectionRequired", "Jogador ${prizePlayer+1}: escolha ${next.pendingPrizes} Prêmio(s)."))
        }
        val resumed = resumeAfterPrizes(next)
        return resumed.copy(events = events + resumed.events)
    }

    private fun resumeAfterPrizes(state: GameState): Transition {
        val nextPrizePlayer = (0..1).firstOrNull { state.prizesOwed[it] > 0 }
        if (nextPrizePlayer != null) return Transition(state.copy(phase = Phase.SELECT_PRIZES, currentPlayer = nextPrizePlayer,
            pendingPrizes = state.prizesOwed[nextPrizePlayer]), Legality.Legal)
        val conditions = (0..1).map { i -> (if (state.players[i].prizes.isEmpty()) 1 else 0) + (if (state.players[1-i].pokemon().isEmpty()) 1 else 0) }
        if (conditions.any { it > 0 }) {
            val winner = if (conditions[0] == conditions[1]) null else if (conditions[0] > conditions[1]) 0 else 1
            val why = if (winner == null) "Condições de vitória simultâneas: é necessária uma partida de desempate." else if (state.players[winner].prizes.isEmpty()) "Você retirou seu último Prêmio." else "O adversário ficou sem Pokémon em jogo."
            return Transition(state.copy(phase = Phase.GAME_OVER, winner = winner, winReason = why), Legality.Legal, listOf(GameEvent("GameOver", why)))
        }
        val nextPlayer = state.resumePlayer ?: state.currentPlayer
        // When both Actives are KO'd, the player whose turn would be next promotes first.
        val promotionOrder = listOf(nextPlayer, 1-nextPlayer).filter { state.players[it].active == null && state.players[it].bench.isNotEmpty() }
        if (promotionOrder.isNotEmpty()) return Transition(state.copy(phase = Phase.PROMOTE, currentPlayer = promotionOrder.first(),
            promotionQueue = promotionOrder), Legality.Legal)
        state.extraAttackAvailableFor?.let { player -> return Transition(state.copy(phase = Phase.MAIN, currentPlayer = player,
            resumePlayer = null, extraAttackAvailableFor = null), Legality.Legal,
            listOf(GameEvent("FestivalExtraAttack", "Festival Lead: pode atacar mais uma vez após o nocaute."))) }
        return if (state.afterResolution == ResolutionEnd.ATTACK) nextTurn(state, nextPlayer)
        else Transition(state.copy(phase = Phase.MAIN, currentPlayer = nextPlayer, resumePlayer = null), Legality.Legal)
    }

    fun legalActions(state: GameState): List<GameAction> {
        if (state.phase == Phase.RESOLVE_CHOICE) {
            val choice = state.resolution?.choices?.firstOrNull() ?: return emptyList()
            fun combinations(ids: List<String>, count: Int): Sequence<List<String>> = sequence {
                if (count == 0) yield(emptyList())
                else if (ids.size >= count) {
                    for (tail in combinations(ids.drop(1), count-1)) yield(listOf(ids.first()) + tail)
                    yieldAll(combinations(ids.drop(1), count))
                }
            }
            return (choice.min..minOf(choice.max, choice.candidates.size)).flatMap { n -> combinations(choice.candidates, n).map { GameAction.ChooseCards(it) }.toList() }
                .filter { validate(state, it) == Legality.Legal }
        }
        val p = state.players[actingPlayer(state)]
        val candidates = mutableListOf<GameAction>(GameAction.ChooseStartingPlayer(0), GameAction.ChooseStartingPlayer(1), GameAction.FinishSetup, GameAction.FinishBonusSetup, GameAction.EndTurn)
        p.hand.forEach { card ->
            candidates += GameAction.PlayTrainer(card.id)
            candidates += GameAction.ChooseActive(card.id); candidates += GameAction.PlayBasic(card.id)
            p.pokemon().forEach { target -> candidates += GameAction.AttachEnergy(card.id, target.id); candidates += GameAction.Evolve(card.id, target.id) }
        }
        p.pokemon().forEach { pokemon -> definition(pokemon.top).abilities.forEach { candidates += GameAction.UseAbility(pokemon.id, it.id) } }
        (0..minOf(p.bonusDraws, p.deck.size)).forEach { candidates += GameAction.MulliganDraw(it) }
        p.active?.let { active ->
            definition(active.top).attacks.forEach { attack ->
                if (attack.effects.any { it is Effect.DiscardEnergyThenBenchDamage }) state.players[1-state.currentPlayer].bench.forEach { candidates += GameAction.Attack(attack.id, it.id) }
                else if (attack.effects.filterIsInstance<Effect.TargetedDamage>().any { it.benchedOnly }) state.players[1-state.currentPlayer].bench.forEach { candidates += GameAction.Attack(attack.id, it.id) }
                else if (attack.effects.any { it is Effect.TargetedDamage }) state.players[1-state.currentPlayer].pokemon().forEach { candidates += GameAction.Attack(attack.id, it.id) }
                else candidates += GameAction.Attack(attack.id)
            }
            val cost = retreatCost(active, p.pokemon(), state.players[1-state.currentPlayer].pokemon())
            fun combinations(ids: List<String>, n: Int): List<List<String>> = when {
                n == 0 -> listOf(emptyList())
                ids.size < n -> emptyList()
                else -> combinations(ids.drop(1), n-1).map { listOf(ids.first()) + it } + combinations(ids.drop(1), n)
            }
            val payments = combinations(active.energies.map { it.id }, cost)
            p.bench.forEach { target -> payments.forEach { candidates += GameAction.Retreat(target.id, it) } }
        }
        p.bench.forEach { candidates += GameAction.Promote(it.id) }
        p.prizes.forEach { candidates += GameAction.TakePrize(it.id) }
        return candidates.filter { validate(state, it) == Legality.Legal }
    }
}
