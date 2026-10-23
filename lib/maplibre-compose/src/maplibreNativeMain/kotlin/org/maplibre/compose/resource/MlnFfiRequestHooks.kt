package org.maplibre.compose.resource

import org.maplibre.compose.mlnffi.reportFailure
import org.maplibre.nativeffi.generated.GeneratedApi
import org.maplibre.nativeffi.generated.HttpHeaderTransform
import org.maplibre.nativeffi.generated.ResourceKind
import org.maplibre.nativeffi.generated.ResourceTransform
import org.maplibre.nativeffi.generated.RuntimeHandle
import org.maplibre.nativeffi.generated.httpHeaderTransformResponseSetHeader
import org.maplibre.nativeffi.generated.setHttpHeaderTransform

/**
 * Installs the URL and header callbacks, each of which uses the interceptor in [config].
 *
 * Native invokes the callbacks independently and passes the header callback the URL that completed
 * the URL transform request.
 */
internal fun RuntimeHandle.installRequestInterceptor(config: MapResourceConfig) {
  // OpenHarmony and the browser FFI decline header transforms; that failure arrives here and leaves
  // the URL transform in place.
  setHttpHeaderTransform(
      HttpHeaderTransform(
        callback = { kind, url, response ->
          val mapRequest = MapResourceRequest(url, kind.toCommon())
          config.interceptor.headersOrNone(mapRequest, config.logger).forEach { (name, value) ->
            GeneratedApi.httpHeaderTransformResponseSetHeader(response, name, value)
          }
        },
      ),
    )
    .reportFailure(
      config.logger,
      "install the header transform; request interceptor headers are ignored",
    )
  setResourceTransform(
      ResourceTransform(
        callback = { kind, url, request ->
          request.use {
            val mapRequest = MapResourceRequest(url, kind.toCommon())
            val rewritten = config.interceptor.rewrittenUrl(mapRequest, config.logger)
            if (rewritten != url) it.complete(rewritten)
          }
        },
      ),
    )
    .reportFailure(config.logger, "install the request interceptor's URL transform")
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
