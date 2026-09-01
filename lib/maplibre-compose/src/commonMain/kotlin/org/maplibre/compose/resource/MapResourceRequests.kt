package org.maplibre.compose.resource

/**
 * The kind of resource MapLibre is about to fetch.
 *
 * A newer engine may report a kind that has no name here; that value becomes [Unknown].
 */
public enum class MapResourceKind {
  Style,
  Source,
  Tile,
  Glyphs,
  SpriteJson,
  SpriteImage,
  Image,
  Unknown,
}

/**
 * One resource MapLibre is about to fetch.
 *
 * [url] is the URL after the engine resolves tile-server aliases.
 */
public data class MapResourceRequest(
  public val url: String,
  public val kind: MapResourceKind,
)

/**
 * Changes to apply to a resource request.
 *
 * A null [url] keeps the incoming URL. [headers] are added to the HTTP request that follows a URL
 * rewrite. Header logic should key off the URL the client will send.
 */
public data class MapRequestTransform(
  public val url: String? = null,
  public val headers: Map<String, String> = emptyMap(),
)

/**
 * Rewrites the URL or headers of every resource the runtime fetches.
 *
 * The engine may invoke this from network threads. The implementation must return quickly, must be
 * safe to call concurrently, and must not call map APIs.
 */
public fun interface MapRequestInterceptor {
  public fun intercept(request: MapResourceRequest): MapRequestTransform
}

/** Bytes or a failure for a request that [MapResourceProvider.accepts] returned true for. */
public sealed interface MapResourceLoad {
  /**
   * A successful body for the request.
   *
   * [etag] and [mustRevalidate] are optional cache validators. Native applies them to the ambient
   * cache; the browser ignores them.
   */
  public class Bytes(
    public val bytes: ByteArray,
    public val etag: String? = null,
    public val mustRevalidate: Boolean = false,
  ) : MapResourceLoad

  /** A failure that MapLibre should treat as a failed fetch. */
  public data class Failed(public val message: String) : MapResourceLoad
}

/**
 * Serves resource bytes for requests the application accepts.
 *
 * [accepts] runs on a network thread and must return quickly. Return true only for requests this
 * provider will load. [load] may suspend; cancellation means the engine no longer needs the
 * resource.
 *
 * A true [accepts] result skips the engine HTTP client for that request, including its cache and
 * retry.
 */
public interface MapResourceProvider {
  public fun accepts(request: MapResourceRequest): Boolean

  public suspend fun load(request: MapResourceRequest): MapResourceLoad
}

/** Returns a provider that calls [accepts] and [load]. */
public fun MapResourceProvider(
  accepts: (MapResourceRequest) -> Boolean,
  load: suspend (MapResourceRequest) -> MapResourceLoad,
): MapResourceProvider =
  object : MapResourceProvider {
    override fun accepts(request: MapResourceRequest): Boolean = accepts(request)

    override suspend fun load(request: MapResourceRequest): MapResourceLoad = load(request)
  }

/**
 * Returns a provider that serves URLs whose scheme is [scheme].
 *
 * [scheme] is the scheme name without a trailing colon, such as `app`.
 */
public fun MapResourceProvider(
  scheme: String,
  load: suspend (MapResourceRequest) -> ByteArray,
): MapResourceProvider {
  val prefix = "${scheme.trimEnd(':')}:"
  return MapResourceProvider(
    accepts = { request -> request.url.startsWith(prefix, ignoreCase = true) },
    load = { request -> MapResourceLoad.Bytes(load(request)) },
  )
}
