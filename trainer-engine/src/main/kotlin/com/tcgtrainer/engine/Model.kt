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
    @Serializable data class DrawToHand(val size: Int) : Effect
    @Serializable data class Heal(val amount: Int) : Effect
    @Serializable data class Unsupported(val text: String) : Effect
    @Serializable data class AccelerateEnergy(val max: Int, val type: EnergyType) : Effect
    @Serializable data class RestrictNextTurn(val attackId: String? = null) : Effect
    @Serializable data class Recoil(val damage: Int) : Effect
    @Serializable data class RequiresBenched(val definitionId: String) : Effect
    @Serializable data object IgnoreWeaknessResistance : Effect
    @Serializable data object IgnoreResistance : Effect
    @Serializable data object IgnoreOpponentActiveEffects : Effect
    @Serializable data class TargetedDamage(val damage: Int, val benchedOnly: Boolean = false) : Effect
    @Serializable data class CoinDamage(val coins: Int, val perHeads: Int, val untilTails: Boolean = false,
        val perEnergy: Boolean = false, val bonusOnly: Boolean = false, val perEnergyBothActive: Boolean = false) : Effect
    @Serializable data class DiscardAttackEnergy(val count: Int, val type: EnergyType? = null, val all: Boolean = false) : Effect
    @Serializable data class EnergyFromDeck(val count: Int, val type: EnergyType) : Effect
    @Serializable data class SupporterBonus(val damage: Int) : Effect
    @Serializable data object PreventRetreatNextTurn : Effect
    @Serializable data class ConditionalEnergyDamage(val type: EnergyType, val minimum: Int, val bonus: Int) : Effect
    @Serializable data class ConditionalInPlayEnergyDamage(val type: EnergyType, val minimum: Int, val bonus: Int) : Effect
    @Serializable data class SwitchOwn(val optional: Boolean = false) : Effect
    @Serializable data class BenchedCountDamage(val perPokemon: Int) : Effect
    @Serializable data class OwnInPlayCountDamage(val perPokemon: Int) : Effect
    @Serializable data class OwnDamageCounterDamage(val perCounter: Int) : Effect
    @Serializable data class OwnUndamagedDamageBonus(val bonus: Int) : Effect
    @Serializable data object SwitchOpponent : Effect
    @Serializable data class BasicPokemonToBench(val count: Int) : Effect
    @Serializable data object SearchRotomToBench : Effect
    @Serializable data object RestrictIfSecondPlayerFirstTurn : Effect
    @Serializable data object PreventBasicNonColorlessDamageNextTurn : Effect
    @Serializable data object PreventBasicPokemonDamageNextTurn : Effect
    @Serializable data class OwnBenchCountDamage(val perPokemon: Int) : Effect
    @Serializable data class OwnBenchWithEnergyDamage(val perPokemon: Int, val type: EnergyType) : Effect
    @Serializable data class OwnDamagedPokemonCountDamage(val perPokemon: Int) : Effect
    @Serializable data object MoveOpponentDamageCounters : Effect
    @Serializable data class SearchPokemonMatchingAttachedBasicEnergy(val max: Int) : Effect
    @Serializable data object AttachAnyBasicEnergyFromHand : Effect
    @Serializable data class AttachBasicEnergyFromDeckAny(val max: Int) : Effect
    @Serializable data class SearchPokemonToHand(val max: Int = 1) : Effect
    @Serializable data class OpponentTypeDamage(val type: EnergyType, val bonus: Int) : Effect
    @Serializable data class HealOwnPokemon(val amount: Int) : Effect
    @Serializable data object CoinKnockOutBasic : Effect
    @Serializable data class StatusCondition(val condition: SpecialCondition, val onHeads: Boolean = false, val minimumHeads: Int = 1, val onSelf: Boolean = false) : Effect
    @Serializable data class DiscardDefendingEnergy(val count: Int, val onHeads: Boolean = false) : Effect
    @Serializable data class BenchDamage(val amount: Int) : Effect
    @Serializable data class BenchDamageCounters(val count: Int) : Effect
    @Serializable data class MultiTargetDamage(val amount: Int, val targets: Int, val allowRepeat: Boolean = false, val ignoreWeaknessResistance: Boolean = false) : Effect
    @Serializable data object KnockOutBothActive : Effect
    @Serializable data object RecoverSelfFromSpecialConditions : Effect
    @Serializable data object NoWeaknessNextTurn : Effect
    @Serializable data class OpponentActiveRetreatCostDamage(val perCost: Int) : Effect
    @Serializable data object CoinMiss : Effect
    @Serializable data class MoveEnergyToBench(val count: Int = 1) : Effect
    @Serializable data class DiscardRandomOpponentHand(val count: Int) : Effect
    @Serializable data class ShuffleRandomOpponentHand(val count: Int) : Effect
    @Serializable data class DiscardOpponentDeck(val count: Int) : Effect
    @Serializable data class DiscardBenchEnergyDamage(val max: Int, val perEnergyDamage: Int) : Effect
    @Serializable data class ReduceDamageNextTurn(val amount: Int) : Effect
    @Serializable data class SelfDamage(val amount: Int) : Effect
    @Serializable data object PreventDefendingAttackNextTurn : Effect
    @Serializable data class OwnHandCounterDamage(val countersPerCard: Int) : Effect
    @Serializable data class OpponentExDamage(val bonus: Int) : Effect
    @Serializable data class OwnEnergyCountDamage(val perEnergy: Int) : Effect
    @Serializable data class OpponentEnergyCountDamage(val perEnergy: Int) : Effect
    @Serializable data class ReduceDefendingAttackDamageNextTurn(val amount: Int) : Effect
    @Serializable data class OpponentConditionDamage(val bonus: Int) : Effect
    @Serializable data class OwnKnockoutLastTurnDamage(val bonus: Int) : Effect
    @Serializable data class RequiresExactHandSize(val size: Int) : Effect
    @Serializable data class OwnToolCountDamage(val perTool: Int) : Effect
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
    val discardEnergyType: EnergyType? = null, val requiresInPlay: String? = null,
    val requiresPreviousKnockout: Boolean = false, val onEvolveSwitch: Boolean = false,
    val attackDiscountPerOpponentPrize: Boolean = false, val usageKey: String = id,
    val requiresActive: Boolean = false, val onEvolveDraw: Int = 0,
    val discardHandCount: Int = 0, val drawTo: Int = 0, val damageReduction: Int = 0,
    val retaliateDamageCounters: Int = 0, val requiresTeamRocketPokemonCount: Int = 0,
    val ignoreOpponentActiveEffects: Boolean = false, val damageCounters: Int = 0,
    val damageTargets: Int = 0, val selfKnockout: Boolean = false,
    val attachHandEnergyType: EnergyType? = null, val drawIfAttached: Int = 0,
    val usagePerPokemon: Boolean = false, val surviveAttackKnockoutAtHp: Int = 0,
    val shuffleSelfIntoDeck: Boolean = false, val healDamage: Int = 0,
    val recoverBasicPokemonToBenchMaxHp: Int = 0, val damageBonusIfHandsEqual: Int = 0,
    val surviveAttackKnockoutOnHeadsAtHp: Int = 0,
    val onEvolveShuffleRandomOpponentHandPerHeads: Int = 0,
    val damageBonusAgainstActiveWhenDamageAtLeast: Int = 0, val damageBonusAmount: Int = 0,
    val freeRetreatWithAttachedEnergyType: EnergyType? = null,
    val onEvolveAttachMetalFromDiscardMax: Int = 0,
    val devolveOpponentPokemonMax: Int = 0,
    val opponentActiveEvolutionRetreatCostBonus: Int = 0,
    val searchBasicEnergyTypeToHand: EnergyType? = null, val searchBasicEnergyMaxToHand: Int = 0,
    val onEvolveRecoverNamedTrainerFromDiscard: String? = null, val onEvolveRecoverNamedTrainerMax: Int = 0
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
    val basicEnergy: Boolean = false
)
fun CardDefinition.canEvolveFrom(base: CardDefinition): Boolean = category == Category.POKEMON &&
    base.category == Category.POKEMON && stage != Stage.BASIC && stage.ordinal == base.stage.ordinal + 1 &&
    (if (evolvesFromName != null) evolvesFromName == base.canonicalName else evolvesFrom == base.id)
