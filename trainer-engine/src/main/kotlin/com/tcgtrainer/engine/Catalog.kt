package com.tcgtrainer.engine

interface CardCatalog { fun get(id: String): CardDefinition }

/** Deliberately fictional: no printed Pokémon card behavior is inferred here. */
object TestCatalog : CardCatalog {
    val definitions = listOf(
        CardDefinition("TEST-001", "Aprendiz de Pedra", "TEST", "001", category = Category.POKEMON,
            hp = 60, retreatCost = 1, weakness = EnergyType.GRASS,
            attacks = listOf(AttackDefinition("strike", "Golpe de teste", listOf(EnergyType.FIGHTING), listOf(Effect.Damage(20))))),
        CardDefinition("TEST-002", "Guardião de Pedra", "TEST", "002", category = Category.POKEMON,
            stage = Stage.STAGE_ONE, evolvesFrom = "TEST-001", hp = 110, retreatCost = 2,
            weakness = EnergyType.GRASS,
            attacks = listOf(AttackDefinition("impact", "Impacto de teste", listOf(EnergyType.FIGHTING, EnergyType.COLORLESS), listOf(Effect.Damage(70))))),
        CardDefinition("TEST-003", "Explorador de Folha", "TEST", "003", category = Category.POKEMON,
            hp = 70, type = EnergyType.GRASS, retreatCost = 1, weakness = EnergyType.FIRE,
            attacks = listOf(AttackDefinition("leaf", "Folha de teste", listOf(EnergyType.COLORLESS), listOf(Effect.Damage(20))))),
        CardDefinition("TEST-004", "Energia de Luta de teste", "TEST", "004", category = Category.ENERGY, basicEnergy = true),
        CardDefinition("TEST-005", "Energia de Planta de teste", "TEST", "005", category = Category.ENERGY, type = EnergyType.GRASS, basicEnergy = true)
    ).associateBy { it.id }
    override fun get(id: String): CardDefinition = definitions.getValue(id)
    // Construction deck, not a tournament-legal deck: more than four copies of fictional Pokémon.
    fun deck(): List<String> = List(12) { "TEST-001" } + List(8) { "TEST-002" } + List(8) { "TEST-003" } +
        List(24) { "TEST-004" } + List(8) { "TEST-005" }
}
