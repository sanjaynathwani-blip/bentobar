package io.github.kuscher.bentobar.data

import kotlinx.serialization.Serializable

/** Where an item lives, like Bartender's sections. */
@Serializable
enum class Section { SHOWN, HIDDEN, OFF }

/** How an item draws in the bar. */
@Serializable
enum class Display {
    ICON_AND_TEXT, TEXT, ICON;

    /** A route line stands in the icon's place: an item shown as text alone has none. The item asks for one by this, and the strip draws one by it. */
    val line: Boolean get() = this != TEXT
}

/** Where BentoBar's strip sits in the status bar's free space. */
@Serializable
enum class Position { RIGHT, CENTER, LEFT }

@Serializable
enum class ColorMode { AUTO, LIGHT, DARK }

@Serializable
enum class TextSize { SMALL, DEFAULT, LARGE }

@Serializable
enum class Pill { NONE, SUBTLE, SOLID }

/** When the running timer or next meeting also shows as an Android Live Update chip. */
@Serializable
enum class ChipMode { OFF, FALLBACK, ALWAYS }

/** One item the user added. [id] is unique per instance; [type] picks the [ItemType]. */
@Serializable
@androidx.compose.runtime.Immutable
data class ItemConfig(
    val id: String,
    val type: String,
    val section: Section = Section.SHOWN,
    /** Hidden items with this on pop into the bar while they have something to say. */
    val whenActive: Boolean = false,
    val display: Display = Display.ICON_AND_TEXT,
    val options: Map<String, String> = emptyMap(),
) {
    fun opt(key: String, default: String) = options[key] ?: default
    fun optInt(key: String, default: Int) = options[key]?.toIntOrNull() ?: default
    fun optBool(key: String, default: Boolean) = options[key]?.toBooleanStrictOrNull() ?: default
    fun with(key: String, value: String?) =
        copy(options = if (value == null) options - key else options + (key to value))
}

/**
 * These items with [id] moved into [section] at [index] among that section's other items (the end
 * if out of range). Unchanged if [id] isn't there. [Store.move] and the drag preview both use it.
 */
fun List<ItemConfig>.moved(id: String, section: Section, index: Int): List<ItemConfig> {
    val item = firstOrNull { it.id == id } ?: return this
    val rest = filterNot { it.id == id }
    val inSection = rest.withIndex().filter { it.value.section == section }
    val at = when {
        inSection.isEmpty() -> rest.size
        index >= inSection.size -> inSection.last().index + 1
        else -> inSection[index.coerceAtLeast(0)].index
    }
    return rest.toMutableList().apply { add(at, item.copy(section = section)) }
}

/**
 * A city added to World clock's list: its time zone (an IANA id such as "Asia/Tokyo") and the name
 * the user gave it; without one it is called by the zone's own city.
 */
@Serializable
@androidx.compose.runtime.Immutable
data class WorldCity(val zone: String, val name: String = "")

@Serializable
data class BarConfig(
    val version: Int = 3,
    /** Master switch (the Quick Settings tile flips it, e.g. for presenting). */
    val enabled: Boolean = true,
    val items: List<ItemConfig> = emptyList(),
    val position: Position = Position.RIGHT,
    /** How the Hidden section works (version 3). */
    val hiddenMode: HiddenMode = HiddenMode.SHOW_ALL,
    /** Before version 3: show the ‹ button. Read only by [migrateToV3]. */
    val chevron: Boolean = true,
    /** Before version 3: reveal hidden items on hover. Read only by [migrateToV3]. */
    val revealOnHover: Boolean = false,
    /** Hide revealed items again after this many seconds; 0 (default) keeps them until ‹ is clicked. */
    val autoCollapseSec: Int = 0,
    /** Hidden items pinned open with ‹, kept across restarts (an update, a reboot). */
    val pinnedOpen: Boolean = false,
    /** Presenting: only a running timer or a meeting about to start, plus ‹ for the menu. */
    val presenting: Boolean = false,
    /** Permissions the user switched off in Setup ([Uses] keys): BentoBar doesn't use them even if granted. */
    val turnedOff: Set<String> = emptySet(),
    val textSize: TextSize = TextSize.DEFAULT,
    val pill: Pill = Pill.NONE,
    val color: ColorMode = ColorMode.AUTO,
    /** Space between items in dp (12 matches the system icons' rhythm on the desktop bar). */
    val spacing: Int = 12,
    /** With a popup open, pointing at another item opens its popup, as in a menu bar. Off: nothing opens on hover by itself. */
    val hoverSwitchesPopups: Boolean = false,
    /** The app menu bar (fork): the app in front's menus (its name, Window, Actions, Settings; Tab in debug builds) right of the clock. */
    val appMenuBar: Boolean = true,
    /** "No animations": BentoBar's popups, highlight and items appear and go at once (`ui/Motion`). */
    val noAnimations: Boolean = false,
    /** "Tint with system colors": the popups' glass takes a tint of the system's (wallpaper's) palette. Off: neutral glass. */
    val systemTint: Boolean = false,
    /**
     * Mirror the running timer or next meeting as an Android Live Update chip: FALLBACK only
     * while BentoBar's own bar isn't showing (accessibility off, or hidden with the tile).
     */
    val chipMode: ChipMode = ChipMode.FALLBACK,
    val onboarded: Boolean = false,
    /**
     * The cities added to World clock: one list for the bar, shown in every clock item's menu, and
     * part of the layout (Copy settings carries it). Layouts saved before it existed have none.
     */
    val cities: List<WorldCity> = emptyList(),
)

