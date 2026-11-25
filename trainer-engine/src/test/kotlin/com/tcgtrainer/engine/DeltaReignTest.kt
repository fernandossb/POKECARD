package com.tcgtrainer.engine

import java.time.LocalDate
import kotlin.test.*

class DeltaReignTest {
    private val rules = RulesEngine(TrainerCatalog)
    private fun card(id: String, print: String) = CardInstance(id, print)
    private fun pokemon(id: String, print: String) = PokemonInstance(listOf(card(id, print)), enteredPlayTurn = 0)
    private fun board(own: PlayerState) = GameState("delta", 1L, RngState(1), listOf(
        if (own.prizes.isEmpty()) own.copy(prizes = listOf(card("own-prize", "sve-001"))) else own,
        PlayerState(hand = emptyList(), deck = emptyList(), prizes = listOf(card("foe-prize", "sve-001")),
            active = pokemon("foe", "me06-001"))
    ), 0, 0, turn = 3, phase = Phase.MAIN)

    @Test fun `all Delta Reign prints are indexed and Standard respects rotation`() {
        val cards = CardLibrary.entries.filter { it.id.startsWith("me06-") }
        assertEquals(141, cards.size)
        assertEquals(141, CardLibrary.search("Reinado Delta", null, onlyPlayable = false).size)
        assertTrue(cards.all { it.regulation == "J" })
        assertTrue(cards.all { it.standard == !LocalDate.now().isBefore(LocalDate.of(2026, 11, 20)) })
        assertTrue(CardLibrary.search("sv01-166", DeckFormat.STANDARD, onlyPlayable = false).none { it.id == "sv01-166" }) // Arven (G)
        assertFalse(CardLibrary.byId.getValue("swsh4-43").standard)
        assertTrue(CardLibrary.byId.getValue("sv02-172").standard) // Reviewed Boss's Orders reprint
        assertTrue(CardLibrary.byId.getValue("sv01-258").standard) // Basic Energy
        assertTrue(CardLibrary.byId.getValue("me06-094").implemented)
        assertTrue((94..99).all { CardLibrary.byId.getValue("me06-0$it").implemented })
        assertEquals(Stage.STAGE_ONE, TrainerCatalog.get("me06-069").stage)
        assertEquals("Inkay", TrainerCatalog.get("me06-069").evolvesFromName)
        assertEquals(Stage.STAGE_TWO, TrainerCatalog.get("me06-088").stage)
        println("Delta Reign executable: ${cards.count { it.implemented }}/${cards.size}")
    }

    @Test fun `Rayquaza can decline its ability or attach only one revealed basic energy`() {
        val deck = listOf(card("a", "me06-001"), card("fire", "sve-002"), card("b", "me06-001"),
            card("electric", "sve-004"), card("untouched", "sve-003"))
        val initial = board(PlayerState(deck = deck, hand = listOf(card("ray", "me06-084")),
            prizes = listOf(card("prize", "sve-002")), active = pokemon("lead", "me06-001")))
        val play = rules.apply(initial, GameAction.PlayBasic("ray"))
        assertEquals(Legality.Legal, play.legality)
        assertTrue(play.state.resolution!!.choices.first().visibleCards.isEmpty())
        val decline = rules.apply(play.state, GameAction.ChooseCards(emptyList())).state
        assertEquals(deck, decline.players[0].deck)
        val reveal = rules.apply(play.state, GameAction.ChooseCards(listOf("use"))).state
        assertEquals(setOf("fire", "electric"), reveal.resolution!!.choices.first().candidates.toSet())
        assertIs<Legality.Illegal>(rules.apply(reveal, GameAction.ChooseCards(listOf("untouched"))).legality)
        val attach = rules.apply(reveal, GameAction.ChooseCards(listOf("fire")))
        assertEquals(Legality.Legal, attach.legality)
        assertEquals(listOf("fire"), attach.state.players[0].bench.single().energies.map { it.id })
        assertEquals("untouched", attach.state.players[0].deck.first().id)
        assertEquals(setOf("a", "b", "electric"), attach.state.players[0].deck.drop(1).map { it.id }.toSet())
    }

