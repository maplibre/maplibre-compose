package org.maplibre.compose.map

import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.withContext
import org.maplibre.compose.mlnffi.MapRenderBackend
import org.maplibre.compose.util.DelicateMaplibreComposeApi
import org.maplibre.nativeffi.map.MapHandle

/** Provides the borrowed MapLibre Native map for one [MapState.withPlatformMap] callback. */
public actual class PlatformMapScope internal constructor(public val map: MapHandle)

@DelicateMaplibreComposeApi
public actual suspend fun <T> MapState.withPlatformMap(block: PlatformMapScope.() -> T): T {
  val (session, engine) =
    withContext(runtime.mainDispatcher) {
      val session =
        lifecycle.retainAdapterForPlatformAccess {
          MlnFfiMapSession(
              lifecycleAuthority = lifecycle,
              callbacks = durableStyleCallbacks(),
              logger = runtime.logger,
              renderBackend =
                loadRuntimeBackends(runtime.logger).firstOrNull() ?: MapRenderBackend.OpenGl,
              layoutDirection = LayoutDirection.Ltr,
              owner = runtime.nativeOwner,
            )
            .also { session ->
              session.setCameraPosition(cameraPosition)
              session.setBaseStyle(style.baseStyle)
            }
        } as MlnFfiMapSession
      session to session.ensureEngine()
    }
  return session.withPlatformMap(engine, block)
}
