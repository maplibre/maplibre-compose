package org.maplibre.compose.map

import androidx.compose.ui.unit.LayoutDirection
import org.maplibre.compose.mlnffi.MapRenderBackend
import org.maplibre.nativeffi.map.MapHandle

/** Provides the borrowed MapLibre Native map for one [MapState.withPlatformMap] callback. */
public actual class PlatformMapScope internal constructor(public val map: MapHandle)

@DelicateMapApi
public actual suspend fun <T> MapState.withPlatformMap(block: PlatformMapScope.() -> T): T {
  val session =
    lifecycle.retainAdapterForPlatformAccess {
      val options = runtime.nativeRuntimeOptions
      MlnFfiMapSession(
          lifecycleAuthority = lifecycle,
          callbacks = durableStyleCallbacks(),
          logger = runtime.logger,
          renderBackend =
            loadRuntimeBackends(runtime.logger).firstOrNull() ?: MapRenderBackend.OPENGL,
          layoutDirection = LayoutDirection.Ltr,
          cacheFile = options.cacheFile,
          resourceProviderFactory = options.resourceProviderFactory,
        )
        .also { session ->
          session.setCameraPosition(cameraPosition)
          session.setBaseStyle(style.baseStyle)
        }
    } as MlnFfiMapSession
  return session.withPlatformMap(block)
}
