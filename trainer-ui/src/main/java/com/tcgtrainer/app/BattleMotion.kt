package com.tcgtrainer.app

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tcgtrainer.engine.CardDefinition
import kotlinx.coroutines.delay
import kotlin.math.*

internal enum class AnimationPace(val duration: Int) { NORMAL(520), FAST(240), OFF(0) }
internal data class CardFlight(val id: Long, val card: CardDefinition?, val from: Rect, val to: Rect,
    val delay: Int = 0, val knockout: Boolean = false, val returning: Boolean = false, val instanceId: String? = null)
internal data class Impact(val id: Long, val point: Offset, val damage: Int = 0, val color: Color = Aqua, val delay: Int = 0)

@Composable internal fun Modifier.sceneRect(rect: Rect): Modifier = with(LocalDensity.current) {
    this@sceneRect.offset { IntOffset(rect.left.roundToInt(), rect.top.roundToInt()) }.size(rect.width.toDp(), rect.height.toDp())
}

@Composable internal fun FlightLayer(flight: CardFlight, pace: AnimationPace, done: (Long) -> Unit) {
    val progress = remember(flight.id) { Animatable(0f) }
    val density = LocalDensity.current
    LaunchedEffect(flight.id) {
        delay(if (pace == AnimationPace.OFF) 0 else flight.delay.toLong())
        if (flight.returning && pace != AnimationPace.OFF) progress.animateTo(1f, spring(dampingRatio = .68f, stiffness = 440f))
        else progress.animateTo(1f, tween(if (pace == AnimationPace.OFF) 1 else pace.duration, easing = FastOutSlowInEasing))
        done(flight.id)
    }
    val t = progress.value
    val center = Offset(lerp(flight.from.center.x, flight.to.center.x, t), lerp(flight.from.center.y, flight.to.center.y, t) - sin(t*PI.toFloat())*flight.from.height*.22f)
    val w = lerp(flight.from.width, flight.to.width, t); val h = lerp(flight.from.height, flight.to.height, t)
    Box(Modifier.offset { IntOffset((center.x-w/2).roundToInt(), (center.y-h/2).roundToInt()) }
        .size(with(density) { w.toDp() }, with(density) { h.toDp() })
        .graphicsLayer {
            rotationZ = if (flight.knockout) t*25f else sin(t*PI.toFloat()) * if (flight.returning) -7f else 6f
            alpha = if (flight.knockout) (1f-t*.55f) else 1f
            shadowElevation = (1f-t)*28.dp.toPx(); shape = RoundedCornerShape(5.dp)
        }) {
        if (flight.card == null) CardBack(Modifier.fillMaxSize(), "") else CardArt(flight.card, Modifier.fillMaxSize())
    }
}

@Composable internal fun ImpactLayer(impact: Impact, pace: AnimationPace, done: (Long) -> Unit) {
    val progress = remember(impact.id) { Animatable(0f) }
    val density = LocalDensity.current
    LaunchedEffect(impact.id) {
        if (pace != AnimationPace.OFF) delay(impact.delay.toLong())
        progress.animateTo(1f, tween(if (pace == AnimationPace.OFF) 1 else pace.duration+200)); done(impact.id)
    }
    val t = progress.value
    Canvas(Modifier.fillMaxSize()) {
        val radius = (22f+t*62f)*density.density
        drawCircle(impact.color.copy(alpha = (1f-t)*.8f), radius, impact.point, style = Stroke((1f-t)*4.dp.toPx()+.5f))
        drawCircle(impact.color.copy(alpha = (1f-t)*.2f), radius*.68f, impact.point)
        repeat(12) { index ->
            val angle = index*PI.toFloat()/6
            val r = radius*(.7f+t*.55f)
            val point = impact.point + Offset(cos(angle)*r, sin(angle)*r)
            drawCircle(impact.color.copy(alpha = (1f-t)*.9f), (1f-t)*3.dp.toPx()+.3f, point)
        }
    }
    if (impact.damage > 0 && t > 0f) Text("−${impact.damage}", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Black,
        modifier = Modifier.offset { IntOffset((impact.point.x-40*density.density).roundToInt(), (impact.point.y-(30+t*55)*density.density).roundToInt()) }
            .graphicsLayer { alpha = if (t < .6f) 1f else (1f-t)/.4f; scaleX = 1f+sin(t*PI.toFloat())*.22f; scaleY = scaleX }
            .background(Color(0xFFAD303F).copy(alpha = .9f), RoundedCornerShape(7.dp)).padding(horizontal = 8.dp))
}

@Composable internal fun CoinPresentation(face: Int, pace: AnimationPace) {
    val spin = remember { Animatable(0f) }
    LaunchedEffect(face) { spin.animateTo(1f, tween(if (pace == AnimationPace.OFF) 1 else pace.duration*3, easing = FastOutSlowInEasing)) }
    val p = spin.value
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(132.dp).graphicsLayer {
            rotationY = p*1800f; translationY = -sin(p*PI.toFloat())*60.dp.toPx(); cameraDistance = 18*density
            shadowElevation = 18.dp.toPx(); shape = RoundedCornerShape(50); clip = true
        }.background(Brush.radialGradient(listOf(Color(0xFFFFEB9E), Color(0xFFD69C3D), Color(0xFF7D501E))))) {
            Box(Modifier.fillMaxSize().padding(7.dp).border(3.dp, Color(0xFFF8D981), RoundedCornerShape(50)).padding(7.dp).border(1.dp, Color(0xFF9E7029), RoundedCornerShape(50)), contentAlignment = Alignment.Center) {
                Text(if (face == 0) "TCG" else "TRAINER", color = Color(0xFF69481B), fontSize = if (face == 0) 27.sp else 17.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
            }
        }
        Spacer(Modifier.height(14.dp))
        Surface(color = Color(0xFFFFF7E4), shape = RoundedCornerShape(4.dp)) { Text(if (p < .97f) "LANÇANDO MOEDA" else if (face == 0) "CARA" else "COROA", Modifier.padding(horizontal = 26.dp, vertical = 8.dp), color = Color(0xFF1A2230), fontWeight = FontWeight.Black, letterSpacing = 2.sp) }
    }
}

private fun lerp(a: Float, b: Float, t: Float) = a+(b-a)*t
