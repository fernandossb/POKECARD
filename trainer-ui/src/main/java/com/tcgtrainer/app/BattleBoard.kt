package com.tcgtrainer.app

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tcgtrainer.engine.*
import kotlin.math.sin

internal val BoardRed = Color(0xFFC56F6D)
internal val BoardBlue = Color(0xFF599BC6)

@Composable internal fun BattleBoard(state: GameState, geometry: BattleGeometry, engine: RulesEngine, reveal: Boolean,
    highlights: List<Rect>, hover: Rect?, hoverValid: Boolean, pace: AnimationPace,
    attackProgress: Float, attackSource: String?, attackTarget: String?, incoming: Set<String>, inspect: (CardInstance) -> Unit, discard: (Int) -> Unit, prizes: () -> Unit) {
    val pulse = rememberInfiniteTransition(label = "drop glow")
    val glow by pulse.animateFloat(.35f, .85f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "drop pulse")
    Canvas(Modifier.fillMaxSize()) {
        drawRect(Brush.verticalGradient(listOf(Color(0xFF19333D), Color(0xFF102B3C))))
        val oval = Rect(-size.width*.29f, -size.height*.015f, size.width*1.29f, size.height*.965f)
        drawOval(Brush.verticalGradient(listOf(Color(0xFF739A68), Color(0xFF3A968C), Color(0xFF266485))), oval.topLeft, oval.size)
        drawOval(Color(0xFF163A49), oval.topLeft, oval.size, style = Stroke(14.dp.toPx()))
        drawOval(Color(0xFF5E9C9A).copy(alpha = .7f), oval.topLeft+Offset(5.dp.toPx(), 5.dp.toPx()), Size(oval.width-10.dp.toPx(), oval.height-10.dp.toPx()), style = Stroke(2.dp.toPx()))
        // A restrained geometric mat; the zones and proportions follow the supplied reference.
        for (column in 0..10) for (row in 0..15) {
            val x = column*size.width/9 + if (row%2 == 0) 0f else size.width/18
            val y = row*size.height/15
            drawCircle(Color(0xFF124D65).copy(alpha = .14f), size.width*.044f, Offset(x, y), style = Stroke(1.dp.toPx()))
            drawLine(Color(0xFF164E60).copy(alpha = .13f), Offset(x-size.width*.025f, y), Offset(x+size.width*.025f, y), 1.dp.toPx())
        }
        for (player in 0..1) {
            val color = if (player == geometry.viewer) BoardBlue else BoardRed
            val zone = geometry.bench(player)
            drawRect(color.copy(alpha = .6f), zone.topLeft, zone.size, style = Stroke(1.5.dp.toPx()))
            if (state.players[player].active == null) {
                val active = geometry.active(player)
                drawRoundRect(color.copy(alpha = .28f), active.topLeft, active.size, androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()), style = Stroke(1.dp.toPx()))
            }
        }
        listOf(.425f to BoardRed, .449f to BoardBlue).forEach { (y, color) ->
            drawLine(color, Offset(0f, size.height*y), Offset(size.width*.235f, size.height*y), 4.dp.toPx())
            drawLine(color, Offset(size.width*.765f, size.height*y), Offset(size.width, size.height*y), 4.dp.toPx())
        }
        highlights.forEach { rect ->
            drawRoundRect(Aqua.copy(alpha = if (pace == AnimationPace.OFF) .12f else glow*.17f), rect.topLeft, rect.size, androidx.compose.ui.geometry.CornerRadius(5.dp.toPx()))
            drawRoundRect(Aqua.copy(alpha = .8f), rect.topLeft, rect.size, androidx.compose.ui.geometry.CornerRadius(5.dp.toPx()), style = Stroke(2.dp.toPx()))
        }
        hover?.let { rect ->
            val color = if (hoverValid) Aqua else Color(0xFFFF7B78)
            drawRoundRect(color.copy(alpha = .3f), rect.topLeft, rect.size, androidx.compose.ui.geometry.CornerRadius(5.dp.toPx()))
            drawRoundRect(color, rect.topLeft, rect.size, androidx.compose.ui.geometry.CornerRadius(5.dp.toPx()), style = Stroke(3.dp.toPx()))
        }
    }
    Box(Modifier.fillMaxSize().clipToBounds()) {
        for (owner in 0..1) {
            val own = owner == geometry.viewer
            val p = state.players[owner]
            val hidden = state.turn == 0 && !own
            val accent = if (own) BoardBlue else BoardRed
            Text(if (own) "SEU BANCO" else "BANCO ADVERSÁRIO", Modifier.sceneRect(geometry.rect(.22f, if (own) .812f else .032f, .56f, .022f)),
                color = Color.White.copy(alpha = .24f), fontSize = 8.sp, letterSpacing = 2.sp)
            p.pokemon().forEach { pokemon -> key(pokemon.id) {
                val bounds = geometry.pokemon(state, owner, pokemon.id)
                val position by animateOffsetAsState(bounds.topLeft, tween(pace.duration), label = "pokemon position")
                val dimensions by animateSizeAsState(bounds.size, tween(pace.duration), label = "pokemon size")
                val displayed = Rect(position.x, position.y, position.x+dimensions.width, position.y+dimensions.height)
                val isSource = pokemon.id == attackSource
                val isTarget = pokemon.id == attackTarget
                val modifier = Modifier.sceneRect(displayed).graphicsLayer {
                    alpha = if (pokemon.top.id in incoming) 0f else 1f
                    if (isSource) translationY = sin(attackProgress*3.14159f)*(if (own) -1 else 1)*bounds.height*.23f
                    if (isTarget) translationX = sin(attackProgress*55f)*(1f-attackProgress)*9.dp.toPx()
                }
                if (hidden) CardBack(modifier, "")
                else BoardPokemon(pokemon, state, engine, modifier, accent, pace, { if (reveal) inspect(pokemon.top) })
            } }
            // Six overlapping, fixed-position prizes like a physical tabletop stack.
            val positions = p.prizePositions.ifEmpty { p.prizes.map { it.id } }
            repeat(6) { slot ->
                val prize = p.prizes.firstOrNull { it.id == positions.getOrNull(slot) }
                if (prize != null) {
                    val prizeModifier = Modifier.sceneRect(geometry.prize(owner, slot)).clickable(
                        enabled = own && reveal && engine.actingPlayer(state) == 0 && state.phase == Phase.SELECT_PRIZES) { prizes() }
                    if (prize.id in p.faceUpPrizeIds) CardArt(engine.definition(prize), prizeModifier)
                    else CardBack(prizeModifier, "")
                }
            }
            Counter("${p.prizes.size}", Modifier.sceneRect(geometry.rect(.038f, if (own) .623f else .221f, .07f, .026f)), accent)
            CardBack(Modifier.sceneRect(geometry.deck(owner)), "TCG")
            Counter("${p.deck.size}", Modifier.sceneRect(geometry.rect(.874f, if (own) .642f else .217f, .074f, .027f)), accent)
            Surface(onClick = { if (reveal) discard(owner) }, modifier = Modifier.sceneRect(geometry.discard(owner)), color = Color(0xFF142C3E).copy(alpha = .83f), shape = RoundedCornerShape(3.dp), border = androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = .75f))) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text("${p.discard.size}", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text("DESCARTE", fontSize = 6.sp, maxLines = 1)
                }
            }
        }
        state.stadium?.let { card -> Surface(onClick = { if (reveal) inspect(card) }, modifier = Modifier.sceneRect(geometry.stadium), color = Color.Transparent) { CardArt(engine.definition(card), Modifier.fillMaxSize()) } }
        if (state.stadium == null) Text("ESTÁDIO", Modifier.sceneRect(geometry.stadium).wrapContentSize(), color = Color.White.copy(alpha = .26f), fontSize = 7.sp)
        Column(Modifier.sceneRect(geometry.rect(.905f, .345f, .095f, .10f)), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Counter("${state.players[1-geometry.viewer].prizes.size}", Modifier.weight(1f).fillMaxWidth(), BoardRed)
            Counter("${state.players[geometry.viewer].prizes.size}", Modifier.weight(1f).fillMaxWidth(), BoardBlue)
        }
        Text("CPU", Modifier.sceneRect(geometry.rect(.02f, .048f, .105f, .025f)), color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        // Opponent hand stays anonymous and deliberately gives no card identities.
        val count = state.players[1-geometry.viewer].hand.size.coerceAtMost(9)
        repeat(count) { index ->
            val w = geometry.width*.17f
            val gap = geometry.width*.115f
            val left = geometry.width/2 - ((count-1)*gap+w)/2 + index*gap
            CardBack(Modifier.sceneRect(Rect(left, -geometry.height*.065f, left+w, geometry.height*.037f))
                .graphicsLayer { rotationZ = (index-(count-1)/2f)*3f }, "")
        }
    }
}

