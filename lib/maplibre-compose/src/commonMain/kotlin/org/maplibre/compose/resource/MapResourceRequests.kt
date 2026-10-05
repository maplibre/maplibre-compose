package org.maplibre.compose.resource

import kotlin.jvm.JvmInline
import kotlin.time.Instant

/**
 * The kind of resource MapLibre is about to fetch.
 *
 * Compare with the named constants. A kind from a newer engine can have no named constant, so a
 * `when` needs an `else` branch. Unnamed kinds preserve their [nativeValue] or [browserValue] and
 * compare equal only when that engine identity is equal. Named kinds compare equal across engines.
 * These values are received from MapLibre; applications cannot create new kinds.
 */
public class MapResourceKind
internal constructor(
  /** The MapLibre Native FFI identifier, or null for an unnamed browser kind. */
  public val nativeValue: Int?,
  /** The MapLibre GL JS resource type, or null for an unnamed Native kind. */
  public val browserValue: String?,
) {
  public companion object {
    public val Style: MapResourceKind = MapResourceKind(1, "Style")
    public val Source: MapResourceKind = MapResourceKind(2, "Source")
    public val Tile: MapResourceKind = MapResourceKind(3, "Tile")
    public val Glyphs: MapResourceKind = MapResourceKind(4, "Glyphs")
    public val SpriteJson: MapResourceKind = MapResourceKind(6, "SpriteJSON")
    public val SpriteImage: MapResourceKind = MapResourceKind(5, "SpriteImage")
    public val Image: MapResourceKind = MapResourceKind(7, "Image")
    /** The unspecified kind, including a browser request with no resource type. */
    public val Unknown: MapResourceKind = MapResourceKind(0, "Unknown")
  }

  override fun equals(other: Any?): Boolean =
    other is MapResourceKind &&
      nativeValue == other.nativeValue &&
      browserValue == other.browserValue

  override fun hashCode(): Int =
    31 * (nativeValue?.hashCode() ?: 0) + (browserValue?.hashCode() ?: 0)

  override fun toString(): String = browserValue ?: "MapResourceKind(nativeValue=$nativeValue)"
}

/**
 * One resource MapLibre is about to fetch.
 *
 * [url] is the URL after the engine resolves tile-server aliases.
 */
public data class MapResourceRequest
internal constructor(
  public val url: String,
  public val kind: MapResourceKind,
)

/**
 * Rewrites the URL of a resource request, or adds HTTP headers to it. Override either function.
 *
 * Callbacks must return quickly, be safe to call concurrently, and call no map API. They may be
 * called repeatedly; return the same result while the request and application state are unchanged.
 * Read changing credentials from a thread-safe store.
 *
 * Use [MapResourceProvider] to supply resource data directly.
 */
public interface MapRequestInterceptor {
  /**
   * Returns the URL to fetch instead of [MapResourceRequest.url]. A null or blank result keeps
   * [MapResourceRequest.url].
   *
   * The [MapResourceProvider] loads a rewrite that it accepts. Otherwise the engine HTTP client
   * fetches an HTTP or HTTPS URL, including a rewrite of a custom-scheme URL, and MapLibre Native
   * reads a `file:` URL as a packaged resource.
   */
  public fun rewriteUrl(request: MapResourceRequest): String? = null

  /**
   * Returns the HTTP headers for a request that the engine HTTP client fetches.
   *
   * [MapResourceRequest.url] is the URL after [rewriteUrl].
   */
  public fun headers(request: MapResourceRequest): Map<String, String> = emptyMap()
}

/** Returns an interceptor that calls [rewriteUrl] and [headers]. */
public fun MapRequestInterceptor(
  rewriteUrl: (MapResourceRequest) -> String? = { null },
  headers: (MapResourceRequest) -> Map<String, String> = { emptyMap() },
): MapRequestInterceptor =
  object : MapRequestInterceptor {
    override fun rewriteUrl(request: MapResourceRequest): String? = rewriteUrl(request)

    override fun headers(request: MapResourceRequest): Map<String, String> = headers(request)
  }

