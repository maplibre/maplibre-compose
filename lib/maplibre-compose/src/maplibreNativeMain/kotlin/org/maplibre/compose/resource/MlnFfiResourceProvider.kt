package org.maplibre.compose.resource

import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import org.maplibre.compose.logging.MapLog
import org.maplibre.compose.util.rethrowIfFatal
import org.maplibre.nativeffi.resource.ResourceErrorReason
import org.maplibre.nativeffi.resource.ResourceLoadingMethod
import org.maplibre.nativeffi.resource.ResourcePriority
import org.maplibre.nativeffi.resource.ResourceProviderCallback
import org.maplibre.nativeffi.resource.ResourceProviderDecision
import org.maplibre.nativeffi.resource.ResourceRequest
import org.maplibre.nativeffi.resource.ResourceRequestHandle
import org.maplibre.nativeffi.resource.ResourceResponse
import org.maplibre.nativeffi.resource.ResourceResponseStatus
import org.maplibre.nativeffi.resource.ResourceStoragePolicy
import org.maplibre.nativeffi.resource.ResourceUsage

/**
 * URI schemes MapLibre's own loader handles; everything else is ours. Its network stack rejects a
 * non-HTTP URI with `invalid authority`.
 */
private val NETWORK_SCHEMES = setOf("http", "https")

internal typealias MlnFfiResourceProviderFactory =
  (getLogger: () -> MapLog?, config: MapResourceConfig) -> MlnFfiResourceProvider

/**
 * Routes each request by its rewritten URL. Requests that the application provider accepts use that
 * provider. HTTP and HTTPS requests use MapLibre's loader. Remaining URLs use the packaged-resource
 * reader.
 *
 * Installed with the runtime. Provider-owned [ResourceRequestHandle] instances remain valid
 * independently of runtime teardown, so accepted reads can safely finish after [close].
 */
