package org.maplibre.compose.resource

import org.maplibre.compose.logging.MapLog

/** Construction-time interceptor and provider for one [org.maplibre.compose.map.MapRuntime]. */
internal class MapResourceConfig(
  val interceptor: MapRequestInterceptor? = null,
  val provider: MapResourceProvider? = null,
  val logger: MapLog? = null,
)

internal sealed interface MapResourceRoute {
  /** The request after [MapRequestInterceptor.rewriteUrl]. */
  val request: MapResourceRequest

  /** The engine fetches [request]. */
  data class Fetch(override val request: MapResourceRequest) : MapResourceRoute

  /** [provider] loads [request]. */
  data class Load(override val request: MapResourceRequest, val provider: MapResourceProvider) :
    MapResourceRoute
}

internal fun MapResourceConfig.route(request: MapResourceRequest): MapResourceRoute {
  val rewritten = request.copy(url = interceptor.rewrittenUrl(request, logger))
  val provider = provider
  return if (provider != null && provider.acceptsOrDeclines(rewritten, logger)) {
    MapResourceRoute.Load(rewritten, provider)
  } else {
    MapResourceRoute.Fetch(rewritten)
  }
}

/**
 * The URL to fetch for [request]. A null interceptor, a null or blank rewrite, and a non-fatal
 * exception all keep the incoming URL. The exception is logged as a warning.
 */
internal fun MapRequestInterceptor?.rewrittenUrl(
  request: MapResourceRequest,
  logger: MapLog?,
): String {
  val rewrite =
    try {
      this?.rewriteUrl(request)
    } catch (error: Exception) {
      logger?.w(error) { "The request interceptor failed to rewrite the URL of ${request.url}" }
      null
    }
  return if (rewrite.isNullOrBlank()) request.url else rewrite
}

/**
 * The headers for [request]. A null interceptor and a non-fatal exception add no headers. The
 * exception is logged as a warning.
 */
internal fun MapRequestInterceptor?.headersOrNone(
  request: MapResourceRequest,
  logger: MapLog?,
): Map<String, String> =
  try {
    this?.headers(request) ?: emptyMap()
  } catch (error: Exception) {
    logger?.w(error) { "The request interceptor failed to supply headers for ${request.url}" }
    emptyMap()
  }

/**
 * Returns false when [MapResourceProvider.accepts] throws, so an engine callback can still decide.
 * The exception is logged as a warning.
 */
internal fun MapResourceProvider.acceptsOrDeclines(
  request: MapResourceRequest,
  logger: MapLog?,
): Boolean =
  try {
    accepts(request)
  } catch (error: Exception) {
    logger?.w(error) { "The resource provider failed to classify ${request.url}" }
    false
  }
