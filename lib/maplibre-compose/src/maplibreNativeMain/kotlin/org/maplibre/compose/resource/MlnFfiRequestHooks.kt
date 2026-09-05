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
 * callback the URL that the URL callback returned.
 */
internal fun RuntimeHandle.installRequestInterceptor(config: MapResourceConfig) {
  try {
    setHttpHeaderTransform(
      HttpHeaderTransformCallback { request ->
        val mapRequest = MapResourceRequest(request.url, request.kind.toCommon())
        config.interceptor.headersOrNone(mapRequest, config.logger).map {
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
      val mapRequest = MapResourceRequest(request.url, request.kind.toCommon())
      val url = config.interceptor.rewrittenUrl(mapRequest, config.logger)
      if (url == request.url) null else url
    }
  )
}

internal fun ResourceKind.toCommon(): MapResourceKind =
  when (this) {
    ResourceKind.STYLE -> MapResourceKind.Style
    ResourceKind.SOURCE -> MapResourceKind.Source
    ResourceKind.TILE -> MapResourceKind.Tile
    ResourceKind.GLYPHS -> MapResourceKind.Glyphs
    ResourceKind.SPRITE_JSON -> MapResourceKind.SpriteJson
    ResourceKind.SPRITE_IMAGE -> MapResourceKind.SpriteImage
    ResourceKind.IMAGE -> MapResourceKind.Image
    else -> MapResourceKind.Unknown
  }
