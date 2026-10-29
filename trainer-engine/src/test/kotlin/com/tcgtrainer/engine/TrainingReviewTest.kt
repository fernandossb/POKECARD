package com.tcgtrainer.engine

import kotlin.test.*

class TrainingReviewTest {
    private val hit = AttackDefinition("hit", "Golpe leve", emptyList(), listOf(Effect.Damage(30)))
    private val finish = AttackDefinition("finish", "Golpe decisivo", listOf(EnergyType.COLORLESS), listOf(Effect.Damage(120)))
    private val hero = CardDefinition("hero", "Atacante", "Test", "1", category = Category.POKEMON, hp = 200, attacks = listOf(hit, finish))
    private val target = CardDefinition("target", "Alvo", "Test", "2", category = Category.POKEMON, hp = 100)
    private val reserve = CardDefinition("reserve", "Reserva", "Test", "3", category = Category.POKEMON, hp = 300)
    private val energy = CardDefinition("energy", "Energia de teste", "Test", "4", category = Category.ENERGY, type = EnergyType.FIGHTING, basicEnergy = true)
    private fun engine(vararg cards: CardDefinition) = RulesEngine(object : CardCatalog {
        private val entries = (listOf(hero, target, reserve, energy) + cards).associateBy { it.id }
        override fun get(id: String) = entries[id] ?: error(id)
    })
    private fun c(id: String, definition: String = "energy") = CardInstance(id, definition)
    private fun state(hand: List<CardInstance> = emptyList(), charged: Boolean = true) = GameState("review", 42, RngState(42), listOf(
        PlayerState(deck = List(8) { c("deck$it") }, hand = hand, prizes = listOf(c("prize")),
            active = PokemonInstance(listOf(c("hero", "hero")), energies = if (charged) listOf(c("attached")) else emptyList(), enteredPlayTurn = 0),
            bench = listOf(PokemonInstance(listOf(c("reserve", "reserve")), enteredPlayTurn = 0))),
        PlayerState(deck = List(8) { c("opdeck$it") }, hand = listOf(c("hidden")), prizes = List(6) { c("opprize$it") },
            active = PokemonInstance(listOf(c("target", "target")), enteredPlayTurn = 0))
    ), 0, 0, turn = 3, phase = Phase.MAIN)

    @Test fun `review identifies a missed immediate victory even if the player later wins`() {
        val engine = engine()
        val before = state()
        val action = GameAction.Attack("hit")
        val result = engine.apply(before, action)
        assertEquals(Legality.Legal, result.legality)
        val text = TrainingReview.compareDecision(engine, before, result.state, ActionRecord(0, 3, action, result.events))!!.second
        assertContains(text, "Golpe decisivo")
        assertContains(text, "encerrar a partida neste turno")
        assertContains(text, "atrasando")
        val victory = before.copy(phase = Phase.GAME_OVER, winner = 0, winReason = "Vitória")
        assertContains(TrainingReview.describe(victory), "Você venceu. Mesmo assim")
    }

    @Test fun `review explains wrong Energy target with a legal attachment and attack sequence`() {
        val engine = engine()
        val before = state(listOf(c("hand-energy")), charged = false)
        val action = GameAction.AttachEnergy("hand-energy", "reserve")
        val result = engine.apply(before, action)
        assertEquals(Legality.Legal, result.legality)
        val text = TrainingReview.compareDecision(engine, before, result.state, ActionRecord(0, 3, action, result.events))!!.second
        assertContains(text, "ligar Energia de teste a Atacante")
        assertContains(text, "Golpe decisivo")
        assertContains(text, "Reserva")
    }

    @Test fun `full victory summary keeps the missed victory from an earlier turn`() {
        val engine = engine()
        val initial = state()
        var game = initial
        fun act(action: GameAction) {
            val result = engine.apply(game, action)
            assertEquals(Legality.Legal, result.legality)
            game = result.state
        }
        act(GameAction.Attack("hit"))
        act(GameAction.EndTurn)
        act(GameAction.Attack("finish"))
        while (game.phase == Phase.SELECT_PRIZES) act(GameAction.TakePrize(game.players[0].prizes.first().id))
        assertEquals(0, game.winner)
        val summary = TrainingReview.describe(game, engine, initial)
        assertContains(summary, "Você venceu. Mesmo assim")
        assertContains(summary, "T3:")
        assertContains(summary, "atrasando")
        assertFalse("Reconstrução parcial" in summary)
    }

