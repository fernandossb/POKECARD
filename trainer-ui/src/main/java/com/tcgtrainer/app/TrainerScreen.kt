package com.tcgtrainer.app

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tcgtrainer.engine.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun TrainerScreen(vm: GameViewModel = viewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle(); val state = ui.game
    val deckUi by vm.deckUi.collectAsStateWithLifecycle()
    val availableDecks = remember(deckUi.saved) { vm.playableDecks() }
    var managingDecks by remember { mutableStateOf(false) }
    var inspected by remember { mutableStateOf<CardInstance?>(null) }
    var textDialog by remember { mutableStateOf<String?>(null) }
    var deckOpen by remember { mutableStateOf(false) }
    var discardOwner by remember { mutableStateOf<Int?>(null) }
    var confirmAttack by remember { mutableStateOf<GameAction.Attack?>(null) }
    var confirmEnd by remember { mutableStateOf(false) }
    var resolveOpen by remember { mutableStateOf(true) }
    var prizesOpen by remember { mutableStateOf(true) }
    var selectedDeck by remember { mutableStateOf("lucario") }
    var panelsReady by remember { mutableStateOf(true) }
    val presentation = LocalContext.current.getSharedPreferences("battle-presentation", 0)
    val actor = state?.let(vm.engine::actingPlayer) ?: 0
    LaunchedEffect(state?.resolution) { resolveOpen = true }
    LaunchedEffect(state?.phase) { if (state?.phase == Phase.SELECT_PRIZES) prizesOpen = true }
    LaunchedEffect(state?.gameId, state?.history?.size) {
        val last = state?.history?.lastOrNull()
        val pace = AnimationPace.entries.getOrElse(presentation.getInt("pace", 0)) { AnimationPace.NORMAL }
        panelsReady = false
        val duration = if (last?.action is GameAction.Attack || last?.events?.any { it.type == "PokemonKnockedOut" } == true) pace.duration+260 else if (last?.action is GameAction.PlayTrainer) pace.duration else 0
        kotlinx.coroutines.delay(if (pace == AnimationPace.OFF) 0L else duration.toLong())
        panelsReady = true
    }
    LaunchedEffect(state?.gameId, state?.phase) { if (state?.phase == Phase.GAME_OVER) textDialog = TrainingReview.describe(state) }
    Surface(Modifier.fillMaxSize(), color = Color(0xFF080F1C)) {
        if (state == null && managingDecks) DeckManager(vm, { managingDecks = false }, { selectedDeck = it; managingDecks = false })
        else if (state == null) HomeScreen(ui, vm, selectedDeck, { selectedDeck = it }, availableDecks, { managingDecks = true }, { deckOpen = true }, { textDialog = it })
        else BattleScene(
            state = state, viewer = 0, reveal = true,
            busy = ui.busy, error = ui.message, engine = vm.engine,
            dispatch = vm::actSequence, inspect = { inspected = it }, discard = { discardOwner = it },
            prizes = { prizesOpen = true }, resolve = { resolveOpen = true }, endTurn = { confirmEnd = true },
            menu = vm::menu,
            history = { textDialog = state.history.joinToString("\n\n") { "T${it.turn} • ${if (it.player == 0) "Você" else "Máquina"}\n${it.events.joinToString("\n") { event -> event.message }}" }.ifEmpty { "Sem ações registradas." } },
            undo = { vm.undo(); inspected = null }, deck = { deckOpen = true }
        )
    }
    if (state != null) {
        inspected?.let { card -> CardDetailSheet(card, state, vm.engine, ui.busy, onDismiss = { inspected = null }, dispatch = { vm.act(it); inspected = null }, attack = { confirmAttack = it }) }
        if (actor == 0 && state.phase == Phase.RESOLVE_CHOICE && resolveOpen && inspected == null && panelsReady) ChoiceSheet(state, vm.engine, ui.busy, onDismiss = { resolveOpen = false }, confirm = { vm.act(it) })
        if (actor == 0 && state.phase == Phase.SELECT_PRIZES && prizesOpen && inspected == null && panelsReady) PrizeSheet(state, ui.busy, { prizesOpen = false }, { vm.act(GameAction.TakePrize(it)) })
        discardOwner?.let { owner -> DiscardSheet(state.players[owner].discard, vm.engine, { discardOwner = null }, { inspected = it; discardOwner = null }) }
    }
    if (deckOpen) {
        val selected = if (state != null && state.deckLists.size == 2) TrainingDeck(state.humanDeckId, state.deckNames.getOrElse(0) { "Seu deck" }, "Lista desta partida", state.deckLists[0].first(), state.deckLists[0],
            state.deckFormats.getOrElse(0) { availableDecks.firstOrNull { it.id == state.humanDeckId }?.format ?: DeckFormat.TRAINING })
            else availableDecks.firstOrNull { it.id == (state?.humanDeckId ?: selectedDeck) } ?: TrainingDecks.all.first()
        DeckSheet(vm.engine, { deckOpen = false }, selected)
    }
    textDialog?.let { text -> AlertDialog(onDismissRequest = { textDialog = null }, text = { Text(text, Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) }, confirmButton = { TextButton(onClick = { textDialog = null }) { Text("FECHAR") } }) }
    confirmAttack?.let { action ->
        val preview = state?.let { vm.engine.preview(it, action.attackId, action.targetId) }
        AlertDialog(onDismissRequest = { confirmAttack = null }, title = { Text("Confirmar ataque") }, text = {
            Text(if (preview?.uncertain == true) "Dano por moedas: ${preview.minDamage}–${preview.maxDamage}.\nMédia estimada: ${preview.damage}. O resultado só será sorteado ao atacar.\n\nOs demais efeitos do ataque serão resolvidos em seguida."
                else "Dano previsto: ${preview?.damage}\nHP efetivo: ${preview?.effectiveHp}\nDano atual: ${preview?.currentDamage}\nApós o ataque: ${preview?.remainingHp} HP\n${if (preview?.knockout == true) "NOCAUTE · ${preview.prizes} Prêmio(s)" else "NÃO É NOCAUTE"}\n\nOs demais efeitos do ataque serão resolvidos em seguida.")
        }, confirmButton = { TextButton(onClick = { vm.act(action); confirmAttack = null; inspected = null }, enabled = !ui.busy) { Text("ATACAR") } }, dismissButton = { TextButton(onClick = { confirmAttack = null }) { Text("VOLTAR") } })
    }
    if (confirmEnd) AlertDialog(onDismissRequest = { confirmEnd = false }, title = { Text("Encerrar seu turno?") }, text = { Text("A máquina jogará em seguida.") }, confirmButton = { TextButton(onClick = { vm.act(GameAction.EndTurn); confirmEnd = false }, enabled = !ui.busy && actor == 0) { Text("ENCERRAR") } }, dismissButton = { TextButton(onClick = { confirmEnd = false }) { Text("VOLTAR") } })
}

