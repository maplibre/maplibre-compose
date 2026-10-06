package org.maplibre.compose.resource

import org.maplibre.compose.util.rethrowIfFatal
import org.maplibre.nativeffi.resource.HttpHeader
import org.maplibre.nativeffi.resource.HttpHeaderTransformCallback
import org.maplibre.nativeffi.resource.ResourceKind
import org.maplibre.nativeffi.resource.ResourceTransformCallback
import org.maplibre.nativeffi.runtime.RuntimeHandle

/**
 * Installs the URL and header callbacks, each of which uses the interceptor in [config].
 *
 * Native invokes the two callbacks independently, on its network threads, and passes the header
 * callback the URL that the URL callback returned. Both run after [MlnFfiResourceProvider] has
 * routed the request, which fails a request whose interceptor throws. If the URL callback's call
 * throws anyway, it returns a [FailedRequestScheme] URL that no transport fetches. The header
 * callback runs again for each retry and cannot fail a request, so an exception there sends that
 * attempt without the added headers.
 */
internal fun RuntimeHandle.installRequestInterceptor(config: MapResourceConfig) {
  try {
    setHttpHeaderTransform(
      HttpHeaderTransformCallback { request ->
        val mapRequest = MapResourceRequest(request.url, request.kind.toCommon())
        config.interceptor.headersOrNull(mapRequest, config.logger).orEmpty().map {
          HttpHeader(it.key, it.value)
        }
      }
    )
  } catch (error: Throwable) {
    rethrowIfFatal(error)
    // OpenHarmony and the browser FFI decline header transforms.
    config.logger?.w(error) {
      "This platform declined the header transform; request interceptor headers are ignored"
    }
  }
  setResourceTransform(
    ResourceTransformCallback { request ->
      transformedUrl(config, MapResourceRequest(request.url, request.kind.toCommon()))
    }
  )
}

/**
 * The URL the resource transform returns for [request], or null to keep it. When the interceptor
 * throws, a [FailedRequestScheme] URL that names the original URL, so the request fails.
 */
internal fun transformedUrl(config: MapResourceConfig, request: MapResourceRequest): String? {
  val url =
    config.interceptor.rewrittenUrlOrNull(request, config.logger)
      ?: return "$FailedRequestScheme:${encodeResourceUrl(request.url)}"
  return if (url == request.url) null else url
}

/**
 * The scheme of a URL whose interceptor threw. No transport serves it, so a request for it fails
 * without contacting a server.
 */
internal const val FailedRequestScheme = "maplibre-compose-failed"

/** The kind with the same name in MapLibre GL JS, or the native number as decimal text. */
internal fun ResourceKind.toCommon(): MapResourceKind =
  when (this) {
    ResourceKind.UNKNOWN -> MapResourceKind.Unknown
    ResourceKind.STYLE -> MapResourceKind.Style
    ResourceKind.SOURCE -> MapResourceKind.Source
    ResourceKind.TILE -> MapResourceKind.Tile
    ResourceKind.GLYPHS -> MapResourceKind.Glyphs
    ResourceKind.SPRITE_JSON -> MapResourceKind.SpriteJson
    ResourceKind.SPRITE_IMAGE -> MapResourceKind.SpriteImage
    ResourceKind.IMAGE -> MapResourceKind.Image
    else -> MapResourceKind(nativeValue.toString())
  }
