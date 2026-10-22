package com.tcgtrainer.engine

/** Reusable card effect transactions. All choices are persisted, validated and replayable. */
internal class ContentEngine(private val rules: RulesEngine) {
    private fun def(card: CardInstance) = rules.definition(card)
    private fun illegal(reason: String) = Legality.Illegal(reason)
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
                    ability.attackDiscountPerOpponentPrize -> illegal("Esta habilidade é aplicada automaticamente ao custo do ataque.")
                    ability.draw <= 0 -> illegal("Habilidade não suportada.")
                    ability.usageKey in p.flags.abilitiesUsed -> illegal("Esta habilidade já foi usada neste turno (limite compartilhado).")
                    ability.requiresInPlay != null && p.pokemon().none { it.top.definitionId == ability.requiresInPlay } -> illegal("É preciso ter ${rules.catalog.get(ability.requiresInPlay).name} em jogo.")
                    ability.requiresPreviousKnockout && p.lastKnockedOutTurn != s.turn-1 -> illegal("Nenhum dos seus Pokémon foi nocauteado no último turno adversário.")
                    ability.discardEnergyType != null && p.hand.none { def(it).category == Category.ENERGY && def(it).type == ability.discardEnergyType } -> illegal("É preciso descartar uma Energia de Luta Básica da mão.")
                    p.deck.isEmpty() -> illegal("Seu deck está vazio.")
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
                update { it.copy(flags = it.flags.copy(abilitiesUsed = it.flags.abilitiesUsed + ability.usageKey)) }
                events += GameEvent("AbilityUsed", "Usou ${ability.name}.")
                if (ability.discardEnergyType != null) begin(Resolution(listOf(ChoiceRequest(ChoiceKind.ABILITY_ENERGY,
                    "Descarte uma Energia de Luta Básica para usar ${ability.name}.", p.hand.filter { def(it).category == Category.ENERGY && def(it).type == ability.discardEnergyType }.map { it.id }, 1, 1)), drawCount = ability.draw))
                else draw(ability.draw)
            }
            is GameAction.ChooseCards -> {
                val resolution = s.resolution!!; val choice = resolution.choices.first(); val ids = action.cardIds
                var remainingChoices = resolution.choices.drop(1)
                val p = s.players[actor]
                when (choice.kind) {
                    ChoiceKind.DISCARD, ChoiceKind.ABILITY_ENERGY -> {
                        val cards = p.hand.filter { it.id in ids }
                        update { it.copy(hand = it.hand - cards.toSet(), discard = it.discard + cards) }
                        events += GameEvent("CardsDiscarded", "Descartou: ${cards.joinToString { def(it).name }}.")
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
                        update(owner) { it.copy(active = target, bench = it.bench - target + listOfNotNull(it.active?.copy(restrictions = emptyList()))) }
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
        CardFilter.ENERGY -> "Energia"; CardFilter.DARKNESS_POKEMON -> "Pokémon de Escuridão"
    }
}
