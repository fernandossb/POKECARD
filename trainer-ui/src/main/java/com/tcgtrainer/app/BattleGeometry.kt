package com.tcgtrainer.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.tcgtrainer.engine.*

/** All scene hit targets and animations share these measured, screen-relative coordinates. */
internal class BattleGeometry(val width: Float, val height: Float, val viewer: Int) {
    fun rect(x: Float, y: Float, w: Float, h: Float) = Rect(x*width, y*height, (x+w)*width, (y+h)*height)
    fun active(player: Int): Rect {
        val area = rect(.38f, if (player == viewer) .45f else .282f, .24f, .145f)
        return cardBounds(area)
    }
    private fun cardBounds(area: Rect): Rect {
        if (width <= height) return area
        val cardWidth = minOf(area.width, area.height * .727f)
        return Rect(area.center.x-cardWidth/2, area.top, area.center.x+cardWidth/2, area.bottom)
    }
    fun bench(player: Int) = rect(.19f, if (player == viewer) .605f else .025f, .62f, .24f)
    fun deck(player: Int) = cardBounds(rect(.87f, if (player == viewer) .67f else .125f, .14f, .088f))
    fun discard(player: Int) = rect(.87f, if (player == viewer) .775f else .252f, .14f, .06f)
    fun prizes(player: Int) = rect(-.015f, if (player == viewer) .65f else .048f, .15f, .17f)
    fun prize(player: Int, position: Int): Rect {
        val area = prizes(player)
        return cardBounds(Rect(area.left, area.top+position*height*.014f, area.right, area.top+position*height*.014f+height*.098f))
    }
    val activation get() = rect(.265f, .43f, .58f, .17f)
    val stadium get() = rect(.018f, .425f, .115f, .10f)
    val hand get() = rect(0f, .855f, 1f, .18f)
    fun benchCard(player: Int, index: Int, count: Int): Rect {
        val rows = if (count > 3 && !(width > height && count <= 5)) 2 else 1
        val columns = (count + rows - 1) / rows
        val row = index / columns
        val rowCount = minOf(columns, count - row * columns)
        val area = bench(player)
        val gap = width*.016f
        val availableWidth = (area.width-(columns-1)*gap)/columns
        val w = minOf(width * .18f, (area.height / rows - height * .014f) * .727f, availableWidth)
        val h = w/.727f
        val left = width/2 - (rowCount*w+(rowCount-1)*gap)/2 + (index % columns)*(w+gap)
        val top = if (player == viewer) area.top+height*.004f+row*(h+height*.01f) else area.bottom-h-height*.004f-row*(h+height*.01f)
        return Rect(left, top, left+w, top+h)
    }
    fun pokemon(state: GameState, player: Int, id: String): Rect {
        val p = state.players[player]
        if (p.active?.id == id) return active(player)
        val index = p.bench.indexOfFirst { it.id == id }
        return if (index >= 0) benchCard(player, index, p.bench.size) else active(player)
    }
    fun targets(state: GameState): List<Pair<BoardTarget, Rect>> = buildList {
        for (owner in 0..1) {
            state.players[owner].pokemon().forEach { add(BoardTarget.Pokemon(owner, it.id) to pokemon(state, owner, it.id)) }
            if (state.players[owner].active == null) add(BoardTarget.Active(owner) to active(owner))
        }
        add(BoardTarget.Stadium(viewer) to stadium)
        add(BoardTarget.Activation(viewer) to activation)
        add(BoardTarget.Bench(viewer) to bench(viewer))
    }
    fun targetAt(state: GameState, point: Offset): Pair<BoardTarget, Rect>? = targets(state).firstOrNull { it.second.contains(point) }
}
