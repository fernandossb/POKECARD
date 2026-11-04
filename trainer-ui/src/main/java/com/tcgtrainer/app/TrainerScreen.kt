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
@Composable internal fun TrainerScreen(vm: GameViewModel = viewModel(), initialDeckId: String? = null) {
    val ui by vm.ui.collectAsStateWithLifecycle(); val state = ui.game
    val deckUi by vm.deckUi.collectAsStateWithLifecycle()
    val binderDecks by vm.binderDecks.collectAsStateWithLifecycle()
    val availableDecks = remember(deckUi.saved, binderDecks) { vm.playableDecks() }
    var inspected by remember { mutableStateOf<CardInstance?>(null) }
    var textDialog by remember { mutableStateOf<String?>(null) }
    var deckOpen by remember { mutableStateOf(false) }
    var discardOwner by remember { mutableStateOf<Int?>(null) }
    var confirmAttack by remember { mutableStateOf<GameAction.Attack?>(null) }
    var confirmEnd by remember { mutableStateOf(false) }
    var resolveOpen by remember { mutableStateOf(true) }
    var prizesOpen by remember { mutableStateOf(true) }
    var selectedDeck by remember { mutableStateOf(initialDeckId ?: "lucario") }
    LaunchedEffect(initialDeckId, availableDecks) {
        if (!initialDeckId.isNullOrBlank() && availableDecks.any { it.id == initialDeckId }) {
            selectedDeck = initialDeckId
        }
    }
    var panelsReady by remember { mutableStateOf(true) }
    val presentation = LocalContext.current.getSharedPreferences("battle-presentation", 0)
    val actor = state?.let(vm.engine::actingPlayer) ?: 0
    LaunchedEffect(state?.resolution) { resolveOpen = true }
    LaunchedEffect(state?.phase) { if (state?.phase == Phase.SELECT_PRIZES) prizesOpen = true }
    LaunchedEffect(state?.gameId, state?.history?.size) {
        val last = state?.history?.lastOrNull()
        if (last?.player == 0 && last.action is GameAction.Attack) {
            last.events.firstOrNull { it.type == "ConfusionDamage" }?.let { event ->
                textDialog = "COROA — CONFUSÃO\n\n${event.message}"
            }
        }
        val pace = AnimationPace.entries.getOrElse(presentation.getInt("pace", 0)) { AnimationPace.NORMAL }
        panelsReady = false
        val duration = if (last?.action is GameAction.Attack || last?.events?.any { it.type == "PokemonKnockedOut" } == true) pace.duration+260 else if (last?.action is GameAction.PlayTrainer) pace.duration else 0
        kotlinx.coroutines.delay(if (pace == AnimationPace.OFF) 0L else duration.toLong())
        panelsReady = true
    }
    LaunchedEffect(state?.gameId, state?.phase) {
        if (state?.phase == Phase.GAME_OVER) {
            textDialog = "Analisando suas decisões e comparando alternativas legais…"
            textDialog = withContext(Dispatchers.Default) { TrainingReview.describe(state) }
        }
    }
    val isRecording by vm.isRecordingEnabled.collectAsStateWithLifecycle()
    Surface(Modifier.fillMaxSize(), color = Color(0xFF080F1C)) {
        if (state == null) HomeScreen(ui, vm, selectedDeck, { selectedDeck = it }, availableDecks, { deckOpen = true }, { textDialog = it })
        else BattleScene(
            state = state, viewer = 0, reveal = true,
            busy = ui.busy, error = ui.message, engine = vm.engine,
            dispatch = vm::actSequence, inspect = { inspected = it }, discard = { discardOwner = it },
            prizes = { prizesOpen = true }, resolve = { resolveOpen = true }, endTurn = { confirmEnd = true },
            menu = vm::menu,
            history = { textDialog = state.history.joinToString("\n\n") { "T${it.turn} • ${if (it.player == 0) "Você" else "Máquina"}\n${it.events.joinToString("\n") { event -> event.message }}" }.ifEmpty { "Sem ações registradas." } },
            undo = { vm.undo(); inspected = null }, deck = { deckOpen = true },
            isRecording = isRecording,
            exportJson = { vm.shareCurrentRecording(asJson = true) },
            exportTxt = { vm.shareCurrentRecording(asJson = false) },
            share = { vm.shareCurrentRecording(asJson = false) }
        )
    }
    if (state != null) {
        inspected?.let { card -> CardDetailSheet(card, state, vm.engine, ui.busy, onDismiss = { inspected = null }, dispatch = { vm.act(it); inspected = null }, attack = { confirmAttack = it }) }
        if (actor == 0 && state.phase == Phase.RESOLVE_CHOICE && resolveOpen && inspected == null && panelsReady) ChoiceSheet(state, vm.engine, ui.busy, onDismiss = { resolveOpen = false }, confirm = { vm.act(it) })
        if (actor == 0 && state.phase == Phase.SELECT_PRIZES && prizesOpen && inspected == null && panelsReady) PrizeSheet(state, vm.engine, ui.busy, { prizesOpen = false }, { vm.act(GameAction.TakePrize(it)) })
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
        val confused = state?.players?.get(0)?.active?.takeIf { SpecialCondition.CONFUSED in it.conditions }
        AlertDialog(onDismissRequest = { confirmAttack = null }, title = { Text("Confirmar ataque") }, text = {
            Text((if (confused != null) "CONFUSO: se a moeda der coroa, o ataque falha e seu Pokémon recebe ${confused.confusionDamageOnFail} de dano. A previsão abaixo supõe cara.\n\n" else "") +
                if (preview?.uncertain == true) "Dano por moedas: ${preview.minDamage}–${preview.maxDamage}.\nMédia estimada: ${preview.damage}. O resultado só será sorteado ao atacar.\n\nOs demais efeitos do ataque serão resolvidos em seguida."
                else "Dano previsto: ${preview?.damage}\nHP efetivo: ${preview?.effectiveHp}\nDano atual: ${preview?.currentDamage}\nApós o ataque: ${preview?.remainingHp} HP\n${if (preview?.knockout == true) "NOCAUTE · ${preview.prizes} Prêmio(s)" else "NÃO É NOCAUTE"}\n\nOs demais efeitos do ataque serão resolvidos em seguida.")
        }, confirmButton = { TextButton(onClick = { vm.act(action); confirmAttack = null; inspected = null }, enabled = !ui.busy) { Text("ATACAR") } }, dismissButton = { TextButton(onClick = { confirmAttack = null }) { Text("VOLTAR") } })
    }
    if (confirmEnd) AlertDialog(onDismissRequest = { confirmEnd = false }, title = { Text("Encerrar seu turno?") }, text = { Text("A máquina jogará em seguida.") }, confirmButton = { TextButton(onClick = { vm.act(GameAction.EndTurn); confirmEnd = false }, enabled = !ui.busy && actor == 0) { Text("ENCERRAR") } }, dismissButton = { TextButton(onClick = { confirmEnd = false }) { Text("VOLTAR") } })
}