    @Test fun `Talonflame enters the bench from hand only with a Colorless Mega ex`() {
        val own = PlayerState(deck = emptyList(), prizes = emptyList(), hand = listOf(card("bird", "me06-088")),
            active = pokemon("lead", "me06-001"))
        assertIs<Legality.Illegal>(rules.validate(board(own), GameAction.PlayBasic("bird")))
        val withMega = board(own.copy(active = pokemon("mega", "me06-084")))
        val result = rules.apply(withMega, GameAction.PlayBasic("bird"))
        assertEquals(Legality.Legal, result.legality)
        assertEquals("bird", result.state.players[0].bench.single().id)
        assertIs<Legality.Illegal>(rules.validate(withMega.copy(phase = Phase.SELECT_BENCH), GameAction.PlayBasic("bird")))
    }

    @Test fun `Storm Emerald counts Fire and Lightning on Ativo and Banco`() {
        val ray = pokemon("ray", "me06-084").copy(energies = listOf(
            card("fire", "sve-002"), card("electric", "sve-004"), card("water", "sve-003")))
        val bench = pokemon("bird", "me06-088").copy(energies = listOf(card("bench-fire", "sve-002")))
        val game = board(PlayerState(deck = emptyList(), prizes = emptyList(), hand = emptyList(),
            active = ray, bench = listOf(bench)))
        val attack = TrainerCatalog.get("me06-084").attacks.single()
        assertEquals(150, rules.preview(game, attack.id)?.damage)
    }

    @Test fun `Mega Golurk requires ten cards in hand to attack`() {
        val active = pokemon("golurk", "me06-047").copy(energies = List(2) { card("energy-$it", "sve-005") })
        val own = PlayerState(deck = emptyList(), prizes = emptyList(),
            hand = List(9) { card("hand-$it", "sve-001") }, active = active)
        val attack = GameAction.Attack(TrainerCatalog.get("me06-047").attacks.single().id)
        assertIs<Legality.Illegal>(rules.validate(board(own), attack))
        assertEquals(Legality.Legal, rules.validate(board(own.copy(hand = own.hand + card("tenth", "sve-001"))), attack))
    }

    @Test fun `Adventure Lantern searches for one Fire and one Lightning energy`() {
        val own = PlayerState(deck = listOf(card("fire", "sve-002"), card("electric", "sve-004"), card("water", "sve-003")),
            prizes = emptyList(), hand = listOf(card("lantern", "me06-090")), active = pokemon("lead", "me06-001"))
        val play = rules.apply(board(own), GameAction.PlayTrainer("lantern"))
        assertEquals(Legality.Legal, play.legality)
        assertEquals(listOf(listOf("fire"), listOf("electric")), play.state.resolution!!.choices.map { it.candidates })
        val fire = rules.apply(play.state, GameAction.ChooseCards(listOf("fire")))
        val electric = rules.apply(fire.state, GameAction.ChooseCards(listOf("electric")))
        assertEquals(Legality.Legal, electric.legality)
        assertEquals(setOf("fire", "electric"), electric.state.players[0].hand.map { it.id }.toSet())
        assertEquals(listOf("water"), electric.state.players[0].deck.map { it.id })
    }

    @Test fun `Legendary Pyroduct requires two distinct halves and discards both when replaced`() {
        val left = card("left", "me06-094")
        val right = card("right", "me06-095")
        val golurk = pokemon("golurk", "me06-047")
        val own = PlayerState(deck = emptyList(), prizes = emptyList(),
            hand = listOf(left, left.copy(id = "duplicate")), active = golurk,
            bench = listOf(pokemon("ray", "me06-084")))
        assertIs<Legality.Illegal>(rules.validate(board(own), GameAction.PlayTrainer(left.id)))
        val play = rules.apply(board(own.copy(hand = listOf(left, right))), GameAction.PlayTrainer(left.id))
        assertEquals(Legality.Legal, play.legality)
        assertEquals(setOf(left, right), play.state.stadiumCards().toSet())
        assertTrue(play.state.players[0].hand.isEmpty())
        assertTrue(rules.effectiveAbilities(play.state, 0, golurk).isEmpty())
        assertTrue(rules.effectiveAbilities(play.state, 0, own.bench.single()).isNotEmpty())
        val tower = card("tower", "sv06-153")
        val next = play.state.copy(turn = 4, currentPlayer = 1,
            players = play.state.players.mapIndexed { index, player ->
                if (index == 1) player.copy(hand = listOf(tower)) else player
            })
        val replace = rules.apply(next, GameAction.PlayTrainer(tower.id))
        assertEquals(Legality.Legal, replace.legality)
        assertEquals(listOf(tower), replace.state.stadiumCards())
        assertEquals(setOf(left, right), replace.state.players[0].discard.toSet())
        assertTrue(rules.effectiveAbilities(replace.state, 0, golurk).isNotEmpty())
    }