/**
 * One resource that a [MapResourceProvider] loads.
 *
 * [url] is the URL after the engine resolves tile-server aliases and [MapRequestInterceptor]
 * rewrites it. [requestedUrl] is the URL in the style; on the browser it equals [url]. The prior
 * fields are the validators and the body of the cached copy. A provider uses them to revalidate.
 * The browser has no ambient cache, so it passes the default for every field after [kind].
 *
 * Request classifications can include unnamed engine values. A provider must choose how to handle
 * them; an unrecognized [loadingMethod] does not grant permission to use both cache and network.
 * Return [MapResourceLoad.Failed] with [MapResourceError.Other] if a request cannot be handled.
 */
public class MapResourceLoadRequest
internal constructor(
  public val url: String,
  public val kind: MapResourceKind,
  public val requestedUrl: String = url,
  public val loadingMethod: LoadingMethod = LoadingMethod.All,
  public val priority: Priority = Priority.Regular,
  public val usage: Usage = Usage.Online,
  public val storagePolicy: StoragePolicy = StoragePolicy.Permanent,
  /** The inclusive byte range to load, or null for the whole resource. */
  public val range: LongRange? = null,
  public val priorEtag: String? = null,
  public val priorModified: Instant? = null,
  public val priorExpires: Instant? = null,
  /** The cached body, or null when the cache has no body for this resource. */
  public val priorData: ByteArray? = null,
) {
  /**
   * Limits the load to the cache or to the network. [All] allows both.
   *
   * Values are received from MapLibre Native FFI. An unnamed value preserves its [nativeValue]; a
   * `when` needs an `else` branch. The browser uses [All].
   */
  @JvmInline
  public value class LoadingMethod internal constructor(public val nativeValue: Int) {
    public companion object {
      public val All: LoadingMethod = LoadingMethod(0)
      public val CacheOnly: LoadingMethod = LoadingMethod(1)
      public val NetworkOnly: LoadingMethod = LoadingMethod(2)
    }
  }

  /**
   * The priority of the load.
   *
   * Values are received from MapLibre Native FFI. An unnamed value preserves its [nativeValue]; a
   * `when` needs an `else` branch. The browser uses [Regular].
   */
  @JvmInline
  public value class Priority internal constructor(public val nativeValue: Int) {
    public companion object {
      public val Regular: Priority = Priority(0)
      public val Low: Priority = Priority(1)
    }
  }

  /**
   * The consumer of the resource: a map, or an offline pack download.
   *
   * Values are received from MapLibre Native FFI. An unnamed value preserves its [nativeValue]; a
   * `when` needs an `else` branch. The browser uses [Online].
   */
  @JvmInline
  public value class Usage internal constructor(public val nativeValue: Int) {
    public companion object {
      public val Online: Usage = Usage(0)
      public val Offline: Usage = Usage(1)
    }
  }

  /**
   * The cache retention policy for the resource.
   *
   * Values are received from MapLibre Native FFI. An unnamed value preserves its [nativeValue]; a
   * `when` needs an `else` branch. The browser uses [Permanent].
   */
  @JvmInline
  public value class StoragePolicy internal constructor(public val nativeValue: Int) {
    public companion object {
      public val Permanent: StoragePolicy = StoragePolicy(0)
      public val Volatile: StoragePolicy = StoragePolicy(1)
    }
  }

  override fun toString(): String = "MapResourceLoadRequest(url=$url, kind=$kind)"
}

/**
 * The cause of a failed resource load.
 *
 * This is a closed set of failure behaviors supported by the engines. Use [Other] for failures
 * outside these categories, with details in [MapResourceLoad.Failed.message]. These reasons do not
 * carry arbitrary HTTP status codes. The browser maps [NotFound], [Server], and [RateLimit] to 404,
 * 500, and 429; [Connection] and [Other] have no HTTP status.
 */
