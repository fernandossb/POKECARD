package com.tcgtrainer.engine

import kotlinx.serialization.Serializable

@Serializable enum class EnergyType { FIGHTING, FIRE, WATER, LIGHTNING, PSYCHIC, GRASS, DARKNESS, METAL, COLORLESS, DRAGON }
@Serializable enum class Category { POKEMON, ENERGY, TRAINER }
@Serializable enum class Stage { BASIC, STAGE_ONE, STAGE_TWO }
@Serializable enum class Phase { CHOOSE_STARTER, SELECT_ACTIVE, SELECT_BENCH, MULLIGAN_BONUS, BONUS_BENCH, MAIN, RESOLVE_CHOICE, SELECT_PRIZES, PROMOTE, GAME_OVER }
@Serializable enum class Mode { MATCH, TRAINING }
@Serializable sealed interface Effect {
    @Serializable data class Damage(val amount: Int) : Effect
    @Serializable data class Draw(val count: Int) : Effect
    @Serializable data class EachPlayerDraw(val count: Int) : Effect
    @Serializable data class ShuffleHandDraw(val count: Int) : Effect
    @Serializable data class DrawToHand(val size: Int) : Effect
    @Serializable data class DiscardHandDraw(val count: Int) : Effect
    @Serializable data class DiscardHandEnergyDamageBonus(val max: Int, val perEnergy: Int) : Effect
    @Serializable data class DiscardOwnEnergyDamageBonus(val max: Int, val perEnergy: Int, val type: EnergyType? = null, val basicOnly: Boolean = false, val attackerOnly: Boolean = false) : Effect
    @Serializable data class Heal(val amount: Int) : Effect
    @Serializable data class Unsupported(val text: String) : Effect
    @Serializable data class AccelerateEnergy(val max: Int, val type: EnergyType) : Effect
    @Serializable data class RestrictNextTurn(val attackId: String? = null) : Effect
    @Serializable data class RestrictNamedAttackNextTurn(val attackName: String) : Effect
    @Serializable data object CannotRepeatAttackUntilLeavingActive : Effect
    @Serializable data object LockOpponentItemsNextTurn : Effect
    @Serializable data object LockOpponentEvolutionNextTurn : Effect
    @Serializable data class ReduceEvolutionDamageNextTurn(val amount: Int) : Effect
    @Serializable data class Recoil(val damage: Int) : Effect
    @Serializable data class RetaliateDamageCountersNextTurn(val counters: Int) : Effect
    @Serializable data class RequiresBenched(val definitionId: String) : Effect
    @Serializable data object IgnoreWeaknessResistance : Effect
    @Serializable data object CopyBenchedNsAttack : Effect
    @Serializable data object CopyOpponentActiveAttack : Effect
    @Serializable data object IgnoreResistance : Effect
    @Serializable data object IgnoreOpponentActiveEffects : Effect
    @Serializable data class TargetedDamage(val damage: Int, val benchedOnly: Boolean = false) : Effect
    @Serializable data class CoinDamage(val coins: Int, val perHeads: Int, val untilTails: Boolean = false,
        val perEnergy: Boolean = false, val bonusOnly: Boolean = false, val perEnergyBothActive: Boolean = false,
        val minimumHeads: Int = 0) : Effect
    @Serializable data class DiscardAttackEnergy(val count: Int, val type: EnergyType? = null, val all: Boolean = false) : Effect
    @Serializable data class EnergyFromDeck(val count: Int, val type: EnergyType?, val poisonSelfIfAttached: Boolean = false) : Effect
    @Serializable data class AttackerConditionDamageBonus(val condition: SpecialCondition, val bonus: Int) : Effect
    @Serializable data class SupporterBonus(val damage: Int) : Effect
    @Serializable data object PreventRetreatNextTurn : Effect
    @Serializable data object DiscardDefendingTools : Effect
    @Serializable data class ConditionalEnergyDamage(val type: EnergyType, val minimum: Int, val bonus: Int) : Effect
    @Serializable data class ConditionalAttachedEnergyDamage(val minimum: Int, val bonus: Int) : Effect
    @Serializable data class ConditionalInPlayEnergyDamage(val type: EnergyType, val minimum: Int, val bonus: Int) : Effect
    @Serializable data class SwitchOwn(val optional: Boolean = false) : Effect
    @Serializable data class BenchedCountDamage(val perPokemon: Int) : Effect
    @Serializable data class OpponentBenchCountDamage(val perPokemon: Int) : Effect
    @Serializable data class OwnInPlayCountDamage(val perPokemon: Int) : Effect
    @Serializable data class OwnSpeciesCountDamage(val speciesName: String, val perPokemon: Int) : Effect
    @Serializable data class OwnAttackNameCountDamage(val attackName: String, val perPokemon: Int) : Effect
    @Serializable data class OwnHandPokemonRevealDamage(val perCard: Int, val pokemonNames: List<String>) : Effect
    @Serializable data class BenchPokemonPresenceDamageBonus(val speciesName: String, val bonus: Int) : Effect
    @Serializable data class BenchTypePresenceDamageBonus(val type: EnergyType, val bonus: Int) : Effect
    @Serializable data class AllOwnBenchedPokemonDamagedBonus(val bonus: Int) : Effect
    @Serializable data class AnyOwnBenchedPokemonDamagedBonus(val bonus: Int) : Effect
    @Serializable data class SameTypePokemonInPlayDamageBonus(val bonus: Int) : Effect
    @Serializable data class SameHandSizeDamageBonus(val bonus: Int) : Effect
    @Serializable data class OpponentExCountDamage(val perPokemon: Int) : Effect
    @Serializable data class OwnNamedBenchDamageCounterDamage(val namePrefix: String, val perCounter: Int) : Effect
    @Serializable data class OwnBenchTypeDamageCounterDamage(val type: EnergyType, val perCounter: Int) : Effect
    @Serializable data class OpponentHandCountDamage(val perCard: Int) : Effect
    @Serializable data class OpponentHandAtMostDamageBonus(val maxCards: Int, val bonus: Int) : Effect
    @Serializable data object IgnoreWeakness : Effect
    @Serializable data class OwnDamageCounterDamage(val perCounter: Int) : Effect
    @Serializable data class ConditionalOwnDamageCounterBonus(val minimumCounters: Int, val bonus: Int) : Effect
    @Serializable data class OwnDamageCounterPenalty(val perCounter: Int) : Effect
    @Serializable data class OwnUndamagedDamageBonus(val bonus: Int) : Effect
    @Serializable data class OwnAnyDamageCounterBonus(val bonus: Int) : Effect
    @Serializable data object SwitchOpponent : Effect
    @Serializable data object PullOpponentBench : Effect
    @Serializable data object PullSelectedBenchBeforeDamage : Effect
    @Serializable data object RecoverSupporterFromDiscard : Effect
    @Serializable data class DiscardTopDeckEnergyDamage(val count: Int, val type: EnergyType, val damagePerEnergy: Int) : Effect
    @Serializable data class SearchBasicEnergyToHand(val max: Int) : Effect
    @Serializable data class OpponentFieldDamageCounterDamage(val damagePerCounter: Int) : Effect
    @Serializable data class OwnEnergyTypeCountDamage(val type: EnergyType, val damagePerEnergy: Int, val allPokemon: Boolean = false) : Effect
    @Serializable data class OpponentChoosesHandShuffle(val count: Int) : Effect
    @Serializable data class OpponentChoosesHandDiscard(val count: Int) : Effect
    @Serializable data object RequiresUndamagedOpponentActive : Effect
    @Serializable data object RequiresOpponentBurned : Effect
    @Serializable data object RequiresStadium : Effect
    @Serializable data class StadiumDamageBonus(val bonus: Int) : Effect
    @Serializable data class RequiresOwnBenchCountAtLeast(val count: Int) : Effect
    @Serializable data class DamageOnlyWithOwnBenchCountAtLeast(val count: Int) : Effect
    @Serializable data object MayAttackFirstTurnIfStarting : Effect
    @Serializable data class NextTurnAttackDamageBonus(val attackName: String, val bonus: Int) : Effect
    @Serializable data object SearchEvolutionForSelfToEvolve : Effect
    @Serializable data object SearchEvolutionForEachBenchedPokemon : Effect
    @Serializable data class BasicPokemonToBench(val count: Int) : Effect
    @Serializable data class NamedPokemonToBench(val name: String, val count: Int) : Effect
    @Serializable data class RecoverNamedPokemonToBench(val name: String, val count: Int) : Effect
    @Serializable data object SearchRotomToBench : Effect
    @Serializable data object RestrictIfSecondPlayerFirstTurn : Effect
    @Serializable data object PreventBasicNonColorlessDamageNextTurn : Effect
    @Serializable data object PreventBasicPokemonDamageNextTurn : Effect
    @Serializable data class OwnBenchCountDamage(val perPokemon: Int) : Effect
    @Serializable data class OwnBenchWithEnergyDamage(val perPokemon: Int, val type: EnergyType) : Effect
    @Serializable data class OwnDamagedPokemonCountDamage(val perPokemon: Int) : Effect
    @Serializable data class OwnDamagedBenchedPokemonCountDamage(val perPokemon: Int) : Effect
    @Serializable data object MoveOpponentDamageCounters : Effect
    @Serializable data object MoveOwnBenchDamageCountersToOpponent : Effect
    @Serializable data object MoveEnergyBetweenOpponentPokemon : Effect
    @Serializable data object MoveEnergyFromOpponentActiveToBench : Effect
    @Serializable data class SearchPokemonMatchingAttachedBasicEnergy(val max: Int) : Effect
    @Serializable data object AttachAnyBasicEnergyFromHand : Effect
    @Serializable data object AttachOneBasicEnergyFromHand : Effect
    @Serializable data class AttachBasicEnergyFromHandToBenchedThenHealAll(val type: EnergyType) : Effect
    @Serializable data object ShuffleOwnBenchedPokemonIntoDeck : Effect
    @Serializable data object DevolveOpponentActive : Effect
    @Serializable data object DevolveOpponentPokemonIntoDeck : Effect
    @Serializable data object DiscardStadium : Effect
    @Serializable data object ReturnSelfAndAttachmentsToHand : Effect
    @Serializable data object OptionalReturnSelfAndAttachmentsToHand : Effect
    @Serializable data class AttachBasicEnergyFromDeckAny(val max: Int, val type: EnergyType? = null, val benchedOnly: Boolean = false) : Effect
    @Serializable data class AttachEnergyFromTopDeck(val count: Int) : Effect
    @Serializable data class AttachBasicEnergyFromDiscardAny(val max: Int, val type: EnergyType? = null, val benchedOnly: Boolean = false) : Effect
    @Serializable data class AttachBasicFightingEnergyFromDiscardToSelf(val max: Int) : Effect
    @Serializable data class AttachBasicEnergyToEachBenchedPokemon(val type: EnergyType) : Effect
    @Serializable data class SearchPokemonToHand(val max: Int = 1) : Effect
    @Serializable data class SearchPokemonTypeAndStadiumToHand(val max: Int, val type: EnergyType) : Effect
    @Serializable data object SearchAnyCardToHand : Effect
    @Serializable data object SearchSupporterToHand : Effect
    @Serializable data object SearchItemToHand : Effect
    @Serializable data object ChooseOpponentAttackToRestrict : Effect
    @Serializable data object HealSelfByDamageDealt : Effect
    @Serializable data class OpponentTypeDamage(val type: EnergyType, val bonus: Int) : Effect
    @Serializable data class OpponentEvolutionActiveDamageBonus(val bonus: Int) : Effect
    @Serializable data class OpponentStageTwoActiveDamageBonus(val bonus: Int) : Effect
    @Serializable data class HealOwnPokemon(val amount: Int) : Effect
    @Serializable data class HealEachOwnPokemon(val amount: Int) : Effect
    @Serializable data class HealEachPokemon(val amount: Int) : Effect
    @Serializable data object CoinKnockOutBasic : Effect
    @Serializable data class StatusCondition(val condition: SpecialCondition, val onHeads: Boolean = false, val minimumHeads: Int = 1, val onSelf: Boolean = false, val poisonDamageCounters: Int = 1) : Effect
    @Serializable data class OpponentActiveTypeCondition(val type: EnergyType) : Effect
    @Serializable data class DiscardDefendingEnergy(val count: Int, val onHeads: Boolean = false, val type: EnergyType? = null, val specialOnly: Boolean = false, val perHeads: Boolean = false) : Effect
    @Serializable data object DiscardAllOpponentSpecialEnergy : Effect
    @Serializable data object DiscardOpponentToolsAndSpecialEnergy : Effect
    @Serializable data class BenchDamage(val amount: Int, val requiresDamageCounters: Boolean = false) : Effect
    @Serializable data class OwnBenchDamage(val amount: Int) : Effect
    @Serializable data class DiscardEnergyThenBenchDamage(val count: Int, val amount: Int) : Effect
    @Serializable data class OptionalShuffleEnergyThenBenchDamage(val count: Int, val amount: Int) : Effect
    @Serializable data class BenchDamageCounters(val count: Int, val distribute: Boolean = false) : Effect
    @Serializable data object PlaceDamageCounter : Effect
    @Serializable data class MultiTargetDamage(val amount: Int, val targets: Int, val allowRepeat: Boolean = false, val ignoreWeaknessResistance: Boolean = false, val discardBeforeDamage: Boolean = false, val benchedOnly: Boolean = false) : Effect
    @Serializable data class CoinDamageEachOpponentPokemon(val amount: Int) : Effect
    @Serializable data object KnockOutBothActive : Effect
    @Serializable data object KnockOutOpponentActiveIfSpecialCondition : Effect
    @Serializable data object KnockOutOpponentActiveAtExactlySixDamageCounters : Effect
    @Serializable data object KnockOutPokemonWithLeastHpRemainingExceptAttacker : Effect
    @Serializable data object ProtectAttackerNextTurnIfKnocksOutByDamage : Effect
    @Serializable data object RecoverSelfFromSpecialConditions : Effect
    @Serializable data object NoWeaknessNextTurn : Effect
    @Serializable data class OpponentActiveRetreatCostDamage(val perCost: Int) : Effect
    @Serializable data object CoinMiss : Effect
    @Serializable data class MoveEnergyToBench(val count: Int = 1) : Effect
    @Serializable data object MoveAllEnergyToBench : Effect
    @Serializable data object ReturnAttachedEnergyToHand : Effect
    @Serializable data class OptionalReturnWaterEnergyDamageBonus(val bonus: Int) : Effect
    @Serializable data class OptionalDiscardLightningForParalysis(val count: Int) : Effect
    @Serializable data class DiscardRandomOpponentHand(val count: Int) : Effect
    @Serializable data class ShuffleRandomOpponentHand(val count: Int) : Effect
    @Serializable data class DiscardOpponentDeck(val count: Int) : Effect
    @Serializable data class ReorderOpponentDeckTop(val count: Int) : Effect
    @Serializable data object DiscardOwnDeckTop : Effect
    @Serializable data object OptionalDiscardOwnDeckTop : Effect
    @Serializable data object RevealOpponentHand : Effect
    @Serializable data object RevealAndChooseOpponentHandDiscard : Effect
    @Serializable data class DiscardBenchEnergyDamage(val max: Int, val perEnergyDamage: Int) : Effect
    @Serializable data class ReduceDamageNextTurn(val amount: Int) : Effect
    @Serializable data class SelfDamage(val amount: Int) : Effect
    @Serializable data object PreventDefendingAttackNextTurn : Effect
    @Serializable data object DefendingAttackFailsOnTailsNextTurn : Effect
    @Serializable data class OwnHandCounterDamage(val countersPerCard: Int) : Effect
    @Serializable data class OpponentExDamage(val bonus: Int) : Effect
    @Serializable data class OwnEnergyCountDamage(val perEnergy: Int) : Effect
    @Serializable data class OpponentEnergyCountDamage(val perEnergy: Int) : Effect
    @Serializable data class OpponentActiveEnergyCountDamage(val perEnergy: Int) : Effect
    @Serializable data class OpponentBasicDiscardEnergyDamage(val perEnergy: Int) : Effect
    @Serializable data class OpponentHandEnergyCountDamage(val perEnergy: Int) : Effect
    @Serializable data class ReduceDefendingAttackDamageNextTurn(val amount: Int) : Effect
    @Serializable data class OpponentConditionDamage(val bonus: Int) : Effect
    @Serializable data class OpponentActiveDamagedDamage(val bonus: Int) : Effect
    @Serializable data class OpponentActiveDamageCounterDamage(val perCounter: Int) : Effect
    @Serializable data class OwnDiscardEnergyDamage(val perEnergy: Int) : Effect
    @Serializable data class DiscardEnergyThresholdDamage(val type: EnergyType, val minimum: Int, val bonus: Int) : Effect
    @Serializable data class DelayedDefendingDamageCounters(val counters: Int) : Effect
    @Serializable data class OwnDiscardAbilityCountBonus(val abilityName: String, val minimum: Int, val bonus: Int) : Effect
    @Serializable data class OwnInPlayEnergyDamage(val perEnergy: Int) : Effect
    @Serializable data class OwnSpecialEnergyDamage(val perEnergy: Int) : Effect
    @Serializable data class OpponentPrizeTakenDamage(val perPrize: Int) : Effect
    @Serializable data class OpponentPrizeTakenBenchDamage(val perPrize: Int) : Effect
    @Serializable data class OpponentPrizeCountDamage(val maxRemaining: Int, val bonus: Int) : Effect
    @Serializable data class OpponentNoRetreatDamage(val bonus: Int) : Effect
    @Serializable data class OpponentResistanceDamage(val type: EnergyType, val bonus: Int) : Effect
    @Serializable data class OwnToolAttachedDamage(val bonus: Int) : Effect
    @Serializable data class OwnKnockoutLastTurnDamage(val bonus: Int) : Effect
    @Serializable data class MovedFromBenchDamageBonus(val bonus: Int) : Effect
    @Serializable data class RequiresExactHandSize(val size: Int) : Effect
    @Serializable data class OwnToolCountDamage(val perTool: Int) : Effect
    @Serializable data class AllPokemonToolCountDamage(val perTool: Int) : Effect
    @Serializable data class BothActiveEnergyCountDamage(val perEnergy: Int) : Effect
    @Serializable data object PreventAttackDamageNextTurnOnHeads : Effect
}
@Serializable enum class SpecialCondition { POISONED, BURNED, ASLEEP, PARALYZED, CONFUSED }
@Serializable data class AttackDefinition(val id: String, val name: String, val cost: List<EnergyType>, val effects: List<Effect>, val text: String = "")
@Serializable enum class TrainerType { ITEM, SUPPORTER, STADIUM, TOOL }
@Serializable enum class CardFilter { POKEMON, BASIC_FIGHTING_OR_ENERGY, POKEMON_OR_ENERGY, ITEM, TOOL, SUPPORTER, STADIUM, BASIC_POKEMON, BASIC_ENERGY, ENERGY, DARKNESS_POKEMON, METAL_POKEMON }
@Serializable sealed interface TrainerEffect {
    @Serializable data class Search(val filters: List<CardFilter>, val discardCost: Int = 0) : TrainerEffect
    @Serializable data class DrawTo(val size: Int, val discardCost: Int = 0) : TrainerEffect
    @Serializable data class ShuffleHandDraw(val count: Int, val sixPrizeCount: Int) : TrainerEffect
    @Serializable data class Switch(val opponent: Boolean, val drawTo: Int = 0) : TrainerEffect
    @Serializable data object Recover : TrainerEffect
    @Serializable data class DamageBoost(val type: EnergyType, val amount: Int) : TrainerEffect
    @Serializable data class RetreatReduction(val amount: Int) : TrainerEffect
    @Serializable data class HpModifier(val stage: Stage, val amount: Int) : TrainerEffect
    @Serializable data class DrawCards(val count: Int, val coinBonus: Int = 0) : TrainerEffect
    @Serializable data class HealPokemon(val amount: Int, val activeOnly: Boolean = false) : TrainerEffect
    @Serializable data object GreatBall : TrainerEffect
    @Serializable data object Sonia : TrainerEffect
    @Serializable data object CoinSwitch : TrainerEffect
    @Serializable data class RecoverEnergy(val max: Int, val toDeck: Boolean = false) : TrainerEffect
}
@Serializable data class AbilityDefinition(
    val id: String, val name: String, val text: String, val draw: Int = 0,
    val discardEnergyType: EnergyType? = null, val discardAttachedBasicEnergyType: EnergyType? = null, val requiresInPlay: String? = null,
    val requiresPreviousKnockout: Boolean = false, val onEvolveSwitch: Boolean = false,
    val onEvolveSwitchOpponentBenchMaxRemainingHp: Int = 0,
    val onEvolveOpponentHandBasicToBench: Boolean = false,
    val attackDiscountPerOpponentPrize: Boolean = false, val usageKey: String = id,
    val requiresActive: Boolean = false, val onEvolveDraw: Int = 0, val onEvolveDamageCounters: Boolean = false,
    val discardHandCount: Int = 0, val drawTo: Int = 0, val damageReduction: Int = 0,
    val retaliateDamageCounters: Int = 0, val requiresTeamRocketPokemonCount: Int = 0,
    val ignoreOpponentActiveEffects: Boolean = false, val damageCounters: Int = 0,
    val damageTargets: Int = 0, val selfKnockout: Boolean = false,
    val suppressSelfKnockoutAbilities: Boolean = false,
    val attachHandEnergyType: EnergyType? = null, val drawIfAttached: Int = 0,
    val attachHandEnergyTypes: List<EnergyType> = emptyList(), val attachHandEnergyTypesMax: Int = 0,
    val healIfAttached: Int = 0,
    val attachHandEnergyMax: Int = 1, val attachHandEnergyBenchOnly: Boolean = false,
    val usagePerPokemon: Boolean = false, val surviveAttackKnockoutAtHp: Int = 0,
    val shuffleSelfIntoDeck: Boolean = false, val healDamage: Int = 0,
    val recoverBasicPokemonToBenchMaxHp: Int = 0, val damageBonusIfHandsEqual: Int = 0,
    val surviveAttackKnockoutOnHeadsAtHp: Int = 0,
    val onEvolveShuffleRandomOpponentHandPerHeads: Int = 0,
    val damageBonusAgainstActiveWhenDamageAtLeast: Int = 0, val damageBonusAmount: Int = 0,
    val freeRetreatWithAttachedEnergyType: EnergyType? = null,
    val basicPokemonHaveFreeRetreat: Boolean = false,
    val onEvolveAttachMetalFromDiscardMax: Int = 0,
    val devolveOpponentPokemonMax: Int = 0,
    val opponentActiveEvolutionRetreatCostBonus: Int = 0,
    val opponentActiveRetreatCostBonus: Int = 0,
    val searchBasicEnergyTypeToHand: EnergyType? = null, val searchBasicEnergyMaxToHand: Int = 0,
    val onEvolveRecoverNamedTrainerFromDiscard: String? = null, val onEvolveRecoverNamedTrainerMax: Int = 0,
    val tundraWallWaterDamageReduction: Int = 0,
    val moveEnergyFromOtherPokemonToSelf: Boolean = false,
    val unlimitedUse: Boolean = false,
    val alternativeAttackName: String? = null,
    val alternativeAttackCost: List<EnergyType> = emptyList(),
    val requiresTeraInPlay: Boolean = false,
    val attachHandEnergyToPokemonType: EnergyType? = null,
    val benchDamageImmunityForAttackerMaxEnergy: Int = 0,
    val attachBasicEnergyFromDiscard: Boolean = false,
    val preventOpponentEffects: Boolean = false,
    val preventOpponentAttackDamageEffectsWhileOnBench: Boolean = false,
    val moveBasicEnergyBetweenOwnPokemon: Boolean = false, val moveBasicEnergyType: EnergyType? = null,
    val onBenchAttachBasicEnergyType: EnergyType? = null, val onBenchAttachBasicEnergyMax: Int = 0,
    val preventDamageFromPokemonWithAbilities: Boolean = false,
    val lookAtTopCardsChooseOne: Int = 0,
    val searchAnyCardToTop: Boolean = false,
    val preventDamageFromSpecialEnergyAttacker: Boolean = false,
    val preventDamageFromOpponentEx: Boolean = false,
    val damageReductionTypes: List<EnergyType> = emptyList(),
    val protectBasicColorlessWithSecondBouffalant: Int = 0,
    val allowAnyEeveeExEvolution: Boolean = false,
    val mayEvolveFirstTurnIfActive: Boolean = false,
    val attachBasicEnergyFromDiscardType: EnergyType? = null,
    val attachBasicEnergyFromDiscardBenchOnly: Boolean = false,
    val attachBasicEnergyFromDiscardMax: Int = 0,
    val attachBasicEnergyFromDiscardToPokemonType: EnergyType? = null,
    val selfKnockoutAfterUse: Boolean = false,
    val onBenchDiscardStadium: Boolean = false,
    val onBenchDiscardOpponentDeckTop: Boolean = false,
    val suppressRuleBoxAbilitiesExceptFuture: Boolean = false,
    val onBenchSwitchAndMoveEnergy: Boolean = false,
    val changeOpponentNsPokemonWeaknessToPsychic: Boolean = false,
    val reducePrizeForDarknessKnockoutByEx: Boolean = false,
    val extraPrizeForOpponentBasicKnockoutByAttack: Int = 0,
    val teamDamageReduction: Int = 0,
    val onEvolveConfuseOpponentActive: Boolean = false,
    val hpBonusPerOpponentPrizeTaken: Int = 0,
    val additionalPokemonTypes: List<EnergyType> = emptyList(),
    val attackDamageBonusAgainstEvolutionActive: Int = 0,
    val attachHandEnergyToPokemonNamePrefix: String? = null,
    val futurePokemonDamageBonus: Int = 0, val retaliateCondition: SpecialCondition? = null,
    val freeRetreatWithNoEnergy: Boolean = false, val switchOwnActive: Boolean = false,
    val attackDiscountPerKofuDiscard: Boolean = false,
    val searchColorlessPokemonMaxHpToHand: Int = 0, val requiresFirstTurn: Boolean = false,
    val coinFlipPreventAttackDamage: Boolean = false, val coinFlipPreventAttackDamageEnergyType: EnergyType? = null,
    val checkupDamageCountersPerPokemonWithAbilityExceptFroslass: Int = 0,
    val preventDamageFromBasicPokemonEx: Boolean = false,
    val preventDamageAndEffectsFromTeraAttacks: Boolean = false,
    val switchOwnDarknessExceptPecharunt: Boolean = false, val poisonNewActiveOnSwitch: Boolean = false,
    val requirePecharuntExForPrizeReduction: Boolean = false,
    val teamAttackDamageBonusType: EnergyType? = null, val teamAttackDamageBonus: Int = 0,
    val hpBonusPerAttachedEnergyType: EnergyType? = null, val hpBonusPerAttachedEnergy: Int = 0,
    val hpAndAttackBonusWithAttachedEnergyType: EnergyType? = null,
    val hpAndAttackBonusWithAttachedEnergy: Int = 0,
    val searchPokemonNamePrefixToHand: String? = null, val searchPokemonEvolutionTypeToHand: EnergyType? = null,
    val searchPokemonMaxToHand: Int = 0, val festivalGroundsMayAttackAgain: Boolean = false,
    val preventOpponentItemSupporterEffects: Boolean = false,
    val preventOpponentItemAndToolPlayFromHand: Boolean = false,
    val opponentShuffleHandAndDraw: Int = 0,
    val eachPlayerDraw: Int = 0,
    val onEvolveHealAllEvolutionAndDiscardEnergy: Boolean = false,
    val onEvolveSearchTrainerIfTeraMax: Int = 0,
    val mayAttackFirstTurnIfStarting: Boolean = false,
    val onEvolveDiscardOpponentDeckCount: Int = 0,
    val opponentActiveAbilityException: String? = null,
    val requiresMegaEvolutionFireExInPlay: Boolean = false
)
@Serializable data class CardDefinition(
    val id: String, val name: String, val set: String, val number: String, val variant: String = "normal",
    val category: Category, val stage: Stage = Stage.BASIC, val evolvesFrom: String? = null,
    val hp: Int = 0, val type: EnergyType = EnergyType.FIGHTING, val weakness: EnergyType? = null,
    val resistance: EnergyType? = null, val resistanceAmount: Int = 30, val retreatCost: Int = 0,
    val prizes: Int = 1, val attacks: List<AttackDefinition> = emptyList(), val verified: Boolean = false,
    val source: String = "FICTÍCIA — somente teste do motor",
    val artAsset: String? = null, val text: String = "", val trainerType: TrainerType? = null,
    val trainerEffect: TrainerEffect? = null, val abilities: List<AbilityDefinition> = emptyList(),
    val aceSpec: Boolean = false, val ruleBox: String = "", val implemented: Boolean = true,
    val canonicalName: String = name, val evolvesFromName: String? = null,
    val basicEnergy: Boolean = false, val isTera: Boolean = false
){
    val hasRuleBox: Boolean get() = ruleBox.isNotBlank() || prizes > 1 || canonicalName.startsWith("Radiant ")
    val isFuturePokemon: Boolean get() = canonicalName.startsWith("Iron ") || canonicalName.startsWith("Miraidon")
}
fun CardDefinition.canEvolveFrom(base: CardDefinition): Boolean = category == Category.POKEMON &&
    base.category == Category.POKEMON && stage != Stage.BASIC &&
    ((stage.ordinal == base.stage.ordinal + 1 && (if (evolvesFromName != null) evolvesFromName == base.canonicalName else evolvesFrom == base.id)) ||
        (base.abilities.any { it.allowAnyEeveeExEvolution } && stage != Stage.BASIC && prizes == 2 && evolvesFromName == "Eevee"))
