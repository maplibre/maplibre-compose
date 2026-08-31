package org.maplibre.compose.map

import androidx.compose.runtime.Composable
import co.touchlab.kermit.Logger

/** Browser runtime configuration. */
public actual data class MapRuntimeOptions(
  public val logger: Logger? = Logger.withTag("maplibre-compose")
)

public actual fun createMapRuntime(options: MapRuntimeOptions): MapRuntime =
  RuntimeImplementation(
    platformOptions = options,
    resources = MapRuntimeResources {},
    logger = options.logger,
    snapshotterAdapterFactory = GlJsSnapshotterAdapterFactory(options.logger),
  )

private val processMapRuntime: MapRuntime =
  RuntimeImplementation(
    platformOptions = null,
    resources = MapRuntimeResources {},
    logger = Logger.withTag("maplibre-compose"),
    snapshotterAdapterFactory = GlJsSnapshotterAdapterFactory(Logger.withTag("maplibre-compose")),
  )

@Composable public actual fun rememberMapRuntime(): MapRuntime = processMapRuntime
