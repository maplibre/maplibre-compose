package org.maplibre.compose.resource

import org.maplibre.nativeffi.HttpHeader
import org.maplibre.nativeffi.HttpHeaderRule
import org.maplibre.nativeffi.HttpHeaderTransform
import org.maplibre.nativeffi.ResourceKind
import org.maplibre.nativeffi.ResourceKindFlag
import org.maplibre.nativeffi.ResourceTransform
import org.maplibre.nativeffi.ResourceTransformRule
import org.maplibre.nativeffi.RuntimeHandle
import org.maplibre.nativeffi.UrlMatchFlag
import org.maplibre.nativeffi.UrlQueryParameter
import org.maplibre.nativeffi.setHeader
import org.maplibre.nativeffi.setHttpHeaderTransform

/**
 * Installs the URL and header transforms for the interceptor in [config].
 *
 * [MapRequestRules] become MapLibre Native's own rules, which run no Kotlin code per request. Any
 * other interceptor becomes two callbacks, which native invokes independently, passing the header
 * callback the URL that completed the URL transform request.
 */
internal fun RuntimeHandle.installRequestInterceptor(config: MapResourceConfig) {
  when (val interceptor = config.interceptor) {
    null -> Unit
    is MapRequestRules -> installRules(interceptor)
    else -> installCallbacks(config)
  }
}

private fun RuntimeHandle.installRules(rules: MapRequestRules) {
  // OpenHarmony and the browser FFI decline header transforms. Native logs that failure, and the
  // URL transform stays in place.
  rules.toHttpHeaderTransform()?.let { discarding.setHttpHeaderTransform(it) }
  rules.toResourceTransform()?.let { discarding.setResourceTransform(it) }
}

private fun RuntimeHandle.installCallbacks(config: MapResourceConfig) {
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

/** The query parameter rules as a native resource transform, or null when there are none. */
internal fun MapRequestRules.toResourceTransform(): ResourceTransform? =
  queryParameterRules
    .takeIf { it.isNotEmpty() }
    ?.let { rules ->
      ResourceTransform(
        rules =
          rules.map { rule ->
            ResourceTransformRule(
              kinds = rule.kinds.toFlags(),
              flags = UrlMatchFlag.GLOB,
              url = rule.urlPattern,
              parameters = rule.values.map { (name, value) -> UrlQueryParameter(name, value) },
            )
          },
      )
    }

/** The header rules as a native header transform, or null when there are none. */
internal fun MapRequestRules.toHttpHeaderTransform(): HttpHeaderTransform? =
  headerRules
    .takeIf { it.isNotEmpty() }
    ?.let { rules ->
      HttpHeaderTransform(
        rules =
          rules.map { rule ->
            HttpHeaderRule(
              kinds = rule.kinds.toFlags(),
              flags = UrlMatchFlag.GLOB,
              url = rule.urlPattern,
              headers = rule.values.map { (name, value) -> HttpHeader(name, value) },
            )
          },
      )
    }

/** The native flags for [RuleKinds]. An empty set is no flags, which covers every kind. */
private fun Set<MapResourceKind>.toFlags(): ResourceKindFlag =
  fold(ResourceKindFlag.NONE) { flags, kind ->
    flags or
      when (kind) {
        MapResourceKind.Unknown -> ResourceKindFlag.UNKNOWN
        MapResourceKind.Style -> ResourceKindFlag.STYLE
        MapResourceKind.Source -> ResourceKindFlag.SOURCE
        MapResourceKind.Tile -> ResourceKindFlag.TILE
        MapResourceKind.Glyphs -> ResourceKindFlag.GLYPHS
        MapResourceKind.SpriteImage -> ResourceKindFlag.SPRITE_IMAGE
        MapResourceKind.SpriteJson -> ResourceKindFlag.SPRITE_JSON
        MapResourceKind.Image -> ResourceKindFlag.IMAGE
        else -> error("MapRequestRule accepts only RuleKinds, had $kind")
      }
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
