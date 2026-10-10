package org.maplibre.compose.map

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.maplibre.nativeffi.generated.MapHandle
import org.maplibre.nativeffi.generated.StyleImageInfo

/**
 * Runs [action] with this session's map, or returns null when the map does not exist. Tests only;
 * the reads in [org.maplibre.compose.map] wait for every command submitted before them.
 */
internal fun <T> MlnFfiMapSession.readMap(action: (MapHandle) -> T): T? = mapForTest?.let(action)

internal fun MlnFfiMapSession.currentStyleLayerIds(): List<String> = readMap {
  it.styleLayerIds()
}
  .orEmpty()

internal fun MlnFfiMapSession.styleImageInfo(imageId: String): StyleImageInfo? = readMap {
  it.styleImageInfo(imageId)
}

/**
 * Holds back this session's runtime events until [release] returns, and returns once the event pump
 * is held. Commands still reach native meanwhile, so the events they raise wait for one drain.
 * [onHeld] runs on the pump as the hold begins. Tests only.
 */
internal fun MlnFfiMapSession.holdEventPump(
  onHeld: () -> Unit = {},
  release: suspend () -> Unit,
) {
  val runtime = runtimeForTest
  val held = CompletableDeferred<Unit>()
  runtime.beforeDrainForTest = {
    runtime.beforeDrainForTest = null
    onHeld()
    held.complete(Unit)
    release()
  }
  runtime.requestDrain()
  runBlocking { held.await() }
}
