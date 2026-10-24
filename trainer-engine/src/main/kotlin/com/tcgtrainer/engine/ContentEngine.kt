package com.tcgtrainer.engine

/** Reusable card effect transactions. All choices are persisted, validated and replayable. */
internal class ContentEngine(private val rules: RulesEngine) {
    private fun def(card: CardInstance) = rules.definition(card)
    private fun illegal(reason: String) = Legality.Illegal(reason)
    private fun abilityUsageKey(pokemon: PokemonInstance, ability: AbilityDefinition) =
        if (ability.usagePerPokemon) "${pokemon.id}:${ability.usageKey}" else ability.usageKey
    fun matches(card: CardInstance, filter: CardFilter): Boolean {
        val d = def(card)
        return when (filter) {
            CardFilter.POKEMON -> d.category == Category.POKEMON
            CardFilter.BASIC_FIGHTING_OR_ENERGY -> d.type == EnergyType.FIGHTING && (d.category == Category.ENERGY || d.category == Category.POKEMON && d.stage == Stage.BASIC)
            CardFilter.POKEMON_OR_ENERGY -> d.category == Category.POKEMON || d.category == Category.ENERGY
            CardFilter.ITEM -> d.trainerType == TrainerType.ITEM
            CardFilter.TOOL -> d.trainerType == TrainerType.TOOL
            CardFilter.SUPPORTER -> d.trainerType == TrainerType.SUPPORTER
            CardFilter.STADIUM -> d.trainerType == TrainerType.STADIUM
            CardFilter.BASIC_POKEMON -> d.category == Category.POKEMON && d.stage == Stage.BASIC
            CardFilter.BASIC_ENERGY, CardFilter.ENERGY -> d.category == Category.ENERGY
            CardFilter.DARKNESS_POKEMON -> d.category == Category.POKEMON && d.type == EnergyType.DARKNESS
            CardFilter.METAL_POKEMON -> d.category == Category.POKEMON && d.type == EnergyType.METAL
        }
    }
    fun validate(s: GameState, action: GameAction): Legality {
        val p = s.players[s.currentPlayer]
        return when (action) {
            is GameAction.ChooseCards -> {
                val choice = s.resolution?.choices?.firstOrNull() ?: return illegal("Nenhuma escolha pendente.")
                when {
                    action.cardIds.distinct().size != action.cardIds.size -> illegal("Não repita cartas.")
                    action.cardIds.size !in choice.min..choice.max -> illegal("Escolha de ${choice.min} a ${choice.max} carta(s).")
                    !choice.candidates.containsAll(action.cardIds) -> illegal("Alvo inválido para este efeito.")
                    choice.kind == ChoiceKind.SONIA && p.deck.filter { it.id in action.cardIds }.map { def(it).category }.distinct().size > 1 -> illegal("Sonia busca Pokémon Básicos OU Energias Básicas; não misture as categorias.")
                    else -> Legality.Legal
                }
            }
            is GameAction.PlayTrainer -> {
                val card = p.hand.find { it.id == action.cardId } ?: return illegal("Carta fora da mão.")
                val d = def(card)
                val effect = d.trainerEffect ?: return illegal("Efeito de Treinador não suportado.")
                if (d.trainerType == TrainerType.SUPPORTER) {
                    if (s.turn == 1) return illegal("Quem começa não pode usar Apoiador no primeiro turno.")
                    if (p.flags.supporter) return illegal("Você já usou um Apoiador neste turno.")
                }
                when (effect) {
                    is TrainerEffect.Search -> when {
                        p.hand.size-1 < effect.discardCost -> illegal("É preciso descartar ${effect.discardCost} outras cartas da mão.")
                        p.deck.isEmpty() -> illegal("Seu deck está vazio.")
                        else -> Legality.Legal
                    }
                    is TrainerEffect.DrawTo -> when {
                        p.hand.size-1 < effect.discardCost -> illegal("É preciso descartar ${effect.discardCost} outra carta.")
                        p.hand.size-1-effect.discardCost >= effect.size || p.deck.isEmpty() -> illegal("Este efeito não compraria cartas.")
                        else -> Legality.Legal
                    }
                    is TrainerEffect.Switch -> if ((if (effect.opponent) s.players[1-s.currentPlayer] else p).bench.isEmpty()) illegal("Não há Pokémon no Banco para trocar.") else Legality.Legal
                    TrainerEffect.Recover -> if (p.discard.none { matches(it, CardFilter.POKEMON_OR_ENERGY) }) illegal("Não há Pokémon ou Energia Básica no descarte.") else Legality.Legal
                    is TrainerEffect.RetreatReduction -> if (p.pokemon().none { it.tool == null }) illegal("Seus Pokémon já têm uma Ferramenta.") else Legality.Legal
                    is TrainerEffect.HpModifier -> when {
                        p.flags.stadium -> illegal("Você já jogou um Estádio neste turno.")
                        s.stadium?.let { def(it).name == d.name } == true -> illegal("Já existe um Estádio com esse nome em jogo.")
                        else -> Legality.Legal
                    }
                    is TrainerEffect.DamageBoost -> Legality.Legal
                    is TrainerEffect.ShuffleHandDraw -> if (p.deck.isEmpty() && p.hand.size <= 1) illegal("Não há cartas para embaralhar ou comprar.") else Legality.Legal
                    is TrainerEffect.DrawCards, TrainerEffect.GreatBall, TrainerEffect.Sonia -> if (p.deck.isEmpty()) illegal("Seu deck está vazio.") else Legality.Legal
                    TrainerEffect.CoinSwitch -> if (s.players[1-s.currentPlayer].bench.isEmpty()) illegal("Não há Pokémon adversário no Banco.") else Legality.Legal
                    is TrainerEffect.HealPokemon -> if ((if (effect.activeOnly) listOfNotNull(p.active) else p.pokemon()).none { it.damage > 0 }) illegal("Não há dano para curar no alvo permitido.") else Legality.Legal
                    is TrainerEffect.RecoverEnergy -> if (p.discard.none { def(it).category == Category.ENERGY }) illegal("Não há Energia Básica no descarte.") else Legality.Legal
                }
            }
            is GameAction.UseAbility -> {
                val pokemon = p.pokemon().find { it.id == action.pokemonId } ?: return illegal("Pokémon fora de jogo.")
                val ability = def(pokemon.top).abilities.find { it.id == action.abilityId } ?: return illegal("Habilidade inexistente.")
                when {
                    ability.onEvolveSwitch -> illegal("Esta habilidade só pode ser usada ao evoluir da mão.")
                    ability.onEvolveDraw > 0 -> illegal("Esta habilidade só pode ser usada ao evoluir da mão.")
                    ability.onEvolveShuffleRandomOpponentHandPerHeads > 0 -> illegal("Esta habilidade só pode ser usada ao evoluir da mão.")
                    ability.onEvolveAttachMetalFromDiscardMax > 0 -> illegal("Esta habilidade só pode ser usada ao evoluir da mão.")
                    ability.devolveOpponentPokemonMax > 0 && s.players[1-s.currentPlayer].pokemon().none { it.cards.size > 1 } -> illegal("Não há Pokémon adversário evoluído para desvoluir.")
                    ability.attackDiscountPerOpponentPrize -> illegal("Esta habilidade é aplicada automaticamente ao custo do ataque.")
                    ability.opponentActiveEvolutionRetreatCostBonus > 0 -> illegal("Esta habilidade passiva é aplicada automaticamente ao recuo.")
                    ability.draw <= 0 && ability.drawTo <= 0 && ability.damageCounters <= 0 && ability.attachHandEnergyType == null && !ability.shuffleSelfIntoDeck && ability.healDamage <= 0 && ability.recoverBasicPokemonToBenchMaxHp <= 0 && ability.devolveOpponentPokemonMax <= 0 && ability.onEvolveShuffleRandomOpponentHandPerHeads <= 0 -> illegal("Habilidade não suportada.")
                    ability.damageCounters > 0 && s.players[1-s.currentPlayer].pokemon().size < ability.damageTargets -> illegal("Não há Pokémon adversários suficientes para esta habilidade.")
                    abilityUsageKey(pokemon, ability) in p.flags.abilitiesUsed -> illegal("Esta habilidade já foi usada neste turno.")
                    ability.requiresActive && p.active?.id != pokemon.id -> illegal("Esta habilidade só pode ser usada com este Pokémon no Ativo.")
                    ability.shuffleSelfIntoDeck && p.bench.isEmpty() -> illegal("É preciso ter um Pokémon no Banco para usar esta habilidade.")
                    p.hand.size < ability.discardHandCount -> illegal("Falta uma carta na mão para pagar o custo da habilidade.")
                    ability.requiresInPlay != null && p.pokemon().none { it.top.definitionId == ability.requiresInPlay } -> illegal("É preciso ter ${rules.catalog.get(ability.requiresInPlay).name} em jogo.")
                    ability.requiresPreviousKnockout && p.lastKnockedOutTurn != s.turn-1 -> illegal("Nenhum dos seus Pokémon foi nocauteado no último turno adversário.")
                    ability.discardEnergyType != null && p.hand.none { def(it).category == Category.ENERGY && def(it).type == ability.discardEnergyType } -> illegal("É preciso descartar uma Energia de Luta Básica da mão.")
                    ability.attachHandEnergyType != null && p.hand.none { def(it).category == Category.ENERGY && def(it).basicEnergy && def(it).type == ability.attachHandEnergyType } -> illegal("Não há Energia Básica do tipo necessário na mão.")
                    ability.healDamage > 0 && p.pokemon().none { it.damage > 0 } -> illegal("Não há dano para curar.")
                    ability.recoverBasicPokemonToBenchMaxHp > 0 && p.bench.size >= 5 -> illegal("O Banco já tem 5 Pokémon.")
                    ability.recoverBasicPokemonToBenchMaxHp > 0 && p.discard.none { def(it).category == Category.POKEMON && def(it).stage == Stage.BASIC && def(it).hp <= ability.recoverBasicPokemonToBenchMaxHp } -> illegal("Não há Pokémon Básico válido no descarte.")
                    p.deck.isEmpty() && (ability.draw > 0 || ability.drawTo > p.hand.size) -> illegal("Seu deck está vazio.")
                    else -> Legality.Legal
                }
            }
            else -> illegal("Ação não pertence ao resolvedor de conteúdo.")
        }
    }

