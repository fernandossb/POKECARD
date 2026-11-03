package com.tcgtrainer.engine

/** Deck-specific priorities distilled from successful lists and their intended lines of play. */
internal data class CpuDeckPlan(
    val id: String,
    val attackers: Set<String>,
    val engines: Set<String> = emptySet(),
    val support: Set<String> = emptySet(),
    val earlyAttackers: Set<String> = emptySet(),
    val energyType: EnergyType? = null,
    val desiredBench: Int = 3
) {
    fun priority(cardId: String): Int = when (cardId) {
        in attackers -> 100
        in engines -> 86
        in support -> 66
        in earlyAttackers -> 75
        else -> 0
    }

    fun boardValue(pokemon: List<PokemonInstance>, catalog: CardCatalog): Double {
        if (id == "custom") return 0.0
        val counts = pokemon.groupingBy { it.top.definitionId }.eachCount()
        val attackerCount = attackers.sumOf { counts[it] ?: 0 }
        val engineCount = engines.sumOf { counts[it] ?: 0 }
        val supportCount = support.sumOf { counts[it] ?: 0 }
        val earlyCount = earlyAttackers.sumOf { counts[it] ?: 0 }
        val benchSlots = (desiredBench - (pokemon.size - 1)).coerceAtLeast(0)
        // Reward the strategic board state, while tapering bonuses once a line is established.
        return minOf(2, attackerCount) * 24.0 + minOf(2, engineCount) * 20.0 +
            minOf(2, supportCount) * 11.0 + minOf(1, earlyCount) * 7.0 +
            (benchSlots * 2.0).coerceAtMost(8.0) -
            pokemon.filter { it.top.definitionId in attackers && catalog.get(it.top.definitionId).prizes > 1 }
                .count { it.id != pokemon.firstOrNull()?.id && it.energies.isEmpty() } * 3.0
    }

    companion object {
        private val known = mapOf(
            "lucario" to CpuDeckPlan("lucario", setOf("me01-077"), engines = setOf("me01-075", "me01-074"), support = setOf("me01-076"), energyType = EnergyType.FIGHTING),
            "hariyama" to CpuDeckPlan("hariyama", setOf("me01-073"), engines = setOf("me01-075", "me01-074"), support = setOf("me01-072"), earlyAttackers = setOf("me01-077"), energyType = EnergyType.FIGHTING),
            "solrock" to CpuDeckPlan("solrock", setOf("me01-075"), engines = setOf("me01-074"), support = setOf("sv06-141"), energyType = EnergyType.FIGHTING),
            "ursaluna" to CpuDeckPlan("ursaluna", setOf("sv06-141"), engines = setOf("me01-075", "me01-074"), earlyAttackers = setOf("me01-075"), energyType = EnergyType.FIGHTING),
            "academy-pikachu" to CpuDeckPlan("academy-pikachu", setOf("swsh4-43"), engines = setOf("swsh8-91", "swsh8-92", "swsh8-93"), energyType = EnergyType.LIGHTNING),
            "academy-cinderace" to CpuDeckPlan("academy-cinderace", setOf("swsh8-43"), engines = setOf("swsh8-46", "swsh8-49"), energyType = EnergyType.FIRE),
            "academy-eevee" to CpuDeckPlan("academy-eevee", setOf("swshp-SWSH065"), engines = setOf("swsh8-167", "swsh8-162", "swsh8-163")),
            "competitive-dragapult" to CpuDeckPlan("competitive-dragapult", setOf("sv06-130"), engines = setOf("sv06-129"), support = setOf("sv06-128"), energyType = EnergyType.PSYCHIC),
            "competitive-basic-box" to CpuDeckPlan("competitive-basic-box", setOf("me01-104", "sv06-025", "sv06-064", "me02.5-076"), engines = setOf("me03-062", "sv08-076"), support = setOf("sv05-123", "sv08-056", "sv05-081", "sv08-111"), energyType = EnergyType.LIGHTNING),
            "competitive-n-zoroark" to CpuDeckPlan("competitive-n-zoroark", setOf("sv09-098", "me02.5-155", "sv09-116"), engines = setOf("me02.5-136"), support = setOf("sv06-095", "sv06.5-039", "sv09-096"), energyType = EnergyType.DARKNESS),
            "competitive-slowking" to CpuDeckPlan("competitive-slowking", setOf("sv07-058", "me01-104", "sv08-076", "sv09-063", "sv08-100"), engines = setOf("sv07-057"), support = setOf("sv07-055", "sv06.5-047"), energyType = EnergyType.PSYCHIC),
            "competitive-dhelmise" to CpuDeckPlan("competitive-dhelmise", setOf("sv09-070", "sv09-060", "sv06-022"), engines = setOf("sv09-059", "sv06-020"), support = setOf("sv06-141"), energyType = EnergyType.PSYCHIC),
            "competitive-alakazam-dudunsparce" to CpuDeckPlan("competitive-alakazam-dudunsparce", setOf("sv06-082"), engines = setOf("sv09-120", "sv05-129"), support = setOf("sv06-081", "sv06-080"), energyType = EnergyType.PSYCHIC),
            "competitive-dragapult-dusknoir" to CpuDeckPlan("competitive-dragapult-dusknoir", setOf("sv06-130"), engines = setOf("sv06-129", "sv06.5-020"), support = setOf("sv06-128", "sv06.5-019", "sv06.5-018"), energyType = EnergyType.PSYCHIC),
            "competitive-mega-excadrill" to CpuDeckPlan("competitive-mega-excadrill", setOf("me05-065"), engines = setOf("sv09-062", "sv09-063"), support = setOf("sv09-061", "sv10.5b-045", "sv10.5b-067"), energyType = EnergyType.METAL),
            "competitive-sharpedo-toxtricity" to CpuDeckPlan("competitive-sharpedo-toxtricity", setOf("me02-061"), engines = setOf("me02-068"), support = setOf("me02-060", "me02-067"), energyType = EnergyType.DARKNESS),
            "competitive-mega-lucario-hariyama" to CpuDeckPlan("competitive-mega-lucario-hariyama", setOf("me01-077"), engines = setOf("me01-073", "me01-075", "me01-074"), support = setOf("me01-076", "me01-072"), energyType = EnergyType.FIGHTING)
        )

        fun forDeck(id: String, deckCards: List<String> = emptyList(), catalog: CardCatalog = TrainerCatalog): CpuDeckPlan = known[id] ?: run {
            if (deckCards.isEmpty()) return CpuDeckPlan("custom", emptySet())
            // User-created decks still receive sensible priorities: favor their multi-copy Pokémon
            // attackers and evolve lines, without assuming a named archetype.
            val pokemonIds = deckCards.groupingBy { it }.eachCount().filter { (_, count) -> count >= 2 }.keys
            val attackers = pokemonIds.filterTo(mutableSetOf()) { id ->
                runCatching { catalog.get(id).category == Category.POKEMON && catalog.get(id).attacks.isNotEmpty() }.getOrDefault(false)
            }
            CpuDeckPlan("custom", attackers)
        }
    }
}
