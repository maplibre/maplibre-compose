package org.maplibre.compose.resource

import org.maplibre.nativeffi.HttpHeaderTransform
import org.maplibre.nativeffi.ResourceKind
import org.maplibre.nativeffi.ResourceTransform
import org.maplibre.nativeffi.RuntimeHandle
import org.maplibre.nativeffi.setHeader
import org.maplibre.nativeffi.setHttpHeaderTransform

/**
 * Installs the URL and header callbacks, each of which uses the interceptor in [config].
 *
 * Native invokes the callbacks independently and passes the header callback the URL that completed
 * the URL transform request.
 */
internal fun RuntimeHandle.installRequestInterceptor(config: MapResourceConfig) {
  // OpenHarmony and the browser FFI decline header transforms. Native logs that failure, and the
  // URL transform stays in place.
  discarding.setHttpHeaderTransform(
    HttpHeaderTransform(
      callback = { kind, url, response ->
        val mapRequest = MapResourceRequest(url, kind.toCommon())
        config.interceptor.headersOrNone(mapRequest, config.logger).forEach { (name, value) ->
          response.setHeader(name, value)
        }
      },
    ),
  )
  discarding.setResourceTransform(
    ResourceTransform(
      callback = { kind, url, request ->
        // A request released without an answer fails, so an unchanged URL completes too.
        request.use {
          val mapRequest = MapResourceRequest(url, kind.toCommon())
          it.complete(config.interceptor.rewrittenUrl(mapRequest, config.logger))
        }
      },
    ),
  )
}

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
    else -> MapResourceKind(rawValue.toString())
  }