@Serializable data class CardInstance(val id: String, val definitionId: String)
@Serializable data class PokemonInstance(
    val cards: List<CardInstance>, val damage: Int = 0, val energies: List<CardInstance> = emptyList(),
    val enteredPlayTurn: Int, val lastEvolutionTurn: Int = -1,
    val hpModifier: Int = 0, val damageModifier: Int = 0,
    val tool: CardInstance? = null, val restrictions: List<AttackRestriction> = emptyList(), val retreatBlockedTurn: Int = -1,
    val defendingAttackFailsOnTailsTurn: Int = -1,
    val conditions: Set<SpecialCondition> = emptySet(), val poisonDamageCounters: Int = 1, val incomingDamageReduction: Int = 0, val damageReductionTurn: Int = -1,
    val attackImmunityTurn: Int = -1, val outgoingDamageReduction: Int = 0, val outgoingDamageReductionTurn: Int = -1,
    val preventBasicNonColorlessDamageTurn: Int = -1, val preventBasicPokemonDamageTurn: Int = -1,
    val weaknessSuppressedTurn: Int = -1,
    val retaliateDamageCounters: Int = 0, val retaliateDamageCountersTurn: Int = -1,
    val incomingDamageReductionEvolutionOnly: Boolean = false,
    val attackIdsUsedWhileActive: List<String> = emptyList(),
    val delayedDamageCounters: Int = 0, val delayedDamageTurn: Int = -1,
    val weaknessOverride: EnergyType? = null, val enteredActiveTurn: Int = -1,
    val nextTurnAttackDamageBonusName: String? = null, val nextTurnAttackDamageBonus: Int = 0,
    val nextTurnAttackDamageBonusTurn: Int = -1
) { val id: String get() = cards.first().id; val top: CardInstance get() = cards.last() }
@Serializable data class AttackRestriction(val attackId: String?, val blockedTurn: Int)
@Serializable data class TurnFlags(val manualEnergy: Boolean = false, val supporter: Boolean = false, val retreat: Boolean = false,
    val stadium: Boolean = false, val abilitiesUsed: List<String> = emptyList(), val damageBoosts: Map<EnergyType, Int> = emptyMap())
