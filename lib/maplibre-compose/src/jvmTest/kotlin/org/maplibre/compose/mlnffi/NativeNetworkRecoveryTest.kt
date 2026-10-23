package org.maplibre.compose.mlnffi

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.maplibre.compose.resource.ConnectivityMode
import org.maplibre.compose.resource.MapConnectivity
import org.maplibre.nativeffi.generated.GeneratedApi
import org.maplibre.nativeffi.generated.MapHandle
import org.maplibre.nativeffi.generated.NetworkStatus
import org.maplibre.nativeffi.generated.RuntimeEvent
import org.maplibre.nativeffi.generated.RuntimeEventHandler
import org.maplibre.nativeffi.generated.RuntimeEventType
import org.maplibre.nativeffi.generated.RuntimeHandle

class NativeNetworkRecoveryTest {
  @Test
  fun the_global_override_loads_cached_styles_and_restores_network_requests() {
    FfiTestPlatform.initialize()
    val cache = FfiTestPlatform.createCacheFile()
    val previousMode = MapConnectivity.mode
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
      MapConnectivity.mode = ConnectivityMode.ForceOnline
      MapConnectivity.acquireMonitor().use {
        try {
          TestRuntime(cache.toString()).use { runtime ->
            runtime.withMap { map ->
              map.setStyleUrl(styleUrl)
              runtime.awaitStyleLoaded()
            }
            assertEquals(1, requests.get())

            MapConnectivity.mode = ConnectivityMode.ForceOffline
            assertEquals(NetworkStatus.OFFLINE, GeneratedApi.networkGetStatus())
            runtime.withMap { map ->
              map.setStyleUrl(styleUrl)
              runtime.awaitStyleLoaded()
            }
            assertEquals(1, requests.get(), "A cached style must load without an HTTP request")

            runtime.withMap { map ->
              map.setStyleUrl("$styleUrl?uncached")
              // withMap discards the earlier maps' events.
              runtime.awaitEvents(5.seconds) { event ->
                event.type == RuntimeEventType.MAP_LOADING_STARTED
              }
              assertEquals(1, requests.get())
              MapConnectivity.mode = ConnectivityMode.ForceOnline
              runtime.awaitStyleLoaded()
              assertEquals(
                2,
                requests.get(),
                "Restoring connectivity must load without resetting style",
              )
            }
          }
        } finally {
          // Restore while the lease is active, so Native is restored before later tests run.
          MapConnectivity.mode = previousMode
        }
      }
    } finally {
      MapConnectivity.mode = previousMode
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
        setStatus = GeneratedApi::networkSetStatus,
      )
    var server: HttpServer? = null
    try {
      monitor.acquire().use {
        TestRuntime(cache.toString()).use { runtime ->
          runtime.withMap { map ->
            map.setStyleUrl("http://127.0.0.1:$port/style.json")
            var failures = 0
            runtime.awaitEvents(20.seconds) { event ->
              if (event.type == RuntimeEventType.MAP_LOADING_FAILED) failures++
              failures >= 4
            }
            // Four connection errors leave an eight-second retry timer. Make the same URL
            // available, then report reconnect without setting the style again.
            onStatus(NetworkStatus.OFFLINE)
            assertEquals(NetworkStatus.OFFLINE, GeneratedApi.networkGetStatus())
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
            runtime.awaitStyleLoaded()
          }
        }
      }
    } finally {
      server?.stop(0)
      GeneratedApi.networkSetStatus(NetworkStatus.ONLINE)
      FfiTestPlatform.deleteCacheFile(cache)
    }
  }
}

/** A raw runtime whose event handler lets a test wait for events without polling. */
private class TestRuntime(cachePath: String) : AutoCloseable {
  private val events = Channel<RuntimeEvent>(Channel.UNLIMITED)
  val runtime: RuntimeHandle = runBlocking {
    GeneratedApi.runtimeCreate(GeneratedApi.runtimeOptionsDefault().copy(cachePath = cachePath))
  }

  init {
    runBlocking {
      runtime
        .setEventHandler(
          RuntimeEventHandler { batch ->
            batch.events.forEach { events.trySend(it) }
          },
        )
        .await()
    }
  }

  fun <T> withMap(block: (MapHandle) -> T): T = runBlocking {
    while (events.tryReceive().isSuccess) {}
    val map = runtime.createMap(GeneratedApi.mapOptionsDefault()).await()
    try {
      block(map)
    } finally {
      map.release().await()
    }
  }

  /** Drains events until [condition] accepts one, or fails after [timeout]. */
  fun awaitEvents(timeout: Duration, condition: (RuntimeEvent) -> Boolean) = runBlocking {
    val met =
      withTimeoutOrNull(timeout) {
        var found = false
        while (!found) {
          found = condition(events.receive())
        }
        true
      } == true
    assertTrue(met, "Network request did not reach the expected state within $timeout")
  }

  fun awaitStyleLoaded() = awaitEvents(5.seconds) { it.type == RuntimeEventType.MAP_STYLE_LOADED }

  override fun close() = runBlocking { runtime.release().await() }
}
