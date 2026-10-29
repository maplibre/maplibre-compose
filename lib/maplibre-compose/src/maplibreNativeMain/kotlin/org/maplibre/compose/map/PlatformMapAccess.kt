package org.maplibre.compose.map

import kotlinx.coroutines.withContext
import org.maplibre.compose.util.DelicateMaplibreComposeApi
import org.maplibre.compose.util.ExperimentalMaplibreComposeApi
import org.maplibre.nativeffi.MapHandle

/** Provides the borrowed MapLibre Native map for one [MapState.withPlatformMap] callback. */
@DelicateMaplibreComposeApi
// Exposes maplibre-native-ffi bindings, which may change in minor releases.
@ExperimentalMaplibreComposeApi
public actual class PlatformMapScope internal constructor(public val map: MapHandle)

@DelicateMaplibreComposeApi
// Exposes maplibre-native-ffi bindings, which may change in minor releases.
@ExperimentalMaplibreComposeApi
public actual suspend fun <T> MapState.withPlatformMap(block: PlatformMapScope.() -> T): T {
  return withContext(runtime.mainDispatcher) {
    val attachment = attachmentAuthority.awaitAttachment()
    val session = attachment.adapter as MlnFfiMapSession
    if (!attachment.isLive) throw kotlinx.coroutines.CancellationException("The presentation ended")
    session.attachPresentation()
    if (!attachment.isLive) throw kotlinx.coroutines.CancellationException("The presentation ended")
    session.withPlatformMap(block)
  }
}
