package org.maplibre.compose.resource

import co.touchlab.kermit.Logger
import java.io.FileNotFoundException
import java.net.URI
import java.net.URISyntaxException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.maplibre.nativeffi.resource.ResourceErrorReason
import org.maplibre.nativeffi.resource.ResourceProviderCallback
import org.maplibre.nativeffi.resource.ResourceProviderDecision
import org.maplibre.nativeffi.resource.ResourceRequest
import org.maplibre.nativeffi.resource.ResourceRequestHandle
import org.maplibre.nativeffi.resource.ResourceResponse
import org.maplibre.nativeffi.resource.ResourceResponseStatus

/**
 * URI schemes MapLibre's own loader handles. Everything else is ours to resolve or to reject.
 *
 * MapLibre's network stack rejects a non-HTTP URI with `invalid authority`, which is what a Compose
 * resource URI looks like to it.
 */
private val NETWORK_SCHEMES = setOf("http", "https")

/** How long the reader thread waits for more work before it goes away. */
private const val READER_IDLE_SECONDS = 30L

/** How long [DesktopResourceProvider.close] waits for reads that are already running. */
private const val QUIESCE_TIMEOUT_SECONDS = 5L

/**
 * Resolves the resource URIs MapLibre Native cannot fetch itself.
 *
 * Compose hands out `jar:file:` and `file:` URIs for packaged resources, so a style, sprite, glyph,
 * or tile referenced from `Res.getUri` never reaches the network stack. This intercepts those and
 * reads them from the classpath or filesystem, and passes everything else through untouched so HTTP
 * keeps MapLibre's caching, retry, and revalidation behavior.
 *
 * Installed with the runtime, before any map exists, so no request can be issued before the
 * provider that serves it. Its owner must [close] it before closing that runtime; see [reader].
 */