@Serializable data class PlayerState(
    val deck: List<CardInstance>, val hand: List<CardInstance> = emptyList(),
    val discard: List<CardInstance> = emptyList(), val prizes: List<CardInstance> = emptyList(),
    val active: PokemonInstance? = null, val bench: List<PokemonInstance> = emptyList(),
    val flags: TurnFlags = TurnFlags(), val mulligans: Int = 0, val bonusDraws: Int = 0,
    val lastKnockedOutTurn: Int = -1, val prizePositions: List<String> = emptyList(),
    val lastKnockedOutByAttackDamageTurn: Int = -1, val itemPlayLockedUntilTurn: Int = -1,
    val evolutionLockedUntilTurn: Int = -1
) { fun pokemon(): List<PokemonInstance> = listOfNotNull(active) + bench }
@Serializable data class RngState(val value: Long, val position: Long = 0)
@Serializable data class GameEvent(val type: String, val message: String, val amount: Int? = null)
@Serializable data class ActionRecord(val player: Int, val turn: Int, val action: GameAction, val events: List<GameEvent>)
@Serializable data class GameState(
    val gameId: String, val seed: Long, val rng: RngState, val players: List<PlayerState>,
    val startingPlayer: Int, val currentPlayer: Int, val turn: Int = 0,
    val phase: Phase = Phase.SELECT_ACTIVE, val setupPlayer: Int = 0, val setupFinished: List<Int> = emptyList(),
    val pendingPrizes: Int = 0, val resumePlayer: Int? = null,
    val bonusCardIds: List<String> = emptyList(),
    val winner: Int? = null, val winReason: String? = null, val mode: Mode = Mode.TRAINING,
    val history: List<ActionRecord> = emptyList(), val schemaVersion: Int = 1,
    val catalogVersion: String = "test-v1",
    val stadium: CardInstance? = null, val stadiumOwner: Int? = null,
    val resolution: Resolution? = null, val prizesOwed: List<Int> = listOf(0, 0),
    val promotionQueue: List<Int> = emptyList(), val afterResolution: ResolutionEnd = ResolutionEnd.MAIN,
    val humanDeckId: String = "lucario", val cpuDeckId: String = "lucario",
    val deckLists: List<List<String>> = emptyList(), val deckNames: List<String> = emptyList(),
    val deckFormats: List<DeckFormat> = emptyList(),
    val pendingAttackDamageKnockoutIds: Set<String> = emptySet(),
    val pendingAttackDamageSourcePokemonId: String? = null,
    val pendingAttackEffectKnockoutIds: Set<String> = emptySet(),
    val extraAttackAvailableFor: Int? = null, val extraAttackUsedThisTurn: Boolean = false,
    val pendingCopiedAttackId: String? = null, val pendingCopiedAttack: AttackDefinition? = null,
    val pendingOptionalEnergyAttackId: String? = null, val pendingOptionalEnergyBonus: Int = 0,
    val pendingHandRevealAttackId: String? = null, val pendingHandRevealCount: Int = 0
)
@Serializable enum class ResolutionEnd { MAIN, ATTACK }
@Serializable enum class ChoiceKind {DISCARD, SEARCH, RECOVER, SWITCH_OWN, SWITCH_OPPONENT, ENERGY_FROM_DISCARD, ENERGY_TARGET, TOOL_TARGET, EVOLUTION_SWITCH, EVOLUTION_CONFUSE_ACTIVE, EVOLUTION_METAL_ENERGY, EVOLUTION_METAL_ENERGY_TARGET, EVOLUTION_RECOVER_NAMED_TRAINER, EVOLUTION_SEARCH_TRAINER, EVOLUTION_DISCARD_OPPONENT_DECK, KNOCKOUT_DISCARD_OPPONENT_DECK, ABILITY_ENERGY, ABILITY_DAMAGE_TARGET, ABILITY_ATTACH_ENERGY, ABILITY_ATTACH_ENERGY_TARGET, ABILITY_SELECT_ENERGY_TARGET, ABILITY_BASIC_POKEMON_TO_BENCH, ABILITY_DEVOLVE_TARGET, ABILITY_SEARCH_BASIC_ENERGY_TO_HAND, ABILITY_SEARCH_COLORLESS_POKEMON_TO_HAND, ABILITY_SEARCH_POKEMON_TO_HAND, ABILITY_MOVE_ENERGY_TO_SELF, DISCARD_ATTACK_ENERGY, ATTACH_DECK_ENERGY, HEAL_TARGET, RECYCLE_ENERGY, SONIA, ATTACK_BENCH_POKEMON, ATTACK_RECOVER_POKEMON, ATTACK_ROTOM_SEARCH, ATTACK_DAMAGE_TARGET, ATTACK_ENERGY, ATTACK_ENERGY_TARGET, ATTACK_ENERGY_TO_HAND, ATTACK_MOVE_ALL_ENERGY_TARGET, ATTACK_EVOLVE_SELF, ATTACK_BENCH_ENERGY_DISCARD, ATTACK_HAND_ENERGY_DISCARD, ATTACK_OWN_ENERGY_DISCARD, ATTACK_RESTRICT_OPPONENT_ATTACK, ATTACK_MOVE_DAMAGE_SOURCE, ATTACK_MOVE_DAMAGE_DESTINATION, ATTACK_MOVE_OWN_DAMAGE_SOURCE, ATTACK_MOVE_OWN_DAMAGE_TARGET, ATTACK_OPPONENT_ENERGY, ATTACK_OPPONENT_ENERGY_TARGET, ATTACK_MATCHING_ENERGY_SEARCH, ATTACK_HAND_ENERGY, ATTACK_HAND_ENERGY_TARGET, ATTACK_KNOCKOUT_BASIC_BENCH, ATTACK_DECK_BASIC_ENERGY_ANY, ATTACK_DECK_BASIC_ENERGY_TARGET, ATTACK_POKEMON_SEARCH, ATTACK_ANY_CARD_SEARCH, ATTACK_HEAL_TARGET, ATTACK_DAMAGE_COUNTER_TARGET, ATTACK_MULTI_DAMAGE_TARGETS, ATTACK_MULTI_DAMAGE_SINGLE_TARGET, ATTACK_DISCARD_THEN_BENCH_TARGET, ATTACK_SHUFFLE_OWN_BENCH, ATTACK_NAMED_POKEMON_TO_BENCH, ATTACK_DEVOLVE_OPPONENT_ACTIVE, OPPONENT_SHUFFLES_OWN_HAND, ABILITY_DISCARD_ENERGY_FROM_DISCARD, ABILITY_DISCARD_ENERGY_TARGET, ABILITY_MOVE_BASIC_ENERGY, ABILITY_MOVE_BASIC_ENERGY_TARGET, ABILITY_ATTACH_MULTIPLE_BASIC_ENERGY, ABILITY_TOP_DECK_KEEP_ONE, ABILITY_SEARCH_TOP_CARD, ON_BENCH_DISCARD_OPPONENT_DECK_TOP, ON_BENCH_SWITCH_AND_MOVE_ENERGY, ON_BENCH_MOVE_ENERGY, ATTACK_COPY_BENCHED_N, ATTACK_COPY_OPPONENT_ACTIVE, OPTIONAL_ATTACK_ENERGY_BONUS, ATTACK_OPTIONAL_SHUFFLE_ENERGY, OPPONENT_DISCARDS_OWN_HAND, EVOLUTION_DAMAGE_TARGETS, ATTACK_OPTIONAL_RETURN_SELF, ATTACK_CHOOSE_OPPONENT_HAND_DISCARD, ATTACK_EVOLVE_BENCHED, ATTACK_OPTIONAL_PARALYSIS_DISCARD, ATTACK_REVEAL_HAND_POKEMON, ATTACK_TOP_DECK_ENERGY, ATTACK_TOP_DECK_ENERGY_TARGET, ATTACK_OPTIONAL_DISCARD_TOP_CARD, EVOLUTION_OPPONENT_HAND_BASIC_TO_BENCH, ATTACK_REORDER_OPPONENT_TOP, ABILITY_ATTACH_ANY_BASIC_ENERGY_FROM_HAND, ABILITY_ATTACH_ANY_BASIC_ENERGY_TARGET, ATTACK_KNOCKOUT_LOWEST_HP_TARGET, ATTACK_DISCARD_BASIC_ENERGY_TO_SELF}
@Serializable data class ChoiceRequest(val kind: ChoiceKind, val title: String, val candidates: List<String>, val min: Int, val max: Int, val remaining: Int = 0, val selectedEnergy: String? = null, val energyType: EnergyType? = null, val amount: Int = 0, val visibleCards: List<String> = emptyList(), val damageMultiplier: Int = 1, val selectedTargets: List<String> = emptyList(), val sourcePokemonId: String? = null, val benchedOnly: Boolean = false, val targetNamePrefix: String? = null, val eachBenchedPokemon: Boolean = false, val optionLabels: Map<String, String> = emptyMap(), val poisonSwitchedPokemon: Boolean = false, val healAmount: Int = 0, val pendingCards: List<String> = emptyList(), val healAll: Boolean = false, val targetPokemonType: EnergyType? = null, val poisonSelfIfAttached: Boolean = false)
@Serializable data class Resolution(val choices: List<ChoiceRequest>, val drawTo: Int = 0, val drawCount: Int = 0, val shuffleAfter: Boolean = false, val toolCard: CardInstance? = null)
@Serializable sealed interface GameAction {
    @Serializable data class ChooseStartingPlayer(val player: Int) : GameAction
    @Serializable data class ChooseActive(val cardId: String) : GameAction
    @Serializable data class PlayBasic(val cardId: String) : GameAction
    @Serializable data object FinishSetup : GameAction
    @Serializable data class MulliganDraw(val count: Int) : GameAction
    @Serializable data object FinishBonusSetup : GameAction
    @Serializable data class AttachEnergy(val cardId: String, val pokemonId: String) : GameAction
    @Serializable data class Evolve(val cardId: String, val pokemonId: String) : GameAction
    @Serializable data class Attack(val attackId: String, val targetId: String? = null) : GameAction
    @Serializable data class PlayTrainer(val cardId: String) : GameAction
    @Serializable data class UseAbility(val pokemonId: String, val abilityId: String) : GameAction
    @Serializable data class ChooseCards(val cardIds: List<String>) : GameAction
    @Serializable data class Retreat(val pokemonId: String, val energyIds: List<String>) : GameAction
    @Serializable data class TakePrize(val cardId: String) : GameAction
    @Serializable data class Promote(val pokemonId: String) : GameAction
    @Serializable data object EndTurn : GameAction
}
sealed interface Legality {
    data object Legal : Legality
    data class Illegal(val reason: String) : Legality
}
data class Transition(val state: GameState, val legality: Legality, val events: List<GameEvent> = emptyList())
data class AttackPreview(val damage: Int, val effectiveHp: Int, val currentDamage: Int, val remainingHp: Int, val knockout: Boolean, val prizes: Int,
    val uncertain: Boolean = false, val minDamage: Int = damage, val maxDamage: Int = damage)

/** All randomness consumes this persisted stream. SplitMix64 + unbiased bounded sampling. */
class SeededRng(state: RngState) {
    var state: RngState = state; private set
    private fun nextLong(): Long {
        val next = state.value + -7046029254386353131L
        state = RngState(next, state.position + 1)
        var z = next
        z = (z xor (z ushr 30)) * -4658895280553007687L
        z = (z xor (z ushr 27)) * -7723592293110705685L
        return z xor (z ushr 31)
    }
    fun nextInt(bound: Int): Int {
        require(bound > 0)
        var bits: Long; var result: Long
        do { bits = nextLong().ushr(1); result = bits % bound } while (bits - result + (bound - 1) < 0)
        return result.toInt()
    }
    fun <T> shuffle(cards: List<T>): List<T> = cards.toMutableList().also { list ->
        for (i in list.lastIndex downTo 1) { val j = nextInt(i + 1); val item = list[i]; list[i] = list[j]; list[j] = item }
    }
}
