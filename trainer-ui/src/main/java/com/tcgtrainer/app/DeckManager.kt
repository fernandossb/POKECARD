package com.tcgtrainer.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tcgtrainer.engine.*
import java.util.UUID

@Composable internal fun DeckManager(vm: GameViewModel, close: () -> Unit, use: (String) -> Unit) {
    val ui by vm.deckUi.collectAsStateWithLifecycle()
    var original by remember { mutableStateOf<SavedDeck?>(null) }
    var draft by remember { mutableStateOf<SavedDeck?>(null) }
    var exitPrompt by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf<SavedDeck?>(null) }
    fun back() {
        if (ui.saving) return
        if (draft != null && draft != original) exitPrompt = true
        else if (draft != null) { draft = null; original = null }
        else close()
    }
    fun edit(deck: SavedDeck) { vm.clearDeckMessage(); original = deck; draft = deck }
    BackHandler(onBack = ::back)
    if (draft != null) DeckEditor(draft!!, vm.owned, { draft = it }, ui.saving, ui.message, ::back) {
        val current = draft!!
        vm.saveDeck(current) { original = current; draft = null }
    } else Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("MEUS DECKS", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            TextButton(onClick = close) { Text("VOLTAR") }
        }
        Text("Sua coleção PokeCard: ${vm.owned.counts.values.sum()} cópias. Os decks do jogo são salvos separadamente dos decks do fichário.", color = Muted, fontSize = 12.sp)
        Text("${CardLibrary.entries.count { it.implemented }} impressões jogáveis, sendo ${CardLibrary.entries.count { it.implemented && it.standard }} no Padrão. Você pode treinar com cartas que ainda não possui. As demais podem ser salvas em rascunhos enquanto aguardam seus efeitos.", color = Muted, fontSize = 11.sp)
        Button(onClick = { edit(SavedDeck("custom-${UUID.randomUUID()}", "Novo deck")) }, enabled = ui.loaded, modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) { Text("+ CRIAR DECK DO ZERO") }
        if (!ui.loaded) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
            item { Text("SALVOS · ${ui.saved.size}", color = Aqua, fontWeight = FontWeight.Bold) }
            if (ui.saved.isEmpty()) item { Text("Seus decks e rascunhos aparecerão aqui.", color = Muted, fontSize = 12.sp) }
            items(ui.saved, key = { it.id }) { deck ->
                val result = remember(deck) { DeckValidator.validate(deck) }
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(deck.name, fontWeight = FontWeight.Bold)
                        Text("${deck.counts.values.sum()}/60 · ${deck.format.label()} · ${if (result.playable) "PRONTO PARA JOGAR" else "RASCUNHO"}", color = if (result.playable) Aqua else Coral, fontSize = 10.sp)
                        vm.ownershipMessage(deck.cards())?.let { Text(it, color = Coral, fontSize = 10.sp) }
                        Row { TextButton(onClick = { edit(deck) }) { Text("EDITAR") }; TextButton(onClick = { use(deck.id) }, enabled = result.playable) { Text("USAR") }; TextButton(onClick = { delete = deck }) { Text("EXCLUIR", color = Coral) } }
                    }
                }
            }
            item { Text("BASES PARA DUPLICAR", color = Aqua, fontWeight = FontWeight.Bold) }
            items(TrainingDecks.all, key = { it.id }) { base ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CardArt(TrainerCatalog.get(base.cover), Modifier.size(45.dp, 64.dp))
                    Column(Modifier.weight(1f)) { Text(base.name, fontSize = 13.sp); Text(base.format.label(), color = Muted, fontSize = 10.sp) }
                    TextButton(onClick = { edit(SavedDeck("custom-${UUID.randomUUID()}", "${base.name} · cópia", base.format, base.cards.groupingBy { it }.eachCount())) }) { Text("COPIAR", fontSize = 11.sp) }
                }
            }
            ui.message?.let { text -> item { Text(text, color = Aqua, fontSize = 12.sp) } }
        }
    }
    if (exitPrompt) AlertDialog(onDismissRequest = { exitPrompt = false }, title = { Text("Alterações não salvas") }, text = { Text("Volte ao editor para salvar o deck ou descarte as alterações desta edição.") },
        confirmButton = { TextButton(onClick = { exitPrompt = false; draft = null; original = null }) { Text("DESCARTAR") } }, dismissButton = { TextButton(onClick = { exitPrompt = false }) { Text("VOLTAR AO EDITOR") } })
    delete?.let { deck -> AlertDialog(onDismissRequest = { delete = null }, title = { Text("Excluir ${deck.name}?") }, text = { Text("A partida salva continua disponível.") }, confirmButton = { TextButton(onClick = { vm.deleteDeck(deck.id); delete = null }) { Text("EXCLUIR") } }, dismissButton = { TextButton(onClick = { delete = null }) { Text("CANCELAR") } }) }
}