internal class DesktopResourceProvider(
  private val logger: Logger?,
  /**
   * Turns a URL into a response. Only the default is used in production; a test supplies its own to
   * hold a read open for as long as it needs to observe cancellation or shutdown mid-read, which
   * cannot be arranged with a real file.
   */
  private val read: (url: String, requestedUrl: String) -> ResourceResponse = { url, requestedUrl ->
    readResource(url, requestedUrl, logger)
  },
) : ResourceProviderCallback, AutoCloseable {

  /**
   * The one thread this provider reads on.
   *
   * Reading where MapLibre offers the request is what this exists to avoid. `handle` arrives on a
   * MapLibre network thread holding a callback lease, and the binding's `RuntimeHandle.close()`
   * spin-waits for every such lease to be returned before it releases the provider — so a read that
   * blocks there blocks runtime teardown for exactly as long as it blocks, and a read that never
   * finishes wedges it. Handing the work to this thread makes the callback a queue insertion.
   *
   * One thread, because these reads are local: the queue is here to get off MapLibre's thread, not
   * to add throughput. The cost is that a stalled read delays the packaged resources queued behind
   * it — which leaves a map missing tiles rather than a runtime that cannot be closed.
   *
   * It is a daemon with a zero core size, so it exists only while there is work and never holds a
   * process open. That is what an unclosed provider costs, and the offline runtime installs one it
   * never closes.
   */
  private val reader =
    ThreadPoolExecutor(0, 1, READER_IDLE_SECONDS, TimeUnit.SECONDS, LinkedBlockingQueue()) { task ->
      Thread(task, "maplibre-compose-resource-reader").also { it.isDaemon = true }
    }

  /**
   * Guards [accepting] and the queue insertion together, so a read cannot be queued onto a reader
   * that [close] has already shut down.
   */
  private val acceptLock = ReentrantLock()
  private var accepting = true

  override fun handle(
    request: ResourceRequest,
    handle: ResourceRequestHandle,
  ): ResourceProviderDecision {
    val url = request.resolvedUrl
    if (isMapLibresToFetch(url)) return ResourceProviderDecision.PASS_THROUGH

    // Taking the request means taking responsibility for completing and closing the handle, on
    // whatever thread; the binding's handles carry no thread affinity, and it reclaims them from
    // its own cleanup thread when one is dropped. So the answer need not — and here does not —
    // happen before this returns.
    take(FfiResourceRequest(handle), url, request.requestedUrl)
    return ResourceProviderDecision.HANDLE
  }

  /**
   * Queues [request] for the reader, or refuses it if this provider is shutting down.
   *
   * Split out from [handle] because a `ResourceRequestHandle` is a final `expect class` that only
   * the binding's own callback machinery can construct, so this is the seam the queueing,
   * cancellation, and shutdown behaviour is tested through.
   */
  fun take(request: TakenResourceRequest, url: String, requestedUrl: String) {
    val queued = acceptLock.withLock {
      if (!accepting) false
      else {
        reader.execute { serve(request, url, requestedUrl) }
        true
      }
    }
    if (!queued) refuse(request, url, requestedUrl)
  }

  /** Reads one resource and answers with it. Runs on [reader]. */
  private fun serve(request: TakenResourceRequest, url: String, requestedUrl: String) {
    try {
      request.use { open ->
        // Checked here rather than only where the request arrived, because one queued behind a slow
        // read may have been abandoned since. It cannot be atomic with the completion below, and
        // does not need to be: cancellation comes from another thread, so the binding has to
        // tolerate a completion that races it however carefully the check is placed.
        if (open.isCancelled()) return
        open.complete(read(url, requestedUrl))
      }
    } catch (error: Throwable) {
      if (error is VirtualMachineError) throw error
      // Reported rather than propagated: this thread is ours, and the only other place it could go
      // is the default uncaught-exception handler. MapLibre is left waiting for a resource that
      // never arrives, which is what a dropped handle means anyway.
      logger?.w(error) { "Failed to answer the desktop resource request for $url" }
    }
  }

  /**
   * Answers a request that arrived after [close], inline.
   *
   * Safe in a way a read is not: it costs nothing to produce, so it cannot hold the callback lease
   * open. Leaving it unanswered would leave whatever asked for it waiting, and passing it through
   * would hand a `jar:` URL to a network stack that cannot open one.
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
      logger?.w(error) { "Failed to refuse the desktop resource request for $url" }
    }
  }

  /**
   * Stops taking reads and waits for the ones already queued.
   *
   * This is what quiesces the provider before its runtime is closed. Queued reads are run rather
   * than dropped — `shutdown` rather than `shutdownNow` — because a request this provider took is a
   * request only it can answer, and because a dropped one would still have to be closed here to
   * release its native request.
   *
   * The wait is bounded so that a read stuck on a file handle costs teardown a few seconds instead
   * of the process. Past the bound the reader keeps running, and a completion may land after the
   * runtime is gone; there is nothing better available, since the reads are the caller's own and
   * cannot be interrupted safely.
   */
  override fun close() {
    acceptLock.withLock {
      accepting = false
      reader.shutdown()
    }
    val quiesced =
      try {
        reader.awaitTermination(QUIESCE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
      } catch (interruption: InterruptedException) {
        Thread.currentThread().interrupt()
        logger?.w(interruption) { "Interrupted while quiescing desktop resource reads" }
        false
      }
    if (!quiesced) {
      logger?.e {
        "Desktop resource reads did not finish within ${QUIESCE_TIMEOUT_SECONDS}s; " +
          "closing the runtime with ${reader.queue.size + reader.activeCount} still outstanding"
      }
    }
  }
}

/**
 * A resource request this provider has taken: the whole of what it does with one.
 *
 * The binding's `ResourceRequestHandle` is final, has no constructible form, and is handed out only
 * from inside its own callback, so nothing outside the binding can stand in for one. This is that
 * handle's three operations, named so a test can supply them.
 */
internal interface TakenResourceRequest : AutoCloseable {
  fun isCancelled(): Boolean

  fun complete(response: ResourceResponse)
}

/** The real thing: a request MapLibre handed the provider. */
private class FfiResourceRequest(private val handle: ResourceRequestHandle) : TakenResourceRequest {
  override fun isCancelled(): Boolean = handle.isCancelled()

  override fun complete(response: ResourceResponse) = handle.complete(response)

  override fun close() = handle.close()
}

/**
 * Reads [url] into a response, reporting every failure as one rather than throwing.
 *
 * Blocking, and deliberately so: it runs on the provider's own reader thread, where taking as long
 * as the filesystem takes costs nothing that MapLibre is waiting on.
 */
internal fun readResource(url: String, requestedUrl: String, logger: Logger?): ResourceResponse =
  try {
    val bytes = URI(url).toURL().openStream().use { it.readBytes() }
    ResourceResponse(ResourceResponseStatus.OK).also {
      it.bytes = bytes
      // Packaged resources cannot change while the process runs, so there is nothing to
      // revalidate and no expiry worth reporting.
      it.mustRevalidate = false
    }
  } catch (error: FileNotFoundException) {
    failure(url, requestedUrl, ResourceErrorReason.NOT_FOUND, "not found", error, logger)
  } catch (error: java.nio.file.NoSuchFileException) {
    // The same thing said by the other file API: a `jar:` URL whose jar is missing fails from
    // java.nio, which is not a FileNotFoundException, and reporting that as an unspecified error
    // would tell a caller its style was unreadable rather than absent.
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
  // The style names one URL and the loader may resolve another, so a failure that says only the
  // resolved one leaves nothing to grep the style for.
  val named = if (requestedUrl == url) url else "$url (requested as $requestedUrl)"
  val cause = error?.let { ": ${it.message ?: it::class.simpleName}" }.orEmpty()
  if (error == null) logger?.w { "Desktop resource $named $what" }
  else logger?.w(error) { "Desktop resource $named $what" }
  return ResourceResponse(ResourceResponseStatus.ERROR).also {
    it.errorReason = reason
    it.errorMessage = "$named $what$cause"
  }
}

/**
 * Whether MapLibre's own loader should fetch [resolvedUrl] rather than this provider.
 *
 * Decided on the *resolved* URL, which is the one thing that makes this correct for MapLibre's
 * tile-server aliases. A style may name `maplibre://maps/style`, and that alias survives all the
 * way into the provider — only `resolvedUrl` has been through the tile-server normalization that
 * turns it into `https://demotiles.maplibre.org/style.json`. Deciding on the requested URL instead
 * would see an unknown `maplibre:` scheme, take responsibility for a URL `URI.toURL()` cannot open,
 * and report a resource error for a style that is perfectly fetchable.
 *
 * A URL with no scheme at all is MapLibre's too: there is nothing here that could resolve it, and
 * its loader gives a better diagnostic than a `MalformedURLException` would.
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
