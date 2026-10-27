package com.tcgtrainer.engine

class EffectEngine(private val catalog: CardCatalog) {
    fun effectiveHp(pokemon: PokemonInstance, state: GameState? = null, opponentPrizeCardsRemaining: Int = 6,
        abilityOverride: List<AbilityDefinition>? = null): Int {
        val def = catalog.get(pokemon.top.definitionId)
        val stadiumEffect = state?.stadium?.let { catalog.get(it.definitionId).trainerEffect } as? TrainerEffect.HpModifier
        val owner = state?.players?.indexOfFirst { player -> player.pokemon().any { it.id == pokemon.id } } ?: -1
        val prizesRemaining = if (owner >= 0) state!!.players[1-owner].prizes.size else opponentPrizeCardsRemaining
        val suppressor = if (owner >= 0 && state!!.players[owner].active?.id == pokemon.id) {
            state.players[1-owner].active?.let { catalog.get(it.top.definitionId).abilities.firstOrNull { ability -> ability.opponentActiveAbilityException != null } }
        } else null
        val abilities = abilityOverride ?: if (suppressor == null) def.abilities else def.abilities.filter { it.name == suppressor.opponentActiveAbilityException }
        val prizeBonus = abilities.sumOf { it.hpBonusPerOpponentPrizeTaken } * (6-prizesRemaining).coerceAtLeast(0)
        val energyBonus = abilities.sumOf { ability -> ability.hpBonusPerAttachedEnergy * pokemon.energies.count {
            ability.hpBonusPerAttachedEnergyType != null && catalog.get(it.definitionId).type == ability.hpBonusPerAttachedEnergyType
        } }
        val conditionalEnergyBonus = abilities.sumOf { ability ->
            val requiredType = ability.hpAndAttackBonusWithAttachedEnergyType
            if (requiredType != null && pokemon.energies.any { catalog.get(it.definitionId).type == requiredType })
                ability.hpAndAttackBonusWithAttachedEnergy else 0
        }
        val attachedToolBonus = pokemon.tools.sumOf { (catalog.get(it.definitionId).trainerEffect as? TrainerEffect.ToolHpModifier)?.amount ?: 0 }
        val specialEnergyBonus = if (pokemon.energies.any { catalog.get(it.definitionId).category == Category.ENERGY && !catalog.get(it.definitionId).basicEnergy })
            abilities.maxOfOrNull { it.hpBonusWithSpecialEnergy } ?: 0 else 0
        return (def.hp + pokemon.hpModifier + prizeBonus + energyBonus + conditionalEnergyBonus + specialEnergyBonus + attachedToolBonus + if (stadiumEffect?.stage == def.stage) stadiumEffect.amount else 0).coerceAtLeast(0)
    }
    fun damage(attacker: PokemonInstance, defender: PokemonInstance, effects: List<Effect>, boost: Int = 0,
        ignoreWeaknessResistance: Boolean = false, ownBenchCount: Int = 0, opponentBenchCount: Int = 0,
        ownInPlayCount: Int = ownBenchCount + 1, ignoreOpponentActiveEffects: Boolean = false,
        ignoreResistance: Boolean = false, ignoreWeakness: Boolean = false, turn: Int = -1, ownHandCount: Int = 0,
        opponentActiveIsEx: Boolean = false, targetIsOpponentActive: Boolean = true, opponentActiveEnergyCount: Int = 0, opponentInPlayEnergyCount: Int = 0, opponentEnergyCounts: Map<EnergyType, Int> = emptyMap(), ownBenchHasTera: Boolean = false, attackerHealedThisTurn: Boolean = false, opponentHandTrainerCount: Int = 0,
        opponentHasSpecialCondition: Boolean = false, opponentSpecialConditionCount: Int = 0, opponentActiveDamage: Int = 0, opponentHandCount: Int = 0, ownToolCount: Int = 0, ownDamagedPokemonCount: Int = 0,
        ownDamagedBenchedPokemonCount: Int = 0,
        ownEnergyCounts: Map<EnergyType, Int> = emptyMap(), ownKnockedOutByAttackLastTurn: Boolean = false,
        ownPokemonNames: List<String> = emptyList(),
        ownPokemonTypes: List<EnergyType> = emptyList(),
        opponentPokemonNames: List<String> = emptyList(),
        opponentPokemonTypes: List<EnergyType> = emptyList(),
        ownBenchPokemonNames: List<String> = emptyList(),
        ownBenchPokemonTypes: List<EnergyType> = emptyList(),
        friendlyPokemon: List<PokemonInstance> = listOf(attacker), friendlyBenchPokemon: List<PokemonInstance> = emptyList(),
        opponentDamageCounterCount: Int = 0, opponentHandEnergyCount: Int = 0, stadiumInPlay: Boolean = false,
        opponentActiveRetreatCost: Int = 0, ownBenchWithEnergyCounts: Map<EnergyType, Int> = emptyMap(),
        ownDiscardEnergyCount: Int = 0, ownSpecialEnergyCount: Int = 0, ownBasicDiscardEnergyCounts: Map<EnergyType, Int> = emptyMap(), opponentPrizeCardsRemaining: Int = 6,
        ownPrizeCardsRemaining: Int = 6,
        opponentBasicDiscardEnergyCount: Int = 0, opponentItemDiscardCount: Int = 0, attackerDamageTakenLastTurn: Int = if (attacker.damageTakenFromAttackTurn == turn - 1) attacker.damageTakenFromAttack else 0,
        ownDiscardPokemonAbilityNames: List<String> = emptyList(), ownDiscardCardNames: List<String> = emptyList(),
        ownBenchPokemonDetails: List<Pair<String, Int>> = emptyList(),
        ownBenchPokemonTypeDetails: List<Pair<EnergyType, Int>> = emptyList(),
        sourceAbilityOverride: List<AbilityDefinition>? = null, targetAbilityOverride: List<AbilityDefinition>? = null,
        allPokemonToolCount: Int = 0, attackerPokemon: List<PokemonInstance> = listOf(attacker),
        targetIsOpponentBench: Boolean = false,
        abilityOverrides: Map<String, List<AbilityDefinition>> = emptyMap()): Int {
        if (defender.attackImmunityTurn >= 0 && turn == defender.attackImmunityTurn) return 0
        val source = catalog.get(attacker.top.definitionId)
        val target = catalog.get(defender.top.definitionId)
        val sourceAbilities = sourceAbilityOverride ?: source.abilities
        val targetAbilities = targetAbilityOverride ?: target.abilities
        if (turn == defender.preventEvolutionPokemonDamageTurn && source.stage != Stage.BASIC) return 0
        if (turn == defender.preventAbilityPokemonDamageTurn && sourceAbilities.isNotEmpty()) return 0
        if (turn == defender.preventBurnedPokemonDamageTurn && SpecialCondition.BURNED in attacker.conditions) return 0
        val bindingMochiBonus = if (targetIsOpponentActive && opponentActiveIsEx && SpecialCondition.POISONED in attacker.conditions)
            attacker.tools.sumOf { (catalog.get(it.definitionId).trainerEffect as? TrainerEffect.BindingMochi)?.amount ?: 0 } else 0
        if (targetIsOpponentBench && targetAbilities.any { it.preventOpponentAttackDamageEffectsWhileOnBench }) return 0
        fun abilitiesOf(pokemon: PokemonInstance): List<AbilityDefinition> = when (pokemon.id) {
            attacker.id -> sourceAbilities
            defender.id -> targetAbilities
            else -> abilityOverrides[pokemon.id] ?: catalog.get(pokemon.top.definitionId).abilities
        }
        if (targetIsOpponentBench && !target.hasRuleBox && friendlyBenchPokemon.any { benchPokemon ->
                abilitiesOf(benchPokemon).any { it.preventDamageToOwnBenchedNonRuleBoxPokemon }
            }) return 0
        if (Effect.RequiresOpponentBurned in effects && (!targetIsOpponentActive || SpecialCondition.BURNED !in defender.conditions)) return 0
        if (targetAbilities.any { it.preventDamageFromPokemonWithAbilities } && sourceAbilities.isNotEmpty()) return 0
        if (targetAbilities.any { it.preventDamageFromSpecialEnergyAttacker } && attacker.energies.any {
                catalog.get(it.definitionId).category == Category.ENERGY && !catalog.get(it.definitionId).basicEnergy
            }) return 0
        if (targetAbilities.any { it.preventDamageFromOpponentEx } && source.name.endsWith(" ex")) return 0
        if (targetAbilities.any { it.preventDamageFromBasicPokemonEx } && source.stage == Stage.BASIC && source.name.endsWith(" ex")) return 0
        if (friendlyBenchPokemon.any { benchPokemon ->
                abilitiesOf(benchPokemon).any { ability ->
                    ability.benchDamageImmunityForAttackerMaxEnergy > 0 && attacker.energies.size <= ability.benchDamageImmunityForAttackerMaxEnergy
                }
            }) return 0
        if (defender.preventBasicNonColorlessDamageTurn >= 0 && defender.preventBasicNonColorlessDamageTurn == turn &&
            source.stage == Stage.BASIC && source.type != EnergyType.COLORLESS) return 0
        if (defender.preventBasicPokemonDamageTurn >= 0 && defender.preventBasicPokemonDamageTurn == turn && source.stage == Stage.BASIC) return 0
        val targetPreventsEffects = targetAbilities.any { it.preventOpponentEffects ||
            it.preventDamageFromSpecialEnergyAttacker && attacker.energies.any { energy ->
                catalog.get(energy.definitionId).category == Category.ENERGY && !catalog.get(energy.definitionId).basicEnergy
            } }
        val counterDamage = if (targetPreventsEffects) 0 else effects.filterIsInstance<Effect.OwnHandCounterDamage>().sumOf { it.countersPerCard * 10 * ownHandCount }
        val abilityHandBonus = if (ownHandCount == opponentHandCount) sourceAbilities.sumOf { it.damageBonusIfHandsEqual } else 0
        val abilityDamageBonus = if (targetIsOpponentActive) sourceAbilities.sumOf { ability ->
            if (ability.damageBonusAgainstActiveWhenDamageAtLeast > 0 && attacker.damage >= ability.damageBonusAgainstActiveWhenDamageAtLeast)
                ability.damageBonusAmount else 0
        } else 0
        val conditionalEnergyAttackBonus = if (targetIsOpponentActive) sourceAbilities.sumOf { ability ->
            val requiredType = ability.hpAndAttackBonusWithAttachedEnergyType
            if (requiredType != null && attacker.energies.any { catalog.get(it.definitionId).type == requiredType })
                ability.hpAndAttackBonusWithAttachedEnergy else 0
        } else 0
        val futureDamageBonus = if (targetIsOpponentActive && source.name != "Iron Crown ex" &&
            (source.name.startsWith("Iron ") || source.name.startsWith("Miraidon")))
            attackerPokemon.flatMap(::abilitiesOf).sumOf { it.futurePokemonDamageBonus } else 0
        val attackerTypes = setOf(source.type) + sourceAbilities.flatMap { it.additionalPokemonTypes }
        val teamAttackDamageBonus = if (targetIsOpponentActive)
            attackerPokemon.sumOf { pokemon ->
                abilitiesOf(pokemon).sumOf { ability ->
                    val nameMatches = ability.teamAttackDamageBonusPokemonNamePrefix?.let { prefix -> source.canonicalName.startsWith(prefix) } ?: true
                    if (!nameMatches) 0 else
                        (if (ability.teamAttackDamageBonusType in attackerTypes) ability.teamAttackDamageBonus else 0) + ability.teamAttackDamageBonusAnyType
                }
            }
        else 0
        val ownInPlayTypes = attackerPokemon.flatMap { pokemon ->
            val def = catalog.get(pokemon.top.definitionId)
            listOf(def.type) + abilitiesOf(pokemon).flatMap { it.additionalPokemonTypes }
        }.toSet()
        val opponentInPlayTypes = friendlyPokemon.flatMap { pokemon ->
            val def = catalog.get(pokemon.top.definitionId)
            listOf(def.type) + abilitiesOf(pokemon).flatMap { it.additionalPokemonTypes }
        }.toSet()
        val sameTypeBonus = if (targetIsOpponentActive && ownInPlayTypes.intersect(opponentInPlayTypes).isNotEmpty())
            effects.filterIsInstance<Effect.SameTypePokemonInPlayDamageBonus>().sumOf { it.bonus } else 0
        val sameHandSizeBonus = if (ownHandCount == opponentHandCount)
            effects.filterIsInstance<Effect.SameHandSizeDamageBonus>().sumOf { it.bonus } else 0
        val opponentExCountDamage = effects.filterIsInstance<Effect.OpponentExCountDamage>().sumOf { effect ->
            effect.perPokemon * friendlyPokemon.count { catalog.get(it.top.definitionId).name.endsWith(" ex") }
        }
        val conditionalTeamReduction = friendlyPokemon.sumOf { pokemon ->
            abilitiesOf(pokemon).sumOf { ability ->
                if (ability.teamDamageReductionWithEnergyType != null && pokemon.energies.any {
                        catalog.get(it.definitionId).type == ability.teamDamageReductionWithEnergyType
                    }) ability.teamDamageReductionWithEnergyAmount else 0
            }
        }
        val opponentActiveAbilityDamageBonus = if (targetIsOpponentActive && targetAbilities.isNotEmpty())
            effects.filterIsInstance<Effect.OpponentActiveHasAbilityDamageBonus>().sumOf { it.bonus } else 0
        val opponentActiveToolDamageBonus = if (targetIsOpponentActive && defender.tools.isNotEmpty())
            effects.filterIsInstance<Effect.OpponentActiveHasToolDamageBonus>().sumOf { it.bonus } else 0
        val ownSpecialEnergyPresenceBonus = if (attacker.energies.any { energy ->
                catalog.get(energy.definitionId).category == Category.ENERGY && !catalog.get(energy.definitionId).basicEnergy
            }) effects.filterIsInstance<Effect.OwnHasSpecialEnergyDamageBonus>().sumOf { it.bonus } else 0
        val ownNamedEnergyBonus = effects.filterIsInstance<Effect.OwnHasNamedEnergyDamageBonus>().filter { effect ->
            attacker.energies.any { energy -> catalog.get(energy.definitionId).category == Category.ENERGY && catalog.get(energy.definitionId).name.startsWith(effect.namePrefix) }
        }.sumOf { it.bonus }
        val ownBenchNamedEnergyBonus = effects.filterIsInstance<Effect.OwnBenchHasNamedEnergyDamageBonus>().filter { effect ->
            friendlyBenchPokemon.any { pokemon -> pokemon.energies.any { energy ->
                catalog.get(energy.definitionId).category == Category.ENERGY && catalog.get(energy.definitionId).name.startsWith(effect.namePrefix)
            } }
        }.sumOf { it.bonus }
        val storedNextTurnBonus = if (targetIsOpponentActive && attacker.nextTurnAllAttacksDamageBonusTurn == turn)
            attacker.nextTurnAllAttacksDamageBonus else 0
        val base = effects.filterIsInstance<Effect.Damage>().sumOf { it.amount } + bindingMochiBonus +
            ownNamedEnergyBonus + ownBenchNamedEnergyBonus + effects.filterIsInstance<Effect.OpponentRuleBoxDamage>().filter { target.hasRuleBox }.sumOf { it.amount } +
            effects.filterIsInstance<Effect.AllOpponentRuleBoxDamage>().filter { target.hasRuleBox }.sumOf { it.amount } +
            effects.filterIsInstance<Effect.StadiumDamageBonus>().filter { stadiumInPlay }.sumOf { it.bonus } + abilityHandBonus + abilityDamageBonus + conditionalEnergyAttackBonus +
            futureDamageBonus + teamAttackDamageBonus + sameTypeBonus + sameHandSizeBonus + opponentExCountDamage + opponentActiveAbilityDamageBonus + opponentActiveToolDamageBonus + ownSpecialEnergyPresenceBonus + storedNextTurnBonus +
            (if (targetIsOpponentActive && target.stage != Stage.BASIC) attackerPokemon.flatMap(::abilitiesOf).sumOf { it.attackDamageBonusAgainstEvolutionActive } else 0) +
            effects.filterIsInstance<Effect.TargetedDamage>().sumOf { it.damage + it.perOwnEnergy * attacker.energies.size } +
            effects.filterIsInstance<Effect.BenchedCountDamage>().sumOf { it.perPokemon * (ownBenchCount + opponentBenchCount) } +
            effects.filterIsInstance<Effect.OpponentBenchCountDamage>().sumOf { it.perPokemon * opponentBenchCount } +
            effects.filterIsInstance<Effect.OpponentBenchCountAtLeastDamageBonus>().filter { opponentBenchCount >= it.count }.sumOf { it.bonus } +
            effects.filterIsInstance<Effect.BothPlayersNamedPokemonCountDamage>().sumOf { effect -> effect.perPokemon *
                (ownPokemonNames + opponentPokemonNames).count { name -> effect.names.any { name == it || name == "${it} ex" } } } +
            effects.filterIsInstance<Effect.OwnTypePokemonCountDamage>().sumOf { effect -> effect.perPokemon * ownPokemonTypes.count { it == effect.type } } +
            effects.filterIsInstance<Effect.OwnBenchCountDamage>().sumOf { it.perPokemon * ownBenchCount } +
            effects.filterIsInstance<Effect.OwnBenchStageTwoCountDamage>().sumOf { effect ->
                effect.perPokemon * attackerPokemon.count {
                    it.id != attacker.id && catalog.get(it.top.definitionId).stage == Stage.STAGE_TWO
                }
            } +
            effects.filterIsInstance<Effect.OwnBenchWithEnergyDamage>().sumOf { it.perPokemon * (ownBenchWithEnergyCounts[it.type] ?: 0) } +
            effects.filterIsInstance<Effect.OwnDamagedPokemonCountDamage>().sumOf { it.perPokemon * ownDamagedPokemonCount } +
            effects.filterIsInstance<Effect.OwnDamagedBenchedPokemonCountDamage>().sumOf { it.perPokemon * ownDamagedBenchedPokemonCount } +
            effects.filterIsInstance<Effect.OwnInPlayCountDamage>().sumOf { it.perPokemon * ownInPlayCount } +
            effects.filterIsInstance<Effect.OwnBasicPokemonCountDamage>().sumOf { effect -> effect.perPokemon * attackerPokemon.count { catalog.get(it.top.definitionId).stage == Stage.BASIC } } +
            effects.filterIsInstance<Effect.OwnStageOnePokemonCountDamage>().sumOf { effect -> effect.perPokemon * attackerPokemon.count { catalog.get(it.top.definitionId).stage == Stage.STAGE_ONE } } +
            effects.filterIsInstance<Effect.OwnSpeciesCountDamage>().sumOf { effect ->
                effect.perPokemon * ownPokemonNames.count { it == effect.speciesName || it == "${effect.speciesName} ex" }
            } +
            effects.filterIsInstance<Effect.OwnNamedPokemonCountDamage>().sumOf { effect ->
                effect.perPokemon * ownPokemonNames.count { it.startsWith(effect.namePrefix) }
            } +
            effects.filterIsInstance<Effect.OwnAncientPokemonCountDamage>().sumOf { effect ->
                effect.perPokemon * attackerPokemon.count { catalog.get(it.top.definitionId).canonicalName in CardTraits.ancientPokemonNames }
            } +
            effects.filterIsInstance<Effect.OwnBenchTeraDamageBonus>().filter { ownBenchHasTera }.sumOf { it.bonus } +
            effects.filterIsInstance<Effect.OwnEvolutionPokemonCountDamage>().sumOf { effect ->
                effect.perPokemon * attackerPokemon.count { catalog.get(it.top.definitionId).stage != Stage.BASIC }
            } +
            effects.filterIsInstance<Effect.OwnTeamRocketPokemonCountDamage>().sumOf { effect ->
                effect.perPokemon * attackerPokemon.count { catalog.get(it.top.definitionId).canonicalName.startsWith("Team Rocket's ") }
            } +
            effects.filterIsInstance<Effect.OwnAttackNameCountDamage>().sumOf { effect ->
                effect.perPokemon * attackerPokemon.count { pokemon ->
                    catalog.get(pokemon.top.definitionId).attacks.any { it.name == effect.attackName }
                }
            } +
            effects.filterIsInstance<Effect.BenchPokemonPresenceDamageBonus>().filter { effect ->
                ownBenchPokemonNames.any { it == effect.speciesName || it == "${effect.speciesName} ex" }
            }.sumOf { it.bonus } +
            effects.filterIsInstance<Effect.BenchTypePresenceDamageBonus>().filter { effect ->
                effect.type in ownBenchPokemonTypes
            }.sumOf { it.bonus } +
            effects.filterIsInstance<Effect.AllOwnBenchedPokemonDamagedBonus>().filter {
                ownBenchPokemonDetails.all { (_, damage) -> damage > 0 }
            }.sumOf { it.bonus } +
            effects.filterIsInstance<Effect.AnyOwnBenchedPokemonDamagedBonus>().filter {
                ownBenchPokemonDetails.any { (_, damage) -> damage > 0 }
            }.sumOf { it.bonus } +
            effects.filterIsInstance<Effect.OwnNamedBenchDamageCounterDamage>().sumOf { effect ->
                effect.perCounter * ownBenchPokemonDetails.filter { (name, _) -> name.startsWith(effect.namePrefix) }.sumOf { (_, damage) -> damage / 10 }
            } +
            effects.filterIsInstance<Effect.OwnBenchTypeDamageCounterDamage>().sumOf { effect ->
                effect.perCounter * ownBenchPokemonTypeDetails.filter { (type, _) -> type == effect.type }.sumOf { (_, damage) -> damage / 10 }
            } +
            effects.filterIsInstance<Effect.OwnDamageCounterDamage>().sumOf { it.perCounter * (attacker.damage / 10) } +
            effects.filterIsInstance<Effect.ConditionalOwnDamageCounterBonus>().filter { attacker.damage / 10 >= it.minimumCounters }.sumOf { it.bonus } +
            effects.filterIsInstance<Effect.OwnDamageCounterPenalty>().sumOf { it.perCounter * (attacker.damage / 10) } * -1 +
            effects.filterIsInstance<Effect.OwnEnergyTypeCountDamage>().sumOf { effect ->
                effect.damagePerEnergy * if (effect.allPokemon) (ownEnergyCounts[effect.type] ?: 0) else attacker.energies.count { catalog.get(it.definitionId).type == effect.type }
            } +
            effects.filterIsInstance<Effect.OpponentFieldDamageCounterDamage>().sumOf { it.damagePerCounter * opponentDamageCounterCount } +
            effects.filterIsInstance<Effect.OwnUndamagedDamageBonus>().filter { attacker.damage == 0 }.sumOf { it.bonus } +
            effects.filterIsInstance<Effect.OwnHealedThisTurnDamageBonus>().filter { attackerHealedThisTurn }.sumOf { it.bonus } +
            effects.filterIsInstance<Effect.OwnAnyDamageCounterBonus>().filter { attacker.damage > 0 }.sumOf { it.bonus } +
            effects.filterIsInstance<Effect.OpponentExDamage>().filter { targetIsOpponentActive && opponentActiveIsEx }.sumOf { it.bonus } +
            effects.filterIsInstance<Effect.OpponentTypeDamage>().filter { targetIsOpponentActive && target.type == it.type }.sumOf { it.bonus } +
            effects.filterIsInstance<Effect.OpponentTypePresentDamageBonus>().filter { effect -> opponentPokemonTypes.contains(effect.type) }.sumOf { it.bonus } +
            effects.filterIsInstance<Effect.OpponentActiveConditionDamageBonus>().filter { targetIsOpponentActive && it.condition in defender.conditions }.sumOf { it.bonus } +
            effects.filterIsInstance<Effect.OpponentEvolutionActiveDamageBonus>().filter { targetIsOpponentActive && target.stage != Stage.BASIC }.sumOf { it.bonus } +
            effects.filterIsInstance<Effect.OpponentStageTwoActiveDamageBonus>().filter { targetIsOpponentActive && target.stage == Stage.STAGE_TWO }.sumOf { it.bonus } +
            effects.filterIsInstance<Effect.OwnEnergyCountDamage>().sumOf { it.perEnergy * attacker.energies.size } +
            effects.filterIsInstance<Effect.AttackerConditionDamageBonus>().filter { it.condition in attacker.conditions }.sumOf { it.bonus } +
            effects.filterIsInstance<Effect.AttackerAnyConditionDamageBonus>().filter { effect -> attacker.conditions.any { it in effect.conditions } }.sumOf { it.bonus } +
            effects.filterIsInstance<Effect.OpponentEnergyCountDamage>().filter { targetIsOpponentActive }.sumOf { it.perEnergy * opponentActiveEnergyCount } +
            effects.filterIsInstance<Effect.OpponentActiveEnergyCountDamage>().filter { targetIsOpponentActive }.sumOf { it.perEnergy * opponentActiveEnergyCount } +
            effects.filterIsInstance<Effect.SameActiveEnergyCountDamageBonus>().filter { targetIsOpponentActive && attacker.energies.size == opponentActiveEnergyCount }.sumOf { it.bonus } +
            effects.filterIsInstance<Effect.OpponentFieldEnergyCountDamage>().sumOf { it.perEnergy * opponentInPlayEnergyCount } +
            effects.filterIsInstance<Effect.OpponentEnergyTypeDamage>().sumOf { it.perEnergy * (opponentEnergyCounts[it.type] ?: 0) } +
            effects.filterIsInstance<Effect.OwnDiscardBasicEnergyDamageAndShuffle>().sumOf { it.damagePerEnergy * (ownBasicDiscardEnergyCounts[it.type] ?: 0) } +
            effects.filterIsInstance<Effect.OpponentHandEnergyCountDamage>().sumOf { it.perEnergy * opponentHandEnergyCount } +
            effects.filterIsInstance<Effect.OpponentHandTrainerCountDamage>().sumOf { it.perTrainer * opponentHandTrainerCount } +
            effects.filterIsInstance<Effect.OpponentHandCountDamage>().sumOf { it.perCard * opponentHandCount } +
            effects.filterIsInstance<Effect.OpponentBasicDiscardEnergyDamage>().sumOf { it.perEnergy * opponentBasicDiscardEnergyCount } +
            effects.filterIsInstance<Effect.OpponentItemDiscardDamage>().sumOf { it.perItem * opponentItemDiscardCount } +
            effects.filterIsInstance<Effect.OpponentHandAtMostDamageBonus>().filter { opponentHandCount <= it.maxCards }.sumOf { it.bonus } +
            effects.filterIsInstance<Effect.OwnHandEmptyDamageBonus>().filter { ownHandCount == 0 }.sumOf { it.bonus } +
            effects.filterIsInstance<Effect.OpponentActiveRetreatCostDamage>().filter { targetIsOpponentActive }.sumOf { it.perCost * opponentActiveRetreatCost } +
            effects.filterIsInstance<Effect.OpponentConditionDamage>().filter { targetIsOpponentActive && opponentHasSpecialCondition }.sumOf { it.bonus } +
            effects.filterIsInstance<Effect.OpponentSpecialConditionCountDamage>().filter { targetIsOpponentActive }.sumOf { it.perCondition * opponentSpecialConditionCount } +
            effects.filterIsInstance<Effect.OpponentActiveDamagedDamage>().filter { targetIsOpponentActive && opponentActiveDamage > 0 }.sumOf { it.bonus } +
            effects.filterIsInstance<Effect.OpponentActiveDamageCounterDamage>().filter { targetIsOpponentActive }.sumOf { it.perCounter * (opponentActiveDamage / 10) } +
            effects.filterIsInstance<Effect.OwnDiscardEnergyDamage>().sumOf { it.perEnergy * ownDiscardEnergyCount } +
            effects.filterIsInstance<Effect.DiscardEnergyThresholdDamage>().filter { (ownBasicDiscardEnergyCounts[it.type] ?: 0) >= it.minimum }.sumOf { it.bonus } +
            effects.filterIsInstance<Effect.OwnDiscardAbilityCountBonus>().filter { effect ->
                ownDiscardPokemonAbilityNames.count { it == effect.abilityName } >= effect.minimum
            }.sumOf { it.bonus } +
            effects.filterIsInstance<Effect.OwnDiscardNamedCardDamage>().sumOf { effect ->
                effect.perCard * ownDiscardCardNames.count { it == effect.cardName }
            } +
            effects.filterIsInstance<Effect.OwnDiscardNamedPrefixDamage>().sumOf { effect ->
                effect.perCard * ownDiscardCardNames.count { it.startsWith(effect.namePrefix) }
            } +
            effects.filterIsInstance<Effect.OwnInPlayEnergyDamage>().sumOf { it.perEnergy * ownEnergyCounts.values.sum() } +
            effects.filterIsInstance<Effect.OwnSpecialEnergyDamage>().sumOf { it.perEnergy * ownSpecialEnergyCount } +
            effects.filterIsInstance<Effect.OpponentPrizeTakenDamage>().filter { targetIsOpponentActive }.sumOf { it.perPrize * (6-opponentPrizeCardsRemaining) } +
            effects.filterIsInstance<Effect.OwnPrizeTakenDamage>().filter { targetIsOpponentActive }.sumOf { it.perPrize * (6-ownPrizeCardsRemaining) } +
            effects.filterIsInstance<Effect.OpponentPrizeCountDamage>().filter { targetIsOpponentActive && opponentPrizeCardsRemaining <= it.maxRemaining }.sumOf { it.bonus } +
            effects.filterIsInstance<Effect.OwnPrizeLeadDamageBonus>().filter { targetIsOpponentActive && ownPrizeCardsRemaining > opponentPrizeCardsRemaining }.sumOf { it.bonus } +
            effects.filterIsInstance<Effect.OpponentNoRetreatDamage>().filter { targetIsOpponentActive && opponentActiveRetreatCost == 0 }.sumOf { it.bonus } +
            effects.filterIsInstance<Effect.OpponentRetreatCostAtLeastDamageBonus>().filter { targetIsOpponentActive && opponentActiveRetreatCost >= it.minimum }.sumOf { it.bonus } +
            effects.filterIsInstance<Effect.OwnEnergyGreaterThanOpponentActiveDamageBonus>().filter { targetIsOpponentActive && attacker.energies.size > opponentActiveEnergyCount }.sumOf { it.bonus } +
            effects.filterIsInstance<Effect.OpponentResistanceDamage>().filter { targetIsOpponentActive && target.resistance == it.type }.sumOf { it.bonus } +
            effects.filterIsInstance<Effect.OwnToolAttachedDamage>().filter { attacker.tools.isNotEmpty() }.sumOf { it.bonus } +
            effects.filterIsInstance<Effect.OwnToolCountDamage>().sumOf { it.perTool * ownToolCount } +
            effects.filterIsInstance<Effect.AllPokemonToolCountDamage>().sumOf { it.perTool * allPokemonToolCount } +
            effects.filterIsInstance<Effect.BothActiveEnergyCountDamage>().sumOf { it.perEnergy * (attacker.energies.size + opponentActiveEnergyCount) } +
            effects.filterIsInstance<Effect.ConditionalEnergyDamage>().filter { conditional ->
                attacker.energies.count { catalog.get(it.definitionId).type == conditional.type } >= conditional.minimum
            }.sumOf { it.bonus } +
            effects.filterIsInstance<Effect.ConditionalAttachedEnergyDamage>().filter { attacker.energies.size >= it.minimum }.sumOf { it.bonus } +
            effects.filterIsInstance<Effect.ConditionalInPlayEnergyDamage>().filter { conditional ->
                (ownEnergyCounts[conditional.type] ?: 0) >= conditional.minimum
            }.sumOf { it.bonus } +
            effects.filterIsInstance<Effect.OwnKnockoutLastTurnDamage>().filter { ownKnockedOutByAttackLastTurn }.sumOf { it.bonus } +
            (if (Effect.DamageEqualToAttackDamageTakenLastTurn in effects) attackerDamageTakenLastTurn else 0) +
            effects.filterIsInstance<Effect.MovedFromBenchDamageBonus>().filter { attacker.enteredActiveTurn == turn }.sumOf { it.bonus }
        val replacedBase = effects.filterIsInstance<Effect.ReplaceDamageIfOpponentActiveDamaged>().firstOrNull()
            ?.takeIf { targetIsOpponentActive && opponentActiveDamage > 0 }?.amount ?: base
        val attackBase = (replacedBase - if (turn == attacker.outgoingDamageReductionTurn) attacker.outgoingDamageReduction else 0).coerceAtLeast(0)
        if (attackBase == 0) return counterDamage
        var value = (attackBase + attacker.damageModifier + boost).coerceAtLeast(0)
        val sourceTypes = setOf(source.type) + sourceAbilities.flatMap { it.additionalPokemonTypes }
        val weakness = defender.weaknessOverride ?: target.weakness
        if (!ignoreWeaknessResistance && !ignoreWeakness && !(defender.weaknessSuppressedTurn >= 0 && defender.weaknessSuppressedTurn == turn) && weakness in sourceTypes) value *= 2
        if (!ignoreWeaknessResistance && !ignoreResistance && target.resistance in sourceTypes) value -= target.resistanceAmount
        val ignoresOpponentActiveEffects = ignoreOpponentActiveEffects || sourceAbilities.any { it.ignoreOpponentActiveEffects }
        if (!ignoresOpponentActiveEffects) {
            val curlyWall = if (target.stage == Stage.BASIC && target.type == EnergyType.COLORLESS && friendlyPokemon.count {
                    catalog.get(it.top.definitionId).name == "Bouffalant"
                } >= 2) friendlyPokemon.flatMap(::abilitiesOf)
                    .maxOfOrNull { it.protectBasicColorlessWithSecondBouffalant } ?: 0 else 0
            value -= targetAbilities.sumOf { if (it.damageReductionTypes.isEmpty() || source.type in it.damageReductionTypes) it.damageReduction else 0 } + curlyWall
        }
        val tundraWallReduction = if (defender.energies.any { catalog.get(it.definitionId).type == EnergyType.WATER })
            friendlyPokemon.flatMap(::abilitiesOf)
                .maxOfOrNull { it.tundraWallWaterDamageReduction } ?: 0 else 0
        value -= tundraWallReduction
        value -= friendlyPokemon.sumOf { pokemon -> abilitiesOf(pokemon).sumOf { ability ->
            if (ability.requiresActive) {
                if (targetIsOpponentActive && pokemon.id == defender.id) ability.teamDamageReduction else 0
            } else ability.teamDamageReduction
        } } + conditionalTeamReduction
        if (!ignoresOpponentActiveEffects && turn == defender.damageReductionTurn && (!defender.incomingDamageReductionEvolutionOnly || source.stage != Stage.BASIC)) value -= defender.incomingDamageReduction
        if (turn == defender.preventAttackDamageAtMostTurn && value.coerceAtLeast(0) <= defender.preventAttackDamageAtMost) return 0
        return value.coerceAtLeast(0) + (if (targetIsOpponentActive && turn == defender.incomingDamageBonusTurn) defender.incomingDamageBonus else 0) + counterDamage
    }
    fun supported(attack: AttackDefinition): Boolean = attack.effects.all { effect -> when (effect) {
        is Effect.Damage, is Effect.DrawToHand, is Effect.DiscardHandDraw, is Effect.DiscardOneHandDraw, is Effect.DiscardHandEnergyDamageBonus, is Effect.RequiredHandBasicEnergyDiscard, is Effect.DiscardOwnEnergyDamageBonus, is Effect.ShuffleHandDraw, is Effect.AccelerateEnergy, is Effect.RestrictNextTurn, is Effect.Recoil, is Effect.RetaliateDamageCountersNextTurn, Effect.RetaliateEqualDamageTakenNextTurn,
        is Effect.AttachBasicEnergyToEachBenchedPokemon, Effect.AttachBasicFightingEnergyFromDiscardToEachBenchedPokemon,
        is Effect.RequiresBenched, is Effect.RequiresNamedBenchedPokemon, is Effect.TargetedDamage, is Effect.SearchPokemonToHand, is Effect.SearchFirePokemonAndEnergyToHand, is Effect.SearchPokemonTypeToHand, Effect.SearchAnyCardToHand, is Effect.SearchUpToAnyCardsToHand, Effect.SearchSupporterToHand, Effect.SearchStadiumToHand, Effect.SearchItemToHand, is Effect.SearchToolToHand, is Effect.SearchNamedTrainerToHand, is Effect.SearchPokemonNamePrefixToHand, Effect.ChooseOpponentAttackToRestrict, Effect.HealSelfByDamageDealt, is Effect.HealOwnPokemon, Effect.IgnoreWeaknessResistance, Effect.IgnoreResistance, Effect.IgnoreOpponentActiveEffects,
        is Effect.RestrictNamedAttackNextTurn, Effect.CannotRepeatAttackUntilLeavingActive, Effect.LockOpponentItemsNextTurn, Effect.LockOpponentEvolutionNextTurn, Effect.RequiresSecondPlayerFirstTurn, Effect.LockOpponentSupportersNextTurn,
        is Effect.ReduceEvolutionDamageNextTurn,
        is Effect.CoinDamage, is Effect.DiscardAttackEnergy, Effect.DiscardTeamRocketEnergyToDiscardOpponentActive, is Effect.EnergyFromDeck, is Effect.AttachEnergyFromTopDeck, is Effect.SupporterBonus, is Effect.Draw, is Effect.EachPlayerDraw,
        Effect.PreventRetreatNextTurn, Effect.DiscardDefendingTools, is Effect.ConditionalEnergyDamage, is Effect.ConditionalAttachedEnergyDamage, is Effect.ConditionalInPlayEnergyDamage, is Effect.SwitchOwn,
        is Effect.BenchedCountDamage, is Effect.OpponentBenchCountDamage, is Effect.OpponentBenchCountAtLeastDamageBonus, is Effect.BothPlayersNamedPokemonCountDamage, is Effect.OwnTypePokemonCountDamage, is Effect.ShuffleOpponentBenchedPokemonIntoDeck, is Effect.OwnBenchStageTwoCountDamage, is Effect.OwnBenchWithEnergyDamage, is Effect.OwnInPlayCountDamage, is Effect.OwnBasicPokemonCountDamage, is Effect.OwnStageOnePokemonCountDamage, is Effect.OwnSpeciesCountDamage, is Effect.OwnNamedPokemonCountDamage, is Effect.OwnAncientPokemonCountDamage, is Effect.OwnBenchTeraDamageBonus, is Effect.OwnEvolutionPokemonCountDamage, is Effect.DamageCountersUntilActiveHasRemainingHp, is Effect.DamageCountersUntilBenchedHasRemainingHp, Effect.RequiresOpponentActiveEx, is Effect.OwnNamedPokemonDamage, is Effect.OwnTeamRocketPokemonCountDamage, is Effect.OwnAttackNameCountDamage, is Effect.OwnHandPokemonRevealDamage, is Effect.BenchPokemonPresenceDamageBonus, is Effect.BenchTypePresenceDamageBonus, is Effect.AnyOwnBenchedPokemonDamagedBonus, is Effect.AllOwnBenchedPokemonDamagedBonus, is Effect.OwnDamagedBenchedPokemonCountDamage, is Effect.SameTypePokemonInPlayDamageBonus, is Effect.SameHandSizeDamageBonus, is Effect.OpponentExCountDamage, is Effect.OwnNamedBenchDamageCounterDamage, is Effect.OwnBenchTypeDamageCounterDamage, Effect.IgnoreWeakness, is Effect.OwnDamageCounterDamage, is Effect.ConditionalOwnDamageCounterBonus, is Effect.OwnUndamagedDamageBonus, is Effect.OwnHealedThisTurnDamageBonus, is Effect.OwnAnyDamageCounterBonus,
        Effect.SwitchOpponent, Effect.CoinSwitchOpponent, Effect.OptionalSwitchOpponent, Effect.PullOpponentBench, Effect.PullSelectedBenchBeforeDamage, Effect.RecoverSupporterFromDiscard, Effect.RecoverTrainerFromDiscard, is Effect.DiscardTopDeckEnergyDamage, is Effect.DiscardBothDeckTopEnergyDamage, Effect.KnockOutOpponentActiveIfPaidDiscard,
        is Effect.SearchBasicEnergyToHand, is Effect.OwnDiscardBasicEnergyDamageAndShuffle, is Effect.OpponentFieldDamageCounterDamage, is Effect.OwnEnergyTypeCountDamage,
        is Effect.OpponentChoosesHandShuffle, Effect.RequiresUndamagedOpponentActive, Effect.RequiresOpponentBurned, is Effect.RequiresOpponentCondition, Effect.RequiresStadium, is Effect.RequiresOpponentRemainingPrizes, is Effect.RequiresOwnRemainingPrizes, Effect.RequiresEqualHandSize, is Effect.StadiumDamageBonus, Effect.MayAttackFirstTurnIfStarting, Effect.SearchEvolutionForSelfToEvolve, Effect.SearchEvolutionForActiveToEvolve, Effect.SearchEvolutionForEachBenchedPokemon,
        is Effect.BasicPokemonToBench, is Effect.NamedPokemonToBench, is Effect.RecoverNamedPokemonToBench, is Effect.RecoverPokemonTypeToBench, is Effect.RecoverPokemonToHand, Effect.RecoverUpToCoinHeads, is Effect.SearchPokemonTypeAndStadiumToHand, Effect.SearchRotomToBench,
        Effect.RestrictIfSecondPlayerFirstTurn, Effect.PreventBasicNonColorlessDamageNextTurn, Effect.PreventBasicPokemonDamageNextTurn, Effect.PreventEvolutionPokemonDamageNextTurn, Effect.PreventAbilityPokemonDamageNextTurn, Effect.PreventBurnedPokemonDamageNextTurn, is Effect.PreventAttackDamageAtMostNextTurn, is Effect.RequiresOwnBenchCountAtLeast, is Effect.DamageOnlyWithOwnBenchCountAtLeast, is Effect.OwnBenchCountDamage,
        is Effect.OwnDamagedPokemonCountDamage, is Effect.OpponentHandTrainerCountDamage,
        is Effect.StatusCondition, is Effect.StatusConditionOnTails, is Effect.OpponentActiveTypeCondition, is Effect.DiscardDefendingEnergy, Effect.DiscardAllOpponentSpecialEnergy, Effect.DiscardOpponentToolsAndSpecialEnergy, is Effect.BenchDamage, is Effect.AllOpponentBenchDamage, is Effect.OpponentRuleBoxDamage, is Effect.AllOpponentRuleBoxDamage, is Effect.OwnBenchDamage, is Effect.DiscardEnergyThenBenchDamage, is Effect.OptionalShuffleEnergyThenBenchDamage, is Effect.BenchDamageCounters, is Effect.DamageCountersOnDamagedOpponentPokemon, is Effect.MassDamageCounters, Effect.GrassDiscardEnergyTargetCounters, Effect.PlaceDamageCounter, is Effect.MultiTargetDamage, is Effect.OptionalDiscardLightningForParalysis, is Effect.CoinDamageEachOpponentPokemon, Effect.KnockOutBothActive, Effect.KnockOutOpponentActiveIfSpecialCondition, Effect.KnockOutOpponentActiveIfSpecialEnergy, is Effect.KnockOutOpponentPokemonRemainingHpAtMost, Effect.KnockOutOpponentActiveAtExactlySixDamageCounters, Effect.KnockOutOpponentActiveIfBasic, Effect.ProtectAttackerNextTurnIfKnocksOutByDamage, Effect.RecoverSelfFromSpecialConditions, Effect.NoWeaknessNextTurn, Effect.CoinMiss, is Effect.HealEachOwnPokemon, is Effect.HealEachPokemon,
        is Effect.ReduceDamageNextTurn, is Effect.SelfDamage, is Effect.SelfDamageIfAllCoinsTails, Effect.PreventDefendingAttackNextTurn, Effect.DefendingAttackFailsOnTailsNextTurn,
        is Effect.OwnHandCounterDamage, is Effect.OpponentHandCountDamage, is Effect.OpponentHandAtMostDamageBonus, is Effect.OwnHandEmptyDamageBonus, is Effect.OpponentExDamage, is Effect.OpponentActiveHasAbilityDamageBonus, is Effect.OpponentActiveHasToolDamageBonus, is Effect.OwnHasSpecialEnergyDamageBonus, is Effect.OwnHasNamedEnergyDamageBonus, is Effect.OwnBenchHasNamedEnergyDamageBonus, is Effect.OpponentTypeDamage, is Effect.OpponentTypePresentDamageBonus, is Effect.OpponentActiveConditionDamageBonus, is Effect.OpponentEvolutionActiveDamageBonus, is Effect.OpponentStageTwoActiveDamageBonus, is Effect.OwnEnergyCountDamage, is Effect.AttackerConditionDamageBonus, is Effect.AttackerAnyConditionDamageBonus,
        is Effect.OpponentEnergyCountDamage, is Effect.OpponentActiveEnergyCountDamage, is Effect.SameActiveEnergyCountDamageBonus, is Effect.OpponentFieldEnergyCountDamage, is Effect.OpponentEnergyTypeDamage, is Effect.OpponentBasicDiscardEnergyDamage, is Effect.OpponentItemDiscardDamage, is Effect.OpponentHandEnergyCountDamage, is Effect.OpponentActiveRetreatCostDamage, is Effect.OpponentRetreatCostAtLeastDamageBonus, is Effect.OwnEnergyGreaterThanOpponentActiveDamageBonus, is Effect.ReduceDefendingAttackDamageNextTurn, is Effect.NextTurnDefendingDamageBonus,
        is Effect.OpponentConditionDamage, is Effect.OpponentSpecialConditionCountDamage, is Effect.OpponentActiveDamagedDamage, is Effect.OpponentActiveDamageCounterDamage,
        is Effect.OwnDiscardEnergyDamage, is Effect.DiscardEnergyThresholdDamage, is Effect.OwnInPlayEnergyDamage, is Effect.OwnSpecialEnergyDamage,
        is Effect.OwnDiscardAbilityCountBonus, is Effect.OwnDiscardNamedCardDamage, is Effect.OwnDiscardNamedPrefixDamage,
        is Effect.OpponentPrizeTakenDamage, is Effect.OwnPrizeTakenDamage, is Effect.ReplaceDamageIfOpponentActiveDamaged, is Effect.OpponentPrizeTakenBenchDamage, is Effect.OpponentPrizeCountDamage, is Effect.OwnPrizeLeadDamageBonus, is Effect.OpponentNoRetreatDamage,
        is Effect.OpponentResistanceDamage, is Effect.OwnToolAttachedDamage,
        is Effect.OwnKnockoutLastTurnDamage, Effect.DamageEqualToAttackDamageTakenLastTurn, is Effect.MovedFromBenchDamageBonus, is Effect.NextTurnAttackDamageBonus, is Effect.NextTurnAllAttacksDamageBonus,
        is Effect.RequiresExactHandSize,
        is Effect.OwnToolCountDamage, is Effect.OwnDamageCounterPenalty,
        is Effect.AllPokemonToolCountDamage,
        is Effect.BothActiveEnergyCountDamage,
        Effect.MoveOpponentDamageCounters, Effect.MoveOwnBenchDamageCountersToOpponent, Effect.MoveTeamRocketBenchDamageCountersToOpponentActive, is Effect.DiscardOpponentPokemonTools, Effect.MoveEnergyBetweenOpponentPokemon, Effect.MoveEnergyFromOpponentActiveToBench,
        is Effect.SearchPokemonMatchingAttachedBasicEnergy,
        Effect.AttachAnyBasicEnergyFromHand, Effect.AttachOneBasicEnergyFromHand, Effect.AttachBasicEnergyFromHandToSelf, is Effect.AttachUpToTwoBasicEnergyFromHand, is Effect.AttachBasicEnergyFromHandToBenchedThenHealAll, Effect.ShuffleOwnBenchedPokemonIntoDeck, is Effect.ShuffleOpponentBenchedPokemonIntoDeck, is Effect.CoinShuffleOpponentBenchedPokemonIntoDeck, Effect.RequiresAngeliteUsedLastTurn, Effect.KnockOutOpponentPokemonWithExactlySixDamageCounters, Effect.DevolveOpponentActive, Effect.DevolveOpponentPokemonIntoDeck, Effect.DiscardStadium, Effect.ReturnSelfAndAttachmentsToHand, Effect.ShuffleSelfAndAttachmentsIntoDeck, is Effect.RevealOpponentHandBasicToBench, Effect.OptionalReturnSelfAndAttachmentsToHand, is Effect.AttachBasicEnergyFromDeckAny, is Effect.AttachEnergyFromDeckToPokemonType, is Effect.AttachBasicEnergyFromDiscardAny, Effect.AttachLightningEnergyFromDiscardUpToOpponentEnergyCount, is Effect.AttachBasicFightingEnergyFromDiscardToSelf, Effect.AttachBasicFightingEnergyFromDiscardToEachBenchedPokemon, Effect.CoinKnockOutBasic,
        Effect.PreventAttackDamageNextTurnOnHeads, Effect.CopyBenchedNsAttack, Effect.CopyOpponentActiveAttack, Effect.CopyOpponentActiveTeraAttack, Effect.CopyAttackFromOpponentDeckTop, Effect.CopyAttackFromOwnDeckTop, Effect.KnockOutPokemonWithLeastHpRemainingExceptAttacker, Effect.TakePrizeCard, Effect.DiscardOpponentDeckPerCoinHead, is Effect.DiscardOpponentDeckForFixedCoinHeads, is Effect.OptionalReturnWaterEnergyDamageBonus, is Effect.OptionalStadiumDiscardDamageBonus, is Effect.OptionalRevealPrizeDamageBonus, is Effect.OptionalSelfDamageBonus,
        is Effect.MoveEnergyToBench, is Effect.MoveEnergyToOneBench, Effect.MoveAllEnergyToBench, Effect.ReturnAttachedEnergyToHand, is Effect.ReturnOpponentActiveEnergyToHand, is Effect.DiscardRandomOpponentHand, is Effect.OpponentChoosesHandDiscard, is Effect.SalazzleHandDiscard, is Effect.ShuffleRandomOpponentHand, Effect.ShuffleRandomOpponentHandPerCoinHead, is Effect.DiscardOpponentDeck, is Effect.DiscardOpponentDeckBonusIfAncientSupporter, is Effect.ReorderOpponentDeckTop, Effect.DiscardOwnDeckTop, is Effect.DiscardOwnDeckTopCards, Effect.OptionalDiscardOwnDeckTop, is Effect.LookTopChooseAndDiscardRest, is Effect.LookTopPokemonToBench, Effect.RevealOpponentHand, Effect.RevealAndChooseOpponentHandDiscard, Effect.RevealAndChooseOpponentHandBottomDeck, is Effect.DelayedDefendingDamageCounters -> true
        is Effect.DiscardBenchEnergyDamage -> true
        is Effect.Heal -> true
        is Effect.Unsupported -> false
    } }
}