@Composable private fun HomeScreen(ui: UiState, vm: GameViewModel, selectedDeck: String, selectDeck: (String) -> Unit, availableDecks: List<TrainingDeck>, deck: () -> Unit, info: (String) -> Unit) {
    var seed by remember { mutableStateOf(java.security.SecureRandom().nextLong().toString()) }; var training by remember { mutableStateOf(true) }
    var options by remember { mutableStateOf(false) }
    var cpuDeckId by remember { mutableStateOf("random") }
    var cpuIntelligenceOpen by remember { mutableStateOf(false) }
    val chosen = availableDecks.firstOrNull { it.id == selectedDeck } ?: availableDecks.first()
    val chosenValidation by produceState<DeckValidation?>(initialValue = null, chosen) {
        value = withContext(Dispatchers.Default) { DeckValidator.validate(chosen) }
    }
    val cpuDeckOptions by produceState(initialValue = availableDecks.filter { it.cards.size == 60 }, availableDecks, chosen.format, training) {
        value = withContext(Dispatchers.Default) {
            val fullDecks = availableDecks.filter {
                it.cards.size == 60 && (if (training) DeckValidator.validate(it).trainingReady else DeckValidator.validate(it).playable)
            }
            if (!training && chosen.format == DeckFormat.STANDARD) fullDecks.filter { cpu ->
                DeckValidator.validate(SavedDeck(cpu.id, cpu.name, DeckFormat.STANDARD, cpu.cards.groupingBy { it }.eachCount())).playable
            } else fullDecks
        }
    }
    val randomCpuDeck = TrainingDeck("random", "Aleatório · Mestre", "Prioriza listas competitivas elegíveis; na Academia 2022, sorteia entre os decks dessa categoria.", cpuDeckOptions.firstOrNull()?.cover ?: chosen.cover, emptyList())
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
        TextButton(onClick = { activity?.finish() }) { Text("‹ VOLTAR AO POKECARD (ABA DECKS)") }
        Text("Coleção PokeCard · ${vm.owned.counts.values.sum()} cópias · ${availableDecks.size} decks sincronizados da aba Decks.", color = Muted, fontSize = 11.sp)
        Row(Modifier.height(160.dp).fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            CardArt("me01-074", Modifier.width(84.dp).height(125.dp))
            CardArt(chosen.cover, Modifier.padding(horizontal = 8.dp).width(110.dp).height(154.dp))
            CardArt(opponent.cover, Modifier.width(84.dp).height(125.dp))
        }
        Surface(shape = RoundedCornerShape(16.dp), color = Color(0xFF192B3E), onClick = deck) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("BARALHO SELECIONADO", fontSize = 9.sp, color = Aqua, letterSpacing = 2.sp)
                Text(chosen.name, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                Text("${chosen.cards.size} cartas · ${chosen.format.label()}", color = Muted, fontSize = 11.sp)
                Text("VER CARTAS  ›", color = Aqua, fontSize = 10.sp)
            }
        }
        val updateContext = LocalContext.current
        OutlinedButton(onClick = {
            val intent = android.content.Intent().setClassName(updateContext.packageName, "br.com.fichariopokemon.pokedex.MainActivity")
                .addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("checkTrainerUpdate", true)
            updateContext.startActivity(intent)
        }, modifier = Modifier.fillMaxWidth()) { Text("VERIFICAR ATUALIZAÇÕES DE CARTAS E REGRAS") }
        Text("Crie e edite seus baralhos diretamente na aba Decks do PokeCard. Todos os decks salvos lá aparecem automaticamente abaixo.", color = Muted, fontSize = 11.sp)
        DeckSelector("SEU BARALHO", chosen.id, availableDecks, selectDeck)
        Text(chosen.strategy, color = Muted, fontSize = 11.sp)
        chosenValidation?.let { valResult ->
            if (!valResult.trainingReady) {
                val reasons = buildList {
                    addAll(valResult.trainingErrors.take(2))
                    if (valResult.unsupported.isNotEmpty()) {
                        val names = valResult.unsupported.take(3).map { CardLibrary.byId[it]?.name ?: it }
                        add("${valResult.unsupported.size} carta(s) com efeitos ainda não implementados (${names.joinToString()}${if (valResult.unsupported.size > 3) "…" else ""}). Edite na aba Decks do PokeCard.")
                    }
                }
                if (reasons.isNotEmpty()) Text(reasons.joinToString("\n"), color = Coral, fontSize = 11.sp)
            }
        }
        if (!training) vm.ownershipMessage(chosen.cards)?.let { Text(it, color = Coral, fontSize = 11.sp) }
        if (training) Text("Treino: 14 a 60 cartas, incluindo cartas ainda não possuídas. Para criar ou editar listas, volte à aba Decks do PokeCard.", color = Muted, fontSize = 11.sp)
        DeckSelector("BARALHO DA MÁQUINA", cpuDeckId, cpuDeckMenu) { cpuDeckId = it }
        Text(if (cpuDeckId == "random") "Baralho aleatório · a máquina escolhe a cada partida." else "A máquina usará ${opponent.name}.", color = Muted, fontSize = 11.sp)
        val difficulty by vm.difficulty.collectAsStateWithLifecycle()
        DifficultySelector(difficulty) { vm.setDifficulty(it) }
        val diffDisclosure = when (difficulty) {
            AiDifficulty.GRANDMASTER -> "GRANDMASTER: A CPU conhece sua mão e seu baralho completo para calcular a melhor resposta possível."
            AiDifficulty.ORACLE -> "ORACLE: A CPU conhece a ordem das compras e cartas prêmio. Modo deliberadamente extremo."
            AiDifficulty.MASTER -> "MASTER: A CPU joga de forma justa, usando apenas informações públicas."
            AiDifficulty.EXPERT -> "EXPERT: IA avançada com busca tática justa."
            AiDifficulty.HARD -> "HARD: IA tática justa."
            AiDifficulty.NORMAL -> "NORMAL: IA padrão."
            AiDifficulty.EASY -> "EASY: IA casual."
            AiDifficulty.CHAMPION -> "CHAMPION: IA competitiva justa."
        }
        Text(diffDisclosure, color = if (difficulty in listOf(AiDifficulty.GRANDMASTER, AiDifficulty.ORACLE)) Coral else Muted, fontSize = 11.sp)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text(if (training) "Treinamento solo" else "Partida contra a máquina", fontWeight = FontWeight.SemiBold); Text(if (training) "Desfaz sua jogada e a resposta da CPU" else "Sem desfazer", color = Muted, fontSize = 11.sp) }
            Switch(training, { training = it })
        }
        TextButton(onClick = { options = !options }, contentPadding = PaddingValues(0.dp)) { Text("${if (options) "−" else "+"} OPÇÕES DA PARTIDA · SEED $seed", fontSize = 10.sp) }
        if (options) OutlinedTextField(seed, { seed = it }, label = { Text("Seed · abertura reproduzível") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedButton(onClick = { cpuIntelligenceOpen = true }, modifier = Modifier.fillMaxWidth()) {
            Text("🧠 INTELIGÊNCIA DA CPU & GRAVAÇÕES")
        }
        Button(onClick = { vm.start(seed.toLong(), if (training) Mode.TRAINING else Mode.MATCH, selectedDeck, cpuDeckId); seed = java.security.SecureRandom().nextLong().toString() }, enabled = !ui.busy && seed.toLongOrNull() != null && (if (training) chosenValidation?.trainingReady == true else (chosenValidation?.playable == true && vm.owned.allows(chosen.cards) && chosen.cards.size == 60)), modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(12.dp)) { Text(if (ui.busy) "PREPARANDO PARTIDA…" else "JOGAR CONTRA A MÁQUINA", fontWeight = FontWeight.Bold, letterSpacing = 1.sp) }
        OutlinedButton(onClick = vm::resume, enabled = ui.canContinue && !ui.busy, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(12.dp)) { Text("CONTINUAR PARTIDA") }
        TextButton(onClick = { info("Jogo exclusivamente para você contra a máquina. Crie, salve e edite seus baralhos na aba Decks do PokeCard — todos os decks salvos lá aparecem automaticamente aqui em SEU BARALHO. No modo Treinamento solo você pode usar qualquer lista compatível de 14 a 60 cartas, mesmo sem possuir as cartas físicas; partidas normais exigem 60 cartas válidas e posse completa.\n\nPadrão 2026 e Academia 2022 são categorias separadas. Treino livre permite misturar cartas implementadas. As regras das cartas são mantidas.") }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("COMO JOGAR", fontSize = 11.sp) }
        Text("1 JOGADOR × MÁQUINA  ·  PARTIDAS OFFLINE", Modifier.fillMaxWidth(), color = Muted, fontSize = 9.sp)
        ui.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
    if (cpuIntelligenceOpen) {
        CpuIntelligenceDialog(vm, onDismiss = { cpuIntelligenceOpen = false })
    }
}
@Composable private fun DeckSelector(label: String, selected: String, availableDecks: List<TrainingDeck>, choose: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, color = Aqua, fontSize = 9.sp, letterSpacing = 1.sp)
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(9.dp)) {
                Text((availableDecks.firstOrNull { it.id == selected } ?: availableDecks.first()).name, Modifier.weight(1f), fontSize = 13.sp)
                Text("⌄", fontSize = 18.sp)
            }
            DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                availableDecks.forEach { entry -> DropdownMenuItem(text = {
                    Column {
                        Text(entry.name, color = if (entry.id == selected) Aqua else Color.White)
                        val detail = if (entry.cards.isEmpty()) entry.format.label() else "${entry.cards.size} cartas · ${entry.format.label()}"
                        Text(detail, color = Muted, fontSize = 10.sp)
                    }
                }, onClick = { choose(entry.id); expanded = false }) }
            }
        }
    }
}
@Composable private fun DifficultySelector(selected: AiDifficulty, choose: (AiDifficulty) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text("DIFICULDADE DA CPU", color = Aqua, fontSize = 9.sp, letterSpacing = 1.sp)
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(9.dp)) {
                Text(selected.name, Modifier.weight(1f), fontSize = 13.sp)
                Text("⌄", fontSize = 18.sp)
            }
            DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                listOf(AiDifficulty.EASY, AiDifficulty.NORMAL, AiDifficulty.HARD, AiDifficulty.EXPERT, AiDifficulty.MASTER, AiDifficulty.GRANDMASTER, AiDifficulty.ORACLE).forEach { entry ->
                    DropdownMenuItem(text = {
                        Column {
                            Text(entry.name, color = if (entry == selected) Aqua else Color.White)
                            val subtext = when (entry) {
                                AiDifficulty.MASTER -> "Padrão · 100% justo"
                                AiDifficulty.GRANDMASTER -> "Vê sua mão e deck completo"
                                AiDifficulty.ORACLE -> "Informação perfeita (compras e prêmios)"
                                else -> "Modo justo"
                            }
                            Text(subtext, color = Muted, fontSize = 10.sp)
                        }
                    }, onClick = { choose(entry); expanded = false })
                }
            }
        }
    }
}
internal fun phaseLabel(phase: Phase) = when (phase) {
    Phase.CHOOSE_STARTER -> "Sorteio da moeda · escolha de quem começa"
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

@Composable
private fun CpuIntelligenceDialog(vm: GameViewModel, onDismiss: () -> Unit) {
    var tab by remember { mutableStateOf(0) }
    var confirmReset by remember { mutableStateOf(false) }
    val isRecording by vm.isRecordingEnabled.collectAsStateWithLifecycle()
    var learningEnabled by remember { mutableStateOf(vm.learningEngine.learningEnabled) }
    var refreshTrigger by remember { mutableStateOf(0) }
    val memory = vm.learningEngine.experienceMemory
    val savedMatches = remember(refreshTrigger) { vm.listSavedRecordings() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Inteligência da CPU & Gravações", fontWeight = FontWeight.Bold, fontSize = 18.sp) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    TextButton(onClick = { tab = 0 }) { Text("VISÃO GERAL", color = if (tab == 0) Aqua else Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                    TextButton(onClick = { tab = 1 }) { Text("LIÇÕES (${memory.lessons.size})", color = if (tab == 1) Aqua else Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                    TextButton(onClick = { tab = 2 }) { Text("GRAVAÇÕES (${savedMatches.size})", color = if (tab == 2) Aqua else Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                }

                if (tab == 0) {
                    Surface(shape = RoundedCornerShape(8.dp), color = Color(0xFF142436)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("STATUS DO MOTOR", color = Aqua, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Text("Partidas analisadas pela CPU: ${memory.matchesAnalyzed}", fontSize = 13.sp)
                            Text("Padrões aprendidos com derrotas: ${memory.lessons.size}", fontSize = 13.sp)
                            val penalty = memory.lessons.find { it.type == "AVOID_REDUNDANT_ENERGY" }
                            if (penalty != null) {
                                Text("Ajuste de energia redundante ativo: ${(penalty.confidence * 100).toInt()}%", fontSize = 12.sp, color = Aqua)
                            }
                        }
                    }

                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Aprendizado Contínuo", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Text("A CPU ajusta parâmetros após derrotas", color = Muted, fontSize = 10.sp)
                        }
                        Switch(checked = learningEnabled, onCheckedChange = {
                            learningEnabled = it
                            vm.toggleLearning(it)
                        })
                    }

                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Gravação Automática", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Text("Salva as últimas 30 partidas completas", color = Muted, fontSize = 10.sp)
                        }
                        Switch(checked = isRecording, onCheckedChange = { vm.setRecordingEnabled(it) })
                    }

                    OutlinedButton(onClick = { confirmReset = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("REDEFINIR MEMÓRIA DA CPU", color = Coral, fontSize = 12.sp)
                    }
                } else if (tab == 1) {
                    if (memory.lessons.isEmpty()) {
                        Text("Nenhuma lição aprendida ainda. A CPU analisará partidas quando for derrotada e ajustará seu jogo automaticamente.", color = Muted, fontSize = 12.sp)
                    } else {
                        for (lesson in memory.lessons) {
                            Surface(shape = RoundedCornerShape(8.dp), color = Color(0xFF142436)) {
                                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(lesson.type, fontWeight = FontWeight.Bold, color = Aqua, fontSize = 12.sp)
                                        Text("${(lesson.confidence * 100).toInt()}% conf.", color = Muted, fontSize = 11.sp)
                                    }
                                    Text(lesson.description, fontSize = 11.sp)
                                    Text("Arquétipo: ${lesson.archetype} · Ocorrências: ${lesson.occurrences}", color = Muted, fontSize = 10.sp)
                                }
                            }
                        }
                    }
                } else {
                    if (savedMatches.isEmpty()) {
                        Text("Nenhuma gravação de partida encontrada no aparelho.", color = Muted, fontSize = 12.sp)
                    } else {
                        for (summary in savedMatches) {
                            Surface(shape = RoundedCornerShape(8.dp), color = Color(0xFF142436)) {
                                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    val resultText = if (summary.winner == 0) "VITÓRIA DO JOGADOR" else if (summary.winner == 1) "VITÓRIA DA CPU" else "PARTIDA INCOMPLETA"
                                    val resultColor = if (summary.winner == 0) Aqua else if (summary.winner == 1) Coral else Muted
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(resultText, fontWeight = FontWeight.Bold, color = resultColor, fontSize = 11.sp)
                                        Text(summary.difficulty, color = Muted, fontSize = 10.sp)
                                    }
                                    Text("Você: ${summary.humanDeck} vs CPU: ${summary.cpuDeck} · ${summary.totalTurns} turnos", fontSize = 11.sp)
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        TextButton(onClick = { vm.shareSavedRecording(summary.gameId, asJson = false) }) {
                                            Text("TXT (ChatGPT)", fontSize = 10.sp)
                                        }
                                        TextButton(onClick = { vm.shareSavedRecording(summary.gameId, asJson = true) }) {
                                            Text("JSON", fontSize = 10.sp)
                                        }
                                        TextButton(onClick = {
                                            vm.deleteSavedRecording(summary.gameId)
                                            refreshTrigger++
                                        }) {
                                            Text("EXCLUIR", color = Coral, fontSize = 10.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("FECHAR") }
        }
    )

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Redefinir memória?") },
            text = { Text("Isso apagará todas as lições aprendidas pela CPU e redefinirá os parâmetros adaptativos aos valores padrão.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.resetCpuLearning()
                    confirmReset = false
                }) { Text("REDEFINIR", color = Coral) }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text("CANCELAR") }
            }
        )
    }
}

