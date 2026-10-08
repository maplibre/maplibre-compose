package org.maplibre.compose.map

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.maplibre.compose.logging.MapLog
import org.maplibre.compose.resource.GlJsRequestController
import org.maplibre.compose.resource.MapRequestInterceptor
import org.maplibre.compose.resource.MapResourceConfig
import org.maplibre.compose.resource.MapResourceProvider

@Immutable
public actual data class MapRuntimeOptions
internal constructor(
  public actual val requestInterceptor: MapRequestInterceptor?,
  public actual val resourceProvider: MapResourceProvider?,
  public actual val mainDispatcher: CoroutineDispatcher,
) {
  public actual constructor(
    from: MapRuntimeOptions,
    block: Builder.() -> Unit,
  ) : this(Builder(from).apply(block))

  private constructor(
    builder: Builder
  ) : this(builder.requestInterceptor, builder.resourceProvider, builder.mainDispatcher)

  @MapOptionsDsl
  public actual class Builder internal constructor(from: MapRuntimeOptions) {
    public actual var requestInterceptor: MapRequestInterceptor? = from.requestInterceptor
    public actual var resourceProvider: MapResourceProvider? = from.resourceProvider
    public actual var mainDispatcher: CoroutineDispatcher = from.mainDispatcher
  }

  public actual companion object {
    public actual val Standard: MapRuntimeOptions =
      MapRuntimeOptions(
        requestInterceptor = null,
        resourceProvider = null,
        mainDispatcher = Dispatchers.Main,
      )
  }
}

public actual fun createMapRuntime(
  from: MapRuntimeOptions,
  block: MapRuntimeOptions.Builder.() -> Unit,
): MapRuntime {
  val options = MapRuntimeOptions(from, block)
  val logger = MapLog
  val resourceConfig =
    MapResourceConfig(options.requestInterceptor, options.resourceProvider, logger)
  val requests = GlJsRequestController(resourceConfig)
  return MapRuntime(
    platformContext = requests,
    closeResources = { requests.close() },
    logger = logger,
    mainDispatcher =
      if (
        options.mainDispatcher === MapRuntimeOptions.Standard.mainDispatcher ||
          options.mainDispatcher === Dispatchers.Main
      )
        platformMainDispatcher()
      else options.mainDispatcher,
    createSnapshotterAdapter = { GlJsSnapshotterAdapter(logger, requests) },
    resourceConfig = resourceConfig,
  )
}

internal val MapRuntime.jsRequests: GlJsRequestController?
  get() = platformContext as? GlJsRequestController