@Serializable data class CardInstance(val id: String, val definitionId: String)
@Serializable data class PokemonInstance(
    val cards: List<CardInstance>, val damage: Int = 0, val energies: List<CardInstance> = emptyList(),
    val enteredPlayTurn: Int, val lastEvolutionTurn: Int = -1,
    val hpModifier: Int = 0, val damageModifier: Int = 0,
    val tool: CardInstance? = null, val restrictions: List<AttackRestriction> = emptyList(), val retreatBlockedTurn: Int = -1,
    val conditions: Set<SpecialCondition> = emptySet(), val incomingDamageReduction: Int = 0, val damageReductionTurn: Int = -1,
    val attackImmunityTurn: Int = -1, val outgoingDamageReduction: Int = 0, val outgoingDamageReductionTurn: Int = -1,
    val preventBasicNonColorlessDamageTurn: Int = -1, val preventBasicPokemonDamageTurn: Int = -1,
    val weaknessSuppressedTurn: Int = -1
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
    val lastKnockedOutByAttackDamageTurn: Int = -1
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
    val pendingAttackDamageKnockoutIds: Set<String> = emptySet()
)
@Serializable enum class ResolutionEnd { MAIN, ATTACK }
@Serializable enum class ChoiceKind { DISCARD, SEARCH, RECOVER, SWITCH_OWN, SWITCH_OPPONENT, ENERGY_FROM_DISCARD, ENERGY_TARGET, TOOL_TARGET, EVOLUTION_SWITCH, EVOLUTION_METAL_ENERGY, EVOLUTION_METAL_ENERGY_TARGET, EVOLUTION_RECOVER_NAMED_TRAINER, ABILITY_ENERGY, ABILITY_DAMAGE_TARGET, ABILITY_ATTACH_ENERGY, ABILITY_BASIC_POKEMON_TO_BENCH, ABILITY_DEVOLVE_TARGET, ABILITY_SEARCH_BASIC_ENERGY_TO_HAND, DISCARD_ATTACK_ENERGY, ATTACH_DECK_ENERGY, HEAL_TARGET, RECYCLE_ENERGY, SONIA, ATTACK_BENCH_POKEMON, ATTACK_ROTOM_SEARCH, ATTACK_DAMAGE_TARGET, ATTACK_ENERGY, ATTACK_ENERGY_TARGET, ATTACK_BENCH_ENERGY_DISCARD, ATTACK_MOVE_DAMAGE_SOURCE, ATTACK_MOVE_DAMAGE_DESTINATION, ATTACK_MATCHING_ENERGY_SEARCH, ATTACK_HAND_ENERGY, ATTACK_HAND_ENERGY_TARGET, ATTACK_KNOCKOUT_BASIC_BENCH, ATTACK_DECK_BASIC_ENERGY_ANY, ATTACK_DECK_BASIC_ENERGY_TARGET, ATTACK_POKEMON_SEARCH, ATTACK_HEAL_TARGET, ATTACK_DAMAGE_COUNTER_TARGET, ATTACK_MULTI_DAMAGE_TARGETS, ATTACK_MULTI_DAMAGE_SINGLE_TARGET }
@Serializable data class ChoiceRequest(val kind: ChoiceKind, val title: String, val candidates: List<String>, val min: Int, val max: Int, val remaining: Int = 0, val selectedEnergy: String? = null, val energyType: EnergyType? = null, val amount: Int = 0, val visibleCards: List<String> = emptyList(), val damageMultiplier: Int = 1, val selectedTargets: List<String> = emptyList(), val sourcePokemonId: String? = null)
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