@OptIn(ExperimentalAtomicApi::class)
internal class MlnFfiResourceProvider(
  private val getLogger: () -> MapLog?,
  private val config: MapResourceConfig = MapResourceConfig(),
  /** Turns a URL into a response. Test seam: a fake can hold a read open mid-shutdown. */
  private val read: (url: String, requestedUrl: String) -> ResourceResponse = { url, requestedUrl ->
    readResource(url, requestedUrl, getLogger())
  },
  /** Keeps the native network source in production while tests claim controlled HTTPS fixtures. */
  private val passThroughNetwork: Boolean = true,
  /** Test seam: observes when a native completion call finishes and whether it failed. */
  private val onResponseCompletionFinished: ((url: String, error: Throwable?) -> Unit)? = null,
  /** Test seam: a cancelled scope reproduces a close that races [takeUser]. */
  userCoroutineScope: CoroutineScope? = null,
) : ResourceProviderCallback, AutoCloseable {

  private val logger: MapLog?
    get() = getLogger()

  private val accepting = AtomicBoolean(true)
  private val userJob = SupervisorJob(userCoroutineScope?.coroutineContext?.get(Job))
  private val userScope =
    if (userCoroutineScope != null) {
      CoroutineScope(userCoroutineScope.coroutineContext + userJob)
    } else {
      CoroutineScope(
        userJob + Dispatchers.Default + CoroutineName("maplibre-compose-resource-provider")
      )
    }

  override fun handle(
    request: ResourceRequest,
    handle: ResourceRequestHandle,
  ): ResourceProviderDecision {
    return when (val route = config.nativeRoute(request, passThroughNetwork)) {
      is NativeResourceRoute.Load -> {
        takeUser(FfiResourceRequest(handle), route.request, route.provider)
        ResourceProviderDecision.HANDLE
      }
      NativeResourceRoute.Fetch -> ResourceProviderDecision.PASS_THROUGH
      is NativeResourceRoute.Read -> {
        take(FfiResourceRequest(handle), route.url, request.requestedUrl)
        ResourceProviderDecision.HANDLE
      }
    }
  }

  internal fun takeUser(
    request: TakenResourceRequest,
    load: MapResourceLoadRequest,
    provider: MapResourceProvider,
  ) {
    val url = load.url
    val requestedUrl = load.requestedUrl
    if (!accepting.load()) {
      refuse(request, url, requestedUrl)
      return
    }
    var started = false
    userScope.launch(start = CoroutineStart.UNDISPATCHED) {
      started = true
      serveUser(request, provider, load)
    }
    if (!started) refuse(request, url, requestedUrl)
  }

  private suspend fun serveUser(
    request: TakenResourceRequest,
    provider: MapResourceProvider,
    load: MapResourceLoadRequest,
  ) {
    val url = load.url
    val requestedUrl = load.requestedUrl
    try {
      request.use { open ->
        if (open.isCancelled()) return
        val response =
          try {
            loadWhileRequestOpen(open, provider, load)
          } catch (error: CancellationException) {
            if (open.isCancelled()) return
            failure(
              url,
              requestedUrl,
              ResourceErrorReason.OTHER,
              "was cancelled",
              error,
              logger,
            )
          } catch (error: Throwable) {
            rethrowIfFatal(error)
            failure(url, requestedUrl, ResourceErrorReason.OTHER, "failed to load", error, logger)
          }
        if (open.isCancelled()) return
        var completionError: Throwable? = null
        try {
          open.complete(response)
        } catch (error: Throwable) {
          completionError = error
          throw error
        } finally {
          onResponseCompletionFinished?.invoke(url, completionError)
        }
      }
    } catch (error: Throwable) {
      rethrowIfFatal(error)
      logger?.w(error) { "Failed to answer the resource request for $url" }
    }
  }

  /** Queues [request] for the reader, or refuses it if this provider is shutting down. */
  fun take(request: TakenResourceRequest, url: String, requestedUrl: String) {
    if (!accepting.load()) {
      refuse(request, url, requestedUrl)
      return
    }
    startMlnFfiBlockingWork("maplibre-compose-resource-reader") {
      serve(request, url, requestedUrl)
    }
  }

  /** Reads one resource and answers with it. Runs away from MapLibre's callback thread. */
  private fun serve(request: TakenResourceRequest, url: String, requestedUrl: String) {
    try {
      request.use { open ->
        // Rechecked here because a request queued behind a slow read may have been abandoned since.
        if (open.isCancelled()) return
        val response = read(url, requestedUrl)
        var completionError: Throwable? = null
        try {
          open.complete(response)
        } catch (error: Throwable) {
          completionError = error
          throw error
        } finally {
          onResponseCompletionFinished?.invoke(url, completionError)
        }
      }
    } catch (error: Throwable) {
      rethrowIfFatal(error)
      logger?.w(error) { "Failed to answer the resource request for $url" }
    }
  }

  /**
   * Answers a request received after [close] on MapLibre's thread. Producing this failure does not
   * block.
   */
  private fun refuse(request: TakenResourceRequest, url: String, requestedUrl: String) {
    try {
      request.use { open ->
        if (open.isCancelled()) return
        open.complete(
          failure(
            url,
            requestedUrl,
            ResourceErrorReason.OTHER,
            "was requested after the resource provider shut down",
            error = null,
            logger = logger,
          )
        )
      }
    } catch (error: Throwable) {
      rethrowIfFatal(error)
      logger?.w(error) { "Failed to refuse the resource request for $url" }
    }
  }

  /**
   * Stops taking new reads and cancels application [MapResourceProvider] loads. Packaged-resource
   * reads own their handles and finish independently.
   */
  override fun close() {
    accepting.store(false)
    userJob.cancel()
  }

  private suspend fun loadWhileRequestOpen(
    request: TakenResourceRequest,
    provider: MapResourceProvider,
    resource: MapResourceLoadRequest,
  ): ResourceResponse = coroutineScope {
    val load = async(start = CoroutineStart.LAZY) { provider.load(resource).toResourceResponse() }
    val cancelled = CompletableDeferred<Unit>()
    try {
      // Native callbacks only signal. Provider cancellation handlers run in our coroutine context.
      request.setCancelCallback { cancelled.complete(Unit) }
      select {
        cancelled.onAwait { throw CancellationException("MapLibre cancelled the resource request") }
        load.onAwait { it }
      }
    } finally {
      load.cancel()
    }
  }
}

/**
 * A resource request this provider has taken. Mirrors `ResourceRequestHandle`, which is final and
 * constructible only inside the binding, so that a test can supply one.
 */
