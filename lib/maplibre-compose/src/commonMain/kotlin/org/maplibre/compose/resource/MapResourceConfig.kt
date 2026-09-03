package org.maplibre.compose.resource

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Live interceptor and construction-time provider for one [org.maplibre.compose.map.MapRuntime].
 */
@OptIn(ExperimentalAtomicApi::class)
internal class MapResourceConfig(
  interceptor: MapRequestInterceptor? = null,
  val provider: MapResourceProvider? = null,
) {
  private val interceptorRef = AtomicReference(interceptor)

  fun interceptor(): MapRequestInterceptor? = interceptorRef.load()

  fun setInterceptor(interceptor: MapRequestInterceptor?) {
    interceptorRef.store(interceptor)
  }
}

internal sealed interface MapResourceRoute {
  val request: MapResourceRequest

  data class Fetch(
    override val request: MapResourceRequest,
    val transform: MapRequestTransform,
  ) : MapResourceRoute

  data class Load(
    override val request: MapResourceRequest,
    val provider: MapResourceProvider,
  ) : MapResourceRoute
}

internal fun MapResourceConfig.route(request: MapResourceRequest): MapResourceRoute {
  val transform = interceptor().transform(request)
  val transformed = request.copy(url = transform.url ?: request.url)
  val provider = provider
  return if (provider != null && provider.acceptsOrDeclines(transformed)) {
    MapResourceRoute.Load(transformed, provider)
  } else {
    MapResourceRoute.Fetch(transformed, transform)
  }
}

internal fun MapRequestInterceptor?.transform(request: MapResourceRequest): MapRequestTransform {
  val result =
    try {
      this?.intercept(request) ?: MapRequestTransform()
    } catch (_: Exception) {
      MapRequestTransform()
    }
  return if (result.url != null && result.url.isBlank()) result.copy(url = null) else result
}

/**
 * Returns false when [MapResourceProvider.accepts] throws, so an engine callback can still decide.
 */
internal fun MapResourceProvider.acceptsOrDeclines(request: MapResourceRequest): Boolean =
  try {
    accepts(request)
  } catch (_: Exception) {
    false
  }
