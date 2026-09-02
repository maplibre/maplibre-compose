package org.maplibre.compose.map

import androidx.compose.runtime.Composable
import co.touchlab.kermit.Logger
import org.maplibre.compose.offline.EmptyOfflineManager
import org.maplibre.compose.resource.GlJsRequestController
import org.maplibre.compose.resource.MapRequestInterceptor
import org.maplibre.compose.resource.MapResourceConfig
import org.maplibre.compose.resource.MapResourceProvider

private val webMapRuntimeCapabilities =
  MapRuntimeCapabilities(
    supportsOfflinePacks = false,
    supportsAmbientCacheManagement = false,
  )

/** Browser runtime configuration. */
public actual data class MapRuntimeOptions(
  public val logger: Logger? = Logger.withTag("maplibre-compose"),
  /** Rewrites URLs and headers for every resource this runtime fetches. */
  public val requestInterceptor: MapRequestInterceptor? = null,
  /** Serves bytes for resource URLs this provider accepts. */
  public val resourceProvider: MapResourceProvider? = null,
)

internal class JsRuntimePlatform(
  val options: MapRuntimeOptions,
  val requests: GlJsRequestController,
)

public actual fun createMapRuntime(options: MapRuntimeOptions): MapRuntime {
  val resourceConfig = MapResourceConfig(options.requestInterceptor, options.resourceProvider)
  val requests = GlJsRequestController(resourceConfig)
  return RuntimeImplementation(
    platformOptions = JsRuntimePlatform(options, requests),
    resources = MapRuntimeResources { requests.close() },
    logger = options.logger,
    capabilities = webMapRuntimeCapabilities,
    offlineManagerBackend = EmptyOfflineManager,
    snapshotterAdapterFactory = GlJsSnapshotterAdapterFactory(options.logger, requests),
    resourceConfig = resourceConfig,
  )
}

internal val RuntimeImplementation.jsRequests: GlJsRequestController?
  get() = (platformOptions as? JsRuntimePlatform)?.requests

private val defaultMapRuntime: MapRuntime by lazy { createMapRuntime(MapRuntimeOptions()) }

@Composable public actual fun rememberDefaultMapRuntime(): MapRuntime = defaultMapRuntime
