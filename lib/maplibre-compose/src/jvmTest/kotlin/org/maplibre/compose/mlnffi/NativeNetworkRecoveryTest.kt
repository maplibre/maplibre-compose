package org.maplibre.compose.mlnffi

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import org.maplibre.compose.resource.MapConnectivity
import org.maplibre.nativeffi.Maplibre
import org.maplibre.nativeffi.map.MapHandle
import org.maplibre.nativeffi.map.MapOptions
import org.maplibre.nativeffi.runtime.NetworkStatus
import org.maplibre.nativeffi.runtime.RuntimeEventType
import org.maplibre.nativeffi.runtime.RuntimeHandle
import org.maplibre.nativeffi.runtime.RuntimeOptions

class NativeNetworkRecoveryTest {
  @Test
  fun the_global_override_loads_cached_styles_and_restores_network_requests() {
    FfiTestPlatform.initialize()
    val cache = FfiTestPlatform.createCacheFile()
    val previousOverride = MapConnectivity.connectedOverride
    val requests = AtomicInteger()
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/") { exchange ->
      exchange.use {
        requests.incrementAndGet()
        val style = """{"version":8,"sources":{},"layers":[]}""".encodeToByteArray()
        it.responseHeaders.add("Cache-Control", "max-age=3600")
        it.sendResponseHeaders(200, style.size.toLong())
        it.responseBody.use { body -> body.write(style) }
      }
    }
    server.start()
    val styleUrl = "http://127.0.0.1:${server.address.port}/style.json"
    try {
      MapConnectivity.connectedOverride = true
      MapConnectivity.acquireMonitor().use {
        try {
          RuntimeHandle.create(RuntimeOptions().also { it.cachePath = cache.toString() }).use {
            runtime ->
            MapHandle.create(runtime, MapOptions()).use { map ->
              map.setStyleUrl(styleUrl)
              awaitStyleLoaded(runtime)
            }
            assertEquals(1, requests.get())

            MapConnectivity.connectedOverride = false
            assertEquals(NetworkStatus.OFFLINE, Maplibre.networkStatus)
            MapHandle.create(runtime, MapOptions()).use { map ->
              map.setStyleUrl(styleUrl)
              awaitStyleLoaded(runtime)
            }
            assertEquals(1, requests.get(), "A cached style must load without an HTTP request")

            MapHandle.create(runtime, MapOptions()).use { map ->
              map.setStyleUrl("$styleUrl?uncached")
              // The cached load above has drained the earlier maps' events.
              pumpUntil(runtime, 5.seconds) {
                runtime.drainEvents().events.any { it.type == RuntimeEventType.MAP_LOADING_STARTED }
              }
              assertEquals(1, requests.get())
              MapConnectivity.connectedOverride = true
              awaitStyleLoaded(runtime)
              assertEquals(
                2,
                requests.get(),
                "Restoring connectivity must load without resetting style",
              )
            }
          }
        } finally {
          // Restore while the lease is active, so Native is restored before later tests run.
          MapConnectivity.connectedOverride = previousOverride
        }
      }
    } finally {
      MapConnectivity.connectedOverride = previousOverride
      server.stop(0)
      FfiTestPlatform.deleteCacheFile(cache)
    }
  }

  @Test
  fun reconnect_retries_a_failed_style_request_before_its_backoff_expires() {
    FfiTestPlatform.initialize()
    val cache = FfiTestPlatform.createCacheFile()
    val port = unusedLoopbackPort()
    var onStatus: (NetworkStatus) -> Unit = {}
    val monitor =
      SharedNetworkMonitor(
        startMonitor = { callback ->
          onStatus = callback
          AutoCloseable {}
        },
        setStatus = Maplibre::setNetworkStatus,
      )
    var server: HttpServer? = null
    try {
      monitor.acquire().use {
        RuntimeHandle.create(RuntimeOptions().also { it.cachePath = cache.toString() }).use {
          runtime ->
          MapHandle.create(runtime, MapOptions()).use { map ->
            map.setStyleUrl("http://127.0.0.1:$port/style.json")
            var failures = 0
            pumpUntil(runtime, 20.seconds) {
              failures +=
                runtime.drainEvents().events.count {
                  it.type == RuntimeEventType.MAP_LOADING_FAILED
                }
              failures >= 4
            }
            // Four connection errors leave an eight-second retry timer. Make the same URL
            // available, then report reconnect without setting the style again.
            onStatus(NetworkStatus.OFFLINE)
            assertEquals(NetworkStatus.OFFLINE, Maplibre.networkStatus)
            val restored = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
            server = restored
            restored.createContext("/style.json") { exchange ->
              exchange.use {
                val style = """{"version":8,"sources":{},"layers":[]}""".encodeToByteArray()
                it.sendResponseHeaders(200, style.size.toLong())
                it.responseBody.use { body -> body.write(style) }
              }
            }
            restored.start()
            onStatus(NetworkStatus.ONLINE)
            pumpUntil(runtime, 5.seconds) {
              runtime.drainEvents().events.any { it.type == RuntimeEventType.MAP_STYLE_LOADED }
            }
          }
        }
      }
    } finally {
      server?.stop(0)
      Maplibre.setNetworkStatus(NetworkStatus.ONLINE)
      FfiTestPlatform.deleteCacheFile(cache)
    }
  }

  private fun pumpUntil(runtime: RuntimeHandle, timeout: Duration, condition: () -> Boolean) {
    val started = TimeSource.Monotonic.markNow()
    while (started.elapsedNow() < timeout) {
      runtime.pump(10)
      if (condition()) return
    }
    assertTrue(condition(), "Network request did not reach the expected state within $timeout")
  }

  private fun awaitStyleLoaded(runtime: RuntimeHandle) {
    pumpUntil(runtime, 5.seconds) {
      runtime.drainEvents().events.any { it.type == RuntimeEventType.MAP_STYLE_LOADED }
    }
  }
}