public enum class MapResourceError {
  /** A 404. */
  NotFound,

  /** A 5xx. */
  Server,

  /** A transport failure. */
  Connection,

  /** A 429. */
  RateLimit,
  /** A failure outside the other categories. */
  Other,
}

/**
 * The result of [MapResourceProvider.load].
 *
 * Each case corresponds to one HTTP response, and the engine handles the case as it handles that
 * response. [modified] and [expires] are cache metadata that every case can include.
 */
public sealed interface MapResourceLoad {
  public val modified: Instant?
  public val expires: Instant?

  /**
   * A 200: the body of the resource.
   *
   * [etag] and [mustRevalidate] are validators for the ambient cache.
   */
  public class Bytes(
    public val bytes: ByteArray,
    public val etag: String? = null,
    public val mustRevalidate: Boolean = false,
    override val modified: Instant? = null,
    override val expires: Instant? = null,
  ) : MapResourceLoad

  /**
   * A 204: the resource exists and is empty.
   *
   * Return this for a tile outside the data set. The engine renders an empty tile and reports no
   * error.
   */
  public class NoContent(
    override val modified: Instant? = null,
    override val expires: Instant? = null,
  ) : MapResourceLoad

  /**
   * A 304: the cached body in the request is current.
   *
   * Valid only when the request has a [MapResourceLoadRequest.priorEtag] or a
   * [MapResourceLoadRequest.priorModified]. The browser has neither, so it reports this result as
   * an error.
   */
  public class NotModified(
    override val modified: Instant? = null,
    override val expires: Instant? = null,
  ) : MapResourceLoad

  /**
   * A failed load.
   *
   * [reason] selects the failure behavior that the engine handles. MapLibre Native reports a tile
   * error for a [MapResourceError.NotFound] tile, and the browser skips the tile. Return
   * [NoContent] for a tile outside the data set.
   */
  public class Failed(
    public val reason: MapResourceError,
    public val message: String,
    public val retryAfter: Instant? = null,
    override val modified: Instant? = null,
    override val expires: Instant? = null,
  ) : MapResourceLoad
}

/**
 * Loads resources for the requests that the application accepts.
 *
 * [accepts] receives the URL after [MapRequestInterceptor.rewriteUrl]. It runs on a network thread
 * and must return quickly. Return true only for requests that this provider loads. [load] may
 * suspend; cancellation means that the engine no longer needs the resource. An exception from
 * [load] becomes a [MapResourceLoad.Failed] with reason [MapResourceError.Other].
 *
 * A true [accepts] result replaces the engine HTTP client for that request. MapLibre Native stores
 * the result in its ambient cache. After the cached entry expires, the engine requests the resource
 * again with the prior validators set on the request.
 */
public interface MapResourceProvider {
  public fun accepts(request: MapResourceRequest): Boolean

  public suspend fun load(request: MapResourceLoadRequest): MapResourceLoad
}

/** Returns a provider that calls [accepts] and [load]. */
public fun MapResourceProvider(
  accepts: (MapResourceRequest) -> Boolean,
  load: suspend (MapResourceLoadRequest) -> MapResourceLoad,
): MapResourceProvider =
  object : MapResourceProvider {
    override fun accepts(request: MapResourceRequest): Boolean = accepts(request)

    override suspend fun load(request: MapResourceLoadRequest): MapResourceLoad = load(request)
  }

/**
 * Returns a provider that serves URLs whose scheme is [scheme].
 *
 * [scheme] is the scheme name without a trailing colon, such as `app`.
 */
public fun MapResourceProvider(
  scheme: String,
  load: suspend (MapResourceLoadRequest) -> ByteArray,
): MapResourceProvider {
  val prefix = "${scheme.trimEnd(':')}:"
  return MapResourceProvider(
    accepts = { request -> request.url.startsWith(prefix, ignoreCase = true) },
    load = { request -> MapResourceLoad.Bytes(load(request)) },
  )
}
