package com.tcgtrainer.engine

import kotlinx.serialization.json.*

/** Imported print data is kept alongside the source URLs; effects are explicit, reviewed mappings. */
object TrainerCatalog : CardCatalog {
    private val counts = linkedMapOf("me01-077" to 3, "me01-076" to 4, "me01-073" to 2, "me01-072" to 3,
        "me01-075" to 2, "me01-074" to 2, "sv06.5-038" to 1, "sv06-141" to 1,
        "me01-116" to 4, "me01-119" to 4, "sv09-149" to 3, "sv02-172" to 2,
        "sv08-187" to 1, "sv08-177" to 2, "me01-124" to 4, "me01-131" to 4,
        "sv06.5-061" to 2, "sv06-163" to 1, "me01-130" to 1, "sv10.5b-079" to 2, "sv01-258" to 12)
    private val academyIds by lazy { Academy2022.decks.flatMap { it.cards }.distinct() }
    private val academyNames by lazy { academyIds.associateBy { json(it).string("name")!! } }
    val definitions: Map<String, CardDefinition> by lazy {
        val reviewed = (counts.keys + academyIds).associateWith(::read)
        CatalogRuleCompiler.rows.mapNotNull { (id, raw) ->
            if (id in reviewed) null else CatalogRuleCompiler.compile(raw, reviewed)?.let { id to it }
        }.toMap() + reviewed
    }
    override fun get(id: String): CardDefinition = definitions[id] ?: TestCatalog.get(id)
    fun deck() = counts.flatMap { (id, count) -> List(count) { id } }
    val deckName = "Mega Lucario ex • treino"
    internal fun sourceData(id: String) = json(id)
    private fun json(id: String, language: String = ""): JsonObject {
        val path = "/cards/$id${if (language.isEmpty()) "" else "-$language"}.json"
        val stream = checkNotNull(javaClass.getResourceAsStream(path)) { "Dados ausentes: $path" }
        return stream.bufferedReader(Charsets.UTF_8).use { Json.parseToJsonElement(it.readText().removePrefix("\uFEFF")).jsonObject }
    }
    private fun JsonObject.string(key: String) = this[key]?.let { if (it is JsonNull) null else it.jsonPrimitive.content }
    private fun JsonObject.array(key: String) = (this[key] as? JsonArray)?.toList().orEmpty()
    private fun type(value: String) = EnergyType.valueOf(value.uppercase())
    private fun read(id: String): CardDefinition {
        val en = json(id); val pt = json(id, "pt")
        val pokemon = en.string("category") == "Pokemon"
        val energy = en.string("category") == "Energy"
        val attacksEn = en.array("attacks"); val attacksPt = pt.array("attacks")
        val attacks = attacksEn.mapIndexed { index, entry ->
            val a = entry.jsonObject; val translated = attacksPt.getOrNull(index)?.jsonObject ?: a
            val attackId = "$id-a$index"
            val base = a.string("damage")?.removeSuffix("+")?.toIntOrNull() ?: 0
            val effects = buildList {
                if (base > 0) add(Effect.Damage(base))
                when (id) {
                    "me01-076" -> add(Effect.RestrictNextTurn(attackId))
                    "me01-077" -> if (index == 0) add(Effect.AccelerateEnergy(3, EnergyType.FIGHTING)) else add(Effect.RestrictNextTurn(attackId))
                    "me01-073" -> add(Effect.Recoil(70))
                    "me01-075" -> { add(Effect.RequiresBenched("me01-074")); add(Effect.IgnoreWeaknessResistance) }
                    "sv06-141" -> add(Effect.RestrictNextTurn())
                    "sv06.5-038" -> add(Effect.TargetedDamage(100))
                    "me01-072", "me01-074" -> Unit
                    "swsh1-29", "swsh6-24" -> if (index == 1) add(Effect.DiscardAttackEnergy(2))
                    "swsh1-76" -> if (index == 1) add(Effect.RestrictNextTurn())
                    "swsh3.5-35" -> add(Effect.CoinDamage(4, 10))
                    "swsh3.5-36", "swsh8-102" -> add(Effect.Recoil(20))
                    "swsh3.5-7" -> if (index == 0) add(Effect.Draw(1))
                    "swsh4-43" -> if (index == 0) add(Effect.EnergyFromDeck(2, EnergyType.LIGHTNING)) else add(Effect.DiscardAttackEnergy(0, all = true))
                    "swsh6-23" -> add(Effect.EnergyFromDeck(1, EnergyType.FIRE))
                    "swsh8-109" -> add(Effect.TargetedDamage(30))
                    "swsh8-171" -> if (index == 0) add(Effect.CoinDamage(2, 40))
                    "swsh8-43" -> add(Effect.DiscardAttackEnergy(2, EnergyType.FIRE))
                    "swshp-SWSH065" -> if (index == 0) add(Effect.Draw(3)) else add(Effect.SupporterBonus(80))
                    "swshp-SWSH193" -> add(Effect.DiscardAttackEnergy(2, EnergyType.DARKNESS))
                    else -> if (a.string("effect") != null) add(Effect.Unsupported(a.string("effect")!!))
                }
            }
            AttackDefinition(attackId, translated.string("name")!!, a.array("cost").map { type(it.jsonPrimitive.content) }, effects, translated.string("effect").orEmpty())
        }
        val abilities = en.array("abilities").mapIndexed { index, value ->
            val original = value.jsonObject
            val a = pt.array("abilities").getOrNull(index)?.jsonObject ?: original
            val abilityId = "$id-ability$index"
            val base = AbilityDefinition(abilityId, a.string("name")!!, a.string("effect").orEmpty())
            when (id) {
                "me01-074" -> base.copy(draw = 3, discardEnergyType = EnergyType.FIGHTING, requiresInPlay = "me01-075", usageKey = "lunar-cycle")
                "me01-073" -> base.copy(onEvolveSwitch = true)
                "sv06.5-038" -> base.copy(draw = 3, requiresPreviousKnockout = true, usageKey = "flip-the-script")
                "sv06-141" -> base.copy(attackDiscountPerOpponentPrize = true)
                else -> base
            }
        }
        val trainerType = when (en.string("trainerType")) {
            "Item" -> TrainerType.ITEM; "Supporter" -> TrainerType.SUPPORTER
            "Stadium" -> TrainerType.STADIUM; "Tool" -> TrainerType.TOOL; else -> null
        }
        val trainer = when (id) {
            "me01-116" -> TrainerEffect.Search(listOf(CardFilter.BASIC_FIGHTING_OR_ENERGY))
            "me01-131" -> TrainerEffect.Search(listOf(CardFilter.POKEMON), discardCost = 2)
            "sv06-163" -> TrainerEffect.Search(listOf(CardFilter.ITEM, CardFilter.TOOL, CardFilter.SUPPORTER, CardFilter.STADIUM), discardCost = 3)
            "me01-119" -> TrainerEffect.ShuffleHandDraw(6, 8)
            "sv09-149" -> TrainerEffect.DrawTo(6, discardCost = 1)
            "sv02-172" -> TrainerEffect.Switch(opponent = true)
            "me01-130" -> TrainerEffect.Switch(opponent = false)
            "sv08-187" -> TrainerEffect.Switch(opponent = false, drawTo = 5)
            "sv06.5-061" -> TrainerEffect.Recover
            "me01-124" -> TrainerEffect.DamageBoost(EnergyType.FIGHTING, 30)
            "sv10.5b-079" -> TrainerEffect.RetreatReduction(2)
            "sv08-177" -> TrainerEffect.HpModifier(Stage.STAGE_TWO, -30)
            "swsh1-160" -> TrainerEffect.RecoverEnergy(2)
            "swsh1-164" -> TrainerEffect.GreatBall
            "swsh1-165" -> TrainerEffect.DrawCards(3)
            "swsh1-175" -> TrainerEffect.CoinSwitch
            "swsh1-177" -> TrainerEffect.HealPokemon(30)
            "swsh1-183" -> TrainerEffect.Switch(opponent = false)
            "swsh2-154" -> TrainerEffect.Switch(opponent = true)
            "swsh2-167" -> TrainerEffect.Sonia
            "swsh3-165" -> TrainerEffect.Search(listOf(CardFilter.ENERGY, CardFilter.DARKNESS_POKEMON))
            "swsh5-124" -> TrainerEffect.RecoverEnergy(5, toDeck = true)
            "swsh8-226" -> TrainerEffect.DrawCards(2, coinBonus = 2)
            "swsh8-228" -> TrainerEffect.HealPokemon(70, activeOnly = true)
            "swsh8-240" -> TrainerEffect.ShuffleHandDraw(5, 5)
            else -> null
        }
        val stage = when (en.string("stage")) { "Stage1" -> Stage.STAGE_ONE; "Stage2" -> Stage.STAGE_TWO; else -> Stage.BASIC }
        val setCode = when (en["set"]!!.jsonObject.string("id")) { "me01" -> "MEG"; "sv06.5" -> "SFA"; "sv06" -> "TWM"; "sv02" -> "PAL"; "sv08" -> "SSP"; "sv09" -> "JTG"; "sv10.5b" -> "BLK"; "sv01" -> "SVI"; "swsh1" -> "SSH"; "swsh2" -> "RCL"; "swsh3" -> "DAA"; "swsh3.5" -> "CPA"; "swsh4" -> "VIV"; "swsh5" -> "BST"; "swsh6" -> "CRE"; "swsh8" -> "FST"; "swshp" -> "SWSHP"; "swsh12.5" -> "CRZ"; else -> "SVE" }
        val prizes = when { id == "me01-077" -> 3; en.string("suffix") in listOf("ex", "V") -> 2; else -> 1 }
        return CardDefinition(id, if (id == "sv01-258") "Energia de Luta Básica" else pt.string("name")!!, setCode, en.string("localId")!!,
            category = if (pokemon) Category.POKEMON else if (energy) Category.ENERGY else Category.TRAINER,
            basicEnergy = energy && CatalogRuleCompiler.basicEnergy(en),
            stage = stage, evolvesFrom = when (en.string("evolveFrom")) { "Riolu" -> "me01-076"; "Makuhita" -> "me01-072"; else -> en.string("evolveFrom")?.let { academyNames[it] } },
            canonicalName = en.string("name")!!, evolvesFromName = en.string("evolveFrom"),
            hp = en.string("hp")?.toIntOrNull() ?: 0,
            type = en.array("types").firstOrNull()?.jsonPrimitive?.content?.let(::type) ?: when (id) { "swsh12.5-153" -> EnergyType.FIRE; "swsh12.5-155" -> EnergyType.LIGHTNING; "swsh12.5-158" -> EnergyType.DARKNESS; else -> EnergyType.FIGHTING },
            weakness = en.array("weaknesses").firstOrNull()?.jsonObject?.string("type")?.let(::type),
            resistance = en.array("resistances").firstOrNull()?.jsonObject?.string("type")?.let(::type),
            retreatCost = en.string("retreat")?.toIntOrNull() ?: 0, prizes = prizes, attacks = attacks,
            verified = true, source = "TCGdex • https://api.tcgdex.net/v2/pt/cards/$id • importado em 22/10/2026",
            artAsset = "cards/$id.webp", text = pt.string("effect").orEmpty(), trainerType = trainerType,
            trainerEffect = trainer, abilities = abilities, aceSpec = id == "sv06-163",
            ruleBox = when (prizes) { 3 -> "Quando seu Pokémon Megaevolução ex é Nocauteado, seu oponente pega 3 cartas de Prêmio."; 2 -> "Quando seu Pokémon ${en.string("suffix")} é Nocauteado, seu oponente pega 2 cartas de Prêmio."; else -> "" })
    }
}
