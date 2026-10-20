package io.github.kuscher.bentobar.bar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The window list as a Googlebook gave it (2026-10-19): Terminal in front with its caption, Text maximized behind. */
class FrontAppRulesTest {
    private val overlay = SeenWindow(129, 4, active = false, focused = false, 2407, 0, 2601, 59)
    private val statusBar = SeenWindow(7, 3, active = false, focused = false, 0, 0, 2880, 59)
    private val terminalCaption = SeenWindow(105, 7, active = false, focused = false, 0, 59, 1720, 124)
    private val terminal = SeenWindow(99, 1, active = true, focused = true, 0, 59, 1720, 1709)
    private val textCaption = SeenWindow(113, 7, active = false, focused = false, 0, 59, 2880, 124)
    private val text = SeenWindow(114, 1, active = false, focused = false, 0, 59, 2880, 1709)
    private val all = listOf(overlay, statusBar, terminalCaption, terminal, textCaption, text)

    @Test fun theActiveAppWindowIsInFront() = assertEquals(terminal, FrontAppRules.front(all))

    @Test fun eachWindowGetsItsOwnCaption() {
        assertEquals(terminalCaption, FrontAppRules.caption(all, terminal))
        // Text's caption starts at the same corner as Terminal's: the width tells them apart.
        assertEquals(textCaption, FrontAppRules.caption(all, text))
    }

    @Test fun aTwoPaneWindowIsListedAsItsRightPaneUnderItsWholeCaption() {
        val settingsCaption = SeenWindow(149, 7, active = false, focused = false, 721, 247, 2794, 312)
        val settings = SeenWindow(142, 1, active = true, focused = true, 1405, 247, 2794, 1543)
        assertEquals(settingsCaption, FrontAppRules.caption(listOf(statusBar, settingsCaption, settings, textCaption, text), settings))
        // Another window's caption just above it, at its top but narrower than it, is not taken for its own.
        val wide = settings.copy(top = 59, left = 900)
        assertNull(FrontAppRules.caption(listOf(statusBar, terminalCaption.copy(left = 1000), wide), wide))
    }

    @Test fun aFullScreenWindowHasNoCaption() {
        // Text's caption is still listed above it, but along the top of the window it covered before.
        val full = terminal.copy(top = 0, bottom = 1800)
        assertNull(FrontAppRules.caption(listOf(statusBar, terminalCaption, full, textCaption, text), full))
    }

    @Test fun ourPopupTakingTheFocusKeepsTheAppItHad() {
        val popup = SeenWindow(140, 4, active = true, focused = true, 40, 70, 400, 500)
        val list = listOf(popup, statusBar, terminalCaption, terminal.copy(active = false, focused = false))
        assertEquals(99, FrontAppRules.next(list, before = 99))
    }

    @Test fun anotherAppComingForwardIsFollowed() {
        val list = listOf(statusBar, terminalCaption, terminal.copy(active = false, focused = false), textCaption, text.copy(active = true, focused = true))
        assertEquals(114, FrontAppRules.next(list, before = 99))
    }

    @Test fun nothingBeforeAndNothingActiveIsNothing() = assertNull(FrontAppRules.next(listOf(statusBar, overlay), before = null))

    // ---- pick: which app the menus are for --------------------------------------------------------------------

    private val packages = mapOf(99 to "com.android.virtualization.terminal", 114 to "com.example.text", 200 to "com.google.android.apps.nexuslauncher",
        300 to "io.github.kuscher.bentobar")
    private fun pick(list: List<SeenWindow>, following: Int? = null, shown: Int? = null, unreadable: Set<Int> = emptySet()) =
        FrontAppRules.pick(list, following, shown, { id -> if (id in unreadable) null else packages[id] }, home = "com.google.android.apps.nexuslauncher", self = "io.github.kuscher.bentobar")

    private val homeScreen = SeenWindow(200, 1, active = true, focused = true, 0, 0, 2880, 1800)
    private val ourSettings = SeenWindow(300, 1, active = true, focused = true, 400, 300, 2000, 1500)

    @Test fun theAppInFrontGetsItsMenus() =
        assertEquals(FrontAppRules.Pick.Show(99, "com.android.virtualization.terminal"), pick(all))

    @Test fun theHomeScreenGetsNone() =
        assertEquals(FrontAppRules.Pick.Hide, pick(listOf(statusBar, terminalCaption, terminal.copy(active = false, focused = false), homeScreen), following = 99, shown = 99))

    @Test fun aFullScreenAppKeepsItsMenus() {
        val full = terminal.copy(top = 0, bottom = 1800)
        assertEquals(FrontAppRules.Pick.Show(99, "com.android.virtualization.terminal"), pick(listOf(statusBar, full)))
    }

    @Test fun aWindowNotReadableYetGetsNoMenusNotTheOldAppsOnes() {
        // Text came forward but its tree isn't there yet: the bar must not go on naming Terminal (a Close would close Terminal).
        val list = listOf(statusBar, textCaption, text.copy(active = true, focused = true), terminalCaption, terminal.copy(active = false, focused = false))
        assertEquals(FrontAppRules.Pick.Unreadable, pick(list, following = 99, shown = 99, unreadable = setOf(114)))
    }

    @Test fun ourSettingsWindowKeepsTheAppBeforeItWhileItsWindowIsListed() {
        val list = listOf(statusBar, ourSettings, terminalCaption, terminal.copy(active = false, focused = false))
        assertEquals(FrontAppRules.Pick.Keep, pick(list, following = 99, shown = 99))
        // Terminal closed behind our settings window: no menus for a window that has gone.
        assertEquals(FrontAppRules.Pick.Hide, pick(listOf(statusBar, ourSettings), following = 99, shown = 99))
    }

    @Test fun theWindowFollowedGoneWithOtherAppsListedHides() {
        // Terminal closed and nothing came forward: Text is listed, inactive, so the list didn't end early.
        assertEquals(FrontAppRules.Pick.Hide, pick(listOf(statusBar, textCaption, text), following = 99, shown = 99))
    }

    @Test fun aListThatEndedEarlyKeepsTheMenus() {
        // A dialog on top: the list holds it and nothing below, no app window at all.
        val dialog = SeenWindow(400, 2, active = true, focused = true, 900, 500, 1900, 1200)
        assertEquals(FrontAppRules.Pick.Keep, pick(listOf(statusBar, dialog), following = 99, shown = 99))
    }
}