@Composable private fun HomeScreen(ui: UiState, vm: GameViewModel, selectedDeck: String, selectDeck: (String) -> Unit, availableDecks: List<TrainingDeck>, manage: () -> Unit, deck: () -> Unit, info: (String) -> Unit) {
    var seed by remember { mutableStateOf(java.security.SecureRandom().nextLong().toString()) }; var training by remember { mutableStateOf(true) }
    var options by remember { mutableStateOf(false) }
    var cpuDeckId by remember { mutableStateOf("random") }
    val chosen = availableDecks.firstOrNull { it.id == selectedDeck } ?: availableDecks.first()
    val chosenValidation by produceState<DeckValidation?>(initialValue = null, chosen) {
        value = withContext(Dispatchers.Default) { DeckValidator.validate(chosen) }
    }
    val cpuDeckOptions by produceState(initialValue = availableDecks.filter { it.cards.size == 60 }, availableDecks, chosen.format, training) {
        value = withContext(Dispatchers.Default) {
            val fullDecks = availableDecks.filter { it.cards.size == 60 && DeckValidator.validate(it).playable }
            if (!training && chosen.format == DeckFormat.STANDARD) fullDecks.filter { cpu ->
                DeckValidator.validate(SavedDeck(cpu.id, cpu.name, DeckFormat.STANDARD, cpu.cards.groupingBy { it }.eachCount())).playable
            } else fullDecks
        }
    }
    val randomCpuDeck = TrainingDeck("random", "Aleatório", "A máquina escolhe um deck elegível a cada partida.", cpuDeckOptions.firstOrNull()?.cover ?: chosen.cover, emptyList())
    val cpuDeckMenu = listOf(randomCpuDeck) + cpuDeckOptions
    val opponent = cpuDeckOptions.firstOrNull { it.id == cpuDeckId } ?: cpuDeckOptions.firstOrNull() ?: randomCpuDeck
    LaunchedEffect(availableDecks) {
        if (availableDecks.none { it.id == selectedDeck }) selectDeck(availableDecks.first().id)
    }
    LaunchedEffect(cpuDeckOptions.map { it.id }) {
        if (cpuDeckId != "random" && cpuDeckOptions.none { it.id == cpuDeckId }) cpuDeckId = "random"
    }
    Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF152D42), Color(0xFF080F1C)))).safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Spacer(Modifier.height(4.dp))
        Text("SEU PRÓXIMO TURNO\nCOMEÇA AQUI.", color = Aqua, fontSize = 11.sp, letterSpacing = 3.sp)
        Text("TCG\nTRAINER", style = MaterialTheme.typography.displayMedium, lineHeight = 42.sp, fontWeight = FontWeight.Black)
        Text("Pratique. Entenda. Evolua.", color = Muted, fontSize = 15.sp)
        val activity = LocalContext.current as? android.app.Activity
        TextButton(onClick = { activity?.finish() }) { Text("‹ VOLTAR AO POKECARD") }
        Text("Coleção PokeCard · ${vm.owned.counts.values.sum()} cópias. Para atualizar, volte ao fichário e abra o jogo novamente.", color = Muted, fontSize = 11.sp)
        Row(Modifier.height(160.dp).fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            CardArt("me01-074", Modifier.width(84.dp).height(125.dp))
            CardArt(chosen.cover, Modifier.padding(horizontal = 8.dp).width(110.dp).height(154.dp))
            CardArt(opponent.cover, Modifier.width(84.dp).height(125.dp))
        }
        Surface(shape = RoundedCornerShape(16.dp), color = Color(0xFF192B3E), onClick = deck) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("BARALHO SELECIONADO", fontSize = 9.sp, color = Aqua, letterSpacing = 2.sp)
                Text(chosen.name, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                Text("${chosen.cards.size} cartas · lista de treino", color = Muted, fontSize = 11.sp)
                Text("VER CARTAS  ›", color = Aqua, fontSize = 10.sp)
            }
        }
        OutlinedButton(onClick = manage, modifier = Modifier.fillMaxWidth()) { Text("MEUS DECKS · CRIAR E EDITAR") }
        val updateContext = LocalContext.current
        OutlinedButton(onClick = {
            val intent = android.content.Intent().setClassName(updateContext.packageName, "br.com.fichariopokemon.pokedex.MainActivity")
                .addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("checkTrainerUpdate", true)
            updateContext.startActivity(intent)
        }, modifier = Modifier.fillMaxWidth()) { Text("VERIFICAR ATUALIZAÇÕES DE CARTAS E REGRAS") }
        Text("Novas regras automáticas chegam nas versões revisadas do aplicativo. A verificação usa as versões oficiais publicadas do PokeCard.", color = Muted, fontSize = 11.sp)
        DeckSelector("SEU BARALHO", chosen.id, availableDecks, selectDeck)
        Text(chosen.strategy, color = Muted, fontSize = 11.sp)
        if (!training) vm.ownershipMessage(chosen.cards)?.let { Text(it, color = Coral, fontSize = 11.sp) }
        if (training) Text("Treino: 14 a 60 cartas, incluindo cartas ainda não possuídas. Listas incompletas alteram as chances de compra. Desative ‘Só minhas cartas’ no editor para planejar compras.", color = Muted, fontSize = 11.sp)
        DeckSelector("BARALHO DA MÁQUINA", cpuDeckId, cpuDeckMenu) { cpuDeckId = it }
        Text(if (cpuDeckId == "random") "Baralho aleatório · a máquina escolhe a cada partida." else "A máquina usará ${opponent.name}.", color = Muted, fontSize = 11.sp)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text(if (training) "Treinamento solo" else "Partida contra a máquina", fontWeight = FontWeight.SemiBold); Text(if (training) "Desfaz sua jogada e a resposta da CPU" else "Sem desfazer", color = Muted, fontSize = 11.sp) }
            Switch(training, { training = it })
        }
        TextButton(onClick = { options = !options }, contentPadding = PaddingValues(0.dp)) { Text("${if (options) "−" else "+"} OPÇÕES DA PARTIDA · SEED $seed", fontSize = 10.sp) }
        if (options) OutlinedTextField(seed, { seed = it }, label = { Text("Seed · abertura reproduzível") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Button(onClick = { vm.start(seed.toLong(), if (training) Mode.TRAINING else Mode.MATCH, selectedDeck, cpuDeckId); seed = java.security.SecureRandom().nextLong().toString() }, enabled = !ui.busy && seed.toLongOrNull() != null && (training || (chosenValidation?.playable == true && vm.owned.allows(chosen.cards) && chosen.cards.size == 60)), modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(12.dp)) { Text(if (ui.busy) "PREPARANDO PARTIDA…" else "JOGAR CONTRA A MÁQUINA", fontWeight = FontWeight.Bold, letterSpacing = 1.sp) }
        OutlinedButton(onClick = vm::resume, enabled = ui.canContinue && !ui.busy, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(12.dp)) { Text("CONTINUAR PARTIDA") }
        TextButton(onClick = { info("Jogo exclusivamente para você contra a máquina. Crie e salve decks em MEUS DECKS. Toque em TREINAR para usar qualquer lista compatível, mesmo sem possuir as cartas e mesmo que ela não esteja legal no formato selecionado. O treino aceita listas de 14 a 60 cartas; partidas normais exigem 60 cartas válidas e posse completa.\n\nCartas sem efeitos implementados continuam como rascunhos. Padrão 2026 e Academia 2022 são categorias separadas. Treino livre permite misturar cartas implementadas. A CPU escolhe um deck válido e não vê sua mão, Prêmios ocultos ou a ordem dos decks.") }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("COMO JOGAR", fontSize = 11.sp) }
        Text("1 JOGADOR × MÁQUINA  ·  PARTIDAS OFFLINE", Modifier.fillMaxWidth(), color = Muted, fontSize = 9.sp)
        ui.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
@Composable private fun DeckSelector(label: String, selected: String, availableDecks: List<TrainingDeck>, choose: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, color = Aqua, fontSize = 9.sp, letterSpacing = 1.sp)
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(9.dp)) {
                Text(availableDecks.first { it.id == selected }.name, Modifier.weight(1f), fontSize = 13.sp)
                Text("⌄", fontSize = 18.sp)
            }
            DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                availableDecks.forEach { entry -> DropdownMenuItem(text = {
                    Column { Text(entry.name, color = if (entry.id == selected) Aqua else Color.White); Text(entry.format.label(), color = Muted, fontSize = 10.sp) }
                }, onClick = { choose(entry.id); expanded = false }) }
            }
        }
    }
}
internal fun phaseLabel(phase: Phase) = when (phase) {
    Phase.CHOOSE_STARTER -> "Você venceu a moeda. Quem começa?"
    Phase.SELECT_ACTIVE -> "Escolha seu Ativo na mão"
    Phase.SELECT_BENCH -> "Adicione Básicos ao Banco"
    Phase.MULLIGAN_BONUS -> "Compras extras por mulligan"
    Phase.BONUS_BENCH -> "Básicos do bônus podem ir ao Banco"
    Phase.MAIN -> "Fase principal"
    Phase.RESOLVE_CHOICE -> "Escolha pendente"
    Phase.SELECT_PRIZES -> "Escolha seus Prêmios"
    Phase.PROMOTE -> "Promova um Pokémon do Banco"
    Phase.GAME_OVER -> "Partida encerrada"
}

