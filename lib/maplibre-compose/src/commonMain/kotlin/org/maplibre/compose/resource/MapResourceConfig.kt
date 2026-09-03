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
  /** The request after [MapRequestInterceptor.rewriteUrl]. */
  val request: MapResourceRequest

  /** The engine fetches [request]. */
  data class Fetch(override val request: MapResourceRequest) : MapResourceRoute

  /** [provider] loads [request]. */
  data class Load(override val request: MapResourceRequest, val provider: MapResourceProvider) :
    MapResourceRoute
}

internal fun MapResourceConfig.route(
  request: MapResourceRequest,
  interceptor: MapRequestInterceptor? = interceptor(),
): MapResourceRoute {
  val rewritten = request.copy(url = interceptor.rewrittenUrl(request))
  val provider = provider
  return if (provider != null && provider.acceptsOrDeclines(rewritten)) {
    MapResourceRoute.Load(rewritten, provider)
  } else {
    MapResourceRoute.Fetch(rewritten)
  }
}

/**
 * The URL to fetch for [request]. A null interceptor, a null or blank rewrite, and a non-fatal
 * exception all keep the incoming URL.
 */
internal fun MapRequestInterceptor?.rewrittenUrl(request: MapResourceRequest): String {
  val rewrite =
    try {
      this?.rewriteUrl(request)
    } catch (_: Exception) {
      null
    }
  return if (rewrite.isNullOrBlank()) request.url else rewrite
}

/** The headers for [request]. A null interceptor and a non-fatal exception add no headers. */
internal fun MapRequestInterceptor?.headersOrNone(
  request: MapResourceRequest
): Map<String, String> =
  try {
    this?.headers(request) ?: emptyMap()
  } catch (_: Exception) {
    emptyMap()
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