internal interface TakenResourceRequest : AutoCloseable {
  fun isCancelled(): Boolean

  fun setCancelCallback(callback: () -> Unit)

  fun complete(response: ResourceResponse)
}

private class FfiResourceRequest(private val handle: ResourceRequestHandle) : TakenResourceRequest {
  override fun isCancelled(): Boolean = handle.isCancelled()

  override fun setCancelCallback(callback: () -> Unit) = handle.setCancelCallback(callback)

  override fun complete(response: ResourceResponse) = handle.complete(response)

  override fun close() = handle.close()
}

/**
 * Reads [url] into a response, reporting every failure as one rather than throwing. Blocks, so it
 * must run away from MapLibre's callback thread.
 */
internal fun readResource(url: String, requestedUrl: String, logger: MapLog?): ResourceResponse =
  try {
    val bytes = readPlatformResourceBytes(url)
    ResourceResponse(ResourceResponseStatus.OK).also {
      it.bytes = bytes
      // Packaged resources cannot change while the process runs.
      it.mustRevalidate = false
    }
  } catch (error: MlnFfiResourceReadException) {
    failure(url, requestedUrl, error.failure.reason, error.failure.description, error.cause, logger)
  }

/**
 * Reads a resource through the platform's packaged-resource and URL mechanisms. Every failure
 * arrives as an [MlnFfiResourceReadException], so that each platform classifies its own read
 * errors.
 */
internal expect fun readPlatformResourceBytes(url: String): ByteArray

/** How a resource read failed, in the terms the caller reports it in. */
internal enum class MlnFfiResourceReadFailure(
  val reason: ResourceErrorReason,
  /** Completes the sentence "Resource <url> …". */
  val description: String,
) {
  NOT_FOUND(ResourceErrorReason.NOT_FOUND, "not found"),
  INVALID_URL(ResourceErrorReason.OTHER, "is not a valid URI"),
  UNREADABLE(ResourceErrorReason.OTHER, "could not be read"),
}

/** The classified failure of one [readPlatformResourceBytes] call. */
internal class MlnFfiResourceReadException(
  val failure: MlnFfiResourceReadFailure,
  override val cause: Throwable,
) : Exception(cause)

private fun failure(
  url: String,
  requestedUrl: String,
  reason: ResourceErrorReason,
  what: String,
  error: Throwable?,
  logger: MapLog?,
): ResourceResponse {
  // The style names one URL and the loader may resolve another; report both so the style is
  // greppable.
  val named = if (requestedUrl == url) url else "$url (requested as $requestedUrl)"
  val cause = error?.let { ": ${it.message ?: it::class.simpleName}" }.orEmpty()
  if (error == null) logger?.w { "Resource $named $what" }
  else logger?.w(error) { "Resource $named $what" }
  return ResourceResponse(ResourceResponseStatus.ERROR).also {
    it.errorReason = reason
    it.errorMessage = "$named $what$cause"
  }
}

/** The loader for one native request. */
internal sealed interface NativeResourceRoute {
  data class Load(val provider: MapResourceProvider, val request: MapResourceLoadRequest) :
    NativeResourceRoute

  data object Fetch : NativeResourceRoute

  data class Read(val url: String) : NativeResourceRoute
}

/** Chooses the loader for [request]. */
internal fun MapResourceConfig.nativeRoute(
  request: ResourceRequest,
  passThroughNetwork: Boolean,
): NativeResourceRoute {
  val incoming = MapResourceRequest(request.resolvedUrl, request.kind.toCommon())
  return when (val route = route(incoming)) {
    is MapResourceRoute.Load ->
      NativeResourceRoute.Load(route.provider, request.toLoadRequest(url = route.request.url))
    is MapResourceRoute.Fetch ->
      if (passThroughNetwork && isMapLibresToFetch(route.request.url)) NativeResourceRoute.Fetch
      else NativeResourceRoute.Read(route.request.url)
  }
}

/**
 * Whether MapLibre's own loader should fetch [url] rather than this provider.
 *
 * [url] is the URL after MapLibre's tile-server normalization, which turns a `maplibre://` alias
 * into an https URL, and after [MapRequestInterceptor.rewriteUrl]. A scheme-less URL is MapLibre's
 * too.
 */