    @Test fun `Legendary Summit reduces Prizes for an attack damage knockout`() {
        val lead = pokemon("lead", "me06-001").copy(energies = listOf(card("grass", "sve-001")))
        val own = PlayerState(deck = listOf(card("draw", "sve-001")), prizes = listOf(card("prize", "sve-001")),
            hand = listOf(card("summit-a", "me06-096"), card("summit-b", "me06-097")), active = lead)
        val initial = board(own)
        val opponent = initial.players[1].copy(active = initial.players[1].active!!.copy(damage = 120),
            bench = listOf(pokemon("backup", "me06-001")), prizes = listOf(card("opp-prize", "sve-001")),
            deck = listOf(card("opp-draw", "sve-001")))
        val stadium = rules.apply(initial.copy(players = listOf(own, opponent)), GameAction.PlayTrainer("summit-a"))
        assertEquals(Legality.Legal, stadium.legality)
        val attack = TrainerCatalog.get("me06-001").attacks.first()
        assertEquals(0, rules.preview(stadium.state, attack.id)?.prizes)
        val knockout = rules.apply(stadium.state, GameAction.Attack(attack.id))
        assertEquals(Legality.Legal, knockout.legality)
        assertEquals(listOf(0, 0), knockout.state.prizesOwed)
        assertTrue(knockout.state.players[1].discard.any { it.id == "foe" })
    }

    @Test fun `Legendary Trench doubles actual healing on both sides`() {
        val own = PlayerState(deck = emptyList(), prizes = emptyList(), hand = listOf(
            card("trench-a", "me06-098"), card("trench-b", "me06-099"), card("grisham", "me06-093")),
            active = pokemon("lead", "me06-001").copy(damage = 110))
        val initial = board(own)
        val opponent = initial.players[1].copy(active = initial.players[1].active!!.copy(damage = 70))
        val stadium = rules.apply(initial.copy(players = listOf(initial.players[0], opponent)), GameAction.PlayTrainer("trench-a"))
        assertEquals(Legality.Legal, stadium.legality)
        val healed = rules.apply(stadium.state, GameAction.PlayTrainer("grisham"))
        assertEquals(Legality.Legal, healed.legality)
        assertEquals(listOf(10, 0), healed.state.players.map { it.active!!.damage })
    }

    @Test fun `Tate and Liza returns to hand with a Legendary Stadium and still spends the Supporter`() {
        val supporter = card("training", "me06-102")
        val own = PlayerState(deck = listOf(card("first", "sve-001"), card("second", "sve-004")),
            prizes = emptyList(), hand = listOf(supporter), active = pokemon("lead", "me06-001"))
        val initial = board(own)
        val action = GameAction.PlayTrainer(supporter.id)
        val normal = rules.apply(initial, action)
        assertEquals(Legality.Legal, normal.legality)
        assertTrue(supporter in normal.state.players[0].discard)
        val legendary = initial.copy(stadium = card("left", "me06-094"),
            stadiumCompanion = card("right", "me06-095"), stadiumOwner = 1)
        val result = rules.apply(legendary, action)
        assertEquals(Legality.Legal, result.legality)
        assertEquals(own.deck + supporter, result.state.players[0].hand)
        assertFalse(supporter in result.state.players[0].discard)
        assertIs<Legality.Illegal>(rules.validate(result.state, action))
    }
}
