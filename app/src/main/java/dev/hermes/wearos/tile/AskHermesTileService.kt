package dev.hermes.wearos.tile

import android.content.Context
import android.util.Log
import androidx.wear.tiles.LayoutElementBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders.Tile
import androidx.wear.tiles.TileService
import androidx.wear.tiles.ActionBuilders
import androidx.wear.tiles.ColorBuilders
import androidx.wear.tiles.DimensionBuilders
import androidx.wear.tiles.ModifiersBuilders
import androidx.wear.tiles.material.Text
import androidx.wear.tiles.material.Typography
import com.google.common.util.concurrent.ListenableFuture
import dev.hermes.wearos.MainActivity
import dev.hermes.wearos.R
import java.util.concurrent.TimeUnit

/**
 * "Ask Hermes" tile (SPEC §5 U3): brand-yellow mic + label; tapping anywhere opens
 * MainActivity with `talk=true`, which jumps straight into the existing hold-to-talk path
 * (or the compose box when the mic permission is missing).
 *
 * Refresh model: the system re-requests the tile whenever the user swipes onto it, and
 * [onTileRequest] builds the layout synchronously from current state — so the tile is always
 * current on enter without background work (the "TilePreviewData / Resource result" option in
 * the task; we need no editor-preview payload, hence no override of the tile preview).
 *
 * tiles 1.4.1 note: the click action must be a proto AndroidActivity (package + class +
 * extras) — newer protolayout `IntentAction` does not exist on this library line.
 */
class AskHermesTileService : TileService() {

    companion object {
        private const val TAG = "HermesWear"
        private const val RESOURCES_VERSION = "1"
        private const val BRAND_YELLOW = 0xFFF5C518.toInt()
    }

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<Tile> {
        Log.i(TAG, "tile requested")
        val tile = Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setFreshnessIntervalMillis(0) // never stale-poll; we refresh on enter
            .setTimeline(androidx.wear.tiles.TimelineBuilders.Timeline.fromLayoutElement(buildLayout(this)))
            .build()
        return ready(tile)
    }

    // The default throws "onTileResourcesRequest not implemented", and the tile stays black.
    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest,
    ): ListenableFuture<androidx.wear.protolayout.ResourceBuilders.Resources> =
        ready(androidx.wear.protolayout.ResourceBuilders.Resources.Builder().setVersion(RESOURCES_VERSION).build())

    private fun buildLayout(context: Context): LayoutElementBuilders.LayoutElement {
        val openTalk = ActionBuilders.LaunchAction.Builder()
            .setAndroidActivity(
                ActionBuilders.AndroidActivity.Builder()
                    .setPackageName(context.packageName)
                    .setClassName(MainActivity::class.java.name)
                    .addKeyToExtraMapping(
                        "talk",
                        ActionBuilders.AndroidBooleanExtra.Builder().setValue(true).build()
                    )
                    .build()
            )
            .build()
        val click = ModifiersBuilders.Clickable.Builder().setOnClick(openTalk).build()

        val mic = Text.Builder(context, "🎙")
            .setTypography(Typography.TYPOGRAPHY_DISPLAY2)
            .setColor(ColorBuilders.ColorProp.Builder().setArgb(BRAND_YELLOW).build())
            .setMultilineAlignment(LayoutElementBuilders.TEXT_ALIGN_CENTER)
            .build()
        val label = Text.Builder(context, context.getString(R.string.tile_label))
            .setTypography(Typography.TYPOGRAPHY_TITLE3)
            .setColor(ColorBuilders.ColorProp.Builder().setArgb(BRAND_YELLOW).build())
            .setMultilineAlignment(LayoutElementBuilders.TEXT_ALIGN_CENTER)
            .build()

        val column = LayoutElementBuilders.Column.Builder()
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(mic)
            .addContent(label)
            .build()
        return LayoutElementBuilders.Box.Builder()
            .setWidth(DimensionBuilders.ExpandedDimensionProp.Builder().build())
            .setHeight(DimensionBuilders.ExpandedDimensionProp.Builder().build())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .addContent(column)
            .setModifiers(ModifiersBuilders.Modifiers.Builder().setClickable(click).build())
            .build()
    }

    /** Guava-free immediate future (the tiny listenablefuture jar we get has no Futures). */
    private fun <T> ready(value: T) = object : ListenableFuture<T> {
        override fun addListener(listener: Runnable, executor: java.util.concurrent.Executor) =
            executor.execute(listener)

        override fun cancel(mayInterruptIfRunning: Boolean) = false
        override fun isCancelled() = false
        override fun isDone() = true
        override fun get(): T = value
        override fun get(timeout: Long, unit: TimeUnit): T = value
    }
}
