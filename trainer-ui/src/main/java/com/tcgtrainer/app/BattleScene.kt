package com.tcgtrainer.app

import android.app.Activity
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.*
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.tcgtrainer.engine.*
import kotlinx.coroutines.delay
import kotlin.math.*

internal data class DraggedCard(val card: CardInstance, val origin: Rect, val pointer: Offset)

@Composable internal fun BattleScene(state: GameState, viewer: Int, reveal: Boolean, busy: Boolean, error: String?, engine: RulesEngine,
    dispatch: (List<GameAction>) -> Unit, inspect: (CardInstance) -> Unit, discard: (Int) -> Unit,
    prizes: () -> Unit, resolve: () -> Unit, endTurn: () -> Unit, menu: () -> Unit, history: () -> Unit, undo: () -> Unit, deck: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("battle-presentation", 0) }
    var pace by remember { mutableStateOf(AnimationPace.entries.getOrElse(prefs.getInt("pace", 0)) { AnimationPace.NORMAL }) }
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val resolver = remember(engine) { DropInteraction(engine) }
    var dragged by remember(state.gameId, viewer) { mutableStateOf<DraggedCard?>(null) }
    val flights = remember(state.gameId, viewer) { mutableStateListOf<CardFlight>() }
    val impacts = remember(state.gameId, viewer) { mutableStateListOf<Impact>() }
    val handPositions = remember(state.gameId, viewer) { mutableMapOf<String, Rect>() }
    var serial by remember { mutableLongStateOf(0) }
    var toast by remember { mutableStateOf<String?>(null) }
    var settings by remember { mutableStateOf(false) }
    var handFilter by remember { mutableStateOf<Category?>(null) }
    var resultReady by remember { mutableStateOf(false) }
    var rootOrigin by remember { mutableStateOf(Offset.Zero) }
    var previous by remember(state.gameId, viewer) { mutableStateOf(state) }
    var dragActionCard by remember { mutableStateOf<String?>(null) }
    var attackSource by remember { mutableStateOf<String?>(null) }
    var attackTarget by remember { mutableStateOf<String?>(null) }
    val attack = remember { Animatable(1f) }
    val currentDispatch by rememberUpdatedState(dispatch)
    val currentState by rememberUpdatedState(state)
    val actor = engine.actingPlayer(state)
    DisposableEffect(context) {
        val window = (context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }
    LaunchedEffect(toast) { if (toast != null) { delay(2700); toast = null } }
    LaunchedEffect(state.phase) {
        resultReady = false
        if (state.phase == Phase.GAME_OVER) { delay(if (pace == AnimationPace.OFF) 0 else pace.duration+300L); resultReady = true }
    }
    BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned { rootOrigin = it.positionInRoot() }) {
        val geometry = remember(constraints.maxWidth, constraints.maxHeight, viewer) { BattleGeometry(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat(), viewer) }
        fun nextId(): Long { serial += 1; return serial }
        fun flight(card: CardInstance?, from: Rect, to: Rect, lag: Int = 0, knockout: Boolean = false, returning: Boolean = false) {
            if (pace != AnimationPace.OFF) flights += CardFlight(nextId(), card?.let(engine::definition), from, to, lag, knockout, returning, card?.id)
        }
        fun impact(point: Offset, damage: Int = 0, color: Color = Aqua, lag: Int = 0) { if (pace != AnimationPace.OFF) impacts += Impact(nextId(), point, damage, color, lag) }
        val validTargets = remember(state, dragged?.card?.id, geometry) {
            dragged?.let { drag -> geometry.targets(state).filter { resolver.resolve(state, drag.card.id, it.first) is DropDecision.Accepted } }.orEmpty()
        }
        val hover = dragged?.let { geometry.targetAt(state, it.pointer) }
        val decision = if (dragged != null && hover != null) resolver.resolve(state, dragged!!.card.id, hover.first) else null
        val interactive = reveal && actor == 0 && !busy && state.phase != Phase.GAME_OVER

        LaunchedEffect(state.history.size, state.phase, viewer) {
            val old = previous
            previous = state
            if (old.history.size >= state.history.size || old.gameId != state.gameId) return@LaunchedEffect
            val recent = state.history.drop(old.history.size)
            val lastAttack = recent.mapNotNull { it.action as? GameAction.Attack }.lastOrNull()
            if (lastAttack != null) {
                val attackerOwner = old.currentPlayer
                val source = old.players[attackerOwner].active
                val target = lastAttack.targetId?.let { id -> old.players[1-attackerOwner].pokemon().find { it.id == id } } ?: old.players[1-attackerOwner].active
                attackSource = source?.id; attackTarget = target?.id
                val damage = recent.lastOrNull { it.action is GameAction.Attack }?.events?.firstOrNull { it.type == "AttackDeclared" }?.amount
                    ?: engine.preview(old, lastAttack.attackId, lastAttack.targetId)?.damage ?: 0
                if (target != null) impact(geometry.pokemon(old, 1-attackerOwner, target.id).center, damage, Color(0xFFFFDA8D), 140)
                if (source != null && recent.any { record -> record.events.any { it.type == "RecoilDamage" } }) {
                    val recoil = engine.definition(source.top).attacks.firstOrNull { it.id == lastAttack.attackId }?.effects?.filterIsInstance<Effect.Recoil>()?.sumOf { it.damage } ?: 0
                    impact(geometry.pokemon(old, attackerOwner, source.id).center, recoil, Coral, 260)
                }
            }
            for (owner in 0..1) {
                val before = old.players[owner]; val after = state.players[owner]
                for (p in after.pokemon()) {
                    val prior = before.pokemon().find { it.id == p.id }
                    val destination = geometry.pokemon(state, owner, p.id)
                    if (prior == null && p.top.id != dragActionCard && reveal) {
                        flight(if (owner != viewer && state.turn == 0) null else p.top, if (owner == viewer) handPositions[p.top.id] ?: geometry.hand else geometry.deck(owner), destination)
                    } else if (prior != null && prior.top.id != p.top.id && p.top.id != dragActionCard && reveal) {
                        flight(p.top, if (owner == viewer) handPositions[p.top.id] ?: geometry.hand else geometry.deck(owner), destination); impact(destination.center)
                    }
                    val newEnergies = p.energies.filter { e -> prior?.energies?.none { it.id == e.id } != false }
                    newEnergies.forEach { energy ->
                        if (energy.id != dragActionCard && reveal)
                            flight(energy, if (before.hand.any { it.id == energy.id }) { if (owner == viewer) handPositions[energy.id] ?: geometry.hand else geometry.deck(owner) } else geometry.discard(owner), destination)
                        impact(destination.center, color = Color(0xFFFFD181))
                    }
                }
                for (p in before.pokemon().filter { beforeP -> after.pokemon().none { it.id == beforeP.id } }) {
                    flight(p.top, geometry.pokemon(old, owner, p.id), geometry.discard(owner), lag = 220, knockout = true)
                }
                if (owner == viewer && reveal) {
                    val newHand = after.hand.filter { card -> before.hand.none { it.id == card.id } }
                    withFrameNanos { }
                    newHand.forEachIndexed { index, card ->
                        val prizePosition = before.prizePositions.indexOf(card.id)
                        val from = when {
                            before.discard.any { it.id == card.id } -> geometry.discard(owner)
                            before.prizes.any { it.id == card.id } -> geometry.prize(owner, prizePosition.coerceAtLeast(0))
                            else -> geometry.deck(owner)
                        }
                        flight(card, from, handPositions[card.id] ?: Rect(geometry.width*.4f, geometry.hand.top, geometry.width*.64f, geometry.hand.bottom), index*65)
                    }
                }
                if (before.prizes.isEmpty() && after.prizes.size == 6) repeat(6) { slot -> flight(null, geometry.deck(owner), geometry.prize(owner, slot), slot*55) }
            }
            dragActionCard = null
            if (lastAttack != null) { attack.snapTo(0f); attack.animateTo(1f, tween(if (pace == AnimationPace.OFF) 1 else pace.duration+100)); attackSource = null; attackTarget = null }
        }
        LaunchedEffect(reveal, viewer, state.gameId) {
            if (reveal && state.phase == Phase.SELECT_ACTIVE) {
                withFrameNanos { }
                state.players[viewer].hand.forEachIndexed { index, card -> flight(card, geometry.deck(viewer), handPositions[card.id] ?: geometry.hand, index*45) }
            } else if (reveal && state.phase == Phase.MAIN && state.history.lastOrNull()?.events?.any { it.type == "TurnStarted" } == true) {
                delay(240)
                state.players[viewer].hand.lastOrNull()?.let { card -> flight(card, geometry.deck(viewer), handPositions[card.id] ?: geometry.hand) }
            }
        }
        val hidden = flights.mapNotNull { it.instanceId }.toSet() + listOfNotNull(dragged?.card?.id)
        BattleBoard(state, geometry, engine, reveal, validTargets.map { it.second }, hover?.second,
            decision is DropDecision.Accepted, pace, attack.value, attackSource, attackTarget, hidden, inspect, discard, prizes)
        val shownHand = state.players[viewer].hand.filter { handFilter == null || engine.definition(it).category == handFilter }
        HandFan(shownHand, engine, geometry, interactive && state.phase != Phase.CHOOSE_STARTER, reveal && state.phase != Phase.CHOOSE_STARTER,
            hidden, rootOrigin, inspect, onPosition = { id, rect -> handPositions[id] = rect },
            start = { card, point ->
                if (dragged == null && interactive) {
                    val origin = handPositions[card.id] ?: geometry.hand
                    dragged = DraggedCard(card, origin, point); haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                }
            }, move = { point -> dragged = dragged?.copy(pointer = point) }, finish = { cancelled ->
                val drag = dragged
                if (drag != null) {
                    val latest = currentState
                    val landing = geometry.targetAt(latest, drag.pointer)
                    val verdict = if (!cancelled && landing != null) resolver.resolve(latest, drag.card.id, landing.first) else null
                    val w = drag.origin.width*1.15f; val h = w/.727f
                    val floating = Rect(drag.pointer.x-w/2, drag.pointer.y-h*.8f, drag.pointer.x+w/2, drag.pointer.y+h*.2f)
                    if (verdict is DropDecision.Accepted && !busy) {
                        dragActionCard = drag.card.id
                        flight(drag.card, floating, landing!!.second)
                        impact(landing.second.center)
                        currentDispatch(verdict.actions)
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    } else {
                        flight(drag.card, floating, drag.origin, returning = true)
                        toast = (verdict as? DropDecision.Rejected)?.reason ?: "Solte a carta em uma área válida."
                    }
                }
                dragged = null
            })

        // Small floating controls keep the entire central table available for play.
        IconButton(onClick = { settings = true }, modifier = Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 6.dp).size(34.dp).background(Color.Black.copy(alpha = .62f), RoundedCornerShape(7.dp))) {
            Icon(Icons.Default.Settings, "Configurações da mesa", modifier = Modifier.size(19.dp), tint = Color.White)
        }
        if (reveal && state.phase != Phase.CHOOSE_STARTER) {
            Surface(Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp), shape = RoundedCornerShape(7.dp), color = Color(0xFF0D263B).copy(alpha = .94f), border = androidx.compose.foundation.BorderStroke(1.dp, BoardBlue)) {
                Row(Modifier.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    TextButton(onClick = { handFilter = null }, contentPadding = PaddingValues(3.dp)) {
                        Text("${state.players[viewer].hand.size}", fontWeight = FontWeight.Black, fontSize = 19.sp, color = if (handFilter == null) Aqua else Color.White)
                    }
                    listOf(Category.POKEMON to Icons.Default.Face, Category.TRAINER to Icons.Default.Person, Category.ENERGY to Icons.Default.Star).forEach { (category, icon) ->
                        IconButton(onClick = { handFilter = if (handFilter == category) null else category }, modifier = Modifier.size(32.dp)) {
                            Icon(icon, contentDescription = when (category) { Category.POKEMON -> "Filtrar Pokémon na mão"; Category.TRAINER -> "Filtrar Treinadores na mão"; Category.ENERGY -> "Filtrar Energias na mão" }, tint = if (handFilter == category) Aqua else Color(0xFFB7D2E6), modifier = Modifier.size(21.dp))
                        }
                    }
                }
            }
            val command: Pair<String, () -> Unit>? = if (actor != 0 && state.phase != Phase.GAME_OVER) null else when (state.phase) {
                Phase.SELECT_BENCH -> "PRONTO" to { dispatch(listOf(GameAction.FinishSetup)) }
                Phase.BONUS_BENCH -> "PRONTO" to { dispatch(listOf(GameAction.FinishBonusSetup)) }
                Phase.RESOLVE_CHOICE -> "ESCOLHER" to resolve
                Phase.SELECT_PRIZES -> "PRÊMIOS" to prizes
                Phase.MAIN -> "ENCERRAR" to endTurn
                Phase.GAME_OVER -> "MENU" to menu
                else -> null
            }
            command?.let { (label, action) ->
                FilledTonalButton(onClick = action, enabled = !busy && dragged == null,
                    modifier = Modifier.align(Alignment.CenterEnd).offset(y = 42.dp).widthIn(max = 78.dp), contentPadding = PaddingValues(horizontal = 7.dp, vertical = 8.dp), shape = RoundedCornerShape(topStart = 5.dp, bottomStart = 5.dp)) { Text(label, fontSize = 9.sp, fontWeight = FontWeight.Bold) }
            }
            if (actor == 0 && (state.phase == Phase.SELECT_ACTIVE || state.phase == Phase.PROMOTE || state.phase == Phase.SELECT_BENCH)) {
                Text(phaseLabel(state.phase), Modifier.align(Alignment.Center).offset(y = if (state.phase == Phase.SELECT_ACTIVE) 110.dp else 154.dp)
                    .background(Color(0xFF102A3B).copy(alpha = .88f), RoundedCornerShape(5.dp)).padding(8.dp), fontSize = 10.sp, color = Color.White)
            }
        }
        flights.forEach { cue -> key(cue.id) { FlightLayer(cue, pace) { id -> flights.removeAll { it.id == id } } } }
        impacts.forEach { cue -> key(cue.id) { ImpactLayer(cue, pace) { id -> impacts.removeAll { it.id == id } } } }
        dragged?.let { drag ->
            val lift = remember(drag.card.id) { Animatable(1f) }
            LaunchedEffect(drag.card.id) { if (pace == AnimationPace.OFF) lift.snapTo(1.15f) else lift.animateTo(1.15f, spring(dampingRatio = .65f, stiffness = 600f)) }
            val w = (drag.origin.width/density.density).dp*lift.value
            val h = w/.727f
            val wp = with(density) { w.toPx() }; val hp = with(density) { h.toPx() }
            Box(Modifier.offset { IntOffset((drag.pointer.x-wp/2).roundToInt(), (drag.pointer.y-hp*.8f).roundToInt()) }.size(w, h)
                .graphicsLayer { rotationZ = ((drag.pointer.x-drag.origin.center.x)/geometry.width*10).coerceIn(-9f, 9f); shadowElevation = 32.dp.toPx(); shape = RoundedCornerShape(5.dp) }
                .border(2.dp, if (decision is DropDecision.Accepted) Aqua else Color(0xFFFFD991), RoundedCornerShape(5.dp))) {
                CardArt(engine.definition(drag.card), Modifier.fillMaxSize())
            }
            val label = (decision as? DropDecision.Accepted)?.label ?: (decision as? DropDecision.Rejected)?.reason ?: "SOLTE EM UMA ÁREA DESTACADA"
            Text(label, Modifier.align(Alignment.BottomCenter).padding(bottom = 160.dp, start = 14.dp, end = 14.dp).background(Color(0xFF102839), RoundedCornerShape(8.dp)).padding(10.dp), color = if (decision is DropDecision.Rejected) Coral else Aqua, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
        AnimatedVisibility(toast != null || error != null, Modifier.align(Alignment.TopCenter).padding(top = 62.dp, start = 24.dp, end = 24.dp), enter = slideInVertically { -it }+fadeIn(), exit = fadeOut()) {
            Surface(color = Color(0xFF542C35), shape = RoundedCornerShape(8.dp)) { Text(toast ?: error.orEmpty(), Modifier.padding(12.dp), color = Color.White, fontSize = 12.sp) }
        }
        if (reveal && actor == 0 && state.phase == Phase.CHOOSE_STARTER) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .4f))) {
                Box(Modifier.align(Alignment.Center).offset(y = (-38).dp)) { CoinPresentation(state.startingPlayer, pace) }
                Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth(), shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp), color = Color(0xFFF5F4EF)) {
                    Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("VOCÊ VENCEU A MOEDA", color = Color(0xFF263442), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text("VOCÊ QUER JOGAR PRIMEIRO?", color = Color(0xFF182735), fontWeight = FontWeight.Black, fontSize = 17.sp)
                        TextButton(onClick = { dispatch(listOf(GameAction.ChooseStartingPlayer(actor))) }, enabled = !busy) { Text("SIM", color = Color(0xFF193C54), fontWeight = FontWeight.Black) }
                        TextButton(onClick = { dispatch(listOf(GameAction.ChooseStartingPlayer(1-actor))) }, enabled = !busy) { Text("NÃO", color = Color(0xFF193C54), fontWeight = FontWeight.Black) }
                    }
                }
            }
        }
        if (reveal && actor == 0 && state.phase == Phase.MULLIGAN_BONUS) Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth(), color = Color(0xFFF2F2EC), shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)) {
            Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("CARTAS EXTRAS POR MULLIGAN", color = Color(0xFF213849), fontWeight = FontWeight.Black)
                Row(Modifier.horizontalScroll(rememberScrollState())) { (0..minOf(state.players[actor].bonusDraws, state.players[actor].deck.size)).forEach { count -> TextButton(onClick = { dispatch(listOf(GameAction.MulliganDraw(count))) }, enabled = !busy) { Text("COMPRAR $count", color = Color(0xFF193C54)) } } }
            }
        }
        if (state.phase != Phase.GAME_OVER && !(actor == 0 && state.phase == Phase.CHOOSE_STARTER)) {
            Surface(Modifier.align(Alignment.TopCenter).padding(top = 35.dp), color = Color(0xFF102A3B).copy(alpha = .94f), shape = RoundedCornerShape(8.dp)) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    if (actor == 1 && busy) CircularProgressIndicator(Modifier.size(12.dp), color = Coral, strokeWidth = 2.dp)
                    Text(if (actor == 1) { if (state.turn == 0) "MÁQUINA PREPARANDO O CAMPO" else "TURNO DA MÁQUINA" } else { if (state.turn == 0) "PREPARE SEU CAMPO" else "SEU TURNO · ${state.turn}" }, color = if (actor == 1) Coral else Aqua, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        AnimatedVisibility(resultReady, Modifier.align(Alignment.Center).fillMaxWidth().padding(16.dp), enter = slideInHorizontally { -it }+fadeIn()) {
            Surface(color = Color(0xFFF5F5ED), shape = RoundedCornerShape(10.dp)) {
                Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(state.winner?.let { if (it == 0) "VOCÊ VENCEU!" else "A MÁQUINA VENCEU" } ?: "PARTIDA EMPATADA", color = Color(0xFF153C4B), fontSize = 20.sp, fontWeight = FontWeight.Black)
                    Text(state.winReason.orEmpty(), color = Color(0xFF435563), fontSize = 13.sp)
                    Button(onClick = menu) { Text("VOLTAR AO MENU") }
                }
            }
        }
        if (settings) AlertDialog(onDismissRequest = { settings = false }, title = { Text("Mesa de jogo") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("Animações", fontWeight = FontWeight.Bold)
                AnimationPace.entries.forEach { option -> Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(pace == option, onClick = { pace = option; prefs.edit().putInt("pace", option.ordinal).apply() })
                    Text(when (option) { AnimationPace.NORMAL -> "Normais"; AnimationPace.FAST -> "Rápidas"; AnimationPace.OFF -> "Desativadas" })
                } }
                TextButton(onClick = { settings = false; history() }, enabled = state.turn > 0 && reveal) { Text("HISTÓRICO") }
                TextButton(onClick = { settings = false; undo() }, enabled = reveal && state.mode == Mode.TRAINING && state.history.isNotEmpty() && !busy) { Text("DESFAZER") }
                TextButton(onClick = { settings = false; deck() }) { Text("VER BARALHO") }
                TextButton(onClick = { settings = false; menu() }, enabled = !busy) { Text("VOLTAR AO MENU") }
            }
        }, confirmButton = { TextButton(onClick = { settings = false }) { Text("FECHAR") } })
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable private fun HandFan(cards: List<CardInstance>, engine: RulesEngine, geometry: BattleGeometry, enabled: Boolean, reveal: Boolean,
    hidden: Set<String>, rootOrigin: Offset, inspect: (CardInstance) -> Unit, onPosition: (String, Rect) -> Unit,
    start: (CardInstance, Offset) -> Unit, move: (Offset) -> Unit, finish: (Boolean) -> Unit) {
    val scroll = rememberLazyListState()
    val currentStart by rememberUpdatedState(start); val currentMove by rememberUpdatedState(move); val currentFinish by rememberUpdatedState(finish)
    val currentOrigin by rememberUpdatedState(rootOrigin)
    val density = LocalDensity.current
    val cardWidth = with(density) { (geometry.width*.29f).toDp() }
    val cardHeight = cardWidth/.727f
    var priorCount by remember { mutableIntStateOf(cards.size) }
    LaunchedEffect(cards.size, reveal) {
        if (reveal && cards.size > 5 && (cards.size > priorCount || scroll.firstVisibleItemIndex == 0)) scroll.animateScrollToItem(cards.lastIndex)
        priorCount = cards.size
    }
    LazyRow(state = scroll, modifier = Modifier.sceneRect(geometry.hand), horizontalArrangement = Arrangement.spacedBy(-cardWidth*.42f, Alignment.CenterHorizontally), contentPadding = PaddingValues(horizontal = 8.dp)) {
        itemsIndexed(cards, key = { _, card -> card.id }) { index, card ->
            val coordinates = remember(card.id) { arrayOfNulls<LayoutCoordinates>(1) }
            val angle = ((index-scroll.firstVisibleItemIndex-2)*2.4f).coerceIn(-8f, 8f)
            Box(Modifier.size(cardWidth, cardHeight).graphicsLayer {
                transformOrigin = TransformOrigin(.5f, 1f); rotationZ = angle
                translationY = abs(angle)*.6.dp.toPx(); alpha = if (card.id in hidden) .08f else 1f
            }.onGloballyPositioned { coordinates[0] = it; onPosition(card.id, it.boundsInRoot().translate(-currentOrigin)) }
                .semantics { contentDescription = if (reveal) "Carta na mão: ${engine.definition(card).name}; arraste para jogar ou toque para ampliar" else "Carta virada para baixo" }
                .combinedClickable(enabled = reveal, onClick = { inspect(card) }, onLongClick = { inspect(card) })
                .pointerInput(card.id, enabled) {
                    if (enabled) detectVerticalDragGestures(
                        onDragStart = { point -> coordinates[0]?.let { currentStart(card, it.localToRoot(point)-currentOrigin) } },
                        onDragEnd = { currentFinish(false) }, onDragCancel = { currentFinish(true) },
                        onVerticalDrag = { change, _ -> change.consume(); coordinates[0]?.let { currentMove(it.localToRoot(change.position)-currentOrigin) } })
                }) {
                if (reveal) CardArt(engine.definition(card), Modifier.fillMaxSize()) else CardBack(Modifier.fillMaxSize(), "")
            }
        }
    }
}
