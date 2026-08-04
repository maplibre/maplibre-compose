package org.maplibre.compose.resource

import co.touchlab.kermit.Logger
import java.io.FileNotFoundException
import java.net.URI
import java.net.URISyntaxException
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

/**
 * Resolves the resource URIs MapLibre Native cannot fetch itself.
 *
 * Compose hands out `jar:file:` and `file:` URIs for packaged resources, so a style, sprite, glyph,
 * or tile referenced from `Res.getUri` never reaches the network stack. This intercepts those and
 * reads them from the classpath or filesystem, and passes everything else through untouched so HTTP
 * keeps MapLibre's caching, retry, and revalidation behavior.
 *
 * Installed with the runtime, before any map exists, so no request can be issued before the
 * provider that serves it.
 */
internal class DesktopResourceProvider(private val logger: Logger?) : ResourceProviderCallback {

  override fun handle(
    request: ResourceRequest,
    handle: ResourceRequestHandle,
  ): ResourceProviderDecision {
    val url = request.resolvedUrl
    if (isMapLibresToFetch(url)) return ResourceProviderDecision.PASS_THROUGH

    // Taking the request means taking responsibility for completing and closing the handle, on
    // whatever thread this is; MapLibre calls providers from its own worker threads.
    handle.use { open ->
      if (open.isCancelled()) return ResourceProviderDecision.HANDLE
      open.complete(readResource(url, request.requestedUrl))
    }
    return ResourceProviderDecision.HANDLE
  }

  private fun readResource(url: String, requestedUrl: String): ResourceResponse =
    try {
      val bytes = URI(url).toURL().openStream().use { it.readBytes() }
      ResourceResponse(ResourceResponseStatus.OK).also {
        it.bytes = bytes
        // Packaged resources cannot change while the process runs, so there is nothing to
        // revalidate and no expiry worth reporting.
        it.mustRevalidate = false
      }
    } catch (error: FileNotFoundException) {
      failure(url, requestedUrl, ResourceErrorReason.NOT_FOUND, "not found", error)
    } catch (error: URISyntaxException) {
      failure(url, requestedUrl, ResourceErrorReason.OTHER, "is not a valid URI", error)
    } catch (error: Throwable) {
      if (error is VirtualMachineError) throw error
      failure(url, requestedUrl, ResourceErrorReason.OTHER, "could not be read", error)
    }

  private fun failure(
    url: String,
    requestedUrl: String,
    reason: ResourceErrorReason,
    what: String,
    error: Throwable,
  ): ResourceResponse {
    // The style names one URL and the loader may resolve another, so a failure that says only the
    // resolved one leaves nothing to grep the style for.
    val named = if (requestedUrl == url) url else "$url (requested as $requestedUrl)"
    logger?.w(error) { "Desktop resource $named $what" }
    return ResourceResponse(ResourceResponseStatus.ERROR).also {
      it.errorReason = reason
      it.errorMessage = "$named $what: ${error.message ?: error::class.simpleName}"
    }
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
