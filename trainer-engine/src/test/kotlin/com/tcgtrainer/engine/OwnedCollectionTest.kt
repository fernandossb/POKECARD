package com.tcgtrainer.engine

import kotlin.test.*

class OwnedCollectionTest {
    @Test fun `snapshot limits exact copies and does not grant other prints`() {
        val owned = OwnedCollection.decode("""{"version":1,"counts":{"swsh4-43":2,"sv01-258":10}}""")
        assertTrue(owned.allows(listOf("swsh4-43", "swsh4-43")))
        assertEquals(mapOf("swsh4-43" to 1, "swsh4-44" to 1), owned.missing(List(3) { "swsh4-43" } + "swsh4-44"))
        assertFalse(owned.allows(List(11) { "sv01-258" })) // Basic energies also require owned copies.
        assertFalse(owned.allows(listOf("SWSh4-43")))
        assertFalse(OwnedCollection(emptyMap(), available = false).allows(emptyList()))
    }
    @Test fun `corrupt snapshots cannot become unlimited inventory`() {
        listOf("{}", """{"version":2,"counts":{}}""", """{"version":1,"counts":{"x":-1}}""",
            """{"version":1,"counts":{"x":1.5}}""").forEach { assertFails { OwnedCollection.decode(it) } }
    }
}
