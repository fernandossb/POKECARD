package com.tcgtrainer.engine

class RulesEngine(val catalog: CardCatalog = TestCatalog) {
    val effects = EffectEngine(catalog)
    private val content by lazy { ContentEngine(this) }
    fun definition(card: CardInstance) = catalog.get(card.definitionId)

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
    fun actingPlayer(state: GameState): Int = if (state.turn == 0) state.setupPlayer else state.currentPlayer
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
                when {
                    state.turn <= 2 -> reason("Não é permitido evoluir no primeiro turno de cada jogador.")
                    target.enteredPlayTurn == state.turn -> reason("Este Pokémon entrou em jogo neste turno.")
                    target.lastEvolutionTurn == state.turn -> reason("Este Pokémon já evoluiu neste turno.")
                    !evolution.canEvolveFrom(definition(target.top)) -> reason("Evolução incompatível.")
                    else -> Legality.Legal
                }
            }
            is GameAction.Attack -> {
                val active = p.active ?: return reason("Não há Pokémon Ativo.")
                val attack = definition(active.top).attacks.find { it.id == action.attackId } ?: return reason("Ataque inexistente.")
                val rocketRequirement = definition(active.top).abilities.maxOfOrNull { it.requiresTeamRocketPokemonCount } ?: 0
                val rocketCount = p.pokemon().count { definition(it.top).name.startsWith("Team Rocket's ") }
                when {
                    state.turn == 1 -> reason("Quem começa não pode atacar no primeiro turno.")
                    Effect.RestrictIfSecondPlayerFirstTurn in attack.effects && state.turn == 2 && state.currentPlayer != state.startingPlayer -> reason("Quem começa em segundo lugar não pode usar este ataque no seu primeiro turno.")
                    SpecialCondition.PARALYZED in active.conditions || SpecialCondition.ASLEEP in active.conditions -> reason("Este Pokémon não pode atacar enquanto está Paralisado ou Adormecido.")
                    rocketRequirement > rocketCount -> reason("Este Pokémon só pode atacar com pelo menos $rocketRequirement Pokémon da Equipe Rocket em jogo.")
                    !effects.supported(attack) -> reason("Efeito ainda não suportado; ataque bloqueado.")
                    active.restrictions.any { it.blockedTurn == state.turn && (it.attackId == null || it.attackId == action.attackId) } -> reason("Este Pokémon não pode usar este ataque neste turno devido ao seu ataque anterior.")
                    attack.effects.filterIsInstance<Effect.TargetedDamage>().any { it.benchedOnly } && state.players[1-state.currentPlayer].bench.none { it.id == action.targetId } -> reason("Escolha um Pokémon do Banco adversário como alvo.")
                    attack.effects.any { it is Effect.TargetedDamage } && state.players[1-state.currentPlayer].pokemon().none { it.id == action.targetId } -> reason("Escolha um Pokémon adversário como alvo.")
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
        }
        val base = if (free) 0 else definition(pokemon.top).retreatCost
        val increase = if (definition(pokemon.top).stage != Stage.BASIC) opponentPokemon.sumOf { source ->
            definition(source.top).abilities.sumOf { it.opponentActiveEvolutionRetreatCostBonus }
        } else 0
        return (base + increase - ((pokemon.tool?.let(::definition)?.trainerEffect as? TrainerEffect.RetreatReduction)?.amount ?: 0)).coerceAtLeast(0)
    }
    fun attackCost(state: GameState, pokemon: PokemonInstance, attack: AttackDefinition): List<EnergyType> {
        val discount = if (definition(pokemon.top).abilities.any { it.attackDiscountPerOpponentPrize }) 6-state.players[1-state.currentPlayer].prizes.size else 0
        var remaining = discount
        return attack.cost.filter { if (it == EnergyType.COLORLESS && remaining > 0) { remaining--; false } else true }
    }
    fun preview(state: GameState, attackId: String, targetId: String? = null, rolledHeads: Int? = null, effectDamage: Int = 0,
        forceIgnoreWeaknessResistance: Boolean = false): AttackPreview? {
        val attacker = state.players[state.currentPlayer].active ?: return null
        val opponent = state.players[1-state.currentPlayer]
        val defender = if (targetId != null) opponent.pokemon().find { it.id == targetId } ?: return null else opponent.active ?: return null
        val attack = definition(attacker.top).attacks.find { it.id == attackId } ?: return null
        if (!effects.supported(attack)) return null
        val requirement = attack.effects.filterIsInstance<Effect.RequiresBenched>().firstOrNull()
        val exactHandSize = attack.effects.filterIsInstance<Effect.RequiresExactHandSize>().firstOrNull()
        val hasRequirement = (requirement == null || state.players[state.currentPlayer].bench.any { it.top.definitionId == requirement.definitionId }) &&
            (exactHandSize == null || state.players[state.currentPlayer].hand.size == exactHandSize.size)
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
        val extra = (coin?.let { expectedHeads*it.perHeads } ?: 0) +
            if (state.players[state.currentPlayer].flags.supporter) attack.effects.filterIsInstance<Effect.SupporterBonus>().sumOf { it.damage } else 0
        val resolvedEffects = attack.effects + (if (extra > 0) listOf(Effect.Damage(extra)) else emptyList()) +
            (if (effectDamage > 0) listOf(Effect.Damage(effectDamage)) else emptyList())
        val damage = if (!hasRequirement) 0 else effects.damage(attacker, defender, resolvedEffects, boost,
            forceIgnoreWeaknessResistance || attack.effects.contains(Effect.IgnoreWeaknessResistance) || defender.id != opponent.active?.id,
            ownBenchCount = state.players[state.currentPlayer].bench.size, opponentBenchCount = opponent.bench.size,
            ownInPlayCount = state.players[state.currentPlayer].pokemon().size,
            ignoreOpponentActiveEffects = Effect.IgnoreOpponentActiveEffects in attack.effects,
            ignoreResistance = Effect.IgnoreResistance in attack.effects, turn = state.turn,
            ownHandCount = state.players[state.currentPlayer].hand.size,
            opponentActiveIsEx = state.players[1-state.currentPlayer].active?.let { definition(it.top).name.endsWith(" ex") } == true,
            targetIsOpponentActive = defender.id == state.players[1-state.currentPlayer].active?.id,
            opponentActiveEnergyCount = state.players[1-state.currentPlayer].active?.energies?.size ?: 0,
            opponentHasSpecialCondition = state.players[1-state.currentPlayer].active?.conditions?.isNotEmpty() == true,
            opponentHandCount = state.players[1-state.currentPlayer].hand.size,
            ownToolCount = state.players[state.currentPlayer].pokemon().count { it.tool != null },
            ownDamagedPokemonCount = state.players[state.currentPlayer].pokemon().count { it.damage > 0 },
            ownEnergyCounts = state.players[state.currentPlayer].pokemon().flatMap { it.energies }
                .mapNotNull { energy -> definition(energy).type?.let { it to energy } }.groupBy({ it.first }, { it.second }).mapValues { it.value.size },
            ownKnockedOutByAttackLastTurn = state.players[state.currentPlayer].lastKnockedOutByAttackDamageTurn == state.turn-1,
            opponentActiveRetreatCost = if (defender.id == opponent.active?.id)
                retreatCost(defender, opponent.pokemon(), state.players[state.currentPlayer].pokemon()) else 0,
            ownBenchWithEnergyCounts = EnergyType.entries.associateWith { type ->
                state.players[state.currentPlayer].bench.count { pokemon -> pokemon.energies.any { definition(it).type == type } }
            })
        val hp = effects.effectiveHp(defender, state)
        val survivalHp = if (defender.damage == 0) definition(defender.top).abilities.maxOfOrNull { it.surviveAttackKnockoutAtHp } ?: 0 else 0
        val remaining = if (survivalHp > 0 && damage >= hp) survivalHp else (hp - defender.damage - damage).coerceAtLeast(0)
        val uncertain = coin != null && rolledHeads == null || benchEnergyBonus > 0
        val minimum = if (coin != null && rolledHeads == null) preview(state, attackId, targetId, 0)!!.damage else damage
        val coinMaximum = if (coin != null && rolledHeads == null) preview(state, attackId, targetId, coinFlipCount)!!.damage else damage
        val bonusAfterWeakness = if (attack.effects.contains(Effect.IgnoreWeaknessResistance) || defender.id != opponent.active?.id) benchEnergyBonus
            else if (!(defender.weaknessSuppressedTurn >= 0 && defender.weaknessSuppressedTurn == state.turn) && definition(defender.top).weakness == definition(attacker.top).type) benchEnergyBonus * 2 else benchEnergyBonus
        val maximum = coinMaximum + bonusAfterWeakness
        return AttackPreview(damage, hp, defender.damage, remaining, remaining == 0, if (remaining == 0) definition(defender.top).prizes else 0,
            uncertain, minimum, maximum)
    }

    fun apply(state: GameState, action: GameAction): Transition {
        val legality = validate(state, action)
        if (legality is Legality.Illegal) return Transition(state, legality)
        val actor = actingPlayer(state)
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
                updatePokemon(action.pokemonId) { it.copy(cards = it.cards + card, lastEvolutionTurn = state.turn, restrictions = emptyList(), conditions = emptySet(), attackImmunityTurn = -1, incomingDamageReduction = 0, damageReductionTurn = -1, outgoingDamageReduction = 0, outgoingDamageReductionTurn = -1, preventBasicNonColorlessDamageTurn = -1, preventBasicPokemonDamageTurn = -1) }
                update(actor) { it.copy(hand = it.hand - card) }
                emit("PokemonEvolved", "Evoluiu para ${definition(card).name}; dano e Energias preservados.")
                definition(card).abilities.filter { it.onEvolveDraw > 0 }.forEach { ability ->
                    val count = minOf(ability.onEvolveDraw, next.players[actor].deck.size)
                    update(actor) { it.copy(hand = it.hand + it.deck.take(count), deck = it.deck.drop(count)) }
                    emit("CardsDrawn", "Comprou $count carta(s) ao evoluir com ${ability.name}.")
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
                if (next.phase != Phase.RESOLVE_CHOICE && definition(card).abilities.any { it.onEvolveSwitch } && next.players[1-actor].bench.isNotEmpty()) {
                    next = next.copy(phase = Phase.RESOLVE_CHOICE, afterResolution = ResolutionEnd.MAIN, resolution = Resolution(listOf(ChoiceRequest(
                        ChoiceKind.EVOLUTION_SWITCH, "Usar habilidade da evolução? Escolha um Pokémon do Banco adversário ou pule.", next.players[1-actor].bench.map { it.id }, 0, 1))))
                }
            }
            is GameAction.Retreat -> {
                val active = p.active!!
                val target = p.bench.first { it.id == action.pokemonId }
                val paid = active.energies.filter { it.id in action.energyIds }
                update(actor) { it.copy(active = target.copy(conditions = emptySet(), attackImmunityTurn = -1, incomingDamageReduction = 0, damageReductionTurn = -1, outgoingDamageReduction = 0, outgoingDamageReductionTurn = -1, preventBasicNonColorlessDamageTurn = -1, preventBasicPokemonDamageTurn = -1), bench = it.bench - target + active.copy(energies = active.energies - paid.toSet(), restrictions = emptyList(), conditions = emptySet(), attackImmunityTurn = -1, incomingDamageReduction = 0, damageReductionTurn = -1, outgoingDamageReduction = 0, outgoingDamageReductionTurn = -1, preventBasicNonColorlessDamageTurn = -1, preventBasicPokemonDamageTurn = -1), discard = it.discard + paid, flags = it.flags.copy(retreat = true)) }
                emit("Retreated", "Recuou descartando ${paid.size} Energia(s).")
            }
            is GameAction.Attack -> {
                val attack = definition(p.active!!.top).attacks.first { it.id == action.attackId }
                val confusionFailed = if (SpecialCondition.CONFUSED in p.active!!.conditions) {
                    val rng = SeededRng(next.rng); val failed = rng.nextInt(2) != 0
                    next = next.copy(rng = rng.state); emit("CoinFlipped", if (failed) "COROA — confusão" else "CARA — confusão")
                    failed
                } else false
                val requiredHandSize = attack.effects.filterIsInstance<Effect.RequiresExactHandSize>().firstOrNull()?.size
                val attackConditionFails = requiredHandSize != null && p.hand.size != requiredHandSize
                val coinMiss = if (!confusionFailed && !attackConditionFails && Effect.CoinMiss in attack.effects) {
                    val rng = SeededRng(next.rng); val missed = rng.nextInt(2) != 0
                    next = next.copy(rng = rng.state); emit("CoinFlipped", if (missed) "COROA" else "CARA")
                    missed
                } else false
                if (confusionFailed || coinMiss || attackConditionFails) {
                    if (confusionFailed) {
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
                val toolTarget = next.players[opponent].active
                if (Effect.DiscardDefendingTools in attack.effects && toolTarget?.tool != null && toolTarget.attackImmunityTurn != state.turn) {
                    val tool = toolTarget.tool
                    update(opponent) { it.copy(active = it.active?.let { active -> if (active.id == toolTarget.id) active.copy(tool = null) else active }, discard = it.discard + tool) }
                    emit("DefendingToolsDiscarded", "Descartou ${definition(tool).name} do Pokémon Ativo adversário.")
                }
                val preview = preview(next, action.attackId, action.targetId, heads)!!
                val discardCoin = attack.effects.any { it is Effect.DiscardDefendingEnergy && it.onHeads }
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
                val defender = action.targetId?.let { id -> next.players[opponent].pokemon().first { it.id == id } } ?: next.players[opponent].active!!
                val defenderEffectsPrevented = defender.attackImmunityTurn == state.turn
                emit("AttackDeclared", "${attack.name}: ${preview.damage} de dano em ${definition(defender.top).name}.", preview.damage)
                val survivalHp = if (defender.damage == 0) definition(defender.top).abilities.maxOfOrNull { it.surviveAttackKnockoutAtHp } ?: 0 else 0
                val headsSurvivalHp = definition(defender.top).abilities.maxOfOrNull { it.surviveAttackKnockoutOnHeadsAtHp } ?: 0
                val wouldBeKnockedOutByDamage = preview.damage > 0 && defender.damage < preview.effectiveHp && defender.damage + preview.damage >= preview.effectiveHp
                val survivalHeads = if (headsSurvivalHp > 0 && wouldBeKnockedOutByDamage && !defenderEffectsPrevented) {
                    val rng = SeededRng(next.rng); val result = rng.nextInt(2) == 0
                    next = next.copy(rng = rng.state); emit("CoinFlipped", if (result) "CARA — Durable Body" else "COROA — Durable Body")
                    result
                } else false
                val damageAfterAttack = when {
                    survivalHeads -> (preview.effectiveHp - headsSurvivalHp).coerceAtLeast(0)
                    survivalHp > 0 && preview.damage >= preview.effectiveHp -> (preview.effectiveHp - survivalHp).coerceAtLeast(0)
                    else -> defender.damage + preview.damage
                }
                val appliedDamage = damageAfterAttack - defender.damage
                val attackDamageKnockout = wouldBeKnockedOutByDamage && !survivalHeads && damageAfterAttack >= preview.effectiveHp
                if (attackDamageKnockout) next = next.copy(pendingAttackDamageKnockoutIds = next.pendingAttackDamageKnockoutIds + defender.id)
                update(opponent) { it.copy(active = it.active?.let { pokemon -> if (pokemon.id == defender.id) pokemon.copy(damage = damageAfterAttack) else pokemon },
                    bench = it.bench.map { pokemon -> if (pokemon.id == defender.id) pokemon.copy(damage = damageAfterAttack) else pokemon }) }
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
                val retaliation = if (preview.damage > 0 && defender.id == state.players[opponent].active?.id)
                    definition(defender.top).abilities.sumOf { it.retaliateDamageCounters * 10 }
                else 0
                if (retaliation > 0) {
                    update(actor) { player -> player.copy(
                        active = player.active?.copy(damage = player.active!!.damage + retaliation),
                        bench = player.bench.map { if (it.id == p.active!!.id) it.copy(damage = it.damage + retaliation) else it }
                    ) }
                    emit("CounterattackDamage", "${definition(defender.top).name} revidou com $retaliation de dano, mesmo que seja Nocauteado.", retaliation)
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
                attack.effects.filterIsInstance<Effect.SelfDamage>().forEach { effect ->
                    val ownActive = next.players[actor].active!!
                    val hp = effects.effectiveHp(ownActive, next)
                    if (effect.amount > 0 && ownActive.damage < hp && ownActive.damage+effect.amount >= hp)
                        next = next.copy(pendingAttackDamageKnockoutIds = next.pendingAttackDamageKnockoutIds + ownActive.id)
                    updatePokemon(p.active.id) { it.copy(damage = it.damage + effect.amount) }
                    emit("SelfDamage", "O atacante recebeu ${effect.amount} de dano.", effect.amount)
                }
                attack.effects.filterIsInstance<Effect.StatusCondition>().forEach { effect ->
                    if ((effect.onSelf || !defenderEffectsPrevented) && (!effect.onHeads || (if (coin != null) (heads ?: 0) >= effect.minimumHeads else statusHeads))) {
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
                attack.effects.filterIsInstance<Effect.ReduceDamageNextTurn>().forEach { effect ->
                    updatePokemon(p.active.id) { it.copy(incomingDamageReduction = effect.amount, damageReductionTurn = state.turn + 1) }
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
                    if (!defenderEffectsPrevented && (!effect.onHeads || discardHeads)) {
                        val target = next.players[opponent].pokemon().first { it.id == defender.id }
                        val discarded = target.energies.take(effect.count)
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
                attack.effects.filterIsInstance<Effect.Recoil>().forEach { recoil ->
                    val ownActive = next.players[actor].active!!
                    val hp = effects.effectiveHp(ownActive, next)
                    if (recoil.damage > 0 && ownActive.damage < hp && ownActive.damage+recoil.damage >= hp)
                        next = next.copy(pendingAttackDamageKnockoutIds = next.pendingAttackDamageKnockoutIds + ownActive.id)
                    updatePokemon(p.active.id) { it.copy(damage = it.damage + recoil.damage) }
                    emit("RecoilDamage", "${recoil.damage} de dano no próprio atacante.")
                }
                attack.effects.filterIsInstance<Effect.RestrictNextTurn>().forEach { restriction -> updatePokemon(p.active.id) { it.copy(restrictions = it.restrictions + AttackRestriction(restriction.attackId, state.turn + 2)) } }
                attack.effects.filterIsInstance<Effect.Draw>().forEach { draw ->
                    val n = minOf(draw.count, next.players[actor].deck.size)
                    update(actor) { it.copy(hand = it.hand + it.deck.take(n), deck = it.deck.drop(n)) }
                    emit("CardsDrawn", "Comprou $n carta(s) pelo ataque.")
                }
                attack.effects.filterIsInstance<Effect.DrawToHand>().forEach { draw ->
                    val player = next.players[actor]
                    val n = minOf((draw.size-player.hand.size).coerceAtLeast(0), player.deck.size)
                    update(actor) { it.copy(hand = it.hand + it.deck.take(n), deck = it.deck.drop(n)) }
                    emit("CardsDrawn", "Comprou $n carta(s) até ter ${minOf(draw.size, player.hand.size+n)} na mão.")
                }
                val choices = mutableListOf<ChoiceRequest>()
                attack.effects.filterIsInstance<Effect.MultiTargetDamage>().forEach { effect ->
                    val candidates = next.players[opponent].pokemon()
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
                attack.effects.filterIsInstance<Effect.AttachAnyBasicEnergyFromHand>().forEach {
                    val eligible = next.players[actor].hand.filter { definition(it).category == Category.ENERGY && definition(it).basicEnergy }
                    if (eligible.isNotEmpty()) choices += ChoiceRequest(ChoiceKind.ATTACK_HAND_ENERGY,
                        "Você pode ligar Energias Básicas da mão aos seus Pokémon, uma por vez.", eligible.map { it.id }, 0, 1)
                }
                if (!defenderEffectsPrevented && knockoutHeads == 0 && Effect.CoinKnockOutBasic in attack.effects) {
                    val targets = next.players[opponent].bench.filter { definition(it.top).stage == Stage.BASIC && it.attackImmunityTurn != state.turn }
                    if (targets.isNotEmpty()) choices += ChoiceRequest(ChoiceKind.ATTACK_KNOCKOUT_BASIC_BENCH,
                        "Escolha um Pokémon Básico do Banco adversário para Nocautear.", targets.map { it.id }, 1, 1)
                }
                if (!defenderEffectsPrevented && Effect.MoveOpponentDamageCounters in attack.effects) {
                    val field = next.players[opponent].pokemon()
                    val sources = field.filter { source -> source.damage >= 10 && field.any { it.id != source.id } }
                    if (sources.isNotEmpty()) choices += ChoiceRequest(ChoiceKind.ATTACK_MOVE_DAMAGE_SOURCE,
                        "Você pode mover contadores de dano entre Pokémon adversários.", sources.map { it.id }, 0, 1)
                }
                attack.effects.filterIsInstance<Effect.BenchDamage>().forEach { effect ->
                    val bench = next.players[opponent].bench.filter { it.attackImmunityTurn != state.turn }
                    if (bench.isNotEmpty()) choices += ChoiceRequest(ChoiceKind.ATTACK_DAMAGE_TARGET,
                        "Escolha um Pokémon do Banco adversário para receber ${effect.amount} de dano.", bench.map { it.id }, 1, 1, amount = effect.amount)
                }
                attack.effects.filterIsInstance<Effect.BenchDamageCounters>().forEach { effect ->
                    val bench = next.players[opponent].bench.filter { it.attackImmunityTurn != state.turn }
                    if (!defenderEffectsPrevented && bench.isNotEmpty()) choices += ChoiceRequest(ChoiceKind.ATTACK_DAMAGE_COUNTER_TARGET,
                        "Escolha um Pokémon do Banco adversário para receber ${effect.count} contadores de dano.",
                        bench.map { it.id }, 1, 1, amount = effect.count * 10)
                }
                attack.effects.filterIsInstance<Effect.MoveEnergyToBench>().forEach { effect ->
                    val energies = next.players[actor].active!!.energies
                    val count = minOf(effect.count, energies.size, next.players[actor].bench.size)
                    if (count > 0) choices += ChoiceRequest(ChoiceKind.ATTACK_ENERGY,
                        "Escolha uma Energia do atacante para mover para ${count} Pokémon do Banco.", energies.map { it.id }, 1, 1, remaining = count)
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
                attack.effects.filterIsInstance<Effect.EnergyFromDeck>().forEach { effect ->
                    val eligible = next.players[actor].deck.filter { definition(it).category == Category.ENERGY && definition(it).type == effect.type }
                    choices += ChoiceRequest(ChoiceKind.ATTACH_DECK_ENERGY, "Buscar até ${effect.count} Energia(s) e ligar ao atacante.", eligible.map { it.id }, 0, minOf(effect.count, eligible.size))
                }
                attack.effects.filterIsInstance<Effect.AttachBasicEnergyFromDeckAny>().forEach { effect ->
                    val eligible = next.players[actor].deck.filter { definition(it).category == Category.ENERGY && definition(it).basicEnergy }
                    choices += ChoiceRequest(ChoiceKind.ATTACK_DECK_BASIC_ENERGY_ANY,
                        "Você pode buscar até ${effect.max} Energia(s) Básica(s) e ligar aos seus Pokémon.", eligible.map { it.id }, 0, minOf(1, effect.max, eligible.size), remaining = effect.max)
                }
                attack.effects.filterIsInstance<Effect.SearchPokemonToHand>().forEach { effect ->
                    val eligible = next.players[actor].deck.filter { definition(it).category == Category.POKEMON }
                    choices += ChoiceRequest(ChoiceKind.ATTACK_POKEMON_SEARCH,
                        "Você pode buscar um Pokémon no deck e colocá-lo na mão.", eligible.map { it.id }, 0, minOf(effect.max, eligible.size), visibleCards = eligible.map { it.id })
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
                if (!defenderEffectsPrevented && Effect.SwitchOpponent in attack.effects) {
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
                    resolution = Resolution(choices, shuffleAfter = attack.effects.any { it is Effect.EnergyFromDeck || it is Effect.AttachBasicEnergyFromDeckAny || it is Effect.SearchPokemonToHand || it is Effect.BasicPokemonToBench || it == Effect.SearchRotomToBench || it is Effect.SearchPokemonMatchingAttachedBasicEnergy }))
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
                update(actor) { it.copy(active = target, bench = it.bench - target) }
                emit("PokemonPromoted", "${definition(target.top).name} foi promovido ao Ativo.")
                val remaining = state.promotionQueue.filter { it != actor }
                if (remaining.isNotEmpty()) next = next.copy(promotionQueue = remaining, currentPlayer = remaining.first())
                else if (state.afterResolution == ResolutionEnd.MAIN) next = next.copy(phase = Phase.MAIN, currentPlayer = state.resumePlayer!!, resumePlayer = null, promotionQueue = emptyList())
                else { val resumed = nextTurn(next.copy(promotionQueue = emptyList()), state.resumePlayer!!); next = resumed.state; events += resumed.events }
            }
            GameAction.EndTurn -> { val ended = nextTurn(next, 1-actor); next = ended.state; events += ended.events }
        }
        next = next.copy(history = state.history + ActionRecord(actor, state.turn, action, events.toList()))
        return Transition(next, Legality.Legal, events)
    }

    internal fun nextTurn(state: GameState, player: Int): Transition {
        val events = mutableListOf<GameEvent>()
        val rng = SeededRng(state.rng)
        val checked = state.copy(players = state.players.mapIndexed { owner, value ->
            val active = value.active ?: return@mapIndexed value
            var conditions = active.conditions
            val poison = if (SpecialCondition.POISONED in conditions) 10 else 0
            val burn = if (SpecialCondition.BURNED in conditions) 20 else 0
            if (poison + burn > 0) events += GameEvent("StatusDamage", "${definition(active.top).name} recebeu ${poison+burn} de dano por Condições Especiais.", poison+burn)
            if (burn > 0 && rng.nextInt(2) == 0) { conditions = conditions - SpecialCondition.BURNED; events += GameEvent("StatusRecovered", "${definition(active.top).name} se recuperou da Queimadura.") }
            if (owner == state.currentPlayer) conditions = conditions - SpecialCondition.PARALYZED
            if (owner == player && SpecialCondition.ASLEEP in conditions) {
                if (rng.nextInt(2) == 0) { conditions = conditions - SpecialCondition.ASLEEP; events += GameEvent("StatusRecovered", "${definition(active.top).name} acordou.") }
                else events += GameEvent("StatusRemains", "${definition(active.top).name} continua Adormecido.")
            }
            value.copy(active = active.copy(damage = active.damage + poison + burn, conditions = conditions))
        })
        val next = checked.copy(rng = rng.state, currentPlayer = player, turn = state.turn + 1, phase = Phase.MAIN,
            resumePlayer = null, resolution = null, promotionQueue = emptyList(), prizesOwed = listOf(0, 0), afterResolution = ResolutionEnd.MAIN)
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
        val owed = mutableListOf(0, 0)
        val events = mutableListOf<GameEvent>()
        val players = state.players.mapIndexed { owner, player ->
            val knockedOut = player.pokemon().filter { it.damage >= effects.effectiveHp(it, state) }
            knockedOut.forEach { pokemon ->
                owed[1-owner] += definition(pokemon.top).prizes
                events += GameEvent("PokemonKnockedOut", "${definition(pokemon.top).name} foi nocauteado; ${definition(pokemon.top).prizes} Prêmio(s).")
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
            pendingAttackDamageKnockoutIds = emptySet())
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
                if (attack.effects.filterIsInstance<Effect.TargetedDamage>().any { it.benchedOnly }) state.players[1-state.currentPlayer].bench.forEach { candidates += GameAction.Attack(attack.id, it.id) }
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
