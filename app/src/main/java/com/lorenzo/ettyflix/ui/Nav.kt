package com.lorenzo.ettyflix.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lorenzo.ettyflix.data.LiveChannel
import com.lorenzo.ettyflix.data.MediaItem
import com.lorenzo.ettyflix.data.PlayRequest
import com.lorenzo.ettyflix.data.PlayerSource
import com.lorenzo.ettyflix.data.SourceDef
import com.lorenzo.ettyflix.data.Tile

sealed class Screen {
    /** schede principali (menu laterale) */
    data object Home : Screen()
    data object Search : Screen()
    data object Library : Screen()
    data object Live : Screen()
    data object Settings : Screen()

    data class Detail(val item: MediaItem) : Screen()
    data class TilePage(val tile: Tile) : Screen()
    data class Grid(val title: String, val sources: List<SourceDef>, val items: List<MediaItem>? = null) : Screen()
    data class Person(val id: Int, val name: String) : Screen()
    data class Recommended(val type: String) : Screen()
    data class Sources(val req: PlayRequest) : Screen()
    data class Player(val req: PlayRequest, val sources: List<PlayerSource>, val selected: Int) : Screen()
    data class LivePlayer(val channel: LiveChannel) : Screen()

    val isTab: Boolean get() = this is Home || this is Search || this is Library || this is Live || this is Settings
    val fullScreen: Boolean get() = this is Player || this is LivePlayer || this is Sources
}

object Nav {
    val stack = mutableStateListOf<Screen>(Screen.Home)
    /** titolo per cui è aperto il menu (tasto ☰) */
    var menu by mutableStateOf<MediaItem?>(null)

    val top: Screen get() = stack.last()
    val tab: Screen get() = stack.first()

    fun push(s: Screen) { stack.add(s) }

    fun pop(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    fun replaceTop(s: Screen) { stack[stack.lastIndex] = s }

    fun selectTab(s: Screen) {
        stack.clear()
        stack.add(s)
    }

    fun play(req: PlayRequest) = push(Screen.Sources(req))
}