@Composable private fun DeckEditor(deck: SavedDeck, owned: OwnedCollection, change: (SavedDeck) -> Unit, saving: Boolean, message: String?, back: () -> Unit, save: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var onlyPlayable by remember { mutableStateOf(false) }
    var inDeck by remember { mutableStateOf(false) }
    var onlyOwned by remember { mutableStateOf(false) }
    var category by remember { mutableStateOf<Category?>(null) }
    var enlarged by remember { mutableStateOf<LibraryCard?>(null) }
    val validation = remember(deck) { DeckValidator.validate(deck) }
    val results = remember(query, onlyPlayable, deck.format, category, inDeck, deck.counts, onlyOwned) {
        val found = if (inDeck) deck.counts.keys.mapNotNull { CardLibrary.byId[it] }.filter { query.isBlank() || normalize(query) in it.searchKey }
        else CardLibrary.search(query, deck.format, onlyPlayable, category)
        if (onlyOwned && !inDeck) found.filter { owned.quantity(it.id) > 0 } else found
    }
    val total = deck.counts.values.sum()
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("EDITOR DE DECK", Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 16.sp)
            TextButton(onClick = back, enabled = !saving) { Text("VOLTAR") }
        }
        OutlinedTextField(deck.name, { change(deck.copy(name = it.take(80))) }, label = { Text("Nome do deck") }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !saving)
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            DeckFormat.entries.forEach { format -> FilterChip(deck.format == format, onClick = { change(deck.copy(format = format)) }, enabled = !saving,
                label = { Text(format.label(), fontSize = 10.sp) }) }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("$total / 60 CARTAS", color = if (validation.playable) Aqua else Coral, fontWeight = FontWeight.Black)
                Text(Category.entries.joinToString(" · ") { c -> "${when(c) { Category.POKEMON -> "Pokémon"; Category.TRAINER -> "Trein."; Category.ENERGY -> "Energia" }} ${deck.counts.entries.sumOf { if (CardLibrary.byId[it.key]?.category == c) it.value else 0 }}" }, color = Muted, fontSize = 10.sp)
            }
            Button(onClick = save, enabled = !saving && deck.name.isNotBlank()) { Text(if (saving) "SALVANDO…" else if (validation.playable) "SALVAR DECK" else "SALVAR RASCUNHO", fontSize = 10.sp) }
        }
        if (validation.errors.isNotEmpty() || validation.unsupported.isNotEmpty()) Text(
            (validation.errors.take(2) + if (validation.unsupported.isNotEmpty()) listOf("${validation.unsupported.size} impressão(ões) com efeitos ainda não implementados: salve como rascunho.") else emptyList()).joinToString("\n"), color = Coral, fontSize = 10.sp)
        else Text("Pronto para treinar. Partida normal exige 60 cartas e posse.", color = Aqua, fontSize = 11.sp)
        owned.message(deck.cards())?.let { Text(it, color = Coral, fontSize = 10.sp, maxLines = 3) }
        OutlinedTextField(query, { query = it }, label = { Text("Buscar nome, coleção, número ou ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(inDeck, onClick = { inDeck = !inDeck }, label = { Text("NO DECK", fontSize = 10.sp) })
            Checkbox(onlyPlayable, { onlyPlayable = it })
            Text("Só implementadas", fontSize = 11.sp)
        }
        FilterChip(onlyOwned, onClick = { onlyOwned = !onlyOwned }, label = { Text("SÓ MINHAS CARTAS · POKECARD", fontSize = 10.sp) })
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            (listOf<Category?>(null) + Category.entries).forEach { c -> FilterChip(category == c, onClick = { category = c }, label = { Text(when (c) { null -> "Todas"; Category.POKEMON -> "Pokémon"; Category.TRAINER -> "Trein."; Category.ENERGY -> "Energia" }, fontSize = 10.sp) }) }
        }
        Text("${results.size} impressões · catálogo ${CardLibrary.fetchedAt.take(10)} · textos extras EN", color = Muted, fontSize = 9.sp)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
            items(results, key = { it.id }) { card ->
                val count = deck.counts[card.id] ?: 0
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    CardArt(card.displayCard(), Modifier.size(43.dp, 60.dp).clickable { enlarged = card })
                    Column(Modifier.weight(1f).clickable { enlarged = card }) {
                        Text(card.name, maxLines = 2, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text("${card.set} ${card.number} · ${card.regulation ?: "—"} · ${if (card.implemented) "JOGÁVEL" else "CATÁLOGO"}", color = if (card.implemented) Aqua else Muted, fontSize = 9.sp)
                        Text("Possui ${owned.quantity(card.id)} · disponível ${(owned.quantity(card.id) - count).coerceAtLeast(0)}", color = if (count <= owned.quantity(card.id)) Muted else Coral, fontSize = 10.sp)
                    }
                    TextButton(onClick = { change(deck.copy(counts = deck.counts.toMutableMap().apply { if (count <= 1) remove(card.id) else put(card.id, count-1) })) }, enabled = count > 0 && !saving, contentPadding = PaddingValues(0.dp), modifier = Modifier.width(30.dp)) { Text("−", fontSize = 22.sp) }
                    Text("$count", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    TextButton(onClick = { change(deck.copy(counts = deck.counts + (card.id to count+1))) }, enabled = count < 60 && !saving, contentPadding = PaddingValues(0.dp), modifier = Modifier.width(30.dp)) { Text("+", fontSize = 22.sp) }
                }
            }
            if (results.isEmpty()) item { Text("Nenhuma carta neste filtro. Altere a busca, a categoria ou o formato.", color = Muted, fontSize = 12.sp) }
        }
        if (message != null) Text(message, color = Coral, fontSize = 10.sp)
    }
    enlarged?.let { card -> AlertDialog(onDismissRequest = { enlarged = null }, title = { Text(card.name) }, text = {
        Column(Modifier.heightIn(max = 540.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CardArt(card.displayCard(), Modifier.fillMaxWidth().height(260.dp))
            Text("${card.set} ${card.number} · marca ${card.regulation ?: "sem marca informada"}")
            Text(if (card.standard) "Padrão: elegível no snapshot do catálogo." else "Não confirmada no Padrão atual.", color = Aqua, fontSize = 11.sp)
            Text(if (card.implemented) "Efeitos implementados no motor." else "Somente catálogo/rascunho. Ainda não liberada para partidas.", color = Coral, fontSize = 11.sp)
            Text(card.text, fontSize = 12.sp)
            Text("Artes adicionais usam internet no primeiro acesso e ficam em cache. Dados: TCGdex; rotação revisada em 22/10/2026.", color = Muted, fontSize = 10.sp)
        }
    }, confirmButton = { TextButton(onClick = { enlarged = null }) { Text("FECHAR") } }) }
}
