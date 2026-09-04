package org.maplibre.compose.map

import org.maplibre.compose.logging.MapLog
import org.maplibre.compose.offline.UnsupportedOfflineManager
import org.maplibre.compose.resource.GlJsRequestController
import org.maplibre.compose.resource.MapRequestInterceptor
import org.maplibre.compose.resource.MapResourceConfig
import org.maplibre.compose.resource.MapResourceProvider

/** Browser runtime configuration. */
public actual data class MapRuntimeOptions(
  /** Rewrites URLs and headers for every resource this runtime fetches. */
  public val requestInterceptor: MapRequestInterceptor? = null,
  /** Serves bytes for resource URLs this provider accepts. */
  public val resourceProvider: MapResourceProvider? = null,
)

internal actual fun defaultMapRuntimeOptions(): MapRuntimeOptions = MapRuntimeOptions()

internal class JsRuntimePlatform(
  val options: MapRuntimeOptions,
  val requests: GlJsRequestController,
)

public actual fun createMapRuntime(options: MapRuntimeOptions): MapRuntime {
  val logger = MapLog
  val resourceConfig =
    MapResourceConfig(options.requestInterceptor, options.resourceProvider, logger)
  val requests = GlJsRequestController(resourceConfig)
  return RuntimeImplementation(
    platformOptions = JsRuntimePlatform(options, requests),
    resources = MapRuntimeResources { requests.close() },
    logger = logger,
    offlineManagerBackend = UnsupportedOfflineManager,
    snapshotterAdapterFactory = GlJsSnapshotterAdapterFactory(logger, requests),
    resourceConfig = resourceConfig,
  )
}

internal val RuntimeImplementation.jsRequests: GlJsRequestController?
  get() = (platformOptions as? JsRuntimePlatform)?.requests
