package org.maplibre.compose.mlnffi

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import org.maplibre.nativeffi.Maplibre
import org.maplibre.nativeffi.map.MapHandle
import org.maplibre.nativeffi.map.MapOptions
import org.maplibre.nativeffi.runtime.NetworkStatus
import org.maplibre.nativeffi.runtime.RuntimeEventType
import org.maplibre.nativeffi.runtime.RuntimeHandle
import org.maplibre.nativeffi.runtime.RuntimeOptions

class NativeNetworkRecoveryTest {
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
}
