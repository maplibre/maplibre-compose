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

internal fun MapRequestInterceptor?.transform(request: MapResourceRequest): MapRequestTransform =
  try {
    this?.intercept(request) ?: MapRequestTransform()
  } catch (_: Throwable) {
    MapRequestTransform()
  }