    @Test fun `replay failure preserves findings from confirmed earlier decisions`() {
        val engine = engine()
        val initial = state()
        val result = engine.apply(initial, GameAction.Attack("hit"))
        val corrupted = result.state.copy(history = result.state.history + ActionRecord(0, 5, GameAction.Attack("missing-attack"), emptyList()),
            phase = Phase.GAME_OVER, winner = 0)
        val summary = TrainingReview.describe(corrupted, engine, initial)
        assertContains(summary, "Reconstrução parcial")
        assertContains(summary, "Golpe decisivo")
        assertContains(summary, "atrasando")
    }

    @Test fun `review includes a cost reduction Tool and target choice before attacking`() {
        val tool = CardDefinition("tool", "Ferramenta de custo", "Test", "5", category = Category.TRAINER,
            trainerType = TrainerType.TOOL, trainerEffect = TrainerEffect.CounterGain)
        val costly = hero.copy(attacks = listOf(finish.copy(cost = List(2) { EnergyType.COLORLESS })))
        val engine = engine(tool, costly)
        val before = state(listOf(c("hand-tool", "tool"))).let { it.copy(players = listOf(
            it.players[0].copy(prizes = List(6) { i -> c("p$i") }), it.players[1].copy(prizes = listOf(c("op")))) ) }
        val best = DecisionReview(engine).best(before)!!
        assertTrue(best.certainKnockout)
        assertTrue(best.steps.any { "Ferramenta de custo" in it })
        assertTrue(best.steps.any { "Golpe decisivo" in it })
    }

    @Test fun `review considers a damage boosting ability before the attack`() {
        val ability = AbilityDefinition("boost", "Força extra", "", selfDamageCountersForAttackBoost = 2, attackDamageBoostAfterSelfDamage = 100)
        val source = hero.copy(attacks = listOf(hit), abilities = listOf(ability))
        val best = DecisionReview(engine(source)).best(state())!!
        assertTrue(best.wins)
        assertTrue(best.steps.any { "Força extra" in it })
    }

    @Test fun `review never criticizes a winning attack for not using another winning attack`() {
        val source = hero.copy(attacks = listOf(hit.copy(effects = listOf(Effect.Damage(120))), finish.copy(effects = listOf(Effect.Damage(300)))))
        val engine = engine(source)
        val before = state()
        val action = GameAction.Attack("hit")
        val result = engine.apply(before, action)
        assertNull(TrainingReview.compareDecision(engine, before, result.state, ActionRecord(0, 3, action, result.events)))
    }

    @Test fun `coin attack is not described as a guaranteed knockout or victory`() {
        val coin = finish.copy(cost = emptyList(), effects = listOf(Effect.CoinDamage(1, 240)))
        val engine = engine(hero.copy(attacks = listOf(coin)))
        val line = DecisionReview(engine).attack(state(), GameAction.Attack("finish"))!!
        assertTrue(line.uncertain)
        assertFalse(line.wins)
        assertFalse(line.certainKnockout)
        assertContains(line.outcome(), "incertos")
    }

    @Test fun `review ignores hidden opponent hand and deck order and does not assume future draws`() {
        val draw = CardDefinition("draw", "Compra", "Test", "6", category = Category.TRAINER,
            trainerType = TrainerType.ITEM, trainerEffect = TrainerEffect.DrawCards(1))
        val engine = engine(draw)
        val original = state(listOf(c("draw-card", "draw")), charged = false)
        val changed = original.copy(players = listOf(original.players[0].copy(deck = original.players[0].deck.reversed()),
            original.players[1].copy(hand = listOf(c("secret1", "hero")), deck = original.players[1].deck.reversed())))
        val best = DecisionReview(engine).best(original)!!
        assertEquals(best, DecisionReview(engine).best(changed))
        assertFalse(best.wins)
        assertEquals(30, best.damage)
        assertFalse(best.steps.any { "Compra" in it })
    }
}
