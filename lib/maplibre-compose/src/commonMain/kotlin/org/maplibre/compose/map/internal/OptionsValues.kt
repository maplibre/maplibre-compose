package org.maplibre.compose.map.internal

import org.maplibre.compose.map.DebugOverlays
import org.maplibre.compose.map.MapUiOptions
import org.maplibre.compose.map.RenderOptions
import org.maplibre.compose.util.formatToString

internal fun RenderOptions.validate() {
  val maximumFps = maximumFps
  require(maximumFps == null || maximumFps > 0) {
    "maximumFps must be positive, was $maximumFps"
  }
}

internal fun RenderOptions.commonEquals(other: RenderOptions): Boolean =
  maximumFps == other.maximumFps && tileLod == other.tileLod && debug == other.debug

internal fun RenderOptions.commonHashCode(platformHashCode: Int): Int =
  listOf(maximumFps, tileLod, debug, platformHashCode).hashCode()

internal fun RenderOptions.commonToString(vararg platformFields: Pair<String, Any?>): String =
  formatToString(
    "RenderOptions",
    "maximumFps" to maximumFps,
    "tileLod" to tileLod,
    "debug" to debug,
    *platformFields,
  )

internal fun DebugOverlays.commonEquals(other: DebugOverlays): Boolean =
  tileBorders == other.tileBorders && collisionBoxes == other.collisionBoxes

internal fun DebugOverlays.commonHashCode(platformHashCode: Int): Int =
  listOf(tileBorders, collisionBoxes, platformHashCode).hashCode()

internal fun DebugOverlays.commonToString(vararg platformFields: Pair<String, Any?>): String =
  formatToString(
    "DebugOverlays",
    "tileBorders" to tileBorders,
    "collisionBoxes" to collisionBoxes,
    *platformFields,
  )

internal fun MapUiOptions.commonEquals(other: MapUiOptions): Boolean =
  loadColor == other.loadColor && bindings == other.bindings

internal fun MapUiOptions.commonHashCode(platformHashCode: Int): Int =
  listOf(loadColor, bindings, platformHashCode).hashCode()

internal fun MapUiOptions.commonToString(vararg platformFields: Pair<String, Any?>): String =
  formatToString("MapUiOptions", "loadColor" to loadColor, "bindings" to bindings, *platformFields)
