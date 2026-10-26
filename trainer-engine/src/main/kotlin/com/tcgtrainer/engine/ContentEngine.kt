package com.tcgtrainer.engine

/** Reusable card effect transactions. All choices are persisted, validated and replayable. */
internal class ContentEngine(private val rules: RulesEngine) {
    private fun def(card: CardInstance) = rules.definition(card)
    private fun illegal(reason: String) = Legality.Illegal(reason)
    private fun abilityUsageKey(pokemon: PokemonInstance, ability: AbilityDefinition) =
        if (ability.usagePerPokemon) "${pokemon.id}:${ability.usageKey}" else ability.usageKey
    private fun hasMegaFireEx(player: PlayerState) = player.pokemon().any { pokemon ->
        val card = def(pokemon.top)
        card.category == Category.POKEMON && card.type == EnergyType.FIRE && card.name.startsWith("Mega ") &&
            card.name.endsWith(" ex") && card.prizes == 3
    }
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
        val p = s.players[rules.actingPlayer(s)]
        return when (action) {
            is GameAction.ChooseCards -> {
                val choice = s.resolution?.choices?.firstOrNull() ?: return illegal("Nenhuma escolha pendente.")
                when {
                    action.cardIds.distinct().size != action.cardIds.size -> illegal("Não repita cartas.")
                    action.cardIds.size !in choice.min..choice.max -> illegal("Escolha de ${choice.min} a ${choice.max} carta(s).")
                    choice.kind == ChoiceKind.ATTACK_OPTIONAL_SHUFFLE_ENERGY && action.cardIds.isNotEmpty() && action.cardIds.size != 3 -> illegal("Escolha zero ou exatamente 3 Energias para pagar o efeito opcional.")
                    choice.kind == ChoiceKind.ATTACK_OPTIONAL_PARALYSIS_DISCARD && action.cardIds.isNotEmpty() && action.cardIds.size != choice.max -> illegal("Escolha zero ou exatamente ${choice.max} Energias {L} para pagar o efeito opcional.")
                    choice.kind == ChoiceKind.EVOLUTION_DAMAGE_TARGETS && action.cardIds.isNotEmpty() && action.cardIds.size != 2 -> illegal("Biting Spree exige escolher exatamente 2 Pokémon, ou nenhum para pular.")
                    !choice.candidates.containsAll(action.cardIds) -> illegal("Alvo inválido para este efeito.")
                    choice.kind == ChoiceKind.EVOLUTION_OPPONENT_HAND_BASIC_TO_BENCH && action.cardIds.size > (5-p.bench.size).coerceAtLeast(0) -> illegal("O Banco adversário não tem espaço para tantas cartas.")
                    choice.kind == ChoiceKind.EVOLUTION_OPPONENT_HAND_BASIC_TO_BENCH && action.cardIds.any { id ->
                        s.players[1-rules.actingPlayer(s)].hand.none { it.id == id && def(it).category == Category.POKEMON && def(it).stage == Stage.BASIC }
                    } -> illegal("Escolha apenas Pokémon Básicos ainda na mão revelada do adversário.")
                    choice.kind == ChoiceKind.ABILITY_ATTACH_BASIC_ENERGY_DECK_TARGET && (p.bench.none { it.id == action.cardIds.singleOrNull() && def(it.top).type == choice.targetPokemonType } || p.deck.none { it.id == choice.selectedEnergy && def(it).category == Category.ENERGY && def(it).basicEnergy && def(it).type == choice.energyType }) -> illegal("Escolha um Pokémon elegível do Banco e uma Energia Básica do deck.")
                    choice.kind == ChoiceKind.ABILITY_ATTACH_ENERGY_TARGET && choice.benchedOnly && action.cardIds.any { id -> p.bench.none { it.id == id } } -> illegal("O alvo precisa estar no Banco.")
                    choice.kind == ChoiceKind.ATTACK_KNOCKOUT_LOWEST_HP_TARGET && action.cardIds.any { id ->
                        val target = s.players.mapIndexedNotNull { owner, player -> player.pokemon().firstOrNull { it.id == id }?.let { owner to it } }.firstOrNull()
                        target == null || target.second.id == choice.sourcePokemonId ||
                            target.first != rules.actingPlayer(s) && rules.attackTargetEffectsPrevented(s, target.first, target.second)
                    } -> illegal("Escolha apenas um Pokémon elegível, não protegido e diferente do atacante.")
                    choice.kind == ChoiceKind.PALAFIN_ZERO_TO_HERO && action.cardIds.isNotEmpty() && action.cardIds.any { id ->
                        p.deck.none { it.id == id && def(it).name == "Palafin ex" }
                    } -> illegal("Escolha um Palafin ex que ainda esteja no deck.")
                    choice.kind == ChoiceKind.PALAFIN_ZERO_TO_HERO && p.bench.none { it.id == choice.sourcePokemonId &&
                        def(it.top).name == "Palafin" && def(it.top).abilities.any { ability -> ability.triggersWhenMovedFromActiveToBench }
                    } -> illegal("Palafin não está mais no Banco para resolver Zero to Hero.")
                    choice.kind == ChoiceKind.ATTACK_REORDER_OPPONENT_TOP && s.players[1-rules.actingPlayer(s)].deck.take(choice.candidates.size).map { it.id }.toSet() != choice.candidates.toSet() -> illegal("As cartas do topo do deck adversário mudaram durante a escolha.")
                    choice.kind == ChoiceKind.SONIA && p.deck.filter { it.id in action.cardIds }.map { def(it).category }.distinct().size > 1 -> illegal("Sonia busca Pokémon Básicos OU Energias Básicas; não misture as categorias.")
                    else -> Legality.Legal
                }
            }
            is GameAction.PlayTrainer -> {
                val card = p.hand.find { it.id == action.cardId } ?: return illegal("Carta fora da mão.")
                val d = def(card)
                val effect = d.trainerEffect ?: return illegal("Efeito de Treinador não suportado.")
                if (d.trainerType == TrainerType.ITEM && s.turn <= p.itemPlayLockedUntilTurn) return illegal("Você não pode jogar cartas de Item neste turno.")
                val opponentActive = s.players[1-actor].active
                if (d.trainerType in setOf(TrainerType.ITEM, TrainerType.TOOL) && opponentActive != null &&
                    rules.effectiveAbilities(s, 1-actor, opponentActive).any { it.preventOpponentItemAndToolPlayFromHand })
                    return illegal("O Pokémon Ativo adversário impede jogar cartas de Item e Ferramenta da mão.")
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
                    rules.effectiveAbilities(s, rules.actingPlayer(s), pokemon).none { it.id == ability.id } -> illegal("Esta Habilidade está suprimida por uma Habilidade em jogo.")
                    ability.onEvolveSwitch -> illegal("Esta habilidade só pode ser usada ao evoluir da mão.")
                    ability.onEvolveDraw > 0 -> illegal("Esta habilidade só pode ser usada ao evoluir da mão.")
                    ability.onEvolveSearchTrainerIfTeraMax > 0 -> illegal("Esta habilidade só pode ser usada ao evoluir da mão com um Pokémon Tera em jogo.")
                    ability.onEvolveDamageCounters -> illegal("Esta habilidade só pode ser usada ao evoluir da mão.")
                    ability.onEvolveHealAllEvolutionAndDiscardEnergy -> illegal("Esta habilidade só pode ser usada ao evoluir da mão.")
                    ability.onEvolveShuffleRandomOpponentHandPerHeads > 0 -> illegal("Esta habilidade só pode ser usada ao evoluir da mão.")
                    ability.onEvolveConfuseOpponentActive -> illegal("Esta habilidade só pode ser usada ao evoluir da mão.")
                    ability.requiresMegaEvolutionFireExInPlay && !hasMegaFireEx(p) -> illegal("É preciso ter uma Mega Evolution Pokémon ex de Fogo em jogo.")
                    ability.onEvolveDiscardOpponentDeckCount > 0 -> illegal("Esta habilidade só pode ser usada ao evoluir da mão ou quando este Pokémon Ativo é nocauteado por dano de ataque.")
                    ability.onBenchSwitchAndMoveEnergy -> illegal("Esta habilidade só pode ser usada quando o Pokémon entra no Banco pela mão.")
                    ability.triggersWhenMovedFromActiveToBench -> illegal("Zero to Hero dispara quando Palafin é movido do Ativo para o Banco.")
                    ability.onEvolveAttachMetalFromDiscardMax > 0 -> illegal("Esta habilidade só pode ser usada ao evoluir da mão.")
                    ability.onEvolveRecoverNamedTrainerFromDiscard != null -> illegal("Esta habilidade só pode ser usada ao evoluir da mão.")
                    ability.devolveOpponentPokemonMax > 0 && s.players[1-s.currentPlayer].pokemon().none { it.cards.size > 1 && rules.effectiveAbilities(s, 1-rules.actingPlayer(s), it).none { targetAbility -> targetAbility.preventOpponentEffects } } -> illegal("Não há Pokémon adversário evoluído e sem proteção para desvoluir.")
                    ability.attackDiscountPerOpponentPrize -> illegal("Esta habilidade é aplicada automaticamente ao custo do ataque.")
                    ability.hpBonusPerOpponentPrizeTaken > 0 || ability.hpBonusPerAttachedEnergyType != null || ability.teamDamageReduction > 0 || ability.additionalPokemonTypes.isNotEmpty() || ability.attackDamageBonusAgainstEvolutionActive > 0 || ability.futurePokemonDamageBonus > 0 || ability.teamAttackDamageBonusType != null || ability.freeRetreatWithNoEnergy || ability.attackDiscountPerKofuDiscard || ability.coinFlipPreventAttackDamage || ability.preventOpponentItemSupporterEffects || ability.preventOpponentItemAndToolPlayFromHand || ability.triggersWhenMovedFromActiveToBench || ability.changeOpponentNsPokemonWeaknessToPsychic || ability.reducePrizeForDarknessKnockoutByEx || ability.mayAttackFirstTurnIfStarting || ability.suppressSelfKnockoutAbilities || ability.opponentActiveAbilityException != null -> illegal("Esta habilidade passiva se aplica automaticamente.")
                    ability.hpBonusPerOpponentPrizeTaken > 0 || ability.hpBonusPerAttachedEnergyType != null || ability.teamDamageReduction > 0 || ability.additionalPokemonTypes.isNotEmpty() || ability.attackDamageBonusAgainstEvolutionActive > 0 || ability.futurePokemonDamageBonus > 0 || ability.teamAttackDamageBonusType != null || ability.freeRetreatWithNoEnergy || ability.attackDiscountPerKofuDiscard || ability.coinFlipPreventAttackDamage || ability.preventOpponentItemSupporterEffects || ability.preventOpponentItemAndToolPlayFromHand || ability.onlyPlayableWithPalafinZeroToHero || ability.changeOpponentNsPokemonWeaknessToPsychic || ability.reducePrizeForDarknessKnockoutByEx || ability.mayAttackFirstTurnIfStarting || ability.suppressSelfKnockoutAbilities || ability.suppressRuleBoxAbilitiesExceptFuture || ability.opponentActiveAbilityException != null -> illegal("Esta habilidade passiva se aplica automaticamente.")
                    ability.selfKnockout && s.players.any { player -> player.pokemon().any { source ->
                        rules.effectiveAbilities(s, s.players.indexOf(player), source).any { it.suppressSelfKnockoutAbilities }
                    } } -> illegal("Habilidades que nocauteiam o próprio Pokémon estão desativadas enquanto Damp estiver em jogo.")
                    ability.alternativeAttackCost.isNotEmpty() -> illegal("Esta habilidade passiva altera o custo do ataque quando a condição é atendida.")
                    ability.opponentActiveEvolutionRetreatCostBonus > 0 || ability.opponentActiveRetreatCostBonus > 0 -> illegal("Esta habilidade passiva é aplicada automaticamente ao recuo.")
                    ability.draw <= 0 && ability.drawTo <= 0 && ability.opponentShuffleHandAndDraw <= 0 && ability.eachPlayerDraw <= 0 && !ability.onBenchSwitchAndMoveEnergy && !ability.switchOwnActive && !ability.changeOpponentNsPokemonWeaknessToPsychic && !ability.reducePrizeForDarknessKnockoutByEx && ability.damageCounters <= 0 && ability.discardAttachedBasicEnergyType == null && ability.attachHandEnergyType == null && ability.attachHandEnergyTypes.isEmpty() && ability.searchBasicEnergyTypeToHand == null && ability.attachBasicEnergyFromDeckToBenchedType == null && ability.damageCountersToEnergyTarget <= 0 && ability.searchColorlessPokemonMaxHpToHand <= 0 && ability.searchPokemonMaxToHand <= 0 && ability.onEvolveSearchTrainerIfTeraMax <= 0 && ability.onEvolveDiscardOpponentDeckCount <= 0 && !ability.onEvolveOpponentHandBasicToBench && !ability.attachBasicEnergyFromDiscard && !ability.moveBasicEnergyBetweenOwnPokemon && ability.lookAtTopCardsChooseOne <= 0 && !ability.searchAnyCardToTop && ability.alternativeAttackCost.isEmpty() && !ability.shuffleSelfIntoDeck && !ability.moveEnergyFromOtherPokemonToSelf && ability.healDamage <= 0 && ability.recoverBasicPokemonToBenchMaxHp <= 0 && ability.devolveOpponentPokemonMax <= 0 && ability.onEvolveShuffleRandomOpponentHandPerHeads <= 0 && !ability.festivalGroundsMayAttackAgain && !ability.preventOpponentItemSupporterEffects && !ability.onlyPlayableWithPalafinZeroToHero && !ability.onEvolveHealAllEvolutionAndDiscardEnergy && !ability.mayAttackFirstTurnIfStarting -> illegal("Habilidade não suportada.")
                    ability.damageCounters > 0 && s.players[1-s.currentPlayer].pokemon().count { target -> rules.effectiveAbilities(s, 1-rules.actingPlayer(s), target).none { it.preventOpponentEffects } } < ability.damageTargets -> illegal("Não há Pokémon adversários suficientes para esta habilidade.")
                    !ability.unlimitedUse && abilityUsageKey(pokemon, ability) in p.flags.abilitiesUsed -> illegal("Esta habilidade já foi usada neste turno.")
                    ability.requiresActive && p.active?.id != pokemon.id -> illegal("Esta habilidade só pode ser usada com este Pokémon no Ativo.")
                    ability.shuffleSelfIntoDeck && p.bench.isEmpty() -> illegal("É preciso ter um Pokémon no Banco para usar esta habilidade.")
                    p.hand.size < ability.discardHandCount -> illegal("Falta uma carta na mão para pagar o custo da habilidade.")
                    ability.requiresInPlay != null && p.pokemon().none { it.top.definitionId == ability.requiresInPlay } -> illegal("É preciso ter ${rules.catalog.get(ability.requiresInPlay).name} em jogo.")
                    ability.requiresPreviousKnockout && p.lastKnockedOutTurn != s.turn-1 -> illegal("Nenhum dos seus Pokémon foi nocauteado no último turno adversário.")
                    ability.requiresFirstTurn && !((s.turn == 1 && s.currentPlayer == s.startingPlayer) || (s.turn == 2 && s.currentPlayer != s.startingPlayer)) -> illegal("Fan Call só pode ser usado no primeiro turno deste jogador.")
                    ability.searchColorlessPokemonMaxHpToHand > 0 && p.deck.none { def(it).category == Category.POKEMON && def(it).type == EnergyType.COLORLESS && def(it).hp <= 100 } -> illegal("Não há Pokémon Incolor de até 100 HP no deck.")
                    ability.searchPokemonMaxToHand > 0 && p.deck.none { card ->
                        val cardDef = def(card)
                        cardDef.category == Category.POKEMON &&
                            (ability.searchPokemonNamePrefixToHand?.let { cardDef.name.startsWith(it) } ?: true) &&
                            (ability.searchPokemonEvolutionTypeToHand?.let { cardDef.type == it && cardDef.stage != Stage.BASIC } ?: true)
                    } -> illegal("Não há Pokémon elegível no deck para esta habilidade.")
                    ability.discardEnergyType != null && p.hand.none { def(it).category == Category.ENERGY && def(it).type == ability.discardEnergyType } -> illegal("É preciso descartar uma Energia de Luta Básica da mão.")
                    ability.discardAttachedBasicEnergyType != null && pokemon.energies.none { def(it).category == Category.ENERGY && def(it).basicEnergy && def(it).type == ability.discardAttachedBasicEnergyType } -> illegal("É preciso descartar uma Energia Básica de Raio ligada a este Pokémon.")
                    ability.attachHandEnergyType != null && p.hand.none { def(it).category == Category.ENERGY && def(it).basicEnergy && def(it).type == ability.attachHandEnergyType } -> illegal("Não há Energia Básica do tipo necessário na mão.")
                    ability.attachHandEnergyTypesMax > 0 && p.hand.none { def(it).category == Category.ENERGY && def(it).basicEnergy && def(it).type in ability.attachHandEnergyTypes } -> illegal("Não há Energia Básica de Fogo ou Luta na mão.")
                    ability.attachHandEnergyToPokemonNamePrefix != null && (if (ability.attachHandEnergyBenchOnly) p.bench else p.pokemon()).none { def(it.top).name.startsWith(ability.attachHandEnergyToPokemonNamePrefix) } -> illegal("Não há Pokémon elegível em jogo para receber a Energia.")
                    ability.switchOwnActive && p.bench.isEmpty() -> illegal("Não há Pokémon no Banco para trocar com o Ativo.")
                    ability.switchOwnDarknessExceptPecharunt && p.bench.none {
                        def(it.top).type == EnergyType.DARKNESS && def(it.top).name != "Pecharunt ex"
                    } -> illegal("Não há Pokémon Darkness elegível no Banco para trocar.")
                    ability.attachHandEnergyToPokemonType != null && (if (ability.attachHandEnergyBenchOnly) p.bench else p.pokemon()).none { def(it.top).type == ability.attachHandEnergyToPokemonType } -> illegal("Não há Pokémon do tipo necessário na área permitida para receber a Energia.")
                    ability.attachHandEnergyBenchOnly && p.bench.none { def(it.top).name.startsWith(ability.attachHandEnergyToPokemonNamePrefix.orEmpty()) } -> illegal("Não há Pokémon elegível no Banco para receber a Energia.")
                    ability.healDamage > 0 && p.pokemon().none { it.damage > 0 } -> illegal("Não há dano para curar.")
                    ability.recoverBasicPokemonToBenchMaxHp > 0 && p.bench.size >= 5 -> illegal("O Banco já tem 5 Pokémon.")
                    ability.recoverBasicPokemonToBenchMaxHp > 0 && p.discard.none { def(it).category == Category.POKEMON && def(it).stage == Stage.BASIC && def(it).hp <= ability.recoverBasicPokemonToBenchMaxHp } -> illegal("Não há Pokémon Básico válido no descarte.")
                    ability.attachBasicEnergyFromDiscard && p.discard.none { def(it).category == Category.ENERGY && def(it).basicEnergy &&
                        (ability.attachBasicEnergyFromDiscardType == null || def(it).type == ability.attachBasicEnergyFromDiscardType) } -> illegal("Não há Energia Básica válida no descarte para ligar.")
                    ability.attachBasicEnergyFromDiscardMax > 0 && p.pokemon().none { def(it.top).type == ability.attachBasicEnergyFromDiscardToPokemonType } -> illegal("Não há Pokémon do tipo necessário em jogo para receber a Energia.")
                    ability.attachBasicEnergyFromDiscardBenchOnly && p.bench.isEmpty() -> illegal("Não há Pokémon no Banco para receber a Energia.")
                    ability.moveBasicEnergyBetweenOwnPokemon && p.pokemon().none { source -> source.energies.any { def(it).category == Category.ENERGY && def(it).basicEnergy } && p.pokemon().any { it.id != source.id } } -> illegal("Não há Energia Básica para mover entre Pokémon diferentes.")
                    ability.moveEnergyFromOtherPokemonToSelf && p.pokemon().filter { it.id != pokemon.id }.none { it.energies.isNotEmpty() } -> illegal("Não há Energia em outro Pokémon para mover.")
                    p.deck.isEmpty() && (ability.draw > 0 || ability.drawTo > p.hand.size || ability.lookAtTopCardsChooseOne > 0 || ability.searchAnyCardToTop || ability.searchColorlessPokemonMaxHpToHand > 0 || ability.searchPokemonMaxToHand > 0) -> illegal("Seu deck está vazio.")
                    else -> Legality.Legal
                }
            }
            else -> illegal("Ação não pertence ao resolvedor de conteúdo.")
        }
    }

    fun apply(state: GameState, action: GameAction): Transition {
        var s = state
        val actor = rules.actingPlayer(s)
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
        fun knockOutOwnPokemon(pokemonId: String) {
            val target = s.players[actor].pokemon().firstOrNull { it.id == pokemonId } ?: return
            val hp = rules.effectiveHp(s, target)
            update { player -> player.copy(
                active = player.active?.let { if (it.id == pokemonId) it.copy(damage = maxOf(it.damage, hp)) else it },
                bench = player.bench.map { if (it.id == pokemonId) it.copy(damage = maxOf(it.damage, hp)) else it }
            ) }
            events += GameEvent("AbilitySelfKnockout", "${def(target.top).name} foi Nocauteado pelo efeito da própria Habilidade.")
        }
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
                        val protectedActive = effect.opponent && s.players[targetPlayer].active?.let { target ->
                            rules.effectiveAbilities(s, 1-rules.actingPlayer(s), target).any { it.preventOpponentItemSupporterEffects }
                        } == true
                        if (protectedActive) {
                            events += GameEvent("TrainerEffectPrevented", "O Pokémon Ativo impediu o efeito de troca do Treinador adversário.")
                            if (effect.drawTo > s.players[actor].hand.size) draw(effect.drawTo - s.players[actor].hand.size)
                        } else begin(Resolution(listOf(ChoiceRequest(if (effect.opponent) ChoiceKind.SWITCH_OPPONENT else ChoiceKind.SWITCH_OWN,
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
                if (!ability.unlimitedUse) update { it.copy(flags = it.flags.copy(abilitiesUsed = it.flags.abilitiesUsed + abilityUsageKey(pokemon, ability))) }
                events += GameEvent("AbilityUsed", "Usou ${ability.name}.")
                if (ability.opponentShuffleHandAndDraw > 0) {
                    val opponent = 1-actor
                    val rng = SeededRng(s.rng)
                    val shuffled = rng.shuffle(s.players[opponent].deck + s.players[opponent].hand)
                    val drawCount = minOf(ability.opponentShuffleHandAndDraw, shuffled.size)
                    update(opponent) { it.copy(hand = shuffled.take(drawCount), deck = shuffled.drop(drawCount)) }
                    s = s.copy(rng = rng.state)
                    events += GameEvent("OpponentHandShuffled", "O adversário embaralhou a mão no deck.")
                    events += GameEvent("CardsDrawn", "Adversário comprou $drawCount carta(s).", drawCount)
                }
                else if (ability.eachPlayerDraw > 0) {
                    for (playerIndex in 0..1) {
                        val n = minOf(ability.eachPlayerDraw, s.players[playerIndex].deck.size)
                        update(playerIndex) { it.copy(hand = it.hand + it.deck.take(n), deck = it.deck.drop(n)) }
                        events += GameEvent("CardsDrawn", if (playerIndex == actor) "Comprou $n carta(s)." else "Adversário comprou $n carta(s).", n)
                    }
                }
                else if (ability.moveBasicEnergyBetweenOwnPokemon) {
                    val energies = p.pokemon().flatMap { it.energies }.filter { def(it).category == Category.ENERGY && def(it).basicEnergy && (ability.moveBasicEnergyType == null || def(it).type == ability.moveBasicEnergyType) }
                    begin(Resolution(listOf(ChoiceRequest(ChoiceKind.ABILITY_MOVE_BASIC_ENERGY,
                        "Escolha uma Energia Básica para mover.", energies.map { it.id }, 1, 1, sourcePokemonId = pokemon.id, energyType = ability.moveBasicEnergyType))))
                }
                else if (ability.switchOwnActive) {
                    val candidates = if (ability.switchOwnDarknessExceptPecharunt) p.bench.filter {
                        def(it.top).type == EnergyType.DARKNESS && def(it.top).name != "Pecharunt ex"
                    } else p.bench
                    begin(Resolution(listOf(ChoiceRequest(ChoiceKind.SWITCH_OWN,
                        "Escolha um Pokémon do Banco para trocar com o Ativo.", candidates.map { it.id }, 1, 1,
                        poisonSwitchedPokemon = ability.poisonNewActiveOnSwitch))))
                }
                if (ability.moveEnergyFromOtherPokemonToSelf) {
                    val energies = p.pokemon().filter { it.id != pokemon.id }.flatMap { it.energies }
                    begin(Resolution(listOf(ChoiceRequest(ChoiceKind.ABILITY_MOVE_ENERGY_TO_SELF,
                        "Escolha uma Energia de outro Pokémon para mover para ${def(pokemon.top).name}.",
                        energies.map { it.id }, 1, 1, sourcePokemonId = pokemon.id))))
                }
                else if (ability.shuffleSelfIntoDeck) {
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
                else if (ability.damageCounters > 0) {
                    val targetChoice = ChoiceRequest(ChoiceKind.ABILITY_DAMAGE_TARGET,
                        "Escolha ${ability.damageTargets} Pokémon adversário para receber ${ability.damageCounters} contadores de dano.",
                        s.players[1-actor].pokemon().filter { target -> rules.effectiveAbilities(s, 1-rules.actingPlayer(s), target).none { it.preventOpponentEffects } }.map { it.id }, ability.damageTargets, ability.damageTargets,
                        amount = ability.damageCounters*10, sourcePokemonId = pokemon.id)
                    val choices = if (ability.discardEnergyType != null) listOf(
                        ChoiceRequest(ChoiceKind.ABILITY_ENERGY,
                            "Descarte uma Energia Básica de ${ability.discardEnergyType} para usar ${ability.name}.",
                            p.hand.filter { def(it).category == Category.ENERGY && def(it).basicEnergy && def(it).type == ability.discardEnergyType }.map { it.id }, 1, 1),
                        targetChoice
                    ) else listOf(targetChoice)
                    begin(Resolution(choices))
                }
                else if (ability.devolveOpponentPokemonMax > 0) begin(Resolution(listOf(ChoiceRequest(ChoiceKind.ABILITY_DEVOLVE_TARGET,
                    "Escolha 1 Pokémon evoluído do adversário para devolver sua evolução de Estágio mais alto à mão.",
                    s.players[1-actor].pokemon().filter { it.cards.size > 1 && rules.effectiveAbilities(s, 1-rules.actingPlayer(s), it).none { targetAbility -> targetAbility.preventOpponentEffects } }.map { it.id }, 1, 1))))
                else if (ability.searchBasicEnergyTypeToHand != null) {
                    val eligible = p.deck.filter { def(it).category == Category.ENERGY && def(it).basicEnergy && def(it).type == ability.searchBasicEnergyTypeToHand }
                    begin(Resolution(listOf(ChoiceRequest(ChoiceKind.ABILITY_SEARCH_BASIC_ENERGY_TO_HAND,
                        "Busque até ${ability.searchBasicEnergyMaxToHand} Energias Básicas de ${ability.searchBasicEnergyTypeToHand} para sua mão.",
                        eligible.map { it.id }, 0, minOf(ability.searchBasicEnergyMaxToHand, eligible.size), visibleCards = eligible.map { it.id })), shuffleAfter = true))
                }
                else if (ability.attachBasicEnergyFromDeckToBenchedType != null) {
                    val type = ability.attachBasicEnergyFromDeckToBenchedType
                    val eligible = p.deck.filter { def(it).category == Category.ENERGY && def(it).basicEnergy && def(it).type == type }
                    val targets = p.bench.filter { def(it.top).type == type }
                    begin(Resolution(listOf(ChoiceRequest(ChoiceKind.ABILITY_ATTACH_BASIC_ENERGY_FROM_DECK,
                        "Escolha uma Energia Básica de $type do deck para ligar a um Pokémon $type do Banco.",
                        if (targets.isEmpty()) emptyList() else eligible.map { it.id }, 0, minOf(1, eligible.size),
                        amount = ability.damageCountersToEnergyTarget, energyType = type, targetPokemonType = type,
                        sourcePokemonId = pokemon.id, visibleCards = eligible.map { it.id })), shuffleAfter = true))
                }
                else if (ability.searchPokemonMaxToHand > 0) {
                    val eligible = p.deck.filter { card ->
                        val cardDef = def(card)
                        cardDef.category == Category.POKEMON &&
                            (ability.searchPokemonNamePrefixToHand?.let { cardDef.name.startsWith(it) } ?: true) &&
                            (ability.searchPokemonEvolutionTypeToHand?.let { cardDef.type == it && cardDef.stage != Stage.BASIC } ?: true)
                    }
                    begin(Resolution(listOf(ChoiceRequest(ChoiceKind.ABILITY_SEARCH_POKEMON_TO_HAND,
                        "Revele e escolha até ${minOf(ability.searchPokemonMaxToHand, eligible.size)} Pokémon elegível(is) do deck.",
                        eligible.map { it.id }, 0, minOf(ability.searchPokemonMaxToHand, eligible.size), visibleCards = eligible.map { it.id })), shuffleAfter = true))
                }
                else if (ability.searchColorlessPokemonMaxHpToHand > 0) {
                    val eligible = p.deck.filter { def(it).category == Category.POKEMON && def(it).type == EnergyType.COLORLESS &&
                        def(it).hp <= 100 }
                    begin(Resolution(listOf(ChoiceRequest(ChoiceKind.ABILITY_SEARCH_COLORLESS_POKEMON_TO_HAND,
                        "Revele e escolha até ${minOf(ability.searchColorlessPokemonMaxHpToHand, eligible.size)} Pokémon Incolores de até 100 HP.",
                        eligible.map { it.id }, 0, minOf(ability.searchColorlessPokemonMaxHpToHand, eligible.size), visibleCards = eligible.map { it.id })), shuffleAfter = true))
                }
                else if (ability.attachBasicEnergyFromDiscardMax > 0) {
                    val eligible = p.discard.filter { def(it).category == Category.ENERGY && def(it).basicEnergy }
                    val remaining = minOf(ability.attachBasicEnergyFromDiscardMax, eligible.size)
                    begin(Resolution(listOf(ChoiceRequest(ChoiceKind.ABILITY_DISCARD_ENERGY_FROM_DISCARD,
                        "${ability.name}: escolha uma Energia Básica do descarte para ligar a um Pokémon {L} ou conclua.",
                        eligible.map { it.id }, 0, 1, remaining = remaining, sourcePokemonId = pokemon.id,
                        targetPokemonType = ability.attachBasicEnergyFromDiscardToPokemonType))))
                }
                else if (ability.attachBasicEnergyFromDiscard) {
                    val eligible = p.discard.filter { def(it).category == Category.ENERGY && def(it).basicEnergy &&
                        (ability.attachBasicEnergyFromDiscardType == null || def(it).type == ability.attachBasicEnergyFromDiscardType) }
                    begin(Resolution(listOf(ChoiceRequest(ChoiceKind.ABILITY_DISCARD_ENERGY_FROM_DISCARD,
                        if (ability.attachBasicEnergyFromDiscardToSelf) "Você pode ligar uma Energia Básica do descarte a este Pokémon."
                        else "Você pode ligar uma Energia Básica do descarte a um dos seus Pokémon.",
                        eligible.map { it.id }, 0, minOf(1, eligible.size), sourcePokemonId = pokemon.id))))
                }
                else if (ability.lookAtTopCardsChooseOne > 0) {
                    val top = p.deck.take(ability.lookAtTopCardsChooseOne)
                    begin(Resolution(listOf(ChoiceRequest(ChoiceKind.ABILITY_TOP_DECK_KEEP_ONE,
                        "${ability.name}: escolha 1 das cartas do topo para a mão; a outra irá para o fundo do deck.",
                        top.map { it.id }, 1, 1, visibleCards = top.map { it.id }))))
                }
                else if (ability.searchAnyCardToTop) {
                    begin(Resolution(listOf(ChoiceRequest(ChoiceKind.ABILITY_SEARCH_TOP_CARD,
                        "${ability.name}: escolha uma carta do deck para colocar no topo.", p.deck.map { it.id }, 1, 1, visibleCards = p.deck.map { it.id }))))
                }
                else if (ability.attachHandEnergyTypesMax > 0) {
                    val eligible = p.hand.filter { def(it).category == Category.ENERGY && def(it).basicEnergy && def(it).type in ability.attachHandEnergyTypes }
                    begin(Resolution(listOf(ChoiceRequest(ChoiceKind.ABILITY_ATTACH_ANY_BASIC_ENERGY_FROM_HAND,
                        "${ability.name}: escolha uma Energia Básica de Fogo ou Luta para ligar a um dos seus Pokémon, ou conclua.",
                        eligible.map { it.id }, 0, minOf(1, eligible.size), remaining = ability.attachHandEnergyTypesMax - 1,
                        sourcePokemonId = pokemon.id))))
                }
                else if (ability.attachHandEnergyType != null && ability.attachHandEnergyMax > 1 && ability.attachHandEnergyBenchOnly) {
                    val targets = p.bench.filter { def(it.top).name.startsWith(ability.attachHandEnergyToPokemonNamePrefix.orEmpty()) }
                    begin(Resolution(listOf(ChoiceRequest(ChoiceKind.ABILITY_SELECT_ENERGY_TARGET,
                        "Escolha um Pokémon do Banco para receber até ${ability.attachHandEnergyMax} Energias Básicas de ${ability.attachHandEnergyType}.",
                        targets.map { it.id }, 1, 1, amount = ability.attachHandEnergyMax, energyType = ability.attachHandEnergyType))))
                }
                else if (ability.attachHandEnergyType != null) {
                    val candidates = p.hand.filter { def(it).category == Category.ENERGY && def(it).basicEnergy && def(it).type == ability.attachHandEnergyType }.map { it.id }
                    begin(Resolution(listOf(ChoiceRequest(ChoiceKind.ABILITY_ATTACH_ENERGY,
                        if (ability.attachHandEnergyToPokemonType == null) "Você pode ligar uma Energia Básica de ${ability.attachHandEnergyType} da mão a este Pokémon."
                        else "Escolha uma Energia Básica de ${ability.attachHandEnergyType} da mão para ligar a um Pokémon de ${ability.attachHandEnergyToPokemonType}.",
                        candidates, if (ability.attachHandEnergyToPokemonType == null && ability.attachHandEnergyToPokemonNamePrefix == null) 0 else 1, minOf(1, candidates.size),
                        amount = ability.drawIfAttached, sourcePokemonId = pokemon.id, energyType = ability.attachHandEnergyToPokemonType,
                        targetNamePrefix = ability.attachHandEnergyToPokemonNamePrefix, healAmount = ability.healIfAttached,
                        benchedOnly = ability.attachHandEnergyBenchOnly))))
                }
                else if (ability.discardAttachedBasicEnergyType != null) {
                    val attachedEnergy = pokemon.energies.filter { def(it).category == Category.ENERGY &&
                        def(it).basicEnergy && def(it).type == ability.discardAttachedBasicEnergyType }
                    begin(Resolution(listOf(ChoiceRequest(ChoiceKind.ABILITY_ENERGY,
                        "Descarte uma Energia Básica de ${ability.discardAttachedBasicEnergyType} ligada a ${def(pokemon.top).name}.",
                        attachedEnergy.map { it.id }, 1, 1, sourcePokemonId = pokemon.id,
                        energyType = ability.discardAttachedBasicEnergyType)), drawTo = ability.drawTo))
                }
                else if (ability.discardEnergyType != null) begin(Resolution(listOf(ChoiceRequest(ChoiceKind.ABILITY_ENERGY,
                    "Descarte uma Energia Básica de ${ability.discardEnergyType} para usar ${ability.name}.", p.hand.filter { def(it).category == Category.ENERGY && def(it).basicEnergy && def(it).type == ability.discardEnergyType }.map { it.id }, 1, 1)), drawCount = ability.draw))
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
                    ChoiceKind.ATTACK_KNOCKOUT_LOWEST_HP_TARGET -> {
                        val targetId = ids.single()
                        val targetOwner = s.players.indexOfFirst { player -> player.pokemon().any { it.id == targetId } }
                        if (targetOwner < 0 || targetId == choice.sourcePokemonId) return illegal("O Pokémon escolhido não está mais em jogo ou é o atacante.")
                        val target = s.players[targetOwner].pokemon().first { it.id == targetId }
                        if (targetOwner != actor && rules.attackTargetEffectsPrevented(s, targetOwner, target))
                            return illegal("O Pokémon escolhido está protegido contra efeitos de ataques.")
                        s = s.copy(pendingAttackEffectKnockoutIds = s.pendingAttackEffectKnockoutIds + targetId)
                    }
                    ChoiceKind.ATTACK_REORDER_OPPONENT_TOP -> {
                        val opponent = s.players[1-actor]
                        val count = choice.candidates.size
                        val top = opponent.deck.take(count).associateBy { it.id }
                        val reordered = ids.map { top.getValue(it) } + opponent.deck.drop(count)
                        update(1-actor) { it.copy(deck = reordered) }
                        events += GameEvent("OpponentDeckTopReordered", "Reorganizou as $count cartas do topo do deck adversário; todas continuam no deck.", count)
                    }
                    ChoiceKind.EVOLUTION_DISCARD_OPPONENT_DECK, ChoiceKind.KNOCKOUT_DISCARD_OPPONENT_DECK -> if (ids.isNotEmpty()) {
                        val opponent = s.players[1-actor]
                        val discarded = opponent.deck.take(choice.amount)
                        if (discarded.isNotEmpty()) {
                            update(1-actor) { it.copy(deck = it.deck.drop(discarded.size), discard = it.discard + discarded) }
                            events += GameEvent("OpponentDeckDiscarded", "Sandy Flapping descartou ${discarded.size} carta(s) do topo do deck adversário.", discarded.size)
                        }
                    }
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
                    ChoiceKind.EVOLUTION_RECOVER_NAMED_TRAINER -> {
                        val cards = p.discard.filter { it.id in ids && def(it).category == Category.TRAINER }
                        update { it.copy(discard = it.discard - cards.toSet(), hand = it.hand + cards) }
                        events += GameEvent("EvolutionCardsRecovered", "Recuperou ${cards.size} carta(s) de Treinador da evolução.", cards.size)
                    }
                    ChoiceKind.DISCARD, ChoiceKind.ABILITY_ENERGY -> {
                        if (choice.kind == ChoiceKind.ABILITY_ENERGY && choice.sourcePokemonId != null) {
                            val source = p.pokemon().firstOrNull { it.id == choice.sourcePokemonId }
                                ?: return illegal("O Pokémon da habilidade não está mais em jogo.")
                            val energy = source.energies.firstOrNull { it.id in ids && it.id in choice.candidates &&
                                def(it).category == Category.ENERGY && def(it).basicEnergy && def(it).type == choice.energyType }
                                ?: return illegal("A Energia escolhida não é elegível para pagar a habilidade.")
                            update { player -> player.copy(
                                active = player.active?.let { if (it.id == source.id) it.copy(energies = it.energies - energy) else it },
                                bench = player.bench.map { if (it.id == source.id) it.copy(energies = it.energies - energy) else it },
                                discard = player.discard + energy)
                            }
                            events += GameEvent("AbilityEnergyDiscarded", "Descartou ${def(energy).name} de ${def(source.top).name}.")
                        } else {
                            val cards = p.hand.filter { it.id in ids }
                            update { it.copy(hand = it.hand - cards.toSet(), discard = it.discard + cards) }
                            events += GameEvent("CardsDiscarded", "Descartou: ${cards.joinToString { def(it).name }}.")
                        }
                    }
                    ChoiceKind.ABILITY_DAMAGE_TARGET -> {
                        val opponent = 1-actor
                        update(opponent) { player -> player.copy(
                            active = player.active?.let { if (it.id in ids) it.copy(damage = it.damage + choice.amount) else it },
                            bench = player.bench.map { if (it.id in ids) it.copy(damage = it.damage + choice.amount) else it }
                        ) }
                        events += GameEvent("AbilityDamage", "Causou ${choice.amount} de dano em ${ids.size} Pokémon adversário.", choice.amount)
                        if (choice.sourcePokemonId != null) update(actor) { player ->
                            fun knockOut(pokemon: PokemonInstance) = pokemon.copy(damage = maxOf(pokemon.damage, rules.effectiveHp(s, pokemon)))
                            player.copy(active = player.active?.let { if (it.id == choice.sourcePokemonId) knockOut(it) else it },
                                bench = player.bench.map { if (it.id == choice.sourcePokemonId) knockOut(it) else it })
                        }
                    }
                    ChoiceKind.EVOLUTION_DAMAGE_TARGETS -> if (ids.isNotEmpty()) {
                        val opponent = 1-actor
                        val validTargets = s.players[opponent].pokemon().filter { target ->
                            target.id in ids && rules.effectiveAbilities(s, 1-rules.actingPlayer(s), target).none { it.preventOpponentEffects }
                        }
                        if (validTargets.size != 2) return Transition(state, Legality.Illegal("Os alvos de Biting Spree não estão mais disponíveis."))
                        update(opponent) { player -> player.copy(
                            active = player.active?.let { pokemon -> if (pokemon.id in ids) pokemon.copy(damage = pokemon.damage + choice.amount) else pokemon },
                            bench = player.bench.map { pokemon -> if (pokemon.id in ids) pokemon.copy(damage = pokemon.damage + choice.amount) else pokemon })
                        }
                        events += GameEvent("AbilityDamageCountersPlaced", "Biting Spree colocou ${choice.amount / 10} contadores de dano em cada um de ${validTargets.size} Pokémon adversários.", choice.amount / 10 * validTargets.size)
                    }
                    ChoiceKind.ABILITY_DEVOLVE_TARGET, ChoiceKind.ATTACK_DEVOLVE_OPPONENT_ACTIVE -> {
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
                    ChoiceKind.ABILITY_SEARCH_TOP_CARD -> {
                        val selected = p.deck.first { it.id == ids.single() }
                        val rng = SeededRng(s.rng)
                        val shuffled = rng.shuffle(p.deck)
                        update { it.copy(deck = listOf(selected) + shuffled.filterNot { card -> card.id == selected.id }) }
                        s = s.copy(rng = rng.state)
                        events += GameEvent("AbilityCardPutOnDeck", "Colocou ${def(selected).name} no topo do deck após embaralhá-lo.")
                    }
                    ChoiceKind.ABILITY_TOP_DECK_KEEP_ONE -> {
                        val count = minOf(2, p.deck.size)
                        val top = p.deck.take(count)
                        val kept = top.first { it.id == ids.single() }
                        val putBottom = top.filterNot { it.id == kept.id }
                        update { it.copy(deck = it.deck.drop(count) + putBottom, hand = it.hand + kept) }
                        events += GameEvent("AbilityTopDeckSelected", "${def(kept).name} foi para a mão; a outra carta foi para o fundo do deck.")
                    }
                    ChoiceKind.ABILITY_SEARCH_BASIC_ENERGY_TO_HAND -> {
                        val cards = p.deck.filter { it.id in ids && def(it).category == Category.ENERGY && def(it).basicEnergy }
                        update { it.copy(deck = it.deck - cards.toSet(), hand = it.hand + cards) }
                        events += GameEvent("AbilityEnergySearched", "Buscou ${cards.size} Energia(s) Básica(s) para a mão.", cards.size)
                    }
                    ChoiceKind.ABILITY_ATTACH_BASIC_ENERGY_FROM_DECK -> {
                        if (ids.isNotEmpty()) {
                            val energy = p.deck.firstOrNull { it.id == ids.single() && it.id in choice.candidates && def(it).category == Category.ENERGY && def(it).basicEnergy && def(it).type == choice.energyType }
                                ?: return illegal("Escolha uma Energia Básica válida do deck.")
                            val targets = p.bench.filter { def(it.top).type == choice.targetPokemonType }
                            if (targets.isEmpty()) return illegal("Não há Pokémon elegível no Banco.")
                            remainingChoices = listOf(ChoiceRequest(ChoiceKind.ABILITY_ATTACH_BASIC_ENERGY_DECK_TARGET,
                                "Escolha um Pokémon ${choice.targetPokemonType} do Banco para receber a Energia.",
                                targets.map { it.id }, 1, 1, amount = choice.amount, selectedEnergy = energy.id,
                                sourcePokemonId = choice.sourcePokemonId, energyType = choice.energyType,
                                targetPokemonType = choice.targetPokemonType)) + remainingChoices
                        }
                    }
                    ChoiceKind.ABILITY_ATTACH_BASIC_ENERGY_DECK_TARGET -> {
                        val energyId = checkNotNull(choice.selectedEnergy)
                        val energy = p.deck.firstOrNull { it.id == energyId && def(it).category == Category.ENERGY && def(it).basicEnergy && def(it).type == choice.energyType }
                            ?: return illegal("A Energia Básica escolhida não está mais no deck.")
                        val targetId = ids.single()
                        val target = p.bench.firstOrNull { it.id == targetId && def(it.top).type == choice.targetPokemonType }
                            ?: return illegal("Escolha um Pokémon elegível do Banco.")
                        update(actor) { player -> player.copy(deck = player.deck.filterNot { it.id == energyId },
                            bench = player.bench.map { if (it.id == targetId) it.copy(energies = it.energies + energy,
                                damage = it.damage + choice.amount * 10) else it }) }
                        events += GameEvent("ToxtricityEnergyAttached", "Ligou uma Energia Básica {D} a ${def(target.top).name} do Banco.")
                        events += GameEvent("AbilityDamageCountersPlaced", "Night Signal colocou ${choice.amount} contadores de dano em ${def(target.top).name}.", choice.amount * 10)
                    }
                    ChoiceKind.ABILITY_SEARCH_COLORLESS_POKEMON_TO_HAND -> {
                        val cards = p.deck.filter { it.id in ids && def(it).category == Category.POKEMON &&
                            def(it).type == EnergyType.COLORLESS && def(it).hp <= 100 }
                        update { it.copy(deck = it.deck - cards.toSet(), hand = it.hand + cards) }
                        events += GameEvent("FanCallSearched", "Buscou ${cards.size} Pokémon Incolor(es) para a mão.", cards.size)
                    }
                    ChoiceKind.ABILITY_SEARCH_POKEMON_TO_HAND -> {
                        val cards = p.deck.filter { it.id in ids && it.id in choice.candidates && def(it).category == Category.POKEMON }
                        update { it.copy(deck = it.deck - cards.toSet(), hand = it.hand + cards) }
                        events += GameEvent("AbilityPokemonSearched", "Buscou ${cards.size} Pokémon para a mão.", cards.size)
                    }
                    ChoiceKind.EVOLUTION_SEARCH_TRAINER -> {
                        val cards = p.deck.filter { it.id in ids && it.id in choice.candidates && def(it).category == Category.TRAINER }
                        update { it.copy(deck = it.deck - cards.toSet(), hand = it.hand + cards) }
                        val revealed = cards.joinToString { def(it).name }.ifEmpty { "nenhuma carta" }
                        events += GameEvent("EvolutionTrainerSearched", "Jewel Seeker revelou $revealed e buscou ${cards.size} carta(s) de Treinador para a mão.", cards.size)
                    }
                    ChoiceKind.EVOLUTION_OPPONENT_HAND_BASIC_TO_BENCH -> {
                        val opponent = 1-actor
                        val opponentState = s.players[opponent]
                        if (ids.size > (5-opponentState.bench.size).coerceAtLeast(0) || ids.any { id ->
                                id !in choice.candidates || opponentState.hand.none { it.id == id && def(it).category == Category.POKEMON && def(it).stage == Stage.BASIC }
                            }) return illegal("Escolha apenas Pokémon Básicos da mão adversária revelada, respeitando o espaço do Banco.")
                        val selected = opponentState.hand.filter { it.id in ids }
                        val revealedNames = opponentState.hand.joinToString { def(it).name }.ifEmpty { "mão vazia" }
                        update(opponent) { player -> player.copy(hand = player.hand - selected.toSet(),
                            bench = player.bench + selected.map { PokemonInstance(listOf(it), enteredPlayTurn = s.turn) }) }
                        events += GameEvent("OpponentHandRevealed", "Inviting Wink revelou a mão adversária: $revealedNames.", opponentState.hand.size)
                        if (selected.isNotEmpty()) events += GameEvent("BasicPokemonPutOnOpponentBench",
                            "Colocou ${selected.joinToString { def(it).name }} no Banco adversário.", selected.size)
                    }
                    ChoiceKind.ABILITY_SELECT_ENERGY_TARGET -> {
                        val targetId = ids.single()
                        val energies = p.hand.filter { def(it).category == Category.ENERGY && def(it).basicEnergy && def(it).type == choice.energyType }
                        remainingChoices = listOf(ChoiceRequest(ChoiceKind.ABILITY_ATTACH_MULTIPLE_BASIC_ENERGY,
                            "Escolha até ${minOf(choice.amount, energies.size)} Energia(s) Básica(s) para ligar.",
                            energies.map { it.id }, 0, minOf(choice.amount, energies.size), sourcePokemonId = targetId)) + remainingChoices
                    }
                    ChoiceKind.ABILITY_ATTACH_ANY_BASIC_ENERGY_FROM_HAND -> if (ids.isNotEmpty()) {
                        if (ids.size != 1) return illegal("Escolha uma Energia Básica por vez.")
                        val source = p.pokemon().firstOrNull { it.id == choice.sourcePokemonId }
                            ?: return illegal("Infernape não está mais em jogo.")
                        val ability = def(source.top).abilities.firstOrNull { it.attachHandEnergyTypesMax > 0 }
                            ?: return illegal("A habilidade Pyro Dance não está mais disponível.")
                        val energy = p.hand.firstOrNull { it.id == ids.single() && def(it).category == Category.ENERGY && def(it).basicEnergy }
                            ?: return illegal("Escolha uma Energia Básica ainda na mão.")
                        val typeName = def(energy).type?.name ?: return illegal("O tipo desta Energia não é válido para Pyro Dance.")
                        if (def(energy).type !in ability.attachHandEnergyTypes || typeName in choice.pendingCards)
                            return illegal("Pyro Dance pode usar uma Energia de Fogo e uma de Luta, no máximo uma de cada.")
                        val targets = p.pokemon()
                        if (targets.isEmpty()) return illegal("Não há Pokémon seu em jogo para receber a Energia.")
                        remainingChoices = listOf(ChoiceRequest(ChoiceKind.ABILITY_ATTACH_ANY_BASIC_ENERGY_TARGET,
                            "Escolha qual Pokémon seu receberá ${def(energy).name}.", targets.map { it.id }, 1, 1,
                            selectedEnergy = energy.id, sourcePokemonId = source.id, remaining = choice.remaining,
                            pendingCards = choice.pendingCards + typeName)) + remainingChoices
                    }
                    ChoiceKind.ABILITY_ATTACH_ANY_BASIC_ENERGY_TARGET -> {
                        val source = p.pokemon().firstOrNull { it.id == choice.sourcePokemonId }
                            ?: return illegal("Infernape não está mais em jogo.")
                        val ability = def(source.top).abilities.firstOrNull { it.attachHandEnergyTypesMax > 0 }
                            ?: return illegal("A habilidade Pyro Dance não está mais disponível.")
                        val energy = p.hand.firstOrNull { it.id == choice.selectedEnergy && def(it).category == Category.ENERGY && def(it).basicEnergy }
                            ?: return illegal("A Energia escolhida não está mais na mão.")
                        val typeName = def(energy).type?.name ?: return illegal("O tipo desta Energia não é válido para Pyro Dance.")
                        if (def(energy).type !in ability.attachHandEnergyTypes || choice.pendingCards.lastOrNull() != typeName || choice.pendingCards.count { it == typeName } != 1)
                            return illegal("A Energia escolhida não corresponde a uma Energia Básica permitida pelo Pyro Dance.")
                        val targetId = ids.single()
                        if (p.pokemon().none { it.id == targetId }) return illegal("Escolha um Pokémon seu que ainda esteja em jogo.")
                        update { player -> player.copy(hand = player.hand - energy,
                            active = player.active?.let { if (it.id == targetId) it.copy(energies = it.energies + energy) else it },
                            bench = player.bench.map { if (it.id == targetId) it.copy(energies = it.energies + energy) else it }) }
                        events += GameEvent("AbilityEnergyAttached", "Ligou ${def(energy).name} a ${def(p.pokemon().first { it.id == targetId }.top).name} por Pyro Dance.")
                        if (choice.remaining > 0) {
                            val eligible = s.players[actor].hand.filter { def(it).category == Category.ENERGY && def(it).basicEnergy &&
                                def(it).type in ability.attachHandEnergyTypes && def(it).type?.name !in choice.pendingCards }
                            if (eligible.isNotEmpty()) remainingChoices = listOf(ChoiceRequest(
                                ChoiceKind.ABILITY_ATTACH_ANY_BASIC_ENERGY_FROM_HAND,
                                "Pyro Dance: escolha uma Energia Básica do outro tipo para ligar, ou conclua.",
                                eligible.map { it.id }, 0, 1, sourcePokemonId = source.id,
                                pendingCards = choice.pendingCards, remaining = choice.remaining - 1)) + remainingChoices
                        }
                    }
                    ChoiceKind.ABILITY_DISCARD_ENERGY_FROM_DISCARD -> {
                        val sourceAbility = p.pokemon().find { it.id == choice.sourcePokemonId }?.let { def(it.top).abilities.firstOrNull { a -> a.attachBasicEnergyFromDiscard } }
                        if (ids.isNotEmpty()) {
                            val energy = p.discard.firstOrNull { it.id == ids.single() && def(it).category == Category.ENERGY && def(it).basicEnergy }
                                ?: return illegal("A Energia selecionada não está mais no descarte.")
                            if (sourceAbility?.attachBasicEnergyFromDiscardToSelf == true) {
                                val sourceId = checkNotNull(choice.sourcePokemonId) { "Pokémon da habilidade ausente." }
                                val target = p.pokemon().firstOrNull { it.id == sourceId }
                                    ?: return illegal("O Pokémon da habilidade não está mais em jogo.")
                                update(actor) { player -> player.copy(discard = player.discard - energy,
                                    active = player.active?.let { if (it.id == sourceId) it.copy(energies = it.energies + energy) else it },
                                    bench = player.bench.map { if (it.id == sourceId) it.copy(energies = it.energies + energy) else it }) }
                                events += GameEvent("AbilityEnergyAttached", "${def(energy).name} do descarte foi ligada a ${def(target.top).name}.")
                            } else {
                                val benchOnly = choice.benchedOnly || sourceAbility?.attachBasicEnergyFromDiscardBenchOnly == true
                                val targets = (if (benchOnly) p.bench else p.pokemon()).filter {
                                    choice.targetPokemonType == null || def(it.top).type == choice.targetPokemonType
                                }
                                if (targets.isEmpty()) return illegal("Não há Pokémon elegível para receber a Energia.")
                                remainingChoices = listOf(ChoiceRequest(ChoiceKind.ABILITY_DISCARD_ENERGY_TARGET,
                                    "Escolha um dos seus Pokémon para receber ${def(energy).name}.",
                                    targets.map { it.id }, 1, 1, remaining = choice.remaining, selectedEnergy = energy.id,
                                    energyType = choice.energyType, sourcePokemonId = choice.sourcePokemonId, benchedOnly = benchOnly,
                                    targetPokemonType = choice.targetPokemonType)) + remainingChoices
                            }
                        } else if (sourceAbility?.selfKnockoutAfterUse == true) {
                            knockOutOwnPokemon(checkNotNull(choice.sourcePokemonId))
                        }
                    }
                    ChoiceKind.ABILITY_DISCARD_ENERGY_TARGET -> {
                        val energy = p.discard.first { it.id == choice.selectedEnergy && def(it).category == Category.ENERGY && def(it).basicEnergy }
                        val targetId = ids.single()
                        val targetPokemon = p.pokemon().firstOrNull { it.id == targetId }
                            ?: return illegal("O Pokémon escolhido não está mais em jogo.")
                        if (choice.targetPokemonType != null && def(targetPokemon.top).type != choice.targetPokemonType)
                            return illegal("O Pokémon escolhido não tem o tipo necessário para receber a Energia.")
                        val sourceAbility = p.pokemon().find { it.id == choice.sourcePokemonId }?.let { def(it.top).abilities.firstOrNull { a -> a.attachBasicEnergyFromDiscard } }
                        update { player -> player.copy(discard = player.discard - energy,
                            active = player.active?.let { if (it.id == targetId) it.copy(energies = it.energies + energy) else it },
                            bench = player.bench.map { if (it.id == targetId) it.copy(energies = it.energies + energy) else it }) }
                        events += GameEvent("AbilityEnergyAttached", "${def(energy).name} do descarte foi ligada a ${def(p.pokemon().first { it.id == targetId }.top).name}.")
                        val remaining = choice.remaining - 1
                        if (sourceAbility?.attachBasicEnergyFromDiscardMax != null && sourceAbility.attachBasicEnergyFromDiscardMax > 0) {
                            val eligibleEnergy = s.players[actor].discard.filter { def(it).category == Category.ENERGY && def(it).basicEnergy }
                            val eligibleTargets = s.players[actor].pokemon().filter { choice.targetPokemonType == null || def(it.top).type == choice.targetPokemonType }
                            if (remaining > 0 && eligibleEnergy.isNotEmpty() && eligibleTargets.isNotEmpty()) {
                                remainingChoices = listOf(ChoiceRequest(ChoiceKind.ABILITY_DISCARD_ENERGY_FROM_DISCARD,
                                    "Você pode ligar outra Energia Básica do descarte (até mais $remaining) ou concluir.",
                                    eligibleEnergy.map { it.id }, 0, 1, remaining = remaining,
                                    sourcePokemonId = choice.sourcePokemonId, targetPokemonType = choice.targetPokemonType)) + remainingChoices
                            } else {
                                knockOutOwnPokemon(checkNotNull(choice.sourcePokemonId))
                            }
                        } else if (remaining > 0) {
                            val energies = s.players[actor].discard.filter { def(it).category == Category.ENERGY && def(it).basicEnergy && (choice.energyType == null || def(it).type == choice.energyType) }
                            if (energies.isNotEmpty() && s.players[actor].bench.isNotEmpty()) remainingChoices = listOf(ChoiceRequest(
                                ChoiceKind.ABILITY_DISCARD_ENERGY_FROM_DISCARD,
                                "Você pode ligar outra Energia Básica do descarte ao Banco (até mais $remaining).",
                                energies.map { it.id }, 0, 1, remaining = remaining, energyType = choice.energyType, benchedOnly = choice.benchedOnly)) + remainingChoices
                        }
                    }
                    ChoiceKind.ABILITY_ATTACH_ENERGY -> if (ids.isNotEmpty()) {
                        val energy = p.hand.first { it.id == ids.single() && def(it).category == Category.ENERGY && def(it).basicEnergy }
                        if (choice.energyType != null || choice.targetNamePrefix != null || choice.healAmount > 0) {
                            val targets = (if (choice.benchedOnly) p.bench else p.pokemon()).filter { pokemon ->
                                (choice.healAmount > 0 || choice.energyType == null || def(pokemon.top).type == choice.energyType) &&
                                    (choice.targetNamePrefix == null || def(pokemon.top).name.startsWith(choice.targetNamePrefix))
                            }.map { it.id }
                            remainingChoices = listOf(ChoiceRequest(ChoiceKind.ABILITY_ATTACH_ENERGY_TARGET,
                                "Escolha um Pokémon elegível para receber a Energia.", targets, 1, 1,
                                selectedEnergy = energy.id, healAmount = choice.healAmount, benchedOnly = choice.benchedOnly)) + remainingChoices
                        } else {
                            val target = choice.sourcePokemonId ?: error("Pokémon da habilidade ausente")
                            update { player -> player.copy(hand = player.hand - energy,
                                active = player.active?.let { pokemon -> if (pokemon.id == target) pokemon.copy(energies = pokemon.energies + energy) else pokemon },
                                bench = player.bench.map { pokemon -> if (pokemon.id == target) pokemon.copy(energies = pokemon.energies + energy) else pokemon }) }
                            events += GameEvent("AbilityEnergyAttached", "Ligou ${def(energy).name} ao Pokémon da habilidade.")
                            if (choice.amount > 0) draw(choice.amount)
                        }
                    }
                    ChoiceKind.ABILITY_ATTACH_MULTIPLE_BASIC_ENERGY -> if (ids.isNotEmpty()) {
                        val selected = p.hand.filter { it.id in ids && def(it).category == Category.ENERGY && def(it).basicEnergy }
                        val targetId = checkNotNull(choice.sourcePokemonId)
                        update { player -> player.copy(hand = player.hand - selected.toSet(),
                            active = player.active?.let { if (it.id == targetId) it.copy(energies = it.energies + selected) else it },
                            bench = player.bench.map { if (it.id == targetId) it.copy(energies = it.energies + selected) else it }) }
                        events += GameEvent("AbilityEnergyAttached", "Ligou ${selected.size} Energia(s) Básica(s) ao Pokémon.", selected.size)
                    }
                    ChoiceKind.ABILITY_ATTACH_ENERGY_TARGET -> {
                        val energy = p.hand.first { it.id == choice.selectedEnergy && def(it).category == Category.ENERGY && def(it).basicEnergy }
                        val targetId = ids.single()
                        val actualHeal = minOf(choice.healAmount, p.pokemon().first { it.id == targetId }.damage)
                        update { player -> player.copy(hand = player.hand - energy,
                            active = player.active?.let { pokemon -> if (pokemon.id == targetId) pokemon.copy(energies = pokemon.energies + energy, damage = (pokemon.damage - choice.healAmount).coerceAtLeast(0)) else pokemon },
                            bench = player.bench.map { pokemon -> if (pokemon.id == targetId) pokemon.copy(energies = pokemon.energies + energy, damage = (pokemon.damage - choice.healAmount).coerceAtLeast(0)) else pokemon }) }
                        events += GameEvent("AbilityEnergyAttached", "Ligou ${def(energy).name} a um Pokémon de ${def(p.pokemon().first { it.id == targetId }.top).type}.")
                        if (actualHeal > 0) events += GameEvent("AbilityPokemonHealed", "Curou $actualHeal de dano do Pokémon que recebeu a Energia.", actualHeal)
                    }
                    ChoiceKind.ABILITY_BASIC_POKEMON_TO_BENCH -> {
                        val card = p.discard.first { it.id == ids.single() && def(it).category == Category.POKEMON && def(it).stage == Stage.BASIC }
                        update { it.copy(discard = it.discard - card, bench = it.bench + PokemonInstance(listOf(card), enteredPlayTurn = s.turn)) }
                        events += GameEvent("PokemonRecoveredToBench", "Colocou ${def(card).name} do descarte no Banco pela habilidade.")
                    }
                    ChoiceKind.ABILITY_MOVE_BASIC_ENERGY -> {
                        val energyId = ids.single()
                        val sourceId = p.pokemon().first { pokemon -> pokemon.energies.any { it.id == energyId } }.id
                        remainingChoices = listOf(ChoiceRequest(ChoiceKind.ABILITY_MOVE_BASIC_ENERGY_TARGET,
                            "Escolha outro Pokémon para receber a Energia Básica.", p.pokemon().filter { it.id != sourceId }.map { it.id }, 1, 1,
                            selectedEnergy = energyId, sourcePokemonId = sourceId, energyType = choice.energyType)) + remainingChoices
                    }
                    ChoiceKind.ABILITY_MOVE_BASIC_ENERGY_TARGET -> {
                        val energy = p.pokemon().flatMap { it.energies }.first { it.id == choice.selectedEnergy && def(it).category == Category.ENERGY && def(it).basicEnergy && (choice.energyType == null || def(it).type == choice.energyType) }
                        val sourceId = checkNotNull(choice.sourcePokemonId); val targetId = ids.single()
                        update { player -> player.copy(
                            active = player.active?.let { pokemon -> when (pokemon.id) { sourceId -> pokemon.copy(energies = pokemon.energies - energy); targetId -> pokemon.copy(energies = pokemon.energies + energy); else -> pokemon } },
                            bench = player.bench.map { pokemon -> when (pokemon.id) { sourceId -> pokemon.copy(energies = pokemon.energies - energy); targetId -> pokemon.copy(energies = pokemon.energies + energy); else -> pokemon } }) }
                        events += GameEvent("AbilityEnergyMoved", "Moveu ${def(energy).name} para ${def(p.pokemon().first { it.id == targetId }.top).name}.")
                    }
                    ChoiceKind.ABILITY_MOVE_ENERGY_TO_SELF -> {
                        val targetId = checkNotNull(choice.sourcePokemonId)
                        val sourceId = p.pokemon().first { pokemon -> pokemon.id != targetId && pokemon.energies.any { it.id == ids.single() } }.id
                        val energy = p.pokemon().first { it.id == sourceId }.energies.first { it.id == ids.single() }
                        update { player -> player.copy(
                            active = player.active?.let { pokemon -> when (pokemon.id) {
                                sourceId -> pokemon.copy(energies = pokemon.energies - energy)
                                targetId -> pokemon.copy(energies = pokemon.energies + energy)
                                else -> pokemon
                            } },
                            bench = player.bench.map { pokemon -> when (pokemon.id) {
                                sourceId -> pokemon.copy(energies = pokemon.energies - energy)
                                targetId -> pokemon.copy(energies = pokemon.energies + energy)
                                else -> pokemon
                            } }) }
                        events += GameEvent("AbilityEnergyMoved", "Moveu uma Energia de outro Pokémon para ${def((p.pokemon().first { it.id == targetId }).top).name}.")
                    }
                    ChoiceKind.SEARCH, ChoiceKind.SONIA -> {
                        val cards = p.deck.filter { it.id in ids }
                        update { it.copy(deck = it.deck - cards.toSet(), hand = it.hand + cards) }
                        events += GameEvent("CardsSearched", if (cards.isEmpty()) "Busca sem carta revelada." else "Revelou e buscou: ${cards.joinToString { def(it).name }}.")
                    }
                    ChoiceKind.OPPONENT_SHUFFLES_OWN_HAND -> {
                        val cards = p.hand.filter { it.id in ids }
                        val rng = SeededRng(s.rng)
                        update { it.copy(hand = it.hand - cards.toSet(), deck = rng.shuffle(it.deck + cards)) }
                        s = s.copy(rng = rng.state)
                        events += GameEvent("OpponentHandShuffled", "Embaralhou ${cards.size} carta(s) da própria mão no deck.", cards.size)
                        events += GameEvent("DeckShuffled", "Deck embaralhado após Cursed Words.")
                    }
                    ChoiceKind.OPPONENT_DISCARDS_OWN_HAND -> {
                        val cards = p.hand.filter { it.id in ids }
                        update { it.copy(hand = it.hand - cards.toSet(), discard = it.discard + cards) }
                        events += GameEvent("OpponentCardsDiscarded", "O adversário descartou ${cards.size} carta(s) da própria mão.", cards.size)
                    }
                    ChoiceKind.ATTACK_OPTIONAL_DISCARD_TOP_CARD -> if (ids.isNotEmpty()) {
                        val top = p.deck.firstOrNull()?.takeIf { it.id == ids.single() && it.id in choice.candidates }
                        if (top != null) {
                            update { it.copy(deck = it.deck.drop(1), discard = it.discard + top) }
                            events += GameEvent("OwnTopCardDiscarded", "Descartou a carta que olhou do topo do próprio deck: ${def(top).name}.")
                        }
                    }
                    ChoiceKind.ATTACK_CHOOSE_OPPONENT_HAND_DISCARD -> {
                        val opponent = s.players[1-actor]
                        val cards = opponent.hand.filter { it.id in ids && it.id in choice.candidates }
                        update(1-actor) { it.copy(hand = it.hand - cards.toSet(), discard = it.discard + cards) }
                        if (cards.isNotEmpty()) events += GameEvent("OpponentCardChosenAndDiscarded",
                            "Escolheu ${def(cards.single()).name} da mão revelada do adversário para descartar.")
                    }
                    ChoiceKind.RECOVER -> {
                        val cards = p.discard.filter { it.id in ids }
                        update { it.copy(discard = it.discard - cards.toSet(), hand = it.hand + cards) }
                        events += GameEvent("CardsRecovered", "Recuperou: ${cards.joinToString { def(it).name }}.")
                    }
                    ChoiceKind.EVOLUTION_CONFUSE_ACTIVE -> if (ids.isNotEmpty()) {
                        val owner = 1-actor; val targetId = ids.single()
                        update(owner) { player -> player.copy(active = player.active?.let { target ->
                            if (target.id == targetId) target.copy(conditions = (target.conditions - setOf(SpecialCondition.ASLEEP, SpecialCondition.PARALYZED, SpecialCondition.CONFUSED)) + SpecialCondition.CONFUSED) else target
                        }) }
                        events += GameEvent("SpecialCondition", "Pokémon Ativo adversário ficou Confuso.")
                    }
                    ChoiceKind.PALAFIN_ZERO_TO_HERO -> if (ids.isNotEmpty()) {
                        val sourceId = checkNotNull(choice.sourcePokemonId)
                        val source = p.bench.firstOrNull { it.id == sourceId && def(it.top).name == "Palafin" }
                            ?: return illegal("Palafin não está mais no Banco para resolver Zero to Hero.")
                        val ability = def(source.top).abilities.firstOrNull { it.triggersWhenMovedFromActiveToBench }
                            ?: return illegal("A habilidade Zero to Hero não está mais disponível.")
                        val palafinEx = p.deck.firstOrNull { it.id == ids.single() && it.id in choice.candidates && def(it).name == "Palafin ex" }
                            ?: return illegal("O Palafin ex escolhido não está mais no deck.")
                        val returningPalafin = source.top
                        val replacement = source.copy(cards = source.cards.dropLast(1) + palafinEx)
                        update { player -> player.copy(
                            deck = player.deck.filterNot { it.id == palafinEx.id } + returningPalafin,
                            bench = player.bench.map { if (it.id == sourceId) replacement else it },
                            flags = player.flags.copy(abilitiesUsed = player.flags.abilitiesUsed + abilityUsageKey(source, ability))) }
                        events += GameEvent("PalafinZeroToHero", "Palafin foi substituído por ${def(palafinEx).name}; dano, Energias e efeitos foram mantidos.")
                        shuffle()
                    }
                    ChoiceKind.SWITCH_OWN, ChoiceKind.SWITCH_OPPONENT, ChoiceKind.EVOLUTION_SWITCH -> if (ids.isNotEmpty()) {
                        val owner = if (choice.kind == ChoiceKind.SWITCH_OWN) actor else 1-actor
                        val player = s.players[owner]; val target = player.bench.first { it.id == ids.single() }
                        val targetConditions = if (choice.poisonSwitchedPokemon) (target.conditions - setOf(SpecialCondition.ASLEEP, SpecialCondition.PARALYZED, SpecialCondition.CONFUSED)) + SpecialCondition.POISONED else emptySet()
                        update(owner) { it.copy(active = target.copy(enteredActiveTurn = if (owner == actor) s.turn else target.enteredActiveTurn, conditions = targetConditions, poisonDamageCounters = 1, attackImmunityTurn = -1, incomingDamageReduction = 0, damageReductionTurn = -1, outgoingDamageReduction = 0, outgoingDamageReductionTurn = -1, preventBasicNonColorlessDamageTurn = -1, preventBasicPokemonDamageTurn = -1), bench = it.bench - target + listOfNotNull(it.active?.copy(restrictions = emptyList(), attackIdsUsedWhileActive = emptyList(), conditions = emptySet(), poisonDamageCounters = 1, attackImmunityTurn = -1, incomingDamageReduction = 0, damageReductionTurn = -1, outgoingDamageReduction = 0, outgoingDamageReductionTurn = -1, preventBasicNonColorlessDamageTurn = -1, preventBasicPokemonDamageTurn = -1))) }
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
                        if (choice.amount > 0) {
                            val attacker = s.players[actor].active ?: error("Atacante ausente durante dano ao Banco.")
                            val attack = rules.definition(attacker.top).attacks.first { candidate -> candidate.effects.any { it is Effect.DiscardEnergyThenBenchDamage && it.amount == choice.amount } }
                            val effect = attack.effects.filterIsInstance<Effect.DiscardEnergyThenBenchDamage>().first { it.amount == choice.amount }
                            val targets = s.players[1-actor].bench.filter { !rules.attackTargetEffectsPrevented(s, 1-actor, it) }
                            if (targets.isNotEmpty()) remainingChoices = listOf(ChoiceRequest(ChoiceKind.ATTACK_DISCARD_THEN_BENCH_TARGET,
                                "Escolha um Pokémon do Banco adversário para receber ${effect.amount} de dano.", targets.map { it.id }, 1, 1, amount = effect.amount)) + remainingChoices
                        }
                    }
                    ChoiceKind.ATTACK_OPTIONAL_SHUFFLE_ENERGY -> if (ids.isNotEmpty()) {
                        val attacker = p.active ?: error("Atacante ausente durante o efeito opcional.")
                        val attack = rules.definition(attacker.top).attacks.first { candidate -> candidate.effects.any {
                            it is Effect.OptionalShuffleEnergyThenBenchDamage && it.amount == choice.amount
                        } }
                        val effect = attack.effects.filterIsInstance<Effect.OptionalShuffleEnergyThenBenchDamage>().first { it.amount == choice.amount }
                        val energies = attacker.energies.filter { it.id in ids }
                        check(energies.size == effect.count) { "O efeito exige exatamente ${effect.count} Energias." }
                        val rng = SeededRng(s.rng)
                        val shuffledDeck = rng.shuffle(p.deck + energies)
                        update { player -> player.copy(active = player.active!!.copy(energies = player.active.energies - energies.toSet()), deck = shuffledDeck) }
                        s = s.copy(rng = rng.state)
                        events += GameEvent("AttackEnergyShuffled", "Embaralhou ${energies.size} Energias do atacante no deck para ativar o dano ao Banco.", energies.size)
                        val targets = s.players[1-actor].bench.filter { !rules.attackTargetEffectsPrevented(s, 1-actor, it) }
                        if (targets.isNotEmpty()) remainingChoices = listOf(ChoiceRequest(ChoiceKind.ATTACK_DISCARD_THEN_BENCH_TARGET,
                            "Escolha um Pokémon do Banco adversário para receber ${effect.amount} de dano.", targets.map { it.id }, 1, 1, amount = effect.amount)) + remainingChoices
                    }
                    ChoiceKind.ATTACK_OPTIONAL_PARALYSIS_DISCARD -> {
                        if (ids.isEmpty()) {
                            events += GameEvent("OptionalParalysisDeclined", "Não descartou Energias; o Ativo adversário não foi Paralisado.")
                        } else {
                            val attacker = p.active ?: return illegal("O atacante não está mais no Campo.")
                            if (choice.sourcePokemonId != null && choice.sourcePokemonId != attacker.id)
                                return illegal("O Pokémon atacante mudou antes da resolução da Paralisia.")
                            val attack = rules.definition(attacker.top).attacks.firstOrNull { candidate ->
                                candidate.effects.any { it is Effect.OptionalDiscardLightningForParalysis }
                            } ?: return illegal("O efeito de Paralisia opcional não está mais disponível.")
                            val effect = attack.effects.filterIsInstance<Effect.OptionalDiscardLightningForParalysis>().single()
                            val energies = attacker.energies.filter { energy ->
                                energy.id in ids && def(energy).category == Category.ENERGY && def(energy).type == EnergyType.LIGHTNING
                            }
                            if (ids.size != effect.count || energies.size != effect.count)
                                return illegal("Descarte exatamente ${effect.count} Energias {L} do atacante.")
                            update(actor) { player -> player.copy(
                                active = player.active!!.copy(energies = player.active.energies - energies.toSet()),
                                discard = player.discard + energies
                            ) }
                            val target = s.players[1-actor].active
                            if (target != null && target.damage < rules.effectiveHp(s, target) && target.attackImmunityTurn != s.turn &&
                                rules.effectiveAbilities(s, 1-rules.actingPlayer(s), target).none { it.preventOpponentEffects }) {
                                update(1-actor) { player -> player.copy(active = player.active?.let { active ->
                                    if (active.id == target.id) active.copy(conditions = (active.conditions - setOf(SpecialCondition.ASLEEP, SpecialCondition.CONFUSED, SpecialCondition.PARALYZED)) + SpecialCondition.PARALYZED)
                                    else active
                                }) }
                                events += GameEvent("SpecialCondition", "Descartou ${energies.size} Energias {L}; o Ativo adversário ficou Paralisado.")
                            } else events += GameEvent("AttackEffectPrevented", "Descartou ${energies.size} Energias {L}, mas o Ativo adversário não recebeu Paralisia.")
                        }
                    }
                    ChoiceKind.ATTACH_DECK_ENERGY -> {
                        val cards = p.deck.filter { it.id in ids }
                        if (cards.size != ids.size || cards.any { it.id !in choice.candidates || def(it).category != Category.ENERGY || !def(it).basicEnergy || choice.energyType != null && def(it).type != choice.energyType })
                            return illegal("Escolha apenas Energias Básicas válidas do deck.")
                        update { player -> player.copy(active = player.active!!.let { active ->
                            val poisoned = choice.poisonSelfIfAttached && cards.isNotEmpty()
                            val wasPoisoned = SpecialCondition.POISONED in active.conditions
                            active.copy(energies = active.energies + cards,
                                conditions = if (poisoned) active.conditions + SpecialCondition.POISONED else active.conditions,
                                poisonDamageCounters = if (poisoned && !wasPoisoned) 1 else active.poisonDamageCounters)
                        }, deck = player.deck - cards.toSet()) }
                        events += GameEvent("EffectEnergyAttached", "Buscou e ligou ${cards.size} Energia(s) ao atacante.")
                        if (choice.poisonSelfIfAttached && cards.isNotEmpty())
                            events += GameEvent("SpecialCondition", "${def(p.active!!.top).name} ficou Poisoned pelo próprio ataque.")
                    }
                    ChoiceKind.ATTACK_TOP_DECK_ENERGY -> {
                        if (ids.size != ids.distinct().size || ids.any { selectedId ->
                                selectedId !in choice.candidates || selectedId !in choice.visibleCards ||
                                    p.deck.none { it.id == selectedId && def(it).category == Category.ENERGY }
                            })
                            return illegal("Escolha apenas Energias distintas entre as cartas reveladas do topo do deck.")
                        if (ids.isNotEmpty()) {
                            val energyName = def(p.deck.first { it.id == ids.first() }).name
                            remainingChoices = listOf(ChoiceRequest(
                                ChoiceKind.ATTACK_TOP_DECK_ENERGY_TARGET,
                                "Escolha um Pokémon seu para receber $energyName.",
                                p.pokemon().map { it.id }, 1, 1, selectedEnergy = ids.first(),
                                visibleCards = choice.visibleCards, pendingCards = ids.drop(1))) + remainingChoices
                        }
                    }
                    ChoiceKind.ATTACK_TOP_DECK_ENERGY_TARGET -> {
                        val energyId = checkNotNull(choice.selectedEnergy)
                        if (ids.size != 1 || energyId !in choice.visibleCards)
                            return illegal("A Energia selecionada não pertence às cartas reveladas.")
                        val energy = p.deck.firstOrNull { it.id == energyId && def(it).category == Category.ENERGY }
                            ?: return illegal("A Energia selecionada não está mais no deck.")
                        val targetId = ids.single()
                        if (p.pokemon().none { it.id == targetId }) return illegal("O Pokémon escolhido não está em jogo.")
                        update { player -> player.copy(deck = player.deck.filterNot { it.id == energyId },
                            active = player.active?.let { if (it.id == targetId) it.copy(energies = it.energies + energy) else it },
                            bench = player.bench.map { if (it.id == targetId) it.copy(energies = it.energies + energy) else it }) }
                        events += GameEvent("TopDeckEnergyAttached", "Ligou ${def(energy).name} a um dos seus Pokémon.")
                        val nextEnergy = choice.pendingCards.firstOrNull()
                        val nextCard = nextEnergy?.let { id -> p.deck.firstOrNull { it.id == id && def(it).category == Category.ENERGY } }
                        if (nextEnergy != null && nextCard != null) remainingChoices = listOf(ChoiceRequest(
                            ChoiceKind.ATTACK_TOP_DECK_ENERGY_TARGET,
                            "Escolha um Pokémon seu para receber ${def(nextCard).name}.",
                            s.players[actor].pokemon().map { it.id }, 1, 1, selectedEnergy = nextEnergy,
                            visibleCards = choice.visibleCards, pendingCards = choice.pendingCards.drop(1))) + remainingChoices
                        else if (choice.pendingCards.drop(1).isNotEmpty()) remainingChoices = listOf(choice.copy(
                            kind = ChoiceKind.ATTACK_TOP_DECK_ENERGY_TARGET, selectedEnergy = choice.pendingCards.drop(1).first(),
                            pendingCards = choice.pendingCards.drop(2))) + remainingChoices
                    }
                    ChoiceKind.ATTACK_DECK_BASIC_ENERGY_ANY -> if (ids.isNotEmpty()) {
                        val energy = p.deck.first { it.id == ids.single() && def(it).category == Category.ENERGY && def(it).basicEnergy }
                        val targets = if (choice.benchedOnly) p.bench else p.pokemon()
                        remainingChoices = listOf(ChoiceRequest(ChoiceKind.ATTACK_DECK_BASIC_ENERGY_TARGET,
                            "Escolha um dos seus Pokémon para receber ${def(energy).name}.", targets.map { it.id }.filter { !choice.eachBenchedPokemon || it !in choice.selectedTargets }, 1, 1,
                            remaining = choice.remaining, selectedEnergy = energy.id, energyType = choice.energyType, benchedOnly = choice.benchedOnly,
                            selectedTargets = choice.selectedTargets, eachBenchedPokemon = choice.eachBenchedPokemon)) + remainingChoices
                    }
                    ChoiceKind.ATTACK_DECK_BASIC_ENERGY_TARGET -> {
                        val energy = p.deck.first { it.id == choice.selectedEnergy && def(it).category == Category.ENERGY && def(it).basicEnergy }
                        val targetId = ids.single()
                        update { player -> player.copy(deck = player.deck - energy,
                            active = player.active?.let { if (it.id == targetId) it.copy(energies = it.energies + energy) else it },
                            bench = player.bench.map { if (it.id == targetId) it.copy(energies = it.energies + energy) else it }) }
                        events += GameEvent("AttackDeckEnergyAttached", "Buscou e ligou ${def(energy).name} a um Pokémon.")
                        val remaining = choice.remaining - 1
                        val eligible = s.players[actor].deck.filter { def(it).category == Category.ENERGY && def(it).basicEnergy && (choice.energyType == null || def(it).type == choice.energyType) }
                        val selectedTargets = choice.selectedTargets + targetId
                        if (remaining > 0 && eligible.isNotEmpty()) remainingChoices = listOf(ChoiceRequest(
                            ChoiceKind.ATTACK_DECK_BASIC_ENERGY_ANY,
                            if (choice.eachBenchedPokemon) "Busque uma Energia Básica para outro Pokémon do Banco." else "Você pode buscar outra Energia Básica ou concluir.",
                            eligible.map { it.id }, if (choice.eachBenchedPokemon) 1 else 0, 1,
                            remaining = remaining, energyType = choice.energyType, benchedOnly = choice.benchedOnly,
                            selectedTargets = selectedTargets, eachBenchedPokemon = choice.eachBenchedPokemon)) + remainingChoices
                    }
                    ChoiceKind.ATTACK_BENCH_POKEMON -> {
                        val cards = p.deck.filter { it.id in ids && def(it).category == Category.POKEMON && def(it).stage == Stage.BASIC }
                        update { it.copy(deck = it.deck - cards.toSet(), bench = it.bench + cards.map { card -> PokemonInstance(listOf(card), enteredPlayTurn = s.turn) }) }
                        events += GameEvent("BasicPokemonSearched", "Colocou ${cards.size} Pokémon Básico(s) no Banco pelo ataque.")
                    }
                    ChoiceKind.ATTACK_NAMED_POKEMON_TO_BENCH -> {
                        val cards = p.deck.filter { it.id in ids && def(it).category == Category.POKEMON && def(it).name == choice.targetNamePrefix }
                        update { it.copy(deck = it.deck - cards.toSet(), bench = it.bench + cards.map { card -> PokemonInstance(listOf(card), enteredPlayTurn = s.turn) }) }
                        events += GameEvent("NamedPokemonSearchedToBench", "Colocou ${cards.size} Pokémon indicado(s) no Banco pelo ataque.", cards.size)
                    }
                    ChoiceKind.ON_BENCH_DISCARD_OPPONENT_DECK_TOP -> if (ids.isNotEmpty()) {
                        val opponent = 1-actor
                        val top = s.players[opponent].deck.firstOrNull()
                        if (top != null && top.id == ids.single()) {
                            update(opponent) { it.copy(deck = it.deck.drop(1), discard = it.discard + top) }
                            events += GameEvent("OpponentDeckDiscarded", "Descartou a carta do topo do deck adversário.", 1)
                        }
                    }
                    ChoiceKind.ON_BENCH_SWITCH_AND_MOVE_ENERGY -> if (ids.isNotEmpty()) {
                        val targetId = ids.single()
                        val newActive = p.bench.first { it.id == targetId }
                        val oldActive = p.active
                        if (oldActive != null) {
                            update { it.copy(active = newActive.copy(enteredActiveTurn = s.turn), bench = it.bench.filterNot { pokemon -> pokemon.id == targetId } + oldActive) }
                            events += GameEvent("OnBenchAbilitySwitched", "${def(newActive.top).name} entrou no Ativo pela habilidade.")
                            val energies = p.pokemon().filter { it.id != targetId }.flatMap { it.energies }
                            if (energies.isNotEmpty()) remainingChoices = listOf(ChoiceRequest(ChoiceKind.ON_BENCH_MOVE_ENERGY,
                                "Você pode mover qualquer quantidade de Energia dos outros Pokémon para ${def(newActive.top).name}.",
                                energies.map { it.id }, 0, energies.size, sourcePokemonId = targetId)) + remainingChoices
                        }
                    }
                    ChoiceKind.ON_BENCH_MOVE_ENERGY -> if (ids.isNotEmpty()) {
                        val targetId = checkNotNull(choice.sourcePokemonId)
                        val moving = p.pokemon().filter { it.id != targetId }.flatMap { it.energies }.filter { it.id in ids }
                        update { player ->
                            fun transfer(pokemon: PokemonInstance) = pokemon.copy(energies = if (pokemon.id == targetId)
                                pokemon.energies + moving else pokemon.energies.filterNot { energy -> energy.id in ids })
                            player.copy(active = player.active?.let(::transfer), bench = player.bench.map(::transfer))
                        }
                        events += GameEvent("OnBenchAbilityEnergyMoved", "Moveu ${moving.size} Energia(s) para o Ativo pela habilidade.", moving.size)
                    }
                    ChoiceKind.ATTACK_RECOVER_POKEMON -> {
                        val cards = p.discard.filter { it.id in ids && def(it).category == Category.POKEMON && def(it).stage == Stage.BASIC }
                        update { it.copy(discard = it.discard - cards.toSet(), bench = it.bench + cards.map { card -> PokemonInstance(listOf(card), enteredPlayTurn = s.turn) }) }
                        events += GameEvent("PokemonRecoveredToBench", "Colocou ${cards.size} Pokémon do descarte no Banco.", cards.size)
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
                    ChoiceKind.ATTACK_ANY_CARD_SEARCH -> {
                        val cards = p.deck.filter { it.id in ids }
                        update { it.copy(deck = it.deck - cards.toSet(), hand = it.hand + cards) }
                        events += GameEvent("CardSearched", "Buscou ${cards.size} carta(s) do deck.", cards.size)
                    }
                    ChoiceKind.ATTACK_EVOLVE_SELF -> {
                        val evolution = p.deck.first { it.id == ids.single() && def(it).canEvolveFrom(def(p.active!!.top)) &&
                            def(it).abilities.none { ability -> ability.onlyPlayableWithPalafinZeroToHero } }
                        update { it.copy(deck = it.deck - evolution, active = it.active!!.copy(cards = it.active.cards + evolution, lastEvolutionTurn = s.turn)) }
                        events += GameEvent("PokemonEvolvedByAttack", "${def(evolution).name} evoluiu do seu Pokémon Ativo pelo efeito do ataque.")
                    }
                    ChoiceKind.ATTACK_EVOLVE_BENCHED -> {
                        val targetId = checkNotNull(choice.sourcePokemonId)
                        if (ids.isNotEmpty()) {
                            val target = s.players[actor].bench.firstOrNull { it.id == targetId }
                                ?: return illegal("O Pokémon do Banco escolhido não está mais em jogo.")
                            val evolution = s.players[actor].deck.firstOrNull { it.id == ids.single() && it.id in choice.candidates &&
                                def(it).canEvolveFrom(def(target.top)) && def(it).abilities.none { ability -> ability.onlyPlayableWithPalafinZeroToHero } }
                                ?: return illegal("A carta escolhida não evolui do Pokémon selecionado ou já saiu do deck.")
                            update { player -> player.copy(deck = player.deck - evolution, bench = player.bench.map { pokemon ->
                                if (pokemon.id != targetId) pokemon else pokemon.copy(cards = pokemon.cards + evolution,
                                    lastEvolutionTurn = s.turn, restrictions = emptyList(), conditions = emptySet(), poisonDamageCounters = 1,
                                    defendingAttackFailsOnTailsTurn = -1, attackImmunityTurn = -1,
                                    incomingDamageReduction = 0, damageReductionTurn = -1, incomingDamageReductionEvolutionOnly = false,
                                    outgoingDamageReduction = 0, outgoingDamageReductionTurn = -1,
                                    preventBasicNonColorlessDamageTurn = -1, preventBasicPokemonDamageTurn = -1)
                            }) }
                            events += GameEvent("PokemonEvolvedByAttack", "${def(target.top).name} evoluiu para ${def(evolution).name} pelo efeito do ataque.")
                            val evolvedInstance = s.players[actor].bench.first { it.id == targetId }
                            val evolvedAbilities = rules.effectiveAbilities(s, actor, evolvedInstance)
                            evolvedAbilities.filter { it.onEvolveDraw > 0 }.forEach { ability ->
                                val count = minOf(ability.onEvolveDraw, s.players[actor].deck.size)
                                update { it.copy(hand = it.hand + it.deck.take(count), deck = it.deck.drop(count)) }
                                events += GameEvent("CardsDrawn", "Comprou $count carta(s) ao evoluir com ${ability.name}.", count)
                            }
                            if (evolvedAbilities.any { it.onEvolveHealAllEvolutionAndDiscardEnergy }) {
                                val evolutionTargets = s.players[actor].pokemon().filter { def(it.top).stage != Stage.BASIC && it.damage > 0 }
                                val energyToDiscard = evolutionTargets.flatMap { it.energies }
                                evolutionTargets.forEach { pokemon ->
                                    update { player -> player.copy(
                                        active = player.active?.let { if (it.id == pokemon.id) it.copy(damage = 0, energies = emptyList()) else it },
                                        bench = player.bench.map { if (it.id == pokemon.id) it.copy(damage = 0, energies = emptyList()) else it }) }
                                }
                                if (energyToDiscard.isNotEmpty()) update { it.copy(discard = it.discard + energyToDiscard) }
                                events += GameEvent("EvolutionTeamHealed", "Curou ${evolutionTargets.size} Pokémon Evoluído(s) e descartou ${energyToDiscard.size} Energia(s).", evolutionTargets.size)
                            }
                            evolvedAbilities.filter { it.onEvolveShuffleRandomOpponentHandPerHeads > 0 }.forEach { ability ->
                                val rng = SeededRng(s.rng)
                                val heads = List(ability.onEvolveShuffleRandomOpponentHandPerHeads) { rng.nextInt(2) == 0 }.count { it }
                                val opponent = 1 - actor
                                val opponentHand = s.players[opponent].hand.toMutableList()
                                val shuffledCards = mutableListOf<CardInstance>()
                                repeat(minOf(heads, opponentHand.size)) { shuffledCards += opponentHand.removeAt(rng.nextInt(opponentHand.size)) }
                                if (shuffledCards.isNotEmpty()) update(opponent) { it.copy(hand = opponentHand, deck = rng.shuffle(it.deck + shuffledCards)) }
                                s = s.copy(rng = rng.state)
                                events += GameEvent("OpponentHandCardsShuffled", "${ability.name}: o oponente embaralhou ${shuffledCards.size} carta(s) da mão no deck.", shuffledCards.size)
                            }
                            val metalAbility = evolvedAbilities.firstOrNull { it.onEvolveAttachMetalFromDiscardMax > 0 }
                            val metalEnergies = s.players[actor].discard.filter { def(it).category == Category.ENERGY && def(it).basicEnergy && def(it).type == EnergyType.METAL }
                            val metalTargets = s.players[actor].pokemon().filter { def(it.top).type == EnergyType.METAL }
                            if (metalAbility != null && metalEnergies.isNotEmpty() && metalTargets.isNotEmpty()) remainingChoices = listOf(ChoiceRequest(
                                ChoiceKind.EVOLUTION_METAL_ENERGY,
                                "${metalAbility.name}: escolha até ${metalAbility.onEvolveAttachMetalFromDiscardMax} Energias Metal Básicas do descarte.",
                                metalEnergies.map { it.id }, 0, minOf(metalAbility.onEvolveAttachMetalFromDiscardMax, metalEnergies.size))) + remainingChoices
                            val namedRecovery = evolvedAbilities.firstOrNull { it.onEvolveRecoverNamedTrainerFromDiscard != null }
                            val namedCards = namedRecovery?.let { ability -> s.players[actor].discard.filter {
                                def(it).category == Category.TRAINER && def(it).name == ability.onEvolveRecoverNamedTrainerFromDiscard
                            } }.orEmpty()
                            if (namedRecovery != null && namedCards.isNotEmpty()) remainingChoices = listOf(ChoiceRequest(
                                ChoiceKind.EVOLUTION_RECOVER_NAMED_TRAINER,
                                "${namedRecovery.name}: recupere até ${namedRecovery.onEvolveRecoverNamedTrainerMax} carta(s) do descarte.",
                                namedCards.map { it.id }, 0, minOf(namedRecovery.onEvolveRecoverNamedTrainerMax, namedCards.size), visibleCards = namedCards.map { it.id })) + remainingChoices
                            val opponentActive = s.players[1 - actor].active
                            if (evolvedAbilities.any { it.onEvolveConfuseOpponentActive } && opponentActive != null &&
                                rules.effectiveAbilities(s, 1-rules.actingPlayer(s), opponentActive).none { it.preventOpponentEffects }) remainingChoices = listOf(ChoiceRequest(
                                ChoiceKind.EVOLUTION_CONFUSE_ACTIVE, "Usar a habilidade da evolução para Confundir o Pokémon Ativo adversário?",
                                listOf(opponentActive.id), 0, 1)) + remainingChoices
                            val onEvolveSwitch = evolvedAbilities.firstOrNull { it.onEvolveSwitch }
                            val switchCandidates = onEvolveSwitch?.let { ability -> s.players[1 - actor].bench.filter { pokemon ->
                                ability.onEvolveSwitchOpponentBenchMaxRemainingHp <= 0 ||
                                    rules.effectiveHp(s, pokemon) - pokemon.damage <= ability.onEvolveSwitchOpponentBenchMaxRemainingHp
                            } }.orEmpty()
                            if (onEvolveSwitch != null && switchCandidates.isNotEmpty()) remainingChoices = listOf(ChoiceRequest(
                                ChoiceKind.EVOLUTION_SWITCH, "${onEvolveSwitch.name}: escolha um Pokémon do Banco adversário ou pule.",
                                switchCandidates.map { it.id }, 0, 1)) + remainingChoices
                            val spread = evolvedAbilities.firstOrNull { it.onEvolveDamageCounters }
                            val damageTargets = s.players[1 - actor].pokemon().filter { target ->
                                rules.effectiveAbilities(s, 1-rules.actingPlayer(s), target).none { it.preventOpponentEffects }
                            }
                            if (spread != null && damageTargets.size >= spread.damageTargets) remainingChoices = listOf(ChoiceRequest(
                                ChoiceKind.EVOLUTION_DAMAGE_TARGETS,
                                "${spread.name}: escolha ${spread.damageTargets} Pokémon adversários ou pule.",
                                damageTargets.map { it.id }, 0, spread.damageTargets, amount = spread.damageCounters * 10)) + remainingChoices
                        }
                        remainingChoices = remainingChoices.mapNotNull { pending ->
                            if (pending.kind != ChoiceKind.ATTACK_EVOLVE_BENCHED) pending else {
                                val pendingTarget = s.players[actor].bench.firstOrNull { it.id == pending.sourcePokemonId }
                                val eligible = pendingTarget?.let { target ->
                                    s.players[actor].deck.filter { def(it).canEvolveFrom(def(target.top)) &&
                                        def(it).abilities.none { ability -> ability.onlyPlayableWithPalafinZeroToHero } }
                                }.orEmpty()
                                if (eligible.isEmpty()) null else pending.copy(
                                    candidates = eligible.map { it.id }, visibleCards = eligible.map { it.id })
                            }
                        }
                    }
                    ChoiceKind.ATTACK_HEAL_TARGET -> {
                        val targetId = ids.single()
                        update { it.copy(active = it.active?.let { pokemon -> if (pokemon.id == targetId) pokemon.copy(damage = (pokemon.damage-choice.amount).coerceAtLeast(0)) else pokemon },
                            bench = it.bench.map { pokemon -> if (pokemon.id == targetId) pokemon.copy(damage = (pokemon.damage-choice.amount).coerceAtLeast(0)) else pokemon }) }
                        events += GameEvent("DamageHealed", "Curou até ${choice.amount} de dano do Pokémon escolhido.")
                    }
                    ChoiceKind.ATTACK_HAND_ENERGY -> if (ids.isNotEmpty()) {
                        val energy = p.hand.first { it.id == ids.single() && def(it).category == Category.ENERGY && def(it).basicEnergy &&
                            (choice.energyType == null || def(it).type == choice.energyType) }
                        val targets = if (choice.benchedOnly) p.bench else p.pokemon()
                        remainingChoices = listOf(ChoiceRequest(ChoiceKind.ATTACK_HAND_ENERGY_TARGET,
                            "Escolha ${if (choice.benchedOnly) "um Pokémon do Banco" else "um dos seus Pokémon"} para receber ${def(energy).name}.",
                            targets.map { it.id }, 1, 1, remaining = choice.remaining, selectedEnergy = energy.id,
                            energyType = choice.energyType, benchedOnly = choice.benchedOnly, healAll = choice.healAll)) + remainingChoices
                    }
                    ChoiceKind.ATTACK_HAND_ENERGY_TARGET -> {
                        val energy = p.hand.first { it.id == choice.selectedEnergy && def(it).category == Category.ENERGY && def(it).basicEnergy &&
                            (choice.energyType == null || def(it).type == choice.energyType) }
                        val targetId = ids.single()
                        val targetIsBench = p.bench.any { it.id == targetId }
                        require(if (choice.benchedOnly) targetIsBench else p.pokemon().any { it.id == targetId })
                        update { player -> player.copy(hand = player.hand - energy,
                            active = player.active?.let { if (it.id == targetId) it.copy(energies = it.energies + energy,
                                damage = if (choice.healAll) 0 else it.damage) else it },
                            bench = player.bench.map { if (it.id == targetId) it.copy(energies = it.energies + energy,
                                damage = if (choice.healAll) 0 else it.damage) else it }) }
                        events += GameEvent("AttackHandEnergyAttached", "Ligou ${def(energy).name} a um Pokémon pelo ataque.${if (choice.healAll) " Todo o dano desse Pokémon foi curado." else ""}")
                        val eligible = s.players[actor].hand.filter { def(it).category == Category.ENERGY && def(it).basicEnergy &&
                            (choice.energyType == null || def(it).type == choice.energyType) }
                        if (eligible.isNotEmpty() && choice.remaining > 0) remainingChoices = listOf(ChoiceRequest(ChoiceKind.ATTACK_HAND_ENERGY,
                            "Você pode ligar outra Energia Básica da mão ou concluir.", eligible.map { it.id }, 0, 1,
                            energyType = choice.energyType, benchedOnly = choice.benchedOnly, healAll = choice.healAll)) + remainingChoices
                    }
                    ChoiceKind.ATTACK_SHUFFLE_OWN_BENCH -> {
                        val targetId = ids.single()
                        val target = p.bench.first { it.id == targetId }
                        val cards = target.cards + target.energies + listOfNotNull(target.tool)
                        val rng = SeededRng(s.rng)
                        update { player -> player.copy(bench = player.bench.filterNot { it.id == targetId }, deck = rng.shuffle(player.deck + cards)) }
                        s = s.copy(rng = rng.state)
                        events += GameEvent("PokemonShuffledIntoDeck", "${def(target.top).name} e todas as cartas ligadas foram embaralhados no deck.", cards.size)
                    }
                    ChoiceKind.ATTACK_KNOCKOUT_BASIC_BENCH -> {
                        val targetId = ids.single()
                        val target = s.players[1-actor].bench.firstOrNull { it.id == targetId }
                            ?: return illegal("O Pokémon escolhido não está mais no Banco adversário.")
                        if (rules.attackTargetEffectsPrevented(s, 1-actor, target)) return illegal("Este Pokémon no Banco está protegido contra ataques.")
                        update(1-actor) { player -> player.copy(bench = player.bench.map { pokemon ->
                            if (pokemon.id == targetId && def(pokemon.top).stage == Stage.BASIC) pokemon.copy(damage = maxOf(pokemon.damage, rules.effectiveHp(s, pokemon))) else pokemon
                        }) }
                        events += GameEvent("AttackEffectKnockout", "Nocauteou um Pokémon Básico do Banco adversário pelo efeito do ataque.")
                    }
                    ChoiceKind.ATTACK_DAMAGE_TARGET -> {
                        val owner = 1-actor
                        val target = s.players[owner].bench.first { it.id == ids.single() }
                        if (rules.attackTargetEffectsPrevented(s, owner, target)) return illegal("Este Pokémon no Banco está protegido contra ataques.")
                        val hp = rules.effectiveHp(s, target)
                        if (choice.amount > 0 && target.damage < hp && target.damage+choice.amount >= hp)
                            s = s.copy(pendingAttackDamageKnockoutIds = s.pendingAttackDamageKnockoutIds + target.id)
                        update(owner) { it.copy(bench = it.bench.map { pokemon ->
                            if (pokemon.id in ids) pokemon.copy(damage = pokemon.damage + choice.amount) else pokemon
                        }) }
                        events += GameEvent("BenchDamageDealt", "Causou ${choice.amount} de dano ao Banco adversário.", choice.amount)
                    }
                    ChoiceKind.ATTACK_DISCARD_THEN_BENCH_TARGET -> {
                        val owner = 1-actor; val targetId = ids.single()
                        val target = s.players[owner].bench.first { it.id == targetId }
                        if (rules.attackTargetEffectsPrevented(s, owner, target)) return illegal("Este Pokémon no Banco está protegido contra ataques.")
                        val hp = rules.effectiveHp(s, target)
                        if (target.damage < hp && target.damage + choice.amount >= hp)
                            s = s.copy(pendingAttackDamageKnockoutIds = s.pendingAttackDamageKnockoutIds + target.id)
                        update(owner) { player -> player.copy(bench = player.bench.map { if (it.id == targetId) it.copy(damage = it.damage + choice.amount) else it }) }
                        events += GameEvent("BenchDamageDealt", "Causou ${choice.amount} de dano ao Banco adversário, sem aplicar Fraqueza ou Resistência.", choice.amount)
                    }
                    ChoiceKind.ATTACK_DAMAGE_COUNTER_TARGET -> {
                        val targetId = ids.single()
                        val target = s.players[1-actor].pokemon().firstOrNull { it.id == targetId }
                            ?: return illegal("O Pokémon escolhido não está mais em jogo.")
                        if (rules.attackTargetEffectsPrevented(s, 1-actor, target)) return illegal("Este Pokémon está protegido contra ataques.")
                        update(1-actor) { player -> player.copy(
                            active = player.active?.let { pokemon -> if (pokemon.id == targetId) pokemon.copy(damage = pokemon.damage + choice.amount) else pokemon },
                            bench = player.bench.map { pokemon -> if (pokemon.id == targetId) pokemon.copy(damage = pokemon.damage + choice.amount) else pokemon }) }
                        events += GameEvent("BenchDamageCountersPlaced", "Colocou ${choice.amount / 10} contador(es) de dano no Pokémon do Banco adversário.", choice.amount)
                        if (choice.remaining > 1) remainingChoices = listOf(choice.copy(
                            title = "Escolha um Pokémon do Banco adversário para receber 1 dos ${choice.remaining-1} contadores restantes.",
                            remaining = choice.remaining - 1)) + remainingChoices
                    }
                    ChoiceKind.ATTACK_OPTIONAL_RETURN_SELF -> if (ids.isNotEmpty()) {
                        val returning = s.players[actor].active?.takeIf { it.id == ids.single() }
                            ?: return Transition(state, Legality.Illegal("O Pokémon que retornaria à mão não está mais no Ativo."))
                        val attached = returning.energies + listOfNotNull(returning.tool)
                        update(actor) { player -> player.copy(active = null, hand = player.hand + returning.cards, discard = player.discard + attached) }
                        events += GameEvent("PokemonReturnedToHand", "Devolveu ${def(returning.top).name} à mão e descartou ${attached.size} carta(s) ligada(s).", returning.cards.size)
                    }
                    ChoiceKind.ATTACK_MULTI_DAMAGE_TARGETS, ChoiceKind.ATTACK_MULTI_DAMAGE_SINGLE_TARGET -> {
                        val attacker = p.active ?: error("Atacante ausente durante a resolução.")
                        val attack = rules.definition(attacker.top).attacks.firstOrNull { candidate ->
                            candidate.effects.any { effect -> when (effect) {
                                is Effect.MultiTargetDamage -> effect.amount == choice.amount
                                is Effect.CoinDamageEachOpponentPokemon -> effect.amount == choice.amount
                                else -> false
                            } }
                        } ?: error("Ataque de dano múltiplo não encontrado.")
                        val multiEffect = attack.effects.filterIsInstance<Effect.MultiTargetDamage>().firstOrNull { it.amount == choice.amount }
                        val opponent = 1-actor
                        ids.forEach { targetId ->
                            val target = s.players[opponent].pokemon().first { it.id == targetId }
                            if (rules.attackTargetEffectsPrevented(s, opponent, target)) return illegal("Este Pokémon está protegido contra ataques.")
                            val ignoreWeaknessResistance = multiEffect?.let { effect ->
                                effect.ignoreWeaknessResistance || (effect.ignoreWeaknessResistanceForBench && target.id != s.players[opponent].active?.id)
                            } ?: false
                            val preview = rules.preview(s, attack.id, targetId, effectDamage = choice.amount,
                                forceIgnoreWeaknessResistance = ignoreWeaknessResistance)
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
                            val candidates = s.players[opponent].pokemon().filter { !rules.attackTargetEffectsPrevented(s, opponent, it) }.map { it.id }
                            if (candidates.isNotEmpty()) remainingChoices = listOf(ChoiceRequest(
                                ChoiceKind.ATTACK_MULTI_DAMAGE_SINGLE_TARGET,
                                "Escolha mais 1 Pokémon adversário (${choice.remaining-1} escolhas restantes).",
                                candidates, 1, 1, remaining = choice.remaining-1, amount = choice.amount)) + remainingChoices
                        }
                    }
                    ChoiceKind.ATTACK_MOVE_DAMAGE_SOURCE -> if (ids.isNotEmpty()) {
                        val opponent = 1-actor
                        val source = s.players[opponent].pokemon().first { it.id == ids.single() }
                        if (rules.attackTargetEffectsPrevented(s, opponent, source)) return illegal("Este Pokémon está protegido contra ataques.")
                        remainingChoices = listOf(ChoiceRequest(ChoiceKind.ATTACK_MOVE_DAMAGE_DESTINATION,
                            "Escolha outro Pokémon adversário para receber 1 contador de dano.",
                            s.players[opponent].pokemon().filter { it.id != source.id && !rules.attackTargetEffectsPrevented(s, opponent, it) }.map { it.id }, 1, 1,
                            sourcePokemonId = source.id)) + remainingChoices
                    }
                    ChoiceKind.ATTACK_MOVE_DAMAGE_DESTINATION -> {
                        val opponent = 1-actor
                        val sourceId = checkNotNull(choice.sourcePokemonId)
                        val destinationId = ids.single()
                        val source = s.players[opponent].pokemon().first { it.id == sourceId }
                        val destination = s.players[opponent].pokemon().firstOrNull { it.id == destinationId }
                            ?: return illegal("O Pokémon escolhido não está mais em jogo.")
                        if (rules.attackTargetEffectsPrevented(s, opponent, source) || rules.attackTargetEffectsPrevented(s, opponent, destination))
                            return illegal("Este Pokémon está protegido contra ataques.")
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
                        val sources = field.filter { source -> !rules.attackTargetEffectsPrevented(s, opponent, source) && source.damage >= 10 && field.any { it.id != source.id && !rules.attackTargetEffectsPrevented(s, opponent, it) } }
                        if (sources.isNotEmpty()) remainingChoices = listOf(ChoiceRequest(ChoiceKind.ATTACK_MOVE_DAMAGE_SOURCE,
                            "Você pode mover outro contador de dano ou concluir.", sources.map { it.id }, 0, 1)) + remainingChoices
                    }
                    ChoiceKind.ATTACK_MOVE_OWN_DAMAGE_SOURCE -> if (ids.isNotEmpty()) {
                        val sourceId = ids.single()
                        val source = p.bench.first { it.id == sourceId }
                        val targets = s.players[1-actor].pokemon().filter { !rules.attackTargetEffectsPrevented(s, 1-actor, it) }
                        if (targets.isNotEmpty()) remainingChoices = listOf(ChoiceRequest(
                            ChoiceKind.ATTACK_MOVE_OWN_DAMAGE_TARGET,
                            "Escolha um Pokémon adversário para receber todos os contadores movidos.", targets.map { it.id }, 1, 1,
                            sourcePokemonId = sourceId, amount = source.damage)) + remainingChoices
                    }
                    ChoiceKind.ATTACK_MOVE_OWN_DAMAGE_TARGET -> {
                        val sourceId = checkNotNull(choice.sourcePokemonId)
                        val targetId = ids.single()
                        val opponent = 1-actor
                        val source = p.bench.first { it.id == sourceId }
                        val target = s.players[opponent].pokemon().firstOrNull { it.id == targetId }
                            ?: return illegal("O Pokémon escolhido não está mais em jogo.")
                        if (rules.attackTargetEffectsPrevented(s, opponent, target)) return illegal("Este Pokémon está protegido contra ataques.")
                        val movedDamage = source.damage
                        update(actor) { player -> player.copy(bench = player.bench.map { pokemon ->
                            if (pokemon.id == sourceId) pokemon.copy(damage = 0) else pokemon
                        }) }
                        update(opponent) { player -> player.copy(
                            active = player.active?.let { pokemon -> if (pokemon.id == targetId) pokemon.copy(damage = pokemon.damage + movedDamage) else pokemon },
                            bench = player.bench.map { pokemon -> if (pokemon.id == targetId) pokemon.copy(damage = pokemon.damage + movedDamage) else pokemon }
                        ) }
                        events += GameEvent("DamageCountersMoved", "Moveu ${movedDamage/10} contador(es) de dano do seu Banco para um Pokémon adversário.", movedDamage)
                    }
                    ChoiceKind.ATTACK_OPPONENT_ENERGY -> if (ids.isNotEmpty()) {
                        val opponent = 1-actor
                        val energyId = ids.single()
                        val source = s.players[opponent].pokemon().first { pokemon -> pokemon.energies.any { it.id == energyId } }
                        if (rules.attackTargetEffectsPrevented(s, opponent, source)) return illegal("Este Pokémon está protegido contra ataques.")
                        val targets = s.players[opponent].pokemon().filter { it.id != source.id &&
                            !rules.attackTargetEffectsPrevented(s, opponent, it) }
                        if (targets.isNotEmpty()) remainingChoices = listOf(ChoiceRequest(
                            ChoiceKind.ATTACK_OPPONENT_ENERGY_TARGET,
                            "Escolha outro Pokémon adversário para receber a Energia.", targets.map { it.id }, 1, 1,
                            selectedEnergy = energyId, sourcePokemonId = source.id)) + remainingChoices
                    }
                    ChoiceKind.ATTACK_OPPONENT_ENERGY_TARGET -> {
                        val opponent = 1-actor
                        val energyId = checkNotNull(choice.selectedEnergy)
                        val sourceId = checkNotNull(choice.sourcePokemonId)
                        val targetId = ids.single()
                        val source = s.players[opponent].pokemon().first { it.id == sourceId }
                        val target = s.players[opponent].pokemon().firstOrNull { it.id == targetId }
                            ?: return illegal("O Pokémon escolhido não está mais em jogo.")
                        if (rules.attackTargetEffectsPrevented(s, opponent, source) || rules.attackTargetEffectsPrevented(s, opponent, target))
                            return illegal("Este Pokémon está protegido contra ataques.")
                        val energy = source.energies.first { it.id == energyId }
                        update(opponent) { player ->
                            fun move(pokemon: PokemonInstance) = when (pokemon.id) {
                                sourceId -> pokemon.copy(energies = pokemon.energies - energy)
                                targetId -> pokemon.copy(energies = pokemon.energies + energy)
                                else -> pokemon
                            }
                            player.copy(active = player.active?.let(::move), bench = player.bench.map(::move))
                        }
                        events += GameEvent("OpponentEnergyMoved", "Moveu uma Energia entre Pokémon adversários.")
                    }
                    ChoiceKind.ATTACK_ENERGY -> {
                        remainingChoices = listOf(ChoiceRequest(ChoiceKind.ATTACK_ENERGY_TARGET,
                            "Escolha um Pokémon do seu Banco para receber a Energia movida.", p.bench.map { it.id }.filter { it !in choice.selectedTargets }, 1, 1,
                            remaining = choice.remaining, selectedEnergy = ids.single(), selectedTargets = choice.selectedTargets)) + remainingChoices
                    }
                    ChoiceKind.ATTACK_MOVE_ENERGY_TO_ONE_BENCH -> {
                        val activeEnergies = p.active?.energies.orEmpty()
                        val selected = ids.map { id -> activeEnergies.firstOrNull { it.id == id }
                            ?: return illegal("Escolha Energias ligadas ao Pokémon Ativo.") }
                        if (selected.isEmpty()) return illegal("Escolha pelo menos uma Energia para mover.")
                        remainingChoices = listOf(ChoiceRequest(ChoiceKind.ATTACK_MOVE_ENERGY_TO_ONE_BENCH_TARGET,
                            "Escolha um Pokémon do seu Banco para receber todas as Energias selecionadas.",
                            p.bench.map { it.id }, 1, 1, pendingCards = selected.map { it.id })) + remainingChoices
                    }
                    ChoiceKind.ATTACK_MOVE_ENERGY_TO_ONE_BENCH_TARGET -> {
                        val targetId = ids.single()
                        val target = p.bench.firstOrNull { it.id == targetId }
                            ?: return illegal("Escolha um Pokémon do seu Banco.")
                        val activeEnergies = p.active?.energies.orEmpty()
                        val moving = choice.pendingCards.map { id -> activeEnergies.firstOrNull { it.id == id }
                            ?: return illegal("Uma Energia selecionada não está mais ligada ao Ativo.") }
                        update(actor) { player -> player.copy(
                            active = player.active?.let { it.copy(energies = it.energies - moving.toSet()) },
                            bench = player.bench.map { pokemon -> if (pokemon.id == targetId) pokemon.copy(energies = pokemon.energies + moving) else pokemon }) }
                        events += GameEvent("AttackEnergyMoved", "Moveu ${moving.size} Energia(s) do Ativo para ${def(target.top).name}.", moving.size)
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
                    ChoiceKind.ATTACK_ENERGY_TO_HAND -> {
                        val energy = p.active!!.energies.first { it.id == ids.single() }
                        update { it.copy(active = it.active!!.copy(energies = it.active!!.energies - energy), hand = it.hand + energy) }
                        events += GameEvent("AttackEnergyReturned", "Devolveu uma Energia do Ativo para a mão.")
                    }
                    ChoiceKind.ATTACK_MOVE_ALL_ENERGY_TARGET -> {
                        val target = ids.single()
                        val energies = p.active!!.energies
                        update { it.copy(active = it.active!!.copy(energies = emptyList()),
                            bench = it.bench.map { pokemon -> if (pokemon.id == target) pokemon.copy(energies = pokemon.energies + energies) else pokemon }) }
                        events += GameEvent("AttackEnergyMoved", "Moveu todas as Energias do Ativo para o Banco.")
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
                            val hp = rules.effectiveHp(s, target)
                            if (target.damage < hp && target.damage+bonus >= hp)
                                s = s.copy(pendingAttackDamageKnockoutIds = s.pendingAttackDamageKnockoutIds + target.id)
                            update(1-actor) { opponent -> opponent.copy(active = opponent.active?.copy(damage = opponent.active!!.damage + bonus)) }
                        }
                        events += GameEvent("BenchEnergyDiscarded", "Descartou ${discarded.size} Energia(s) do Banco e causou $bonus de dano adicional.", bonus)
                    }
                    ChoiceKind.ATTACK_HAND_ENERGY_DISCARD -> if (ids.isNotEmpty()) {
                        val discarded = p.hand.filter { it.id in ids && def(it).category == Category.ENERGY }
                        update(actor) { it.copy(hand = it.hand - discarded.toSet(), discard = it.discard + discarded) }
                        val attacker = s.players[actor].active ?: error("Atacante ausente durante o descarte de Energia.")
                        val attack = rules.definition(attacker.top).attacks.firstOrNull { candidate ->
                            candidate.effects.filterIsInstance<Effect.DiscardHandEnergyDamageBonus>().any { it.perEnergy == choice.amount }
                        } ?: error("Ataque com descarte de Energias da mão não encontrado.")
                        val totalPreview = rules.preview(s, attack.id, effectDamage = discarded.size * choice.amount)
                            ?: error("Não foi possível calcular o dano adicional.")
                        val basePreview = rules.preview(s, attack.id) ?: error("Não foi possível calcular o dano-base.")
                        val bonus = (totalPreview.damage - basePreview.damage).coerceAtLeast(0)
                        val target = s.players[1-actor].active ?: error("Alvo ativo ausente durante o ataque.")
                        if (bonus > 0 && target.damage < totalPreview.effectiveHp && target.damage + bonus >= totalPreview.effectiveHp)
                            s = s.copy(pendingAttackDamageKnockoutIds = s.pendingAttackDamageKnockoutIds + target.id)
                        update(1-actor) { opponent -> opponent.copy(active = opponent.active?.copy(damage = opponent.active!!.damage + bonus)) }
                        events += GameEvent("HandEnergyDiscardedForDamage", "Descartou ${discarded.size} Energia(s) da mão e causou $bonus de dano adicional.", bonus)
                    }
                    ChoiceKind.ATTACK_OWN_ENERGY_DISCARD -> if (ids.isNotEmpty()) {
                        val discarded = p.pokemon().flatMap { it.energies }.filter { it.id in ids && def(it).category == Category.ENERGY }
                        update(actor) { player -> player.copy(
                            active = player.active?.let { pokemon -> pokemon.copy(energies = pokemon.energies - discarded.toSet()) },
                            bench = player.bench.map { pokemon -> pokemon.copy(energies = pokemon.energies - discarded.toSet()) },
                            discard = player.discard + discarded)
                        }
                        val attacker = s.players[actor].active ?: error("Atacante ausente durante o descarte de Energia.")
                        val attack = rules.definition(attacker.top).attacks.firstOrNull { candidate ->
                            candidate.effects.filterIsInstance<Effect.DiscardOwnEnergyDamageBonus>().any { it.perEnergy == choice.amount }
                        } ?: error("Ataque com descarte de Energias dos seus Pokémon não encontrado.")
                        val totalPreview = rules.preview(s, attack.id, effectDamage = discarded.size * choice.amount)
                            ?: error("Não foi possível calcular o dano adicional.")
                        val basePreview = rules.preview(s, attack.id) ?: error("Não foi possível calcular o dano-base.")
                        val bonus = (totalPreview.damage - basePreview.damage).coerceAtLeast(0)
                        val target = s.players[1-actor].active ?: error("Alvo ativo ausente durante o ataque.")
                        if (bonus > 0 && target.damage < totalPreview.effectiveHp && target.damage + bonus >= totalPreview.effectiveHp)
                            s = s.copy(pendingAttackDamageKnockoutIds = s.pendingAttackDamageKnockoutIds + target.id)
                        update(1-actor) { opponent -> opponent.copy(active = opponent.active?.copy(damage = opponent.active!!.damage + bonus)) }
                        events += GameEvent("PokemonEnergyDiscardedForDamage", "Descartou ${discarded.size} Energia(s) dos seus Pokémon e causou $bonus de dano adicional.", bonus)
                    }
                    ChoiceKind.ATTACK_DISCARD_BASIC_ENERGY_TO_SELF -> if (ids.isNotEmpty()) {
                        val targetId = checkNotNull(choice.sourcePokemonId) { "Atacante ausente durante a ligação de Energia do descarte." }
                        val energies = ids.map { id -> p.discard.firstOrNull { it.id == id && def(it).category == Category.ENERGY && def(it).basicEnergy && def(it).type == EnergyType.FIGHTING }
                            ?: return illegal("Escolha apenas Energias Básicas de Luta do descarte.") }
                        val target = p.pokemon().firstOrNull { it.id == targetId }
                            ?: return illegal("O Pokémon que usou o ataque não está mais em jogo.")
                        update(actor) { player -> player.copy(discard = player.discard - energies.toSet(),
                            active = player.active?.let { pokemon -> if (pokemon.id == targetId) pokemon.copy(energies = pokemon.energies + energies) else pokemon },
                            bench = player.bench.map { pokemon -> if (pokemon.id == targetId) pokemon.copy(energies = pokemon.energies + energies) else pokemon }) }
                        events += GameEvent("AttackDiscardEnergyAttached", "Ligou ${energies.size} Energia(s) Básica(s) de Luta do descarte a ${def(target.top).name}.", energies.size)
                    }
                    ChoiceKind.ATTACK_TEAM_ROCKET_ENERGY_DISCARD -> if (ids.isNotEmpty()) {
                        val energy = p.active?.energies?.firstOrNull { it.id == ids.single() && def(it).name == "Team Rocket's Energy" }
                            ?: return illegal("Escolha uma Team Rocket's Energy ligada ao seu Pokémon Ativo.")
                        val opponent = 1-actor
                        val target = s.players[opponent].active ?: return illegal("Não há Pokémon Ativo adversário para descartar.")
                        if (rules.attackTargetEffectsPrevented(s, opponent, target))
                            return illegal("O Pokémon Ativo adversário está protegido contra efeitos de ataques.")
                        val attached = target.cards + target.energies + listOfNotNull(target.tool)
                        update(actor) { player -> player.copy(
                            active = player.active?.let { attacker -> attacker.copy(energies = attacker.energies - energy) },
                            discard = player.discard + energy)
                        }
                        update(opponent) { player -> player.copy(active = null, discard = player.discard + attached) }
                        events += GameEvent("OpponentActiveDiscarded", "Descartou ${def(target.top).name} e todas as cartas ligadas pelo ataque Evil Incineration.", attached.size)
                    }
                    ChoiceKind.ATTACK_RESTRICT_OPPONENT_ATTACK -> {
                        val pokemonId = s.players[1-actor].active?.id ?: error("Pokémon ativo adversário ausente.")
                        val attackId = ids.single()
                        update(1-actor) { player -> player.copy(active = player.active?.let { target ->
                            if (target.id == pokemonId) target.copy(restrictions = target.restrictions + AttackRestriction(attackId, s.turn + 1)) else target
                        }) }
                        events += GameEvent("OpponentAttackRestricted", "Bloqueou o ataque selecionado do Pokémon Ativo adversário no próximo turno.")
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
                    ChoiceKind.ATTACK_COPY_BENCHED_N -> error("A escolha do ataque copiado deve ser consumida pelo RulesEngine antes do ContentEngine.")
                    ChoiceKind.ATTACK_COPY_OPPONENT_ACTIVE -> error("A escolha do ataque adversário copiado deve ser consumida pelo RulesEngine antes do ContentEngine.")
                    ChoiceKind.ATTACK_REVEAL_HAND_POKEMON -> error("A escolha de revelar Pokémon da mão é consumida pelo RulesEngine antes do ContentEngine.")
                }
                s = s.copy(resolution = resolution.copy(choices = remainingChoices))
            }
            else -> error("Ação incorreta para ContentEngine")
        }
        s = rules.enqueuePalafinZeroToHero(state, s, actor)
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
