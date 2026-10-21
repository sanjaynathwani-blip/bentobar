package io.github.kuscher.bentobar.bar

import android.annotation.SuppressLint
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.RemoteViews
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.kuscher.bentobar.R
import io.github.kuscher.bentobar.items.MenuHost
import io.github.kuscher.bentobar.ui.MenuCard
import io.github.kuscher.bentobar.ui.MenuNote
import io.github.kuscher.bentobar.ui.SmallIconButton
import io.github.kuscher.bentobar.util.Sym
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * One widget an app publishes, as the home screen's widget picker lists it: its name, its size in home screen cells,
 * and a preview. The preview is the one the picker shows, where Android lets BentoBar have it: the app's own preview
 * made while it runs ([generated], Android 15), else its preview layout ([layout], drawn here from the app's resources
 * at the widget's own size), else its preview picture ([image]). Neither runs any of the app's code here, and a click
 * on a preview does nothing.
 */
@Immutable
data class AppWidget(
    /** What Android says of the widget: a layout preview is drawn the way the home screen draws it, from this. */
    val info: AppWidgetProviderInfo,
    val provider: ComponentName,
    val label: String,
    val cellsWide: Int,
    val cellsHigh: Int,
    /** The widget's size as laid out on the home screen, in dp: the size a [layout] or [generated] preview is drawn at. */
    val widthDp: Int,
    val heightDp: Int,
    val generated: RemoteViews?,
    val layout: Int,
    val image: ImageBitmap?,
)

/** The widgets an app publishes for the home screen (`AppWidgetManager`, which any app may ask). */
object AppWidgets {
    private const val TAG = "BentoBarApps"
    /** A preview picture is kept no bigger than this (px a side), which is more than the menu shows. */
    private const val IMAGE_MAX_PX = 720
    /** A home screen cell on a Googlebook's launcher, about: for widgets that only give a size in dp. */
    private const val CELL_DP = 70

    /** Slow (a package's resources, decoded pictures): never on the main thread. */
    fun load(context: Context, pkg: String): List<AppWidget> {
        val awm = AppWidgetManager.getInstance(context) ?: return emptyList()
        val pm = context.packageManager
        val density = context.resources.displayMetrics.density
        val infos = runCatching { awm.getInstalledProvidersForPackage(pkg, null) }.getOrDefault(emptyList())
            .filter { it.widgetCategory and AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN != 0 }
        return infos.mapNotNull { info ->
            runCatching {
                val wDp = (info.minWidth / density).roundToInt().coerceAtLeast(40)
                val hDp = (info.minHeight / density).roundToInt().coerceAtLeast(40)
                val cw = info.targetCellWidth.takeIf { it > 0 } ?: ((wDp + 30) / CELL_DP).coerceAtLeast(1)
                val ch = info.targetCellHeight.takeIf { it > 0 } ?: ((hDp + 30) / CELL_DP).coerceAtLeast(1)
                // The app's own preview, made while it runs: the picker's. Android may keep it to the home screen; then the next one.
                val generated = if (Build.VERSION.SDK_INT >= 35) runCatching {
                    awm.getWidgetPreview(info.provider, info.profile, AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN)
                }.onFailure { Log.i(TAG, "no generated preview for ${info.provider.shortClassName}: ${it.javaClass.simpleName}") }.getOrNull() else null
                val image = if (generated == null && info.previewLayout == 0)
                    info.loadPreviewImage(context, context.resources.displayMetrics.densityDpi)?.let(::bitmap)?.asImageBitmap() else null
                AppWidget(info, info.provider, info.loadLabel(pm).toString(), cw, ch, wDp, hDp, generated, info.previewLayout, image)
            }.onFailure { Log.w(TAG, "widget ${info.provider.flattenToShortString()}: $it") }.getOrNull()
        }
    }

    private fun bitmap(d: Drawable): Bitmap {
        val w = d.intrinsicWidth.coerceAtLeast(1)
        val h = d.intrinsicHeight.coerceAtLeast(1)
        val s = min(1f, IMAGE_MAX_PX.toFloat() / maxOf(w, h))
        val b = Bitmap.createBitmap((w * s).roundToInt().coerceAtLeast(1), (h * s).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, b.width, b.height)
        d.draw(Canvas(b))
        return b
    }
}

/**
 * A widget's preview, at most [maxWidth] by [maxHeight]. A layout preview is laid out at the widget's own size and
 * drawn scaled down, as the picker does, so its text keeps its proportions.
 */
@Composable
fun WidgetPreview(w: AppWidget, maxWidth: Dp, maxHeight: Dp, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(16.dp)
    val remote = w.generated ?: if (w.layout != 0) remember(w.provider, w.layout) { RemoteViews(w.provider.packageName, w.layout) } else null
    when {
        remote != null -> ScaledToFit(w.widthDp.dp, w.heightDp.dp, maxWidth, maxHeight, modifier.clip(shape)) {
            AndroidView(factory = { ctx -> InertFrame(ctx).apply { applyPreview(w.info, remote, this) } })
        }
        w.image != null -> {
            val ratio = w.image.width.toFloat() / w.image.height
            val width = min(maxWidth.value, maxHeight.value * ratio)
            Image(w.image, w.label, modifier.width(width.dp).clip(shape))
        }
        else -> Box(modifier.size(min(maxWidth.value, 96f).dp).clip(shape).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
            contentAlignment = Alignment.Center) { io.github.kuscher.bentobar.util.SymIcon(Sym.WIDGETS, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/**
 * Draws [remote] into [frame] as the home screen's picker does: in a widget host view that knows [info], which draws it
 * in the widget's own app's context and theme (drawn in BentoBar's, some came out blank). A layout this process can't
 * draw leaves the frame empty, and never takes the menu down.
 */
private fun applyPreview(info: AppWidgetProviderInfo, remote: RemoteViews, frame: FrameLayout) {
    runCatching {
        val host = AppWidgetHostView(frame.context)
        host.setAppWidget(AppWidgetManager.INVALID_APPWIDGET_ID, info)
        host.updateAppWidget(remote)
        frame.addView(host, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }.onFailure { Log.w("BentoBarApps", "can't draw a widget preview: ${it.javaClass.simpleName}") }
}

/** A frame that keeps every touch from what it holds: a preview's buttons would start the app's own actions. */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
private class InertFrame(context: Context) : FrameLayout(context) {
    init { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS }
    override fun onInterceptTouchEvent(ev: MotionEvent) = true
    override fun onTouchEvent(event: MotionEvent) = true
}

/** Lays [content] out at [width] by [height] and draws it scaled down to fit [maxWidth] by [maxHeight] (never up). */
@Composable
private fun ScaledToFit(width: Dp, height: Dp, maxWidth: Dp, maxHeight: Dp, modifier: Modifier, content: @Composable () -> Unit) {
    val d = LocalDensity.current
    Layout(content, modifier) { measurables, _ ->
        val w = with(d) { width.roundToPx() }
        val h = with(d) { height.roundToPx() }
        val s = min(1f, min(with(d) { maxWidth.toPx() } / w, with(d) { maxHeight.toPx() } / h))
        val placeable = measurables.first().measure(Constraints.fixed(w, h))
        layout((w * s).roundToInt(), (h * s).roundToInt()) {
            placeable.placeWithLayer(0, 0) { scaleX = s; scaleY = s; transformOrigin = TransformOrigin(0f, 0f) }
        }
    }
}

/**
 * The Widgets menu: each widget [app] publishes, with its preview, its name and size, and a + that adds it to the
 * home screen ([add]: through the home screen's own widget picker, see [WidgetAddRules]).
 */
@Composable
fun WidgetsMenu(app: FrontApp, widgets: List<AppWidget>, add: (AppWidget) -> Unit, host: MenuHost) {
    MenuCard(Sym.WIDGETS, stringResource(R.string.appmenu_widgets), subtitle = app.label) {
        for (w in widgets) Column(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                .padding(10.dp), contentAlignment = Alignment.Center) {
                WidgetPreview(w, maxWidth = 252.dp, maxHeight = 170.dp)
            }
            Row(Modifier.fillMaxWidth().padding(start = 6.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text(w.label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(stringResource(R.string.appmenu_widget_size, w.cellsWide, w.cellsHigh), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.width(4.dp))
                SmallIconButton(Sym.ADD, stringResource(R.string.appmenu_widget_add, w.label)) { host.afterClose { add(w) } }
            }
        }
        MenuNote(stringResource(R.string.appmenu_widgets_note))
    }
}
