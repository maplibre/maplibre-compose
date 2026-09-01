package org.maplibre.compose.map

import androidx.compose.runtime.Composable
import co.touchlab.kermit.Logger
import org.maplibre.compose.offline.EmptyOfflineManager

private val webMapRuntimeCapabilities =
  MapRuntimeCapabilities(
    supportsOfflinePacks = false,
    supportsAmbientCacheManagement = false,
  )

/** Browser runtime configuration. */
public actual data class MapRuntimeOptions(
  public val logger: Logger? = Logger.withTag("maplibre-compose")
)

public actual fun createMapRuntime(options: MapRuntimeOptions): MapRuntime =
  RuntimeImplementation(
    platformOptions = options,
    resources = MapRuntimeResources {},
    logger = options.logger,
    capabilities = webMapRuntimeCapabilities,
    offlineManagerBackend = EmptyOfflineManager,
    snapshotterAdapterFactory = GlJsSnapshotterAdapterFactory(options.logger),
  )

private val processMapRuntime: MapRuntime = createMapRuntime(MapRuntimeOptions())

@Composable public actual fun rememberMapRuntime(): MapRuntime = processMapRuntime
