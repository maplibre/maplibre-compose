package org.maplibre.compose.resource

import co.touchlab.kermit.Logger
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import org.maplibre.compose.util.rethrowIfFatal
import org.maplibre.nativeffi.resource.ResourceErrorReason
import org.maplibre.nativeffi.resource.ResourceProviderCallback
import org.maplibre.nativeffi.resource.ResourceProviderDecision
import org.maplibre.nativeffi.resource.ResourceRequest
import org.maplibre.nativeffi.resource.ResourceRequestHandle
import org.maplibre.nativeffi.resource.ResourceResponse
import org.maplibre.nativeffi.resource.ResourceResponseStatus

/**
 * URI schemes MapLibre's own loader handles; everything else is ours. Its network stack rejects a
 * non-HTTP URI with `invalid authority`.
 */
private val NETWORK_SCHEMES = setOf("http", "https")

internal typealias MlnFfiResourceProviderFactory =
  (getLogger: () -> Logger?) -> MlnFfiResourceProvider

/**
 * Resolves the `jar:file:` and `file:` resource URIs Compose hands out for packaged resources,
 * which MapLibre Native cannot fetch itself; everything else passes through so HTTP keeps
 * MapLibre's caching, retry, and revalidation behavior.
 *
 * Installed with the runtime. Provider-owned [ResourceRequestHandle] instances remain valid
 * independently of runtime teardown, so accepted reads can safely finish after [close].
 */
@OptIn(ExperimentalAtomicApi::class)
internal class MlnFfiResourceProvider(
  private val getLogger: () -> Logger?,
  /** Turns a URL into a response. Test seam: a fake can hold a read open mid-shutdown. */
  private val read: (url: String, requestedUrl: String) -> ResourceResponse = { url, requestedUrl ->
    readResource(url, requestedUrl, getLogger())
  },
  /** Keeps the native network source in production while tests claim controlled HTTPS fixtures. */
  private val passThroughNetwork: Boolean = true,
  /** Test seam: observes when a native completion call finishes and whether it failed. */
  private val onResponseCompletionFinished: ((url: String, error: Throwable?) -> Unit)? = null,
) : ResourceProviderCallback, AutoCloseable {

  private val logger: Logger?
    get() = getLogger()

  private val accepting = AtomicBoolean(true)

  override fun handle(
    request: ResourceRequest,
    handle: ResourceRequestHandle,
  ): ResourceProviderDecision {
    val url = request.resolvedUrl
    if (passThroughNetwork && isMapLibresToFetch(url)) {
      return ResourceProviderDecision.PASS_THROUGH
    }

    // Taking the request means owning the handle's completion and close; handles carry no thread
    // affinity, so the answer need not happen before this returns.
    take(FfiResourceRequest(handle), url, request.requestedUrl)
    return ResourceProviderDecision.HANDLE
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
   * Answers a request that arrived after [close], inline — safe on MapLibre's thread only because
   * producing the failure blocks on nothing.
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

  /** Stops taking new reads. Accepted reads own their handles and finish independently. */
  override fun close() {
    accepting.store(false)
  }
}

/**
 * A resource request this provider has taken. Mirrors `ResourceRequestHandle`, which is final and
 * constructible only inside the binding, so that a test can supply one.
 */
internal interface TakenResourceRequest : AutoCloseable {
  fun isCancelled(): Boolean

  fun complete(response: ResourceResponse)
}

private class FfiResourceRequest(private val handle: ResourceRequestHandle) : TakenResourceRequest {
  override fun isCancelled(): Boolean = handle.isCancelled()

  override fun complete(response: ResourceResponse) = handle.complete(response)

  override fun close() = handle.close()
}

/**
 * Reads [url] into a response, reporting every failure as one rather than throwing. Blocks, so it
 * must run away from MapLibre's callback thread.
 */
internal fun readResource(url: String, requestedUrl: String, logger: Logger?): ResourceResponse =
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
  logger: Logger?,
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

/**
 * Whether MapLibre's own loader should fetch [resolvedUrl] rather than this provider.
 *
 * Must be decided on the *resolved* URL: only that one has been through MapLibre's tile-server
 * normalization, which turns a `maplibre://` alias into an https URL. A scheme-less URL is
 * MapLibre's too.
 */
internal fun isMapLibresToFetch(resolvedUrl: String): Boolean =
  schemeOf(resolvedUrl).let { it == null || it in NETWORK_SCHEMES }

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