@Composable private fun BoardPokemon(p: PokemonInstance, state: GameState, engine: RulesEngine, modifier: Modifier, accent: Color, pace: AnimationPace, inspect: () -> Unit) {
    val maxHp = engine.effects.effectiveHp(p, state)
    val remaining = (maxHp-p.damage).coerceAtLeast(0)
    val isActive = state.players.any { it.active?.id == p.id }
    val animatedHp by animateIntAsState(remaining, tween(pace.duration), label = "remaining hp")
    val attachmentPulse = remember(p.id) { Animatable(0f) }
    LaunchedEffect(p.energies.map { it.id }, p.tools.map { it.id }) {
        if (pace != AnimationPace.OFF && (p.energies.isNotEmpty() || p.tools.isNotEmpty())) {
            attachmentPulse.snapTo(1f)
            attachmentPulse.animateTo(0f, tween(pace.duration + 500))
        }
    }
    Box(modifier.clickable(onClick = inspect)) {
        CardArt(engine.definition(p.top), Modifier.fillMaxSize())
        if (attachmentPulse.value > 0f) Box(Modifier.fillMaxSize().border(3.dp, Aqua.copy(alpha = attachmentPulse.value), RoundedCornerShape(4.dp)))
        Surface(Modifier.align(Alignment.TopEnd).offset(x = 7.dp, y = (-4).dp), color = Color(0xFFF4F5EF), shape = RoundedCornerShape(4.dp), border = androidx.compose.foundation.BorderStroke(1.dp, accent)) {
            Text("$animatedHp/$maxHp", Modifier.padding(horizontal = 4.dp, vertical = 1.dp), color = if (remaining < maxHp/3) Color(0xFFA42D37) else Color(0xFF182A36), fontWeight = FontWeight.Black, fontSize = 10.sp, maxLines = 1)
        }
        if (p.damage > 0) Surface(Modifier.align(Alignment.BottomEnd).offset(x = 4.dp, y = 3.dp), color = Color(0xFFB33C43), shape = RoundedCornerShape(4.dp)) {
            Text("${p.damage}", Modifier.padding(horizontal = 4.dp), fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
        if (p.energies.isNotEmpty()) Surface(Modifier.align(Alignment.BottomStart).offset(x = (-4).dp, y = if (p.tools.isEmpty()) 3.dp else if (isActive) (-39).dp else (-30).dp), color = Color(0xFF173A51), shape = RoundedCornerShape(4.dp)) {
            val label = p.energies.groupingBy { engine.definition(it).type }.eachCount().entries.joinToString(" · ") { "${energyLabel(it.key)} ×${it.value}" }
            Text(label, Modifier.padding(horizontal = 4.dp), color = Color(0xFFFFD894), fontSize = 9.sp, fontWeight = FontWeight.Bold)
        }
        if (p.tools.isNotEmpty()) Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().offset(y = 4.dp)
            .background(Color(0xFF281E3E), RoundedCornerShape(3.dp)).border(1.dp, Color(0xFFC3A2F0), RoundedCornerShape(3.dp))) {
            p.tools.forEach { tool -> Row(Modifier.fillMaxWidth().padding(2.dp), verticalAlignment = Alignment.CenterVertically) {
                CardArt(engine.definition(tool), Modifier.size(if (isActive) 25.dp else 18.dp, if (isActive) 34.dp else 25.dp))
                Text(engine.definition(tool).name, Modifier.weight(1f).padding(start = 3.dp), color = Color.White,
                    fontSize = if (isActive) 10.sp else 8.sp, lineHeight = if (isActive) 11.sp else 9.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold)
            } }
        }
    }
}
@Composable private fun Counter(text: String, modifier: Modifier, accent: Color) {
    Box(modifier.background(Color(0xFF122839).copy(alpha = .9f), RoundedCornerShape(3.dp)).border(1.dp, accent, RoundedCornerShape(3.dp)), contentAlignment = Alignment.Center) {
        Text(text, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Black)
    }
}
