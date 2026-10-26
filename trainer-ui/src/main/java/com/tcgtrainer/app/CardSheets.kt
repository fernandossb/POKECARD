package com.tcgtrainer.app

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tcgtrainer.engine.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun CardDetailSheet(card: CardInstance, state: GameState, engine: RulesEngine, busy: Boolean,
    onDismiss: () -> Unit, dispatch: (GameAction) -> Unit, attack: (GameAction.Attack) -> Unit) {
    val d = engine.definition(card); val actor = 0; val own = state.players[actor]
    val humanTurn = engine.actingPlayer(state) == 0
    val pokemon = state.players.flatMap { it.pokemon() }.find { it.top.id == card.id }
    val ownPokemon = own.pokemon().find { it.top.id == card.id }
    val inHand = own.hand.any { it.id == card.id }
    val legal = (if (humanTurn && state.phase != Phase.RESOLVE_CHOICE) engine.legalActions(state) else emptyList()).filter { a -> when (a) {
        is GameAction.ChooseActive -> a.cardId == card.id
        is GameAction.PlayBasic -> a.cardId == card.id
        is GameAction.AttachEnergy -> a.cardId == card.id
        is GameAction.Evolve -> a.cardId == card.id
        is GameAction.PlayTrainer -> a.cardId == card.id
        is GameAction.UseAbility -> a.pokemonId == ownPokemon?.id
        is GameAction.Promote -> a.pokemonId == ownPokemon?.id
        is GameAction.Retreat -> own.active?.top?.id == card.id
        else -> false
    } }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().heightIn(max = 730.dp).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 30.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CardArt(d, Modifier.height(300.dp).fillMaxWidth())
            Text(d.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("${d.set} ${d.number} · ${d.variant}", color = Aqua, fontSize = 11.sp)
            if (pokemon != null) {
                val hp = engine.effects.effectiveHp(pokemon, state)
                Text("${(hp-pokemon.damage).coerceAtLeast(0)} / $hp HP  ·  Dano: ${pokemon.damage}", fontWeight = FontWeight.Bold)
                Text("Energias: ${pokemon.energies.groupingBy { engine.definition(it).type }.eachCount().entries.joinToString { "${energyLabel(it.key)} ×${it.value}" }.ifEmpty { "nenhuma" }}", fontSize = 12.sp)
                Text("Ferramenta: ${pokemon.tool?.let { engine.definition(it).name } ?: "nenhuma"} · Condição: nenhuma", fontSize = 12.sp)
            }
            if (d.text.isNotEmpty()) Text(d.text, fontSize = 13.sp)
            if (inHand && humanTurn) {
                val candidates: List<GameAction> = when (d.category) {
                    Category.TRAINER -> listOf(GameAction.PlayTrainer(card.id))
                    Category.ENERGY -> own.pokemon().map { GameAction.AttachEnergy(card.id, it.id) }
                    Category.POKEMON -> if (d.stage == Stage.BASIC) listOf(if (state.phase == Phase.SELECT_ACTIVE) GameAction.ChooseActive(card.id) else GameAction.PlayBasic(card.id))
                        else own.pokemon().map { GameAction.Evolve(card.id, it.id) }
                }
                if (legal.isEmpty()) candidates.mapNotNull { (engine.validate(state, it) as? Legality.Illegal)?.reason }.distinct().forEach { Text(it, color = Coral, fontSize = 12.sp) }
            }
            legal.forEach { action -> Button(onClick = { dispatch(action) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(actionLabel(action, state, engine), fontSize = 12.sp) } }
            d.abilities.forEach { ability ->
                HorizontalDivider()
                Text("HABILIDADE · ${ability.name}", color = Aqua, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text(ability.text, fontSize = 12.sp)
                if (humanTurn && ownPokemon != null && !ability.attackDiscountPerOpponentPrize && !ability.onEvolveSwitch) {
                    val legality = engine.validate(state, GameAction.UseAbility(ownPokemon.id, ability.id))
                    if (legality is Legality.Illegal) Text(legality.reason, color = Coral, fontSize = 11.sp)
                }
            }
            d.attacks.forEach { definition ->
                HorizontalDivider()
                Text(definition.name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text("Custo: ${definition.cost.joinToString { energyLabel(it) }}", color = Muted, fontSize = 12.sp)
                if (definition.text.isNotEmpty()) Text(definition.text, fontSize = 12.sp)
                val targeted = definition.effects.any { it is Effect.TargetedDamage }
                val benchOnly = definition.effects.filterIsInstance<Effect.TargetedDamage>().any { it.benchedOnly }
                val targets = if (benchOnly) state.players[1-actor].bench else state.players[1-actor].pokemon()
                val choices = if (targeted) targets.map { GameAction.Attack(definition.id, it.id) } else listOf(GameAction.Attack(definition.id))
                if (humanTurn && own.active?.top?.id == card.id && state.turn > 0) choices.forEach { action ->
                    val legality = engine.validate(state, action)
                    val preview = engine.preview(state, action.attackId, action.targetId)
                    if (preview != null) Text(if (preview.uncertain) "${preview.minDamage}–${preview.maxDamage} de dano por moedas · média ${preview.damage}" else "${preview.damage} de dano · ${preview.remainingHp} HP após · ${if (preview.knockout) "KO / ${preview.prizes} Prêmio(s)" else "não é KO"}", color = Aqua, fontSize = 12.sp)
                    Button(onClick = { attack(action) }, enabled = legality == Legality.Legal && !busy, modifier = Modifier.fillMaxWidth()) { Text(if (targeted) "ATACAR → ${targetName(action.targetId!!, state, engine)}" else "PREVER E ATACAR", fontSize = 11.sp) }
                    if (legality is Legality.Illegal) Text(legality.reason, color = Coral, fontSize = 11.sp)
                }
            }
            Text("HP impresso ${d.hp} · ${d.stage}\nFraqueza: ${d.weakness?.let(::energyLabel) ?: "—"} · Resistência: ${d.resistance?.let(::energyLabel) ?: "—"}\nRecuo impresso: ${d.retreatCost} · Prêmios: ${d.prizes}", color = Muted, fontSize = 11.sp)
            if (d.ruleBox.isNotEmpty()) Text(d.ruleBox, color = Coral, fontSize = 12.sp)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun ChoiceSheet(state: GameState, engine: RulesEngine, busy: Boolean, onDismiss: () -> Unit, confirm: (GameAction.ChooseCards) -> Unit) {
    val choice = state.resolution?.choices?.firstOrNull() ?: return
    var selected by remember(choice) { mutableStateOf<List<String>>(emptyList()) }
    val player = state.players[state.currentPlayer]
    fun candidate(id: String): CardInstance? = (player.hand + player.deck + player.discard + player.pokemon().flatMap { it.energies }).find { it.id == id }
        ?: state.players[1-state.currentPlayer].deck.find { it.id == id }
        ?: state.players.flatMap { it.pokemon() }.find { it.id == id }?.top
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(choice.title, style = MaterialTheme.typography.titleLarge)
            Text("Selecione ${choice.min}–${choice.max} · ${selected.size} selecionada(s)", color = Aqua, fontSize = 12.sp)
            if (choice.candidates.isEmpty()) Text("Não há cartas elegíveis. Confirme para continuar.", color = Muted)
            LazyVerticalGrid(columns = GridCells.Fixed(3), modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items((choice.visibleCards + choice.candidates).distinct(), key = { it }) { id ->
                    val optionLabel = choice.optionLabels[id]
                    if (optionLabel != null) {
                        Box(Modifier.fillMaxWidth().border(if (id in selected) 2.dp else 1.dp, if (id in selected) Aqua else Color(0xFF3A4D65), RoundedCornerShape(8.dp))
                            .clickable(enabled = !busy && id in choice.candidates) { selected = if (id in selected) selected-id else listOf(id) }
                            .padding(10.dp), contentAlignment = Alignment.CenterStart) {
                            Text(optionLabel, color = if (id in selected) Aqua else Color.White, fontSize = 12.sp)
                        }
                    } else candidate(id)?.let { card ->
                        Column(Modifier.border(if (id in selected) 3.dp else 1.dp, if (id in selected) Aqua else Color(0xFF3A4D65), RoundedCornerShape(8.dp)).clickable(enabled = !busy && id in choice.candidates) {
                            selected = if (id in selected) selected-id else if (choice.max == 1) listOf(id) else if (selected.size < choice.max) selected+id else selected
                        }.padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            CardArt(engine.definition(card), Modifier.fillMaxWidth().height(130.dp))
                            Text(engine.definition(card).name, fontSize = 10.sp, maxLines = 2)
                            if (choice.kind == ChoiceKind.ATTACK_KNOCKOUT_LOWEST_HP_TARGET) {
                                val owner = state.players.indexOfFirst { it.pokemon().any { pokemon -> pokemon.id == id } }
                                val target = state.players.getOrNull(owner)?.pokemon()?.firstOrNull { it.id == id }
                                val side = if (owner == state.currentPlayer) "Seu" else "Adversário"
                                val position = if (target?.id == state.players[owner].active?.id) "Ativo" else "Banco"
                                Text("$side · $position · ${engine.effects.effectiveHp(target!!, state) - target.damage} HP restantes", color = Aqua, fontSize = 9.sp)
                            }
                            if (choice.kind == ChoiceKind.ATTACK_REORDER_OPPONENT_TOP && id in selected)
                                Text("Posição ${selected.indexOf(id) + 1}", color = Aqua, fontSize = 9.sp)
                            Text("${engine.definition(card).set} ${engine.definition(card).number}", color = Muted, fontSize = 8.sp)
                            if (id !in choice.candidates) Text("Vista · não selecionável", color = Muted, fontSize = 8.sp)
                        }
                    }
                }
            }
            Button(onClick = { confirm(GameAction.ChooseCards(selected)) }, enabled = !busy && engine.validate(state, GameAction.ChooseCards(selected)) == Legality.Legal,
                modifier = Modifier.fillMaxWidth()) { Text(if (selected.isEmpty() && choice.min == 0) "CONCLUIR SEM SELECIONAR" else "CONFIRMAR SELEÇÃO") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun DiscardSheet(cards: List<CardInstance>, engine: RulesEngine, onDismiss: () -> Unit, inspect: (CardInstance) -> Unit) {
    var filter by remember { mutableStateOf<Category?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("DESCARTE · ${cards.size} cartas", style = MaterialTheme.typography.titleLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                (listOf<Category?>(null) + Category.entries).forEach { category -> FilterChip(selected = filter == category, onClick = { filter = category }, label = { Text(when (category) { null -> "Todas"; Category.POKEMON -> "Pokémon"; Category.ENERGY -> "Energia"; Category.TRAINER -> "Treinador" }, fontSize = 10.sp) }) }
            }
            LazyVerticalGrid(GridCells.Fixed(3), Modifier.fillMaxWidth().heightIn(max = 450.dp), horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                items(cards.filter { filter == null || engine.definition(it).category == filter }, key = { it.id }) { card ->
                    CardArt(engine.definition(card), Modifier.height(145.dp).clickable { inspect(card) })
                }
            }
        }
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun DeckSheet(engine: RulesEngine, onDismiss: () -> Unit, deck: TrainingDeck = TrainingDecks.get("lucario")) {
    val counts = deck.cards.groupingBy { it }.eachCount()
    var large by remember { mutableStateOf<CardDefinition?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 18.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(deck.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("LISTA DE TREINO · 60 CARTAS · ${counts.size} IMPRESSÕES", color = Aqua, fontSize = 10.sp)
            Text("${deck.format.label()} · Artes offline das impressões de referência. Energias Básicas podem usar impressão equivalente. Não reproduz a marca numerada da caixa.", color = Muted, fontSize = 11.sp)
            if (large != null) {
                CardArt(large!!, Modifier.fillMaxWidth().height(410.dp).clickable { large = null })
                TextButton(onClick = { large = null }) { Text("VOLTAR À LISTA") }
            } else LazyColumn(Modifier.heightIn(max = 530.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(counts.keys.toList(), key = { it }) { id ->
                    val d = engine.catalog.get(id)
                    Row(Modifier.fillMaxWidth().clickable { large = d }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CardArt(d, Modifier.size(62.dp, 86.dp))
                        Column(Modifier.weight(1f)) { Text(d.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold); Text("${d.set} ${d.number}", color = Muted, fontSize = 11.sp) }
                        Text("${counts[id]}×", fontWeight = FontWeight.Bold, color = Aqua, fontSize = 20.sp)
                    }
                }
            }
        }
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun PrizeSheet(state: GameState, busy: Boolean, onDismiss: () -> Unit, choose: (String) -> Unit) {
    val p = state.players[state.currentPlayer]
    val slots = p.prizePositions.ifEmpty { p.prizes.map { it.id } }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Escolha seus Prêmios", style = MaterialTheme.typography.titleLarge)
            Text("Faltam ${state.pendingPrizes} nesta resolução. As posições permanecem fixas.", color = Aqua, fontSize = 12.sp)
            repeat(2) { row -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                repeat(3) { column ->
                    val slot = row*3+column; val card = p.prizes.find { it.id == slots.getOrNull(slot) }
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (card != null) CardBack(Modifier.fillMaxWidth().height(126.dp).clickable(enabled = !busy) { choose(card.id) }, "PRÊMIO ${slot+1}")
                        else Box(Modifier.fillMaxWidth().height(126.dp).border(1.dp, Muted.copy(alpha = .2f), RoundedCornerShape(5.dp)), contentAlignment = Alignment.Center) { Text("Retirado", color = Muted, fontSize = 10.sp) }
                    }
                }
            } }
            Spacer(Modifier.height(12.dp))
        }
    }
}
private fun targetName(id: String, state: GameState, engine: RulesEngine): String = state.players.flatMap { it.pokemon() }.find { it.id == id }?.let { "${engine.definition(it.top).name} [${id.takeLast(3)}]" } ?: id
private fun actionLabel(action: GameAction, state: GameState, engine: RulesEngine): String = when (action) {
    is GameAction.ChooseActive -> "ESCOLHER COMO ATIVO"
    is GameAction.PlayBasic -> "COLOCAR NO BANCO"
    is GameAction.AttachEnergy -> "LIGAR → ${targetName(action.pokemonId, state, engine)}"
    is GameAction.Evolve -> "EVOLUIR → ${targetName(action.pokemonId, state, engine)}"
    is GameAction.PlayTrainer -> "JOGAR ${engine.definition(state.players[state.currentPlayer].hand.first { it.id == action.cardId }).name}"
    is GameAction.UseAbility -> "USAR HABILIDADE"
    is GameAction.Promote -> "PROMOVER AO ATIVO"
    is GameAction.Retreat -> "RECUAR → ${targetName(action.pokemonId, state, engine)} · descartar ${action.energyIds.size} Energia(s) [${action.energyIds.joinToString { it.takeLast(3) }}]"
    else -> action.toString()
}
internal fun energyLabel(type: EnergyType) = when (type) {
    EnergyType.FIGHTING -> "Luta"; EnergyType.FIRE -> "Fogo"; EnergyType.WATER -> "Água"; EnergyType.LIGHTNING -> "Elétrica"
    EnergyType.PSYCHIC -> "Psíquica"; EnergyType.GRASS -> "Planta"; EnergyType.DARKNESS -> "Escuridão"; EnergyType.METAL -> "Metal"; EnergyType.COLORLESS -> "Incolor"; EnergyType.DRAGON -> "Dragão"
}
