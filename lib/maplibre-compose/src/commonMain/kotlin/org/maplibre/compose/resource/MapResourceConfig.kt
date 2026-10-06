package org.maplibre.compose.resource

import org.maplibre.compose.logging.MapLog

/** Construction-time interceptor and provider for one [org.maplibre.compose.map.MapRuntime]. */
internal class MapResourceConfig(
  val interceptor: MapRequestInterceptor? = null,
  val provider: MapResourceProvider? = null,
  val logger: MapLog? = null,
)

internal sealed interface MapResourceRoute {
  /** The request after [MapRequestInterceptor.rewriteUrl], or the incoming request for [Fail]. */
  val request: MapResourceRequest

  /** The engine fetches [request]. */
  data class Fetch(override val request: MapResourceRequest) : MapResourceRoute

  /** [provider] loads [request]. */
  data class Load(override val request: MapResourceRequest, val provider: MapResourceProvider) :
    MapResourceRoute

  /** The interceptor threw for [request], so the engine fails it without sending it. */
  data class Fail(override val request: MapResourceRequest) : MapResourceRoute
}

internal fun MapResourceConfig.route(request: MapResourceRequest): MapResourceRoute {
  val url = interceptor.rewrittenUrlOrNull(request, logger) ?: return MapResourceRoute.Fail(request)
  val rewritten = request.copy(url = url)
  val provider = provider
  return if (provider != null && provider.acceptsOrDeclines(rewritten, logger)) {
    MapResourceRoute.Load(rewritten, provider)
  } else {
    MapResourceRoute.Fetch(rewritten)
  }
}

/**
 * The URL to fetch for [request], or null when the interceptor throws a non-fatal exception; the
 * caller then fails the request. A null interceptor and a null or blank rewrite keep the incoming
 * URL. The exception is logged as a warning.
 */
internal fun MapRequestInterceptor?.rewrittenUrlOrNull(
  request: MapResourceRequest,
  logger: MapLog?,
): String? {
  val rewrite =
    try {
      this?.rewriteUrl(request)
    } catch (error: Exception) {
      logger?.w(error) { "The request interceptor failed to rewrite the URL of ${request.url}" }
      return null
    }
  return if (rewrite.isNullOrBlank()) request.url else rewrite
}

/**
 * The headers for [request], or null when the interceptor throws a non-fatal exception; the caller
 * then fails the request if it still can. A null interceptor adds no headers. The exception is
 * logged as a warning.
 */
internal fun MapRequestInterceptor?.headersOrNull(
  request: MapResourceRequest,
  logger: MapLog?,
): Map<String, String>? =
  try {
    this?.headers(request) ?: emptyMap()
  } catch (error: Exception) {
    logger?.w(error) { "The request interceptor failed to supply headers for ${request.url}" }
    null
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
