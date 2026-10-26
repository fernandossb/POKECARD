package com.tcgtrainer.engine

import kotlin.test.*

class CpuPlayerTest {
    private val rules = RulesEngine(TrainerCatalog)
    private val cpu = CpuPlayer()
    private fun c(id: String, definition: String = "sv01-258") = CardInstance(id, definition)
    private fun p(id: String, definition: String = "me01-075", energies: Int = 1) = PokemonInstance(
        listOf(c(id, definition)), energies = List(energies) { c("$id-e$it") }, enteredPlayTurn = 0)
    private fun main(): GameState {
        fun player(id: String) = PlayerState(List(20) { c("$id-d$it") }, hand = listOf(c("$id-h")),
            prizes = List(6) { c("$id-p$it") }, active = p(id), bench = listOf(p("$id-b", "me01-074")))
        return GameState("cpu", 42, RngState(42), listOf(player("human"), player("cpu")), 0, 1, 4, Phase.MAIN, catalogVersion = "lucario-v1")
    }
    @Test fun `all seventeen decks contain 60 verified cards and respect copy limits`() {
        assertEquals(17, TrainingDecks.all.size)
        assertEquals(17, TrainingDecks.all.map { it.cards }.distinct().size)
        TrainingDecks.all.forEach { deck ->
            assertEquals(60, deck.cards.size)
            deck.cards.groupingBy { it }.eachCount().forEach { (id, n) ->
                val d = TrainerCatalog.get(id)
                assertTrue(d.verified && d.artAsset != null)
                assertTrue(d.category == Category.ENERGY || n <= 4)
                assertTrue(d.attacks.all(rules.effects::supported))
            }
            assertTrue(deck.cards.count { TrainerCatalog.get(it).aceSpec } <= 1)
        }
    }
    @Test fun `hidden hand prizes and both deck orders do not affect CPU input or decision`() {
        val s = main()
        val modified = s.copy(rng = RngState(9999), seed = 777,
            players = s.players.mapIndexed { i, player -> player.copy(deck = player.deck.reversed(),
                prizes = player.prizes.map { it.copy(definitionId = "me01-077") },
                hand = if (i == 0) player.hand.map { it.copy(definitionId = "me01-077") } else player.hand) })
        assertEquals(rules.cpuInput(s), rules.cpuInput(modified))
        assertEquals(cpu.choose(rules.cpuInput(s)), cpu.choose(rules.cpuInput(modified)))
        val obs = rules.cpuInput(s).observation
        assertTrue(obs.ownHand.none { it.id.startsWith("human") })
    }
    @Test fun `deck search reveals eligible cards without revealing order`() {
        val s = main().let { it.copy(phase = Phase.RESOLVE_CHOICE, resolution = Resolution(listOf(
            ChoiceRequest(ChoiceKind.SEARCH, "Busca", it.players[1].deck.map { c -> c.id }, 0, 1)))) }
        val reversed = s.copy(players = listOf(s.players[0], s.players[1].copy(deck = s.players[1].deck.reversed())),
            resolution = s.resolution!!.copy(choices = s.resolution.choices.map { it.copy(candidates = it.candidates.reversed()) }))
        assertEquals(rules.cpuInput(s), rules.cpuInput(reversed))
        assertEquals(Legality.Legal, rules.validate(s, cpu.choose(rules.cpuInput(s))))
    }
    @Test fun `facedown setup field is hidden from CPU`() {
        val input = rules.cpuInput(main().copy(turn = 0, phase = Phase.SELECT_ACTIVE, setupPlayer = 1))
        assertTrue(input.observation.opponentField.isEmpty())
        assertNull(input.observation.opponentActiveId)
    }
    @Test fun `optional mulligan bonus does not exhaust CPU deck`() {
        val s = main().let { it.copy(turn = 0, phase = Phase.MULLIGAN_BONUS, setupPlayer = 1,
            players = listOf(it.players[0].copy(mulligans = 60), it.players[1].copy(
                deck = List(47) { n -> c("cpu-deck$n") }, hand = List(6) { n -> c("cpu-hand$n") }, bench = emptyList(), bonusDraws = 60))) }
        val action = assertIs<GameAction.MulliganDraw>(cpu.choose(rules.cpuInput(s)))
        assertTrue(action.count > 0)
        assertEquals(Legality.Legal, rules.validate(s, action))
        assertTrue(rules.apply(s, action).state.players[1].deck.size >= 2)
    }
    @Test fun `CPU takes winning knockout over drawing and setup`() {
        val s = main().let { it.copy(players = listOf(it.players[0].copy(active = p("target").copy(damage = 100)),
            it.players[1].copy(prizes = listOf(c("last-prize")), hand = listOf(c("draw", "me01-119"), c("energy"))))) }
        assertIs<GameAction.Attack>(cpu.choose(rules.cpuInput(s)))
    }
    @Test fun `undo restores human checkpoint including RNG before complete CPU response`() {
        val s = main().copy(currentPlayer = 0, history = List(5) { ActionRecord(0, 3, GameAction.EndTurn, emptyList()) })
        var session = GameSession(rules, SessionSnapshot(s))
        session.dispatch(GameAction.EndTurn)
        // Legacy v0.3 saves omit checkpoint history sizes. Migrate before the CPU continues.
        session = GameSession(rules, session.snapshot.copy(undoHistorySizes = emptyList()))
        var steps = 0
        while (rules.actingPlayer(session.snapshot.state) == 1 && session.snapshot.state.phase != Phase.GAME_OVER) {
            assertTrue(steps++ < 100)
            assertEquals(Legality.Legal, session.dispatch(cpu.choose(rules.cpuInput(session.snapshot.state)), recordUndo = false).legality)
        }
        val restored = GameSession(rules, GameSession.decode(GameSession.encode(session.snapshot)))
        assertTrue(restored.undo())
        assertEquals(s, restored.snapshot.state)
        assertFalse(restored.undo())
    }
    @Test fun `all deck matchups finish autonomously with legal actions conservation and reproducible replay`() {
        val seen = mutableSetOf<String>()
        for (a in TrainingDecks.all) for (b in TrainingDecks.all) for (seed in listOf(7L, 42L, 123456L)) {
            val initial = rules.newGame(seed, listOf(a.cards, b.cards), Mode.MATCH)
            var state = initial
            var steps = 0
            while (state.phase != Phase.GAME_OVER) {
                assertTrue(steps++ < 1200, "Stalled ${a.id}/${b.id} seed $seed turn ${state.turn}")
                val action = cpu.choose(rules.cpuInput(state))
                seen += action.javaClass.simpleName
                val next = rules.apply(state, action)
                assertEquals(Legality.Legal, next.legality, "${a.id}/${b.id} $seed ${state.phase} $action")
                state = next.state
                val all = state.players.flatMap { player -> player.deck + player.hand + player.prizes + player.discard +
                    player.pokemon().flatMap { it.cards + it.energies + listOfNotNull(it.tool) } } +
                    listOfNotNull(state.stadium) + listOfNotNull(state.resolution?.toolCard)
                assertEquals(120, all.size, "Conservation after $action")
                assertEquals(120, all.map { it.id }.distinct().size)
            }
            assertNotNull(state.winReason) // Simultaneous final knockouts may correctly end in a draw.
            assertEquals(state, GameSession.replay(rules, initial, state.history.map { it.action }))
        }
        assertTrue(seen.containsAll(listOf("Attack", "Evolve", "UseAbility", "PlayTrainer", "ChooseCards", "TakePrize", "Promote")), "$seen")
    }
}
