package org.maplibre.compose.resource

import co.touchlab.kermit.Logger
import java.io.FileNotFoundException
import java.net.URI
import java.net.URISyntaxException
import java.util.concurrent.atomic.AtomicBoolean
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

/**
 * Resolves the `jar:file:` and `file:` resource URIs Compose hands out for packaged resources,
 * which MapLibre Native cannot fetch itself; everything else passes through so HTTP keeps
 * MapLibre's caching, retry, and revalidation behavior.
 *
 * Installed with the runtime. Provider-owned [ResourceRequestHandle] instances remain valid
 * independently of runtime teardown, so accepted reads can safely finish after [close].
 */
internal class MlnFfiResourceProvider(
  private val logger: Logger?,
  /** Turns a URL into a response. Test seam: a fake can hold a read open mid-shutdown. */
  private val read: (url: String, requestedUrl: String) -> ResourceResponse = { url, requestedUrl ->
    readResource(url, requestedUrl, logger)
  },
) : ResourceProviderCallback, AutoCloseable {

  private val accepting = AtomicBoolean(true)

  override fun handle(
    request: ResourceRequest,
    handle: ResourceRequestHandle,
  ): ResourceProviderDecision {
    val url = request.resolvedUrl
    if (isMapLibresToFetch(url)) return ResourceProviderDecision.PASS_THROUGH

    // Taking the request means owning the handle's completion and close; handles carry no thread
    // affinity, so the answer need not happen before this returns.
    take(FfiResourceRequest(handle), url, request.requestedUrl)
    return ResourceProviderDecision.HANDLE
  }

  /** Queues [request] for the reader, or refuses it if this provider is shutting down. */
  fun take(request: TakenResourceRequest, url: String, requestedUrl: String) {
    if (!accepting.get()) {
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
        open.complete(read(url, requestedUrl))
      }
    } catch (error: Throwable) {
      if (error is VirtualMachineError) throw error
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
      if (error is VirtualMachineError) throw error
      logger?.w(error) { "Failed to refuse the resource request for $url" }
    }
  }

  /** Stops taking new reads. Accepted reads own their handles and finish independently. */
  override fun close() {
    accepting.set(false)
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
    val bytes = URI(url).toURL().openStream().use { it.readBytes() }
    ResourceResponse(ResourceResponseStatus.OK).also {
      it.bytes = bytes
      // Packaged resources cannot change while the process runs.
      it.mustRevalidate = false
    }
  } catch (error: FileNotFoundException) {
    failure(url, requestedUrl, ResourceErrorReason.NOT_FOUND, "not found", error, logger)
  } catch (error: java.nio.file.NoSuchFileException) {
    // A `jar:` URL whose jar is missing fails from java.nio, not with a FileNotFoundException.
    failure(url, requestedUrl, ResourceErrorReason.NOT_FOUND, "not found", error, logger)
  } catch (error: URISyntaxException) {
    failure(url, requestedUrl, ResourceErrorReason.OTHER, "is not a valid URI", error, logger)
  } catch (error: Throwable) {
    if (error is VirtualMachineError) throw error
    failure(url, requestedUrl, ResourceErrorReason.OTHER, "could not be read", error, logger)
  }

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

/** The scheme of [url], or null when it has none or cannot be parsed. */
internal fun schemeOf(url: String): String? =
  try {
    URI(url).scheme?.lowercase()
  } catch (_: URISyntaxException) {
    null
  }