    fun apply(state: GameState, action: GameAction): Transition {
        var s = state
        val actor = s.currentPlayer
        val events = mutableListOf<GameEvent>()
        fun update(index: Int = actor, transform: (PlayerState) -> PlayerState) { s = s.copy(players = s.players.mapIndexed { i, p -> if (i == index) transform(p) else p }) }
        fun draw(count: Int) {
            val n = minOf(count.coerceAtLeast(0), s.players[actor].deck.size)
            update { it.copy(hand = it.hand + it.deck.take(n), deck = it.deck.drop(n)) }
            events += GameEvent("CardsDrawn", "Comprou $n carta(s).")
        }
        fun shuffle() {
            val rng = SeededRng(s.rng)
            update { it.copy(deck = rng.shuffle(it.deck)) }
            s = s.copy(rng = rng.state)
            events += GameEvent("DeckShuffled", "Deck embaralhado.")
        }
        fun begin(resolution: Resolution) { s = s.copy(resolution = resolution, phase = Phase.RESOLVE_CHOICE, afterResolution = ResolutionEnd.MAIN) }
        when (action) {
            is GameAction.PlayTrainer -> {
                val p = s.players[actor]
                val card = p.hand.first { it.id == action.cardId }; val d = def(card)
                val effect = d.trainerEffect!!
                update { it.copy(hand = it.hand - card,
                    discard = if (d.trainerType == TrainerType.TOOL || d.trainerType == TrainerType.STADIUM) it.discard else it.discard + card,
                    flags = it.flags.copy(supporter = it.flags.supporter || d.trainerType == TrainerType.SUPPORTER)) }
                events += GameEvent("TrainerPlayed", "Jogou ${d.name} (${d.set} ${d.number}).")
                val hand = s.players[actor].hand
                fun cost(n: Int) = if (n == 0) emptyList() else listOf(ChoiceRequest(ChoiceKind.DISCARD, "Escolha $n carta(s) para pagar o custo.", hand.map { it.id }, n, n))
                when (effect) {
                    is TrainerEffect.Search -> begin(Resolution(cost(effect.discardCost) + effect.filters.map { filter ->
                        ChoiceRequest(ChoiceKind.SEARCH, "Buscar ${filterLabel(filter)} no deck (pode falhar a busca).", p.deck.filter { matches(it, filter) }.map { it.id }, 0, 1)
                    }, shuffleAfter = true))
                    is TrainerEffect.DrawTo -> begin(Resolution(cost(effect.discardCost), drawTo = effect.size))
                    is TrainerEffect.ShuffleHandDraw -> {
                        update { it.copy(deck = it.deck + it.hand, hand = emptyList()) }; shuffle()
                        draw(if (p.prizes.size == 6) effect.sixPrizeCount else effect.count)
                    }
                    is TrainerEffect.Switch -> {
                        val targetPlayer = if (effect.opponent) 1-actor else actor
                        begin(Resolution(listOf(ChoiceRequest(if (effect.opponent) ChoiceKind.SWITCH_OPPONENT else ChoiceKind.SWITCH_OWN,
                            "Escolha o Pokémon que entrará no Ativo.", s.players[targetPlayer].bench.map { it.id }, 1, 1)), drawTo = effect.drawTo))
                    }
                    TrainerEffect.Recover -> begin(Resolution(listOf(ChoiceRequest(ChoiceKind.RECOVER, "Recuperar Pokémon ou Energia Básica.", p.discard.filter { matches(it, CardFilter.POKEMON_OR_ENERGY) }.map { it.id }, 1, 1))))
                    is TrainerEffect.DamageBoost -> update { it.copy(flags = it.flags.copy(damageBoosts = it.flags.damageBoosts + (effect.type to ((it.flags.damageBoosts[effect.type] ?: 0) + effect.amount)))) }
                    is TrainerEffect.RetreatReduction -> begin(Resolution(listOf(ChoiceRequest(ChoiceKind.TOOL_TARGET, "Ligar Ferramenta a qual Pokémon?", p.pokemon().filter { it.tool == null }.map { it.id }, 1, 1)), toolCard = card))
                    is TrainerEffect.HpModifier -> {
                        val old = s.stadium; val owner = s.stadiumOwner
                        if (old != null && owner != null) update(owner) { it.copy(discard = it.discard + old) }
                        s = s.copy(stadium = card, stadiumOwner = actor)
                        update { it.copy(flags = it.flags.copy(stadium = true)) }
                    }
                    is TrainerEffect.DrawCards -> {
                        draw(effect.count)
                        if (effect.coinBonus > 0) {
                            val rng = SeededRng(s.rng); val heads = rng.nextInt(2) == 0; s = s.copy(rng = rng.state)
                            events += GameEvent("CoinFlipped", if (heads) "CARA" else "COROA")
                            if (heads) draw(effect.coinBonus)
                        }
                    }
                    TrainerEffect.GreatBall -> begin(Resolution(listOf(ChoiceRequest(ChoiceKind.SEARCH, "Olhe as 7 primeiras cartas: escolha até 1 Pokémon.",
                        p.deck.take(7).filter { def(it).category == Category.POKEMON }.map { it.id }, 0, 1, visibleCards = p.deck.take(7).map { it.id })), shuffleAfter = true))
                    TrainerEffect.Sonia -> begin(Resolution(listOf(ChoiceRequest(ChoiceKind.SONIA, "Sonia: até 2 Pokémon Básicos OU até 2 Energias Básicas.",
                        p.deck.filter { matches(it, CardFilter.BASIC_POKEMON) || matches(it, CardFilter.BASIC_ENERGY) }.map { it.id }, 0, 2)), shuffleAfter = true))
                    TrainerEffect.CoinSwitch -> {
                        val rng = SeededRng(s.rng); val heads = rng.nextInt(2) == 0; s = s.copy(rng = rng.state)
                        events += GameEvent("CoinFlipped", if (heads) "CARA" else "COROA")
                        if (heads) begin(Resolution(listOf(ChoiceRequest(ChoiceKind.SWITCH_OPPONENT, "Cara: escolha o novo Ativo adversário.", s.players[1-actor].bench.map { it.id }, 1, 1))))
                    }
                    is TrainerEffect.HealPokemon -> begin(Resolution(listOf(ChoiceRequest(ChoiceKind.HEAL_TARGET, "Curar até ${effect.amount} de dano.",
                        (if (effect.activeOnly) listOfNotNull(p.active) else p.pokemon()).filter { it.damage > 0 }.map { it.id }, 1, 1, amount = effect.amount))))
                    is TrainerEffect.RecoverEnergy -> begin(Resolution(listOf(ChoiceRequest(if (effect.toDeck) ChoiceKind.RECYCLE_ENERGY else ChoiceKind.RECOVER,
                        "Escolha até ${effect.max} Energias Básicas do descarte.", p.discard.filter { def(it).category == Category.ENERGY }.map { it.id }, 0,
                        minOf(effect.max, p.discard.count { def(it).category == Category.ENERGY }))), shuffleAfter = effect.toDeck))
                }
            }
            is GameAction.UseAbility -> {
                val p = s.players[actor]; val pokemon = p.pokemon().first { it.id == action.pokemonId }
                val ability = def(pokemon.top).abilities.first { it.id == action.abilityId }
                update { it.copy(flags = it.flags.copy(abilitiesUsed = it.flags.abilitiesUsed + abilityUsageKey(pokemon, ability))) }
                events += GameEvent("AbilityUsed", "Usou ${ability.name}.")
                if (ability.shuffleSelfIntoDeck) {
                    val cards = pokemon.cards + pokemon.energies + listOfNotNull(pokemon.tool)
                    update { it.copy(active = null, deck = it.deck + cards) }
                    val rng = SeededRng(s.rng)
                    update { it.copy(deck = rng.shuffle(it.deck)) }
                    s = s.copy(rng = rng.state, phase = Phase.PROMOTE, promotionQueue = listOf(actor),
                        resumePlayer = actor, afterResolution = ResolutionEnd.MAIN)
                    events += GameEvent("PokemonShuffledIntoDeck", "${def(pokemon.top).name} e todas as cartas ligadas foram embaralhados no deck.", cards.size)
                }
                else if (ability.healDamage > 0) begin(Resolution(listOf(ChoiceRequest(ChoiceKind.HEAL_TARGET,
                    "Curar até ${ability.healDamage} de dano de 1 dos seus Pokémon.", p.pokemon().filter { it.damage > 0 }.map { it.id }, 1, 1, amount = ability.healDamage))))
                else if (ability.recoverBasicPokemonToBenchMaxHp > 0) begin(Resolution(listOf(ChoiceRequest(ChoiceKind.ABILITY_BASIC_POKEMON_TO_BENCH,
                    "Escolha um Pokémon Básico de até ${ability.recoverBasicPokemonToBenchMaxHp} HP do descarte para colocar no Banco.",
                    p.discard.filter { def(it).category == Category.POKEMON && def(it).stage == Stage.BASIC && def(it).hp <= ability.recoverBasicPokemonToBenchMaxHp }.map { it.id },
                    1, 1))))
                else if (ability.damageCounters > 0) begin(Resolution(listOf(ChoiceRequest(ChoiceKind.ABILITY_DAMAGE_TARGET,
                    "Escolha ${ability.damageTargets} Pokémon adversário para receber ${ability.damageCounters} contadores de dano.",
                    s.players[1-actor].pokemon().map { it.id }, ability.damageTargets, ability.damageTargets,
                    amount = ability.damageCounters*10, sourcePokemonId = pokemon.id))))
                else if (ability.devolveOpponentPokemonMax > 0) begin(Resolution(listOf(ChoiceRequest(ChoiceKind.ABILITY_DEVOLVE_TARGET,
                    "Escolha 1 Pokémon evoluído do adversário para devolver sua evolução de Estágio mais alto à mão.",
                    s.players[1-actor].pokemon().filter { it.cards.size > 1 }.map { it.id }, 1, 1))))
                else if (ability.attachHandEnergyType != null) {
                    val candidates = p.hand.filter { def(it).category == Category.ENERGY && def(it).basicEnergy && def(it).type == ability.attachHandEnergyType }.map { it.id }
                    begin(Resolution(listOf(ChoiceRequest(ChoiceKind.ABILITY_ATTACH_ENERGY,
                        "Você pode ligar uma Energia Básica de ${ability.attachHandEnergyType} da mão a este Pokémon.",
                        candidates, 0, minOf(1, candidates.size), amount = ability.drawIfAttached, sourcePokemonId = pokemon.id))))
                }
                else if (ability.discardEnergyType != null) begin(Resolution(listOf(ChoiceRequest(ChoiceKind.ABILITY_ENERGY,
                    "Descarte uma Energia de Luta Básica para usar ${ability.name}.", p.hand.filter { def(it).category == Category.ENERGY && def(it).type == ability.discardEnergyType }.map { it.id }, 1, 1)), drawCount = ability.draw))
                else if (ability.discardHandCount > 0) begin(Resolution(listOf(ChoiceRequest(ChoiceKind.DISCARD,
                    "Descarte ${ability.discardHandCount} carta(s) para usar ${ability.name}.", p.hand.map { it.id }, ability.discardHandCount, ability.discardHandCount)),
                    drawCount = ability.draw, drawTo = ability.drawTo))
                else if (ability.drawTo > 0) draw((ability.drawTo-p.hand.size).coerceAtLeast(0))
                else draw(ability.draw)
            }
            is GameAction.ChooseCards -> {
                val resolution = s.resolution!!; val choice = resolution.choices.first(); val ids = action.cardIds
                var remainingChoices = resolution.choices.drop(1)
                val p = s.players[actor]
                when (choice.kind) {
                    ChoiceKind.EVOLUTION_METAL_ENERGY -> if (ids.isNotEmpty()) {
                        val eligible = p.pokemon().filter { def(it.top).type == EnergyType.METAL }.map { it.id }
                        remainingChoices = listOf(ChoiceRequest(ChoiceKind.EVOLUTION_METAL_ENERGY_TARGET,
                            "Escolha um dos seus Pokémon Metal para receber a Energia.", eligible, 1, 1,
                            selectedEnergy = ids.first(), selectedTargets = ids.drop(1))) + remainingChoices
                    }
                    ChoiceKind.EVOLUTION_METAL_ENERGY_TARGET -> {
                        val energy = p.discard.first { it.id == choice.selectedEnergy && def(it).category == Category.ENERGY && def(it).basicEnergy && def(it).type == EnergyType.METAL }
                        val target = ids.single()
                        update { player -> player.copy(discard = player.discard - energy,
                            active = player.active?.let { if (it.id == target) it.copy(energies = it.energies + energy) else it },
                            bench = player.bench.map { if (it.id == target) it.copy(energies = it.energies + energy) else it }) }
                        events += GameEvent("EvolutionEnergyAttached", "Ligou ${def(energy).name} a um Pokémon Metal.")
                        val remainingEnergyIds = choice.selectedTargets
                        if (remainingEnergyIds.isNotEmpty()) {
                            val eligible = s.players[actor].pokemon().filter { def(it.top).type == EnergyType.METAL }.map { it.id }
                            remainingChoices = listOf(ChoiceRequest(ChoiceKind.EVOLUTION_METAL_ENERGY_TARGET,
                                "Escolha um dos seus Pokémon Metal para receber a próxima Energia.", eligible, 1, 1,
                                selectedEnergy = remainingEnergyIds.first(), selectedTargets = remainingEnergyIds.drop(1))) + remainingChoices
                        }
                    }
                    ChoiceKind.DISCARD, ChoiceKind.ABILITY_ENERGY -> {
                        val cards = p.hand.filter { it.id in ids }
                        update { it.copy(hand = it.hand - cards.toSet(), discard = it.discard + cards) }
                        events += GameEvent("CardsDiscarded", "Descartou: ${cards.joinToString { def(it).name }}.")
                    }
                    ChoiceKind.ABILITY_DAMAGE_TARGET -> {
                        val opponent = 1-actor
                        update(opponent) { player -> player.copy(
                            active = player.active?.let { if (it.id in ids) it.copy(damage = it.damage + choice.amount) else it },
                            bench = player.bench.map { if (it.id in ids) it.copy(damage = it.damage + choice.amount) else it }
                        ) }
                        events += GameEvent("AbilityDamage", "Causou ${choice.amount} de dano em ${ids.size} Pokémon adversário.", choice.amount)
                        if (choice.sourcePokemonId != null) update(actor) { player ->
                            fun knockOut(pokemon: PokemonInstance) = pokemon.copy(damage = maxOf(pokemon.damage, rules.effects.effectiveHp(pokemon, s)))
                            player.copy(active = player.active?.let { if (it.id == choice.sourcePokemonId) knockOut(it) else it },
                                bench = player.bench.map { if (it.id == choice.sourcePokemonId) knockOut(it) else it })
                        }
                    }
                    ChoiceKind.ABILITY_DEVOLVE_TARGET -> {
                        val opponent = 1-actor
                        val targetId = ids.single()
                        val target = s.players[opponent].pokemon().first { it.id == targetId && it.cards.size > 1 }
                        val returnedEvolution = target.cards.last()
                        update(opponent) { player -> player.copy(hand = player.hand + returnedEvolution,
                            active = player.active?.let { pokemon -> if (pokemon.id == targetId) pokemon.copy(
                                cards = pokemon.cards.dropLast(1), conditions = emptySet(), restrictions = emptyList(), retreatBlockedTurn = -1,
                                damageModifier = 0, incomingDamageReduction = 0, damageReductionTurn = -1, attackImmunityTurn = -1,
                                outgoingDamageReduction = 0, outgoingDamageReductionTurn = -1, preventBasicNonColorlessDamageTurn = -1,
                                preventBasicPokemonDamageTurn = -1, weaknessSuppressedTurn = -1) else pokemon },
                            bench = player.bench.map { pokemon -> if (pokemon.id == targetId) pokemon.copy(
                                cards = pokemon.cards.dropLast(1), conditions = emptySet(), restrictions = emptyList(), retreatBlockedTurn = -1,
                                damageModifier = 0, incomingDamageReduction = 0, damageReductionTurn = -1, attackImmunityTurn = -1,
                                outgoingDamageReduction = 0, outgoingDamageReductionTurn = -1, preventBasicNonColorlessDamageTurn = -1,
                                preventBasicPokemonDamageTurn = -1, weaknessSuppressedTurn = -1) else pokemon }) }
                        events += GameEvent("PokemonDevolved", "${def(returnedEvolution).name} voltou à mão do adversário; Condições Especiais e efeitos foram removidos.")
                    }
                    ChoiceKind.ABILITY_ATTACH_ENERGY -> if (ids.isNotEmpty()) {
                        val energy = p.hand.first { it.id == ids.single() && def(it).category == Category.ENERGY && def(it).basicEnergy }
                        val target = choice.sourcePokemonId ?: error("Pokémon da habilidade ausente")
                        update { player -> player.copy(hand = player.hand - energy,
                            active = player.active?.let { pokemon -> if (pokemon.id == target) pokemon.copy(energies = pokemon.energies + energy) else pokemon },
                            bench = player.bench.map { pokemon -> if (pokemon.id == target) pokemon.copy(energies = pokemon.energies + energy) else pokemon }) }
                        events += GameEvent("AbilityEnergyAttached", "Ligou ${def(energy).name} ao Pokémon da habilidade.")
                        if (choice.amount > 0) draw(choice.amount)
                    }
                    ChoiceKind.ABILITY_BASIC_POKEMON_TO_BENCH -> {
                        val card = p.discard.first { it.id == ids.single() && def(it).category == Category.POKEMON && def(it).stage == Stage.BASIC }
                        update { it.copy(discard = it.discard - card, bench = it.bench + PokemonInstance(listOf(card), enteredPlayTurn = s.turn)) }
                        events += GameEvent("PokemonRecoveredToBench", "Colocou ${def(card).name} do descarte no Banco pela habilidade.")
                    }
                    ChoiceKind.SEARCH, ChoiceKind.SONIA -> {
                        val cards = p.deck.filter { it.id in ids }
                        update { it.copy(deck = it.deck - cards.toSet(), hand = it.hand + cards) }
                        events += GameEvent("CardsSearched", if (cards.isEmpty()) "Busca sem carta revelada." else "Revelou e buscou: ${cards.joinToString { def(it).name }}.")
                    }
                    ChoiceKind.RECOVER -> {
                        val cards = p.discard.filter { it.id in ids }
                        update { it.copy(discard = it.discard - cards.toSet(), hand = it.hand + cards) }
                        events += GameEvent("CardsRecovered", "Recuperou: ${cards.joinToString { def(it).name }}.")
                    }
                    ChoiceKind.SWITCH_OWN, ChoiceKind.SWITCH_OPPONENT, ChoiceKind.EVOLUTION_SWITCH -> if (ids.isNotEmpty()) {
                        val owner = if (choice.kind == ChoiceKind.SWITCH_OWN) actor else 1-actor
                        val player = s.players[owner]; val target = player.bench.first { it.id == ids.single() }
                        update(owner) { it.copy(active = target.copy(conditions = emptySet(), attackImmunityTurn = -1, incomingDamageReduction = 0, damageReductionTurn = -1, outgoingDamageReduction = 0, outgoingDamageReductionTurn = -1, preventBasicNonColorlessDamageTurn = -1, preventBasicPokemonDamageTurn = -1), bench = it.bench - target + listOfNotNull(it.active?.copy(restrictions = emptyList(), conditions = emptySet(), attackImmunityTurn = -1, incomingDamageReduction = 0, damageReductionTurn = -1, outgoingDamageReduction = 0, outgoingDamageReductionTurn = -1, preventBasicNonColorlessDamageTurn = -1, preventBasicPokemonDamageTurn = -1))) }
                        events += GameEvent("ActiveSwitched", "${def(target.top).name} entrou no Ativo do jogador ${owner+1}.")
                    }
                    ChoiceKind.TOOL_TARGET -> {
                        val id = ids.single(); val tool = resolution.toolCard!!
                        update { it.copy(active = it.active?.let { pokemon -> if (pokemon.id == id) pokemon.copy(tool = tool) else pokemon },
                            bench = it.bench.map { pokemon -> if (pokemon.id == id) pokemon.copy(tool = tool) else pokemon }) }
                        events += GameEvent("ToolAttached", "${def(tool).name} ligada a $id.")
                    }
                    ChoiceKind.ENERGY_FROM_DISCARD -> if (ids.isNotEmpty()) {
                        remainingChoices = listOf(ChoiceRequest(ChoiceKind.ENERGY_TARGET, "Escolha um Pokémon do seu Banco para esta Energia.", p.bench.map { it.id }, 1, 1,
                            remaining = choice.remaining, selectedEnergy = ids.single(), energyType = choice.energyType)) + remainingChoices
                    }
                    ChoiceKind.ENERGY_TARGET -> {
                        val energy = p.discard.first { it.id == choice.selectedEnergy }; val target = ids.single()
                        update { it.copy(discard = it.discard - energy, bench = it.bench.map { pokemon -> if (pokemon.id == target) pokemon.copy(energies = pokemon.energies + energy) else pokemon }) }
                        events += GameEvent("EffectEnergyAttached", "Efeito ligou ${def(energy).name} em $target (não consome ligação manual).")
                        val eligible = s.players[actor].discard.filter { def(it).category == Category.ENERGY && def(it).type == choice.energyType }.map { it.id }
                        if (choice.remaining > 1 && eligible.isNotEmpty()) remainingChoices = listOf(ChoiceRequest(ChoiceKind.ENERGY_FROM_DISCARD,
                            "Escolha outra Energia ou conclua o efeito.", eligible, 0, 1, choice.remaining-1, energyType = choice.energyType)) + remainingChoices
                    }
                    ChoiceKind.DISCARD_ATTACK_ENERGY -> {
                        val cards = p.active!!.energies.filter { it.id in ids }
                        update { it.copy(active = it.active!!.copy(energies = it.active.energies - cards.toSet()), discard = it.discard + cards) }
                        events += GameEvent("AttackEnergyDiscarded", "Descartou ${cards.size} Energia(s) do atacante.")
                    }
                    ChoiceKind.ATTACH_DECK_ENERGY -> {
                        val cards = p.deck.filter { it.id in ids }
                        update { it.copy(active = it.active!!.copy(energies = it.active.energies + cards), deck = it.deck - cards.toSet()) }
                        events += GameEvent("EffectEnergyAttached", "Buscou e ligou ${cards.size} Energia(s) ao atacante.")
                    }
                    ChoiceKind.ATTACK_DECK_BASIC_ENERGY_ANY -> if (ids.isNotEmpty()) {
                        val energy = p.deck.first { it.id == ids.single() && def(it).category == Category.ENERGY && def(it).basicEnergy }
                        remainingChoices = listOf(ChoiceRequest(ChoiceKind.ATTACK_DECK_BASIC_ENERGY_TARGET,
                            "Escolha um dos seus Pokémon para receber ${def(energy).name}.", p.pokemon().map { it.id }, 1, 1,
                            remaining = choice.remaining, selectedEnergy = energy.id)) + remainingChoices
                    }
                    ChoiceKind.ATTACK_DECK_BASIC_ENERGY_TARGET -> {
                        val energy = p.deck.first { it.id == choice.selectedEnergy && def(it).category == Category.ENERGY && def(it).basicEnergy }
                        val targetId = ids.single()
                        update { player -> player.copy(deck = player.deck - energy,
                            active = player.active?.let { if (it.id == targetId) it.copy(energies = it.energies + energy) else it },
                            bench = player.bench.map { if (it.id == targetId) it.copy(energies = it.energies + energy) else it }) }
                        events += GameEvent("AttackDeckEnergyAttached", "Buscou e ligou ${def(energy).name} a um Pokémon.")
                        val remaining = choice.remaining - 1
                        val eligible = s.players[actor].deck.filter { def(it).category == Category.ENERGY && def(it).basicEnergy }
                        if (remaining > 0 && eligible.isNotEmpty()) remainingChoices = listOf(ChoiceRequest(
                            ChoiceKind.ATTACK_DECK_BASIC_ENERGY_ANY, "Você pode buscar outra Energia Básica ou concluir.",
                            eligible.map { it.id }, 0, 1, remaining = remaining)) + remainingChoices
                    }
                    ChoiceKind.ATTACK_BENCH_POKEMON -> {
                        val cards = p.deck.filter { it.id in ids && def(it).category == Category.POKEMON && def(it).stage == Stage.BASIC }
                        update { it.copy(deck = it.deck - cards.toSet(), bench = it.bench + cards.map { card -> PokemonInstance(listOf(card), enteredPlayTurn = s.turn) }) }
                        events += GameEvent("BasicPokemonSearched", "Colocou ${cards.size} Pokémon Básico(s) no Banco pelo ataque.")
                    }
                    ChoiceKind.ATTACK_ROTOM_SEARCH -> {
                        val cards = p.deck.filter { it.id in ids && def(it).category == Category.POKEMON && def(it).name.contains("Rotom", ignoreCase = true) }
                        update { it.copy(deck = it.deck - cards.toSet(), bench = it.bench + cards.map { card -> PokemonInstance(listOf(card), enteredPlayTurn = s.turn) }) }
                        events += GameEvent("RotomSearched", "Colocou ${cards.size} Pokémon com Rotom no nome no Banco.", cards.size)
                    }
                    ChoiceKind.ATTACK_MATCHING_ENERGY_SEARCH -> {
                        val cards = p.deck.filter { it.id in ids && def(it).category == Category.POKEMON }
                        update { it.copy(deck = it.deck - cards.toSet(), hand = it.hand + cards) }
                        events += GameEvent("PokemonSearchedByEnergyType", "Revelou e buscou ${cards.size} Pokémon do tipo correspondente à Energia Básica ligada.", cards.size)
                    }
                    ChoiceKind.ATTACK_POKEMON_SEARCH -> {
                        val cards = p.deck.filter { it.id in ids && def(it).category == Category.POKEMON }
                        update { it.copy(deck = it.deck - cards.toSet(), hand = it.hand + cards) }
                        events += GameEvent("PokemonSearched", "Revelou e buscou ${cards.size} Pokémon do deck.", cards.size)
                    }
                    ChoiceKind.ATTACK_HEAL_TARGET -> {
                        val targetId = ids.single()
                        update { it.copy(active = it.active?.let { pokemon -> if (pokemon.id == targetId) pokemon.copy(damage = (pokemon.damage-choice.amount).coerceAtLeast(0)) else pokemon },
                            bench = it.bench.map { pokemon -> if (pokemon.id == targetId) pokemon.copy(damage = (pokemon.damage-choice.amount).coerceAtLeast(0)) else pokemon }) }
                        events += GameEvent("DamageHealed", "Curou até ${choice.amount} de dano do Pokémon escolhido.")
                    }
                    ChoiceKind.ATTACK_HAND_ENERGY -> if (ids.isNotEmpty()) {
                        val energy = p.hand.first { it.id == ids.single() && def(it).category == Category.ENERGY && def(it).basicEnergy }
                        remainingChoices = listOf(ChoiceRequest(ChoiceKind.ATTACK_HAND_ENERGY_TARGET,
                            "Escolha um dos seus Pokémon para receber ${def(energy).name}.",
                            p.pokemon().map { it.id }, 1, 1, selectedEnergy = energy.id)) + remainingChoices
                    }
                    ChoiceKind.ATTACK_HAND_ENERGY_TARGET -> {
                        val energy = p.hand.first { it.id == choice.selectedEnergy && def(it).category == Category.ENERGY && def(it).basicEnergy }
                        val targetId = ids.single()
                        update { player -> player.copy(hand = player.hand - energy,
                            active = player.active?.let { if (it.id == targetId) it.copy(energies = it.energies + energy) else it },
                            bench = player.bench.map { if (it.id == targetId) it.copy(energies = it.energies + energy) else it }) }
                        events += GameEvent("AttackHandEnergyAttached", "Ligou ${def(energy).name} a um Pokémon pelo ataque.")
                        val eligible = s.players[actor].hand.filter { def(it).category == Category.ENERGY && def(it).basicEnergy }
                        if (eligible.isNotEmpty()) remainingChoices = listOf(ChoiceRequest(ChoiceKind.ATTACK_HAND_ENERGY,
                            "Você pode ligar outra Energia Básica da mão ou concluir.", eligible.map { it.id }, 0, 1)) + remainingChoices
                    }
                    ChoiceKind.ATTACK_KNOCKOUT_BASIC_BENCH -> {
                        val targetId = ids.single()
                        update(1-actor) { player -> player.copy(bench = player.bench.map { pokemon ->
                            if (pokemon.id == targetId && def(pokemon.top).stage == Stage.BASIC) pokemon.copy(damage = maxOf(pokemon.damage, rules.effects.effectiveHp(pokemon, s))) else pokemon
                        }) }
                        events += GameEvent("AttackEffectKnockout", "Nocauteou um Pokémon Básico do Banco adversário pelo efeito do ataque.")
                    }
                    ChoiceKind.ATTACK_DAMAGE_TARGET -> {
                        val owner = 1-actor
                        val target = s.players[owner].bench.first { it.id == ids.single() }
                        val hp = rules.effects.effectiveHp(target, s)
                        if (choice.amount > 0 && target.damage < hp && target.damage+choice.amount >= hp)
                            s = s.copy(pendingAttackDamageKnockoutIds = s.pendingAttackDamageKnockoutIds + target.id)
                        update(owner) { it.copy(bench = it.bench.map { pokemon ->
                            if (pokemon.id in ids) pokemon.copy(damage = pokemon.damage + choice.amount) else pokemon
                        }) }
                        events += GameEvent("BenchDamageDealt", "Causou ${choice.amount} de dano ao Banco adversário.", choice.amount)
                    }
                    ChoiceKind.ATTACK_DAMAGE_COUNTER_TARGET -> {
                        val targetId = ids.single()
                        update(1-actor) { player -> player.copy(bench = player.bench.map { pokemon ->
                            if (pokemon.id == targetId) pokemon.copy(damage = pokemon.damage + choice.amount) else pokemon
                        }) }
                        events += GameEvent("BenchDamageCountersPlaced", "Colocou ${choice.amount / 10} contadores de dano no Pokémon do Banco adversário.", choice.amount)
                    }
                    ChoiceKind.ATTACK_MULTI_DAMAGE_TARGETS, ChoiceKind.ATTACK_MULTI_DAMAGE_SINGLE_TARGET -> {
                        val attacker = p.active ?: error("Atacante ausente durante a resolução.")
                        val attack = rules.definition(attacker.top).attacks.firstOrNull { candidate ->
                            candidate.effects.filterIsInstance<Effect.MultiTargetDamage>().any { it.amount == choice.amount }
                        } ?: error("Ataque de dano múltiplo não encontrado.")
                        val multiEffect = attack.effects.filterIsInstance<Effect.MultiTargetDamage>().first { it.amount == choice.amount }
                        val opponent = 1-actor
                        ids.forEach { targetId ->
                            val target = s.players[opponent].pokemon().first { it.id == targetId }
                            val preview = rules.preview(s, attack.id, targetId, effectDamage = choice.amount,
                                forceIgnoreWeaknessResistance = multiEffect.ignoreWeaknessResistance)
                                ?: error("Não foi possível calcular o dano ao alvo selecionado.")
                            val damage = preview.damage
                            val hp = preview.effectiveHp
                            val knockout = damage > 0 && target.damage < hp && target.damage + damage >= hp
                            if (knockout) s = s.copy(pendingAttackDamageKnockoutIds = s.pendingAttackDamageKnockoutIds + target.id)
                            update(opponent) { player -> player.copy(
                                active = player.active?.let { if (it.id == targetId) it.copy(damage = it.damage + damage) else it },
                                bench = player.bench.map { if (it.id == targetId) it.copy(damage = it.damage + damage) else it }
                            ) }
                            events += GameEvent("MultiTargetAttackDamage", "Causou $damage de dano a ${def(target.top).name}.", damage)
                        }
                        if (choice.kind == ChoiceKind.ATTACK_MULTI_DAMAGE_SINGLE_TARGET && choice.remaining > 1) {
                            val candidates = s.players[opponent].pokemon().map { it.id }
                            if (candidates.isNotEmpty()) remainingChoices = listOf(ChoiceRequest(
                                ChoiceKind.ATTACK_MULTI_DAMAGE_SINGLE_TARGET,
                                "Escolha mais 1 Pokémon adversário (${choice.remaining-1} escolhas restantes).",
                                candidates, 1, 1, remaining = choice.remaining-1, amount = choice.amount)) + remainingChoices
                        }
                    }
                    ChoiceKind.ATTACK_MOVE_DAMAGE_SOURCE -> if (ids.isNotEmpty()) {
                        val opponent = 1-actor
                        val source = s.players[opponent].pokemon().first { it.id == ids.single() }
                        remainingChoices = listOf(ChoiceRequest(ChoiceKind.ATTACK_MOVE_DAMAGE_DESTINATION,
                            "Escolha outro Pokémon adversário para receber 1 contador de dano.",
                            s.players[opponent].pokemon().map { it.id }.filter { it != source.id }, 1, 1,
                            sourcePokemonId = source.id)) + remainingChoices
                    }
                    ChoiceKind.ATTACK_MOVE_DAMAGE_DESTINATION -> {
                        val opponent = 1-actor
                        val sourceId = checkNotNull(choice.sourcePokemonId)
                        val destinationId = ids.single()
                        update(opponent) { player ->
                            fun move(pokemon: PokemonInstance) = when (pokemon.id) {
                                sourceId -> pokemon.copy(damage = (pokemon.damage-10).coerceAtLeast(0))
                                destinationId -> pokemon.copy(damage = pokemon.damage+10)
                                else -> pokemon
                            }
                            player.copy(active = player.active?.let(::move), bench = player.bench.map(::move))
                        }
                        events += GameEvent("DamageCounterMoved", "Moveu 1 contador de dano entre Pokémon adversários.", 10)
                        val field = s.players[opponent].pokemon()
                        val sources = field.filter { source -> source.damage >= 10 && field.any { it.id != source.id } }
                        if (sources.isNotEmpty()) remainingChoices = listOf(ChoiceRequest(ChoiceKind.ATTACK_MOVE_DAMAGE_SOURCE,
                            "Você pode mover outro contador de dano ou concluir.", sources.map { it.id }, 0, 1)) + remainingChoices
                    }
                    ChoiceKind.ATTACK_ENERGY -> {
                        remainingChoices = listOf(ChoiceRequest(ChoiceKind.ATTACK_ENERGY_TARGET,
                            "Escolha um Pokémon do seu Banco para receber a Energia movida.", p.bench.map { it.id }.filter { it !in choice.selectedTargets }, 1, 1,
                            remaining = choice.remaining, selectedEnergy = ids.single(), selectedTargets = choice.selectedTargets)) + remainingChoices
                    }
                    ChoiceKind.ATTACK_ENERGY_TARGET -> {
                        val energy = p.active!!.energies.first { it.id == choice.selectedEnergy }
                        val target = ids.single()
                        update { it.copy(active = it.active!!.copy(energies = it.active.energies - energy),
                            bench = it.bench.map { pokemon -> if (pokemon.id == target) pokemon.copy(energies = pokemon.energies + energy) else pokemon }) }
                        events += GameEvent("AttackEnergyMoved", "Moveu uma Energia do Ativo para o Banco.")
                        val selectedTargets = choice.selectedTargets + target
                        val remaining = choice.remaining - 1
                        if (remaining > 0) {
                            val energies = s.players[actor].active!!.energies.filter { it.id != energy.id }
                            val bench = s.players[actor].bench.map { it.id }.filter { it !in selectedTargets }
                            if (energies.isNotEmpty() && bench.isNotEmpty()) remainingChoices = listOf(ChoiceRequest(
                                ChoiceKind.ATTACK_ENERGY, "Escolha outra Energia para mover.", energies.map { it.id }, 1, 1,
                                remaining = remaining, selectedTargets = selectedTargets)) + remainingChoices
                        }
                    }
                    ChoiceKind.ATTACK_BENCH_ENERGY_DISCARD -> {
                        val discarded = p.bench.flatMap { it.energies }.filter { it.id in ids }
                        update(actor) { it.copy(
                            bench = it.bench.map { pokemon -> pokemon.copy(energies = pokemon.energies - discarded.toSet()) },
                            discard = it.discard + discarded
                        ) }
                        val bonus = discarded.size * choice.amount * choice.damageMultiplier
                        if (bonus > 0) {
                            val target = s.players[1-actor].active!!
                            val hp = rules.effects.effectiveHp(target, s)
                            if (target.damage < hp && target.damage+bonus >= hp)
                                s = s.copy(pendingAttackDamageKnockoutIds = s.pendingAttackDamageKnockoutIds + target.id)
                            update(1-actor) { opponent -> opponent.copy(active = opponent.active?.copy(damage = opponent.active!!.damage + bonus)) }
                        }
                        events += GameEvent("BenchEnergyDiscarded", "Descartou ${discarded.size} Energia(s) do Banco e causou $bonus de dano adicional.", bonus)
                    }
                    ChoiceKind.HEAL_TARGET -> {
                        val target = ids.single()
                        update { it.copy(active = it.active?.let { pokemon -> if (pokemon.id == target) pokemon.copy(damage = (pokemon.damage-choice.amount).coerceAtLeast(0)) else pokemon },
                            bench = it.bench.map { pokemon -> if (pokemon.id == target) pokemon.copy(damage = (pokemon.damage-choice.amount).coerceAtLeast(0)) else pokemon }) }
                        events += GameEvent("DamageHealed", "Curou até ${choice.amount} de dano.")
                    }
                    ChoiceKind.RECYCLE_ENERGY -> {
                        val cards = p.discard.filter { it.id in ids }
                        update { it.copy(discard = it.discard - cards.toSet(), deck = it.deck + cards) }
                        events += GameEvent("EnergyRecycled", "Devolveu ${cards.size} Energia(s) ao deck.")
                    }
                }
                s = s.copy(resolution = resolution.copy(choices = remainingChoices))
            }
            else -> error("Ação incorreta para ContentEngine")
        }
        val resolution = s.resolution
        if (resolution != null && resolution.choices.isNotEmpty()) return Transition(s, Legality.Legal, events)
        if (resolution != null) {
            if (resolution.shuffleAfter) shuffle()
            if (resolution.drawCount > 0) draw(resolution.drawCount)
            if (resolution.drawTo > s.players[actor].hand.size) draw(resolution.drawTo-s.players[actor].hand.size)
        }
        val settled = rules.settle(s.copy(resolution = null), s.afterResolution)
        return settled.copy(events = events + settled.events)
    }
    private fun filterLabel(filter: CardFilter) = when (filter) {
        CardFilter.POKEMON -> "Pokémon"; CardFilter.BASIC_FIGHTING_OR_ENERGY -> "Básico de Luta ou Energia de Luta Básica"
        CardFilter.POKEMON_OR_ENERGY -> "Pokémon ou Energia Básica"; CardFilter.ITEM -> "Item"
        CardFilter.TOOL -> "Ferramenta"; CardFilter.SUPPORTER -> "Apoiador"; CardFilter.STADIUM -> "Estádio"
        CardFilter.BASIC_POKEMON -> "Pokémon Básico"; CardFilter.BASIC_ENERGY -> "Energia Básica"
        CardFilter.ENERGY -> "Energia"; CardFilter.DARKNESS_POKEMON -> "Pokémon de Escuridão"; CardFilter.METAL_POKEMON -> "Pokémon Metal"
    }
}