internal fun isMapLibresToFetch(url: String): Boolean =
  schemeOf(url).let { it == null || it in NETWORK_SCHEMES }

/**
 * The scheme of [url] in lowercase, or null when it has none or cannot be parsed.
 *
 * A scheme is a letter followed by letters, digits, `+`, `-`, or `.`, and then `:`, as RFC 3986
 * defines it. A URL holding whitespace or a backslash reports no scheme at all, because neither
 * character can appear in a URI; that keeps a Windows path such as `C:\dir\style.json` out of this
 * provider.
 */
internal fun schemeOf(url: String): String? {
  val end = url.indexOf(':')
  if (end < 1 || !url[0].isAsciiLetter()) return null
  for (index in 1 until end) {
    val char = url[index]
    if (!char.isAsciiLetter() && !char.isAsciiDigit() && char !in "+-.") return null
  }
  if (url.any { it.isWhitespace() || it == '\\' }) return null
  return url.substring(0, end).lowercase()
}

private fun Char.isAsciiLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'

private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'

/** Copies the FFI request into the request a [MapResourceProvider] loads, field for field. */
internal fun ResourceRequest.toLoadRequest(url: String = resolvedUrl): MapResourceLoadRequest =
  MapResourceLoadRequest(
    url = url,
    kind = kind.toCommon(),
    requestedUrl = requestedUrl,
    loadingMethod =
      when (loadingMethod) {
        ResourceLoadingMethod.CACHE_ONLY -> MapResourceLoadRequest.LoadingMethod.CacheOnly
        ResourceLoadingMethod.NETWORK_ONLY -> MapResourceLoadRequest.LoadingMethod.NetworkOnly
        else -> MapResourceLoadRequest.LoadingMethod.All
      },
    priority =
      when (priority) {
        ResourcePriority.LOW -> MapResourceLoadRequest.Priority.Low
        else -> MapResourceLoadRequest.Priority.Regular
      },
    usage =
      when (usage) {
        ResourceUsage.OFFLINE -> MapResourceLoadRequest.Usage.Offline
        else -> MapResourceLoadRequest.Usage.Online
      },
    storagePolicy =
      when (storagePolicy) {
        ResourceStoragePolicy.VOLATILE -> MapResourceLoadRequest.StoragePolicy.Volatile
        else -> MapResourceLoadRequest.StoragePolicy.Permanent
      },
    range = range?.let { it.start..it.end },
    priorEtag = priorEtag,
    priorModified = priorModifiedUnixMs?.let(Instant::fromEpochMilliseconds),
    priorExpires = priorExpiresUnixMs?.let(Instant::fromEpochMilliseconds),
    priorData = priorData.takeIf { it.isNotEmpty() },
  )

/** Copies a load result to the FFI response, field for field. */
internal fun MapResourceLoad.toResourceResponse(): ResourceResponse {
  val response =
    when (this) {
      is MapResourceLoad.Bytes ->
        ResourceResponse(ResourceResponseStatus.OK).also {
          it.bytes = bytes
          it.etag = etag
          it.mustRevalidate = mustRevalidate
        }
      is MapResourceLoad.NoContent -> ResourceResponse(ResourceResponseStatus.NO_CONTENT)
      is MapResourceLoad.NotModified -> ResourceResponse(ResourceResponseStatus.NOT_MODIFIED)
      is MapResourceLoad.Failed ->
        ResourceResponse(ResourceResponseStatus.ERROR).also {
          it.errorReason = reason.toFfi()
          it.errorMessage = message
          it.retryAfterUnixMs = retryAfter?.toEpochMilliseconds()
        }
    }
  response.modifiedUnixMs = modified?.toEpochMilliseconds()
  response.expiresUnixMs = expires?.toEpochMilliseconds()
  return response
}

private fun MapResourceError.toFfi(): ResourceErrorReason =
  when (this) {
    MapResourceError.NotFound -> ResourceErrorReason.NOT_FOUND
    MapResourceError.Server -> ResourceErrorReason.SERVER
    MapResourceError.Connection -> ResourceErrorReason.CONNECTION
    MapResourceError.RateLimit -> ResourceErrorReason.RATE_LIMIT
    MapResourceError.Other -> ResourceErrorReason.OTHER
  }
