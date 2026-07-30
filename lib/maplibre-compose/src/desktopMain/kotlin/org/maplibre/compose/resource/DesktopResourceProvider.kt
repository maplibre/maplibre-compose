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
    val scheme = schemeOf(request.url)
    if (scheme == null || scheme in NETWORK_SCHEMES) return ResourceProviderDecision.PASS_THROUGH

    // Taking the request means taking responsibility for completing and closing the handle, on
    // whatever thread this is; MapLibre calls providers from its own worker threads.
    handle.use { open ->
      if (open.isCancelled()) return ResourceProviderDecision.HANDLE
      open.complete(readResource(request.url))
    }
    return ResourceProviderDecision.HANDLE
  }

  private fun readResource(url: String): ResourceResponse =
    try {
      val bytes = URI(url).toURL().openStream().use { it.readBytes() }
      ResourceResponse(ResourceResponseStatus.OK).also {
        it.bytes = bytes
        // Packaged resources cannot change while the process runs, so there is nothing to
        // revalidate and no expiry worth reporting.
        it.mustRevalidate = false
      }
    } catch (error: FileNotFoundException) {
      failure(url, ResourceErrorReason.NOT_FOUND, "not found", error)
    } catch (error: URISyntaxException) {
      failure(url, ResourceErrorReason.OTHER, "is not a valid URI", error)
    } catch (error: Throwable) {
      if (error is VirtualMachineError) throw error
      failure(url, ResourceErrorReason.OTHER, "could not be read", error)
    }

  private fun failure(
    url: String,
    reason: ResourceErrorReason,
    what: String,
    error: Throwable,
  ): ResourceResponse {
    logger?.w(error) { "Desktop resource $url $what" }
    return ResourceResponse(ResourceResponseStatus.ERROR).also {
      it.errorReason = reason
      it.errorMessage = "$url $what: ${error.message ?: error::class.simpleName}"
    }
  }
}

/** The scheme of [url], or null when it has none or cannot be parsed. */
internal fun schemeOf(url: String): String? =
  try {
    URI(url).scheme?.lowercase()
  } catch (_: URISyntaxException) {
    null
  }
