package com.tcgtrainer.engine

/** Unseen deck plus sealed Prizes form one exchangeable pool; their actual identities are never read. */
internal object CpuDrawOdds {
    /** Hypothesis over compatible practice lists, not knowledge of the opponent's chosen list. */
    fun opponentCategories(state: GameState, catalog: CardCatalog): Map<Category, Double>? {
        val opponent = state.players[1-state.currentPlayer]
        val seen = (opponent.discard + opponent.pokemon().flatMap { it.cards + it.energies + it.tools }).groupingBy { it.definitionId }.eachCount()
        if (seen.isEmpty()) return null
        val hypotheses = TrainingDecks.all.mapNotNull { deck ->
            val counts = deck.cards.groupingBy { it }.eachCount().toMutableMap()
            if (seen.any { (id, count) -> (counts[id] ?: 0) < count }) return@mapNotNull null
            seen.forEach { (id, count) -> counts[id] = counts.getValue(id)-count }
            val total = counts.values.sum()
            if (total == 0) return@mapNotNull null
            Category.entries.associateWith { category -> counts.entries.sumOf { (id, count) -> if (catalog.get(id).category == category) count else 0 }.toDouble()/total }
        }
        if (hypotheses.isEmpty()) return null
        return Category.entries.associateWith { category -> hypotheses.map { it.getValue(category) }.average() }
    }
    fun pool(state: GameState, owner: Int): Map<String, Int>? {
        val list = state.deckLists.getOrNull(owner)?.takeIf { it.isNotEmpty() } ?: return null
        val counts = list.groupingBy { it }.eachCount().toMutableMap()
        val player = state.players[owner]
        val visible = player.hand + player.discard + player.pokemon().flatMap { it.cards + it.energies + it.tools }
        for (card in visible) {
            if (card.definitionId == "cpu-hidden-card") return null
            val left = (counts[card.definitionId] ?: return null)-1
            if (left < 0) return null
            counts[card.definitionId] = left
        }
        if (counts.values.sum() != player.deck.size+player.prizes.size) return null
        return counts.filterValues { it > 0 }
    }

    fun atLeastOne(total: Int, successes: Int, draws: Int): Double {
        require(total >= 0 && successes in 0..total && draws in 0..total)
        var miss = 1.0
        repeat(draws) { i -> miss *= ((total-successes-i).coerceAtLeast(0)).toDouble()/(total-i) }
        return 1.0-miss
    }
}
