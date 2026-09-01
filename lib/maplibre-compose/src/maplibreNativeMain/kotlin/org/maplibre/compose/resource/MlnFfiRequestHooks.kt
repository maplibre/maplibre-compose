package org.maplibre.compose.resource

import org.maplibre.nativeffi.resource.HttpHeader
import org.maplibre.nativeffi.resource.HttpHeaderTransformCallback
import org.maplibre.nativeffi.resource.ResourceKind
import org.maplibre.nativeffi.resource.ResourceTransformCallback
import org.maplibre.nativeffi.runtime.RuntimeHandle

/** Installs live URL and header transforms that read [config] on every request. */
internal fun RuntimeHandle.installRequestInterceptor(config: MapResourceConfig) {
  setResourceTransform(
    ResourceTransformCallback { request ->
      config.interceptor().transform(MapResourceRequest(request.url, request.kind.toCommon())).url
        ?: ""
    }
  )
  try {
    setHttpHeaderTransform(
      HttpHeaderTransformCallback { request ->
        config
          .interceptor()
          .transform(MapResourceRequest(request.url, request.kind.toCommon()))
          .headers
          .map { HttpHeader(it.key, it.value) }
      }
    )
  } catch (_: Throwable) {
    // OpenHarmony and the browser FFI decline header transforms.
  }
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
