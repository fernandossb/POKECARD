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
                    evolution.category != Category.POKEMON || evolution.stage == Stage.BASIC || evolution.evolvesFrom != target.top.definitionId -> reason("Evolução incompatível.")
                    else -> Legality.Legal
                }
            }
            is GameAction.Attack -> {
                val active = p.active ?: return reason("Não há Pokémon Ativo.")
                val attack = definition(active.top).attacks.find { it.id == action.attackId } ?: return reason("Ataque inexistente.")
                when {
                    state.turn == 1 -> reason("Quem começa não pode atacar no primeiro turno.")
                    !effects.supported(attack) -> reason("Efeito ainda não suportado; ataque bloqueado.")
                    active.restrictions.any { it.blockedTurn == state.turn && (it.attackId == null || it.attackId == action.attackId) } -> reason("Este Pokémon não pode usar este ataque neste turno devido ao seu ataque anterior.")
                    attack.effects.any { it is Effect.TargetedDamage } && state.players[1-state.currentPlayer].pokemon().none { it.id == action.targetId } -> reason("Escolha um Pokémon adversário como alvo.")
                    else -> energyCost(active, attackCost(state, active, attack))
                }
            }
            is GameAction.Retreat -> {
                val active = p.active ?: return reason("Sem Ativo.")
                when {
                    p.flags.retreat -> reason("Você já recuou neste turno.")
                    p.bench.none { it.id == action.pokemonId } -> reason("Escolha um Pokémon do Banco.")
                    action.energyIds.size != retreatCost(active) -> reason("Selecione exatamente o custo de recuo em Energias.")
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

    fun retreatCost(pokemon: PokemonInstance): Int = (definition(pokemon.top).retreatCost - ((pokemon.tool?.let(::definition)?.trainerEffect as? TrainerEffect.RetreatReduction)?.amount ?: 0)).coerceAtLeast(0)
    fun attackCost(state: GameState, pokemon: PokemonInstance, attack: AttackDefinition): List<EnergyType> {
        val discount = if (definition(pokemon.top).abilities.any { it.attackDiscountPerOpponentPrize }) 6-state.players[1-state.currentPlayer].prizes.size else 0
        var remaining = discount
        return attack.cost.filter { if (it == EnergyType.COLORLESS && remaining > 0) { remaining--; false } else true }
    }
    fun preview(state: GameState, attackId: String, targetId: String? = null, rolledHeads: Int? = null): AttackPreview? {
        val attacker = state.players[state.currentPlayer].active ?: return null
        val opponent = state.players[1-state.currentPlayer]
        val defender = if (targetId != null) opponent.pokemon().find { it.id == targetId } ?: return null else opponent.active ?: return null
        val attack = definition(attacker.top).attacks.find { it.id == attackId } ?: return null
        if (!effects.supported(attack)) return null
        val requirement = attack.effects.filterIsInstance<Effect.RequiresBenched>().firstOrNull()
        val hasRequirement = requirement == null || state.players[state.currentPlayer].bench.any { it.top.definitionId == requirement.definitionId }
        val boost = if (defender.id == opponent.active?.id) state.players[state.currentPlayer].flags.damageBoosts[definition(attacker.top).type] ?: 0 else 0
        val coin = attack.effects.filterIsInstance<Effect.CoinDamage>().firstOrNull()
        val extra = (coin?.let { (rolledHeads ?: it.coins/2)*it.perHeads } ?: 0) +
            if (state.players[state.currentPlayer].flags.supporter) attack.effects.filterIsInstance<Effect.SupporterBonus>().sumOf { it.damage } else 0
        val resolvedEffects = attack.effects + if (extra > 0) listOf(Effect.Damage(extra)) else emptyList()
        val damage = if (!hasRequirement) 0 else effects.damage(attacker, defender, resolvedEffects, boost,
            attack.effects.contains(Effect.IgnoreWeaknessResistance) || defender.id != opponent.active?.id)
        val hp = effects.effectiveHp(defender, state)
        val remaining = (hp - defender.damage - damage).coerceAtLeast(0)
        val uncertain = coin != null && rolledHeads == null
        val minimum = if (uncertain) preview(state, attackId, targetId, 0)!!.damage else damage
        val maximum = if (uncertain) preview(state, attackId, targetId, coin!!.coins)!!.damage else damage
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
                updatePokemon(action.pokemonId) { it.copy(cards = it.cards + card, lastEvolutionTurn = state.turn, restrictions = emptyList()) }
                update(actor) { it.copy(hand = it.hand - card) }
                emit("PokemonEvolved", "Evoluiu para ${definition(card).name}; dano e Energias preservados.")
                if (definition(card).abilities.any { it.onEvolveSwitch } && next.players[1-actor].bench.isNotEmpty()) {
                    next = next.copy(phase = Phase.RESOLVE_CHOICE, afterResolution = ResolutionEnd.MAIN, resolution = Resolution(listOf(ChoiceRequest(
                        ChoiceKind.EVOLUTION_SWITCH, "Usar habilidade da evolução? Escolha um Pokémon do Banco adversário ou pule.", next.players[1-actor].bench.map { it.id }, 0, 1))))
                }
            }
            is GameAction.Retreat -> {
                val active = p.active!!
                val target = p.bench.first { it.id == action.pokemonId }
                val paid = active.energies.filter { it.id in action.energyIds }
                update(actor) { it.copy(active = target, bench = it.bench - target + active.copy(energies = active.energies - paid.toSet(), restrictions = emptyList()), discard = it.discard + paid, flags = it.flags.copy(retreat = true)) }
                emit("Retreated", "Recuou descartando ${paid.size} Energia(s).")
            }
            is GameAction.Attack -> {
                val attack = definition(p.active!!.top).attacks.first { it.id == action.attackId }
                val coin = attack.effects.filterIsInstance<Effect.CoinDamage>().firstOrNull()
                val heads = coin?.let { effect ->
                    val rng = SeededRng(next.rng)
                    val results = List(effect.coins) { rng.nextInt(2) == 0 }
                    next = next.copy(rng = rng.state)
                    emit("CoinFlipped", results.joinToString { if (it) "CARA" else "COROA" })
                    results.count { it }
                }
                val preview = preview(state, action.attackId, action.targetId, heads)!!
                val opponent = 1-actor
                val defender = action.targetId?.let { id -> next.players[opponent].pokemon().first { it.id == id } } ?: next.players[opponent].active!!
                emit("AttackDeclared", "${attack.name}: ${preview.damage} de dano em ${definition(defender.top).name}.", preview.damage)
                update(opponent) { it.copy(active = it.active?.let { pokemon -> if (pokemon.id == defender.id) pokemon.copy(damage = pokemon.damage + preview.damage) else pokemon },
                    bench = it.bench.map { pokemon -> if (pokemon.id == defender.id) pokemon.copy(damage = pokemon.damage + preview.damage) else pokemon }) }
                emit("DamageDealt", "HP restante: ${preview.remainingHp}.")
                attack.effects.filterIsInstance<Effect.Heal>().forEach { heal ->
                    val healed = minOf(heal.amount, next.players[actor].active!!.damage)
                    updatePokemon(p.active.id) { it.copy(damage = it.damage - healed) }
                    emit("PokemonHealed", "Curou $healed de dano do atacante.", healed)
                }
                attack.effects.filterIsInstance<Effect.Recoil>().forEach { recoil -> updatePokemon(p.active.id) { it.copy(damage = it.damage + recoil.damage) }; emit("RecoilDamage", "${recoil.damage} de dano no próprio atacante.") }
                attack.effects.filterIsInstance<Effect.RestrictNextTurn>().forEach { restriction -> updatePokemon(p.active.id) { it.copy(restrictions = it.restrictions + AttackRestriction(restriction.attackId, state.turn + 2)) } }
                attack.effects.filterIsInstance<Effect.Draw>().forEach { draw ->
                    val n = minOf(draw.count, next.players[actor].deck.size)
                    update(actor) { it.copy(hand = it.hand + it.deck.take(n), deck = it.deck.drop(n)) }
                    emit("CardsDrawn", "Comprou $n carta(s) pelo ataque.")
                }
                val choices = mutableListOf<ChoiceRequest>()
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
                val acceleration = attack.effects.filterIsInstance<Effect.AccelerateEnergy>().firstOrNull()
                if (choices.isNotEmpty()) next = next.copy(phase = Phase.RESOLVE_CHOICE, afterResolution = ResolutionEnd.ATTACK,
                    resolution = Resolution(choices, shuffleAfter = attack.effects.any { it is Effect.EnergyFromDeck }))
                else if (acceleration != null && p.bench.isNotEmpty()) {
                    val eligible = next.players[actor].discard.filter { definition(it).category == Category.ENERGY && definition(it).type == acceleration.type }.map { it.id }
                    if (eligible.isNotEmpty()) next = next.copy(phase = Phase.RESOLVE_CHOICE, afterResolution = ResolutionEnd.ATTACK,
                        resolution = Resolution(listOf(ChoiceRequest(ChoiceKind.ENERGY_FROM_DISCARD, "Escolha uma Energia do descarte ou conclua o efeito.", eligible, 0, 1, acceleration.max, energyType = acceleration.type))))
                    else { val settled = settle(next, ResolutionEnd.ATTACK); next = settled.state; events += settled.events }
                } else { val settled = settle(next, ResolutionEnd.ATTACK); next = settled.state; events += settled.events }
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
        val p = state.players[player]
        val next = state.copy(currentPlayer = player, turn = state.turn + 1, phase = Phase.MAIN,
            resumePlayer = null, resolution = null, promotionQueue = emptyList(), prizesOwed = listOf(0, 0), afterResolution = ResolutionEnd.MAIN)
        if (p.deck.isEmpty()) return Transition(next.copy(phase = Phase.GAME_OVER, winner = 1-player,
            winReason = "O jogador ${player+1} não conseguiu comprar no início do turno."), Legality.Legal,
            listOf(GameEvent("GameWon", "Derrota por falta de compra obrigatória.")))
        return Transition(next.copy(players = next.players.mapIndexed { i, value -> if (i != player) value else
            value.copy(hand = value.hand + value.deck.first(), deck = value.deck.drop(1), flags = TurnFlags()) }),
            Legality.Legal, listOf(GameEvent("TurnStarted", "Turno ${next.turn} — jogador ${player+1}; comprou 1 carta.")))
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
                lastKnockedOutTurn = if (knockedOut.isNotEmpty()) state.turn else player.lastKnockedOutTurn)
        }
        var next = state.copy(players = players, prizesOwed = owed.mapIndexed { i, n -> minOf(n, players[i].prizes.size) },
            afterResolution = end, resolution = null, resumePlayer = if (end == ResolutionEnd.ATTACK) 1-actor else actor)
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
                if (attack.effects.any { it is Effect.TargetedDamage }) state.players[1-state.currentPlayer].pokemon().forEach { candidates += GameAction.Attack(attack.id, it.id) }
                else candidates += GameAction.Attack(attack.id)
            }
            val cost = retreatCost(active)
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