/** This config with the settings that belong to one install taken from [local] (see [Store.import]). */
fun BarConfig.keepingLocal(local: BarConfig): BarConfig = copy(enabled = local.enabled, turnedOff = local.turnedOff,
    presenting = local.presenting, pinnedOpen = local.pinnedOpen, onboarded = local.onboarded)

/**
 * What BentoBar uses, as switched in Setup: a permission counts only if Android granted it and the
 * user hasn't switched it off here.
 */
object Uses {
    const val NOTIFICATIONS = "notifications"
    const val CALENDAR = "calendar"
    const val EXACT_ALARMS = "exactAlarms"

    fun on(key: String) = key !in Store.config.value.turnedOff
}

/**
 * How hidden items work. SHOW_ALL (the default): no ‹; an item in Hidden with a "Show when" rule
 * appears only while it applies, the others always show. CLICK and HOVER: hidden items wait behind
 * ‹ and come out on a click, or on hover too.
 */
@Serializable
enum class HiddenMode { SHOW_ALL, CLICK, HOVER }

/**
 * Version 3 replaced the ‹ switch and "reveal on hover" with [HiddenMode]. With ‹ off, a hidden
 * item without a rule never showed; under SHOW_ALL it would, so those move to Off and the bar looks
 * the same as before.
 */
fun BarConfig.migrateToV3(): BarConfig = if (version >= 3) this else copy(
    version = 3,
    hiddenMode = when { !chevron -> HiddenMode.SHOW_ALL; revealOnHover -> HiddenMode.HOVER; else -> HiddenMode.CLICK },
    items = if (chevron) items else items.map { if (it.section == Section.HIDDEN && !it.whenActive) it.copy(section = Section.OFF) else it },
)

/** Whether an item in the bar config is drawn right now (outside presenting), for the strip and the preview. */
fun BarConfig.shows(item: ItemConfig, active: Boolean): Boolean = when (item.section) {
    Section.SHOWN -> true
    Section.HIDDEN -> if (hiddenMode == HiddenMode.SHOW_ALL) !item.whenActive || active else item.whenActive && active
    Section.OFF -> false
}

/**
 * The items waiting behind ‹ (the click and hover modes; Show everything has no ‹ for them): hidden
 * ones that aren't out on their own. The strip, the settings preview and the controller all ask here.
 */
fun BarConfig.behindChevron(active: (ItemConfig) -> Boolean): List<ItemConfig> =
    if (hiddenMode == HiddenMode.SHOW_ALL) emptyList() else items.filter { it.section == Section.HIDDEN && !shows(it, active(it)) }

/**
 * The items the ‹ menu lists as hidden: in the layout but not drawn right now. Under Show everything
 * that's a rule item waiting for its rule, never a hidden item that is drawn.
 */
fun BarConfig.notDrawn(active: (ItemConfig) -> Boolean): List<ItemConfig> =
    items.filter { it.section != Section.OFF && !shows(it, active(it)) }

/** Whether [shows] can be true for [item] once its "Show when" rule applies: the items worth sampling. */
fun BarConfig.couldShow(item: ItemConfig): Boolean = shows(item, active = true)
