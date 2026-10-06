package org.maplibre.compose.resource

import kotlin.jvm.JvmInline
import kotlin.time.Instant

/**
 * The kind of resource MapLibre is about to fetch.
 *
 * [value] is the engine's name for the kind, such as `SpriteJSON`. MapLibre Native reports a kind
 * as a number, so a kind that has no name here holds that number as decimal text, such as `8`.
 *
 * Values may be added in minor releases; use an `else` branch when matching.
 */
@JvmInline
public value class MapResourceKind internal constructor(public val value: String) {
  public companion object {
    public val Style: MapResourceKind = MapResourceKind("Style")
    public val Source: MapResourceKind = MapResourceKind("Source")
    public val Tile: MapResourceKind = MapResourceKind("Tile")
    public val Glyphs: MapResourceKind = MapResourceKind("Glyphs")
    public val SpriteJson: MapResourceKind = MapResourceKind("SpriteJSON")
    public val SpriteImage: MapResourceKind = MapResourceKind("SpriteImage")
    public val Image: MapResourceKind = MapResourceKind("Image")

    /** A resource that the engine requests without saying which kind it is. */
    public val Unknown: MapResourceKind = MapResourceKind("Unknown")
  }
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
 * On MapLibre Native, both functions run on background threads, and calls for different requests
 * can run at the same time. On the browser, both run on the page's main thread, one call at a time.
 *
 * Callbacks must return quickly, be safe to call concurrently, and call no map API. They may be
 * called repeatedly; return the same result while the request and application state are unchanged.
 * Read changing credentials from a thread-safe store.
 *
 * If a function throws an exception, the library logs a warning and uses the default result: the
 * URL is not rewritten, or the request gets no added headers.
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

/**
 * Returns an interceptor that calls [rewriteUrl] and [headers].
 *
 * [rewriteUrl] and [headers] run on the same threads as the [MapRequestInterceptor] functions with
 * the same names, and the library handles their exceptions the same way.
 */
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
   * [value] is MapLibre Native's name for the method, such as `CacheOnly`. A method that has no
   * name here holds the number that MapLibre Native reports, as decimal text.
   *
   * Values may be added in minor releases; use an `else` branch when matching.
   */
  @JvmInline
  public value class LoadingMethod internal constructor(public val value: String) {
    public companion object {
      public val All: LoadingMethod = LoadingMethod("All")
      public val CacheOnly: LoadingMethod = LoadingMethod("CacheOnly")
      public val NetworkOnly: LoadingMethod = LoadingMethod("NetworkOnly")
    }
  }

  /**
   * The priority of the load.
   *
   * [value] is MapLibre Native's name for the priority, such as `Low`. A priority that has no name
   * here holds the number that MapLibre Native reports, as decimal text.
   *
   * Values may be added in minor releases; use an `else` branch when matching.
   */
  @JvmInline
  public value class Priority internal constructor(public val value: String) {
    public companion object {
      public val Regular: Priority = Priority("Regular")
      public val Low: Priority = Priority("Low")
    }
  }

  /**
   * The consumer of the resource: a map, or an offline pack download.
   *
   * [value] is MapLibre Native's name for the usage, such as `Offline`. A usage that has no name
   * here holds the number that MapLibre Native reports, as decimal text.
   *
   * Values may be added in minor releases; use an `else` branch when matching.
   */
  @JvmInline
  public value class Usage internal constructor(public val value: String) {
    public companion object {
      public val Online: Usage = Usage("Online")
      public val Offline: Usage = Usage("Offline")
    }
  }

  /**
   * The cache retention policy for the resource.
   *
   * [value] is MapLibre Native's name for the policy, such as `Volatile`. A policy that has no name
   * here holds the number that MapLibre Native reports, as decimal text.
   *
   * Values may be added in minor releases; use an `else` branch when matching.
   */
  @JvmInline
  public value class StoragePolicy internal constructor(public val value: String) {
    public companion object {
      public val Permanent: StoragePolicy = StoragePolicy("Permanent")
      public val Volatile: StoragePolicy = StoragePolicy("Volatile")
    }
  }

  override fun toString(): String = "MapResourceLoadRequest(url=$url, kind=$kind)"
}

/**
 * The cause of a failed resource load. Most reasons correspond to an HTTP status.
 *
 * [value] is MapLibre Native's name for the reason, such as `NotFound`. MapLibre Native reports a
 * reason as a number, so a reason that has no name here holds that number as decimal text, such as
 * `6`.
 *
 * Values may be added in minor releases; use an `else` branch when matching.
 */
@JvmInline
public value class MapResourceError internal constructor(public val value: String) {
  public companion object {
    /** A 404. */
    public val NotFound: MapResourceError = MapResourceError("NotFound")

    /** A 5xx. */
    public val Server: MapResourceError = MapResourceError("Server")

    /** A transport failure. */
    public val Connection: MapResourceError = MapResourceError("Connection")

    /** A 429. */
    public val RateLimit: MapResourceError = MapResourceError("RateLimit")

    /** A failure that matches no other reason. */
    public val Other: MapResourceError = MapResourceError("Other")
  }
}

/**
 * The result of [MapResourceProvider.load].
 *
 * Values may be added in minor releases; use an `else` branch when matching.
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
   * [reason] is the HTTP status that the engine handles. MapLibre Native reports a tile error for a
   * [MapResourceError.NotFound] tile, and the browser skips the tile. Return [NoContent] for a tile
   * outside the data set.
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
 * A true [accepts] result replaces the engine HTTP client for that request. MapLibre Native stores
 * the result in its ambient cache. After the cached entry expires, the engine requests the resource
 * again with the prior validators set on the request.
 */
public interface MapResourceProvider {
  /**
   * Returns whether this provider loads [request]. Return true only for requests that [load]
   * handles.
   *
   * [MapResourceRequest.url] is the URL after [MapRequestInterceptor.rewriteUrl]. This function
   * runs on the same threads as [MapRequestInterceptor.rewriteUrl]. It must return quickly, be safe
   * to call concurrently, and call no map API.
   *
   * If it throws an exception, the library logs a warning and treats the result as false: the
   * request loads as if this provider had not accepted it.
   */
  public fun accepts(request: MapResourceRequest): Boolean

  /**
   * Loads the resource for a request that [accepts] returned true for.
   *
   * On MapLibre Native, calls run on a background thread, and calls for different requests can run
   * at the same time; move blocking work to another dispatcher such as `Dispatchers.IO`. On the
   * browser, calls run on the page's main thread and overlap only where they suspend.
   *
   * The library cancels a call when the engine no longer needs the resource or the map runtime
   * closes. Any other exception, including a cancellation that the provider causes itself, such as
   * its own timeout, fails the request the same way as a [MapResourceLoad.Failed] with reason
   * [MapResourceError.Other].
   */
  public suspend fun load(request: MapResourceLoadRequest): MapResourceLoad
}

/**
 * Returns a provider that calls [accepts] and [load].
 *
 * [accepts] and [load] run on the same threads as the [MapResourceProvider] functions with the same
 * names, and the library handles their exceptions the same way.
 */
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
 * [scheme] is the scheme name without a trailing colon, such as `app`. [load] runs as
 * [MapResourceProvider.load] does, and an exception from it fails the request the same way.
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
