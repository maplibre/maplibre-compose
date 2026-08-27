package org.maplibre.compose.map

import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlin.time.measureTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import org.maplibre.compose.mlnffi.FfiTestCache
import org.maplibre.compose.mlnffi.MapRenderBackend
import org.maplibre.compose.mlnffi.MlnFfiApplication
import org.maplibre.compose.mlnffi.MlnFfiSnapshotTarget
import org.maplibre.compose.style.BaseStyle
import org.maplibre.nativeffi.map.MapHandle
import org.maplibre.nativeffi.render.RenderSessionHandle
import org.maplibre.nativeffi.render.RenderTargetExtent

/** The snapshot's engine reservation and its exits when the state closes underneath it. */
class MlnFfiMapSnapshotLifecycleTest {

  private val cache = FfiTestCache()

  private var state: MapState? = null

  @AfterTest
  fun cleanUp() {
    state?.close()
    MlnFfiApplication.resetForTest()
    cache.close()
  }

  private fun bareState(): MapState {
    cache.configure()
    return MapState().also { state = it }
  }

  /** Serves one style URL, holding each response until [releaseResponse] opens. */
  private class StallingStyleServer : AutoCloseable {
    val requestArrived = CountDownLatch(1)
    val releaseResponse = CountDownLatch(1)

    private val server =
      HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/style.json") { exchange ->
          requestArrived.countDown()
          releaseResponse.await(60, TimeUnit.SECONDS)
          val body = STYLE_BODY.encodeToByteArray()
          exchange.sendResponseHeaders(200, body.size.toLong())
          exchange.responseBody.use { it.write(body) }
        }
        start()
      }

    val styleUri = "http://127.0.0.1:${server.address.port}/style.json"

    override fun close() {
      releaseResponse.countDown()
      server.stop(0)
    }

    private companion object {
      const val STYLE_BODY =
        """{"version":8,"sources":{},
            "layers":[{"id":"bg","type":"background","paint":{"background-color":"#ff0000"}}]}"""
    }
  }

  @Test
  fun a_snapshot_reserves_the_engine_against_a_concurrent_session() {
    val state = bareState()
    StallingStyleServer().use { server ->
      state.baseStyle = BaseStyle.Uri(server.styleUri)
      runBlocking {
        val image =
          async(Dispatchers.Default) {
            state.snapshot(width = 20.dp, height = 20.dp, timeout = 60.seconds)
          }
        assertTrue(
          server.requestArrived.await(30, TimeUnit.SECONDS),
          "the style fetch never started",
        )
        val engine = state.engine
        val core = assertNotNull(engine.core, "the snapshot must have created a core")
        val refusal =
          assertFailsWith<IllegalStateException>("a session must not attach mid-snapshot") {
            engine.createSession(core, MapRenderBackend.VULKAN)
          }
        assertTrue(
          refusal.message.orEmpty().contains("snapshot"),
          "the refusal must name the snapshot, got: ${refusal.message}",
        )
        assertFailsWith<IllegalStateException>("an eviction must not close the snapshot's core") {
          engine.acquireCore(123.0, LayoutDirection.Ltr, MapRenderBackend.VULKAN)
        }
        assertFalse(core.isClosed, "the refused eviction must leave the core alive")
        assertSame(core, engine.core, "the refused eviction must leave the core in place")

        server.releaseResponse.countDown()
        assertEquals(20, image.await().width)
      }
    }
  }

  @Test
  fun a_close_during_the_style_wait_fails_the_snapshot_promptly() {
    val state = bareState()
    StallingStyleServer().use { server ->
      state.baseStyle = BaseStyle.Uri(server.styleUri)
      runBlocking {
        // A supervisor keeps the snapshot's expected failure from cancelling the test scope.
        supervisorScope {
          val image =
            async(Dispatchers.Default) {
              state.snapshot(width = 20.dp, height = 20.dp, timeout = 60.seconds)
            }
          assertTrue(
            server.requestArrived.await(30, TimeUnit.SECONDS),
            "the style fetch never started",
          )
          val elapsed = measureTime {
            state.close()
            assertFailsWith<IllegalStateException> { image.await() }
          }
          assertTrue(elapsed < 10.seconds, "the snapshot failed only after $elapsed")
        }
      }
    }
  }

  /** The still-image session never attaches here, so its target must never be asked for one. */
  private class UnreachedTarget : MlnFfiSnapshotTarget {
    override val backend = MapRenderBackend.VULKAN

    override fun attach(map: MapHandle, extent: RenderTargetExtent): RenderSessionHandle =
      throw AssertionError("the pump must fail before it attaches a render session")

    override fun close() {}
  }

  @Test
  fun a_close_before_the_render_session_attaches_releases_the_handshake() {
    val state = bareState()
    val core = state.engine.acquireCore(1.0, LayoutDirection.Ltr, MapRenderBackend.VULKAN)
    val pumpEntered = CountDownLatch(1)
    val holdPump = CountDownLatch(1)
    runBlocking {
      val render =
        async(Dispatchers.Default) {
          runCatching {
            renderStillImage(
              core = core,
              target = UnreachedTarget(),
              width = 10,
              height = 10,
              deadline = TimeSource.Monotonic.markNow() + 60.seconds,
              loadFailure = {
                pumpEntered.countDown()
                holdPump.await(60, TimeUnit.SECONDS)
                "held for the close"
              },
            )
          }
        }
      assertTrue(pumpEntered.await(30, TimeUnit.SECONDS), "the pump never started")
      val closeDuration = async(Dispatchers.IO) { measureTime { core.close() } }
      // A beat for the close to reach the handshake before the pump is allowed to exit.
      Thread.sleep(250)
      holdPump.countDown()
      assertTrue(render.await().isFailure, "the held load failure must fail the pump")
      val elapsed = closeDuration.await()
      assertTrue(elapsed < 5.seconds, "the close waited out the handshake: $elapsed")
    }
  }

  @Test
  fun a_new_base_style_clears_a_previous_load_failure() {
    val state = bareState()
    state.baseStyle = BaseStyle.Json("this is not a style")
    assertFailsWith<IllegalStateException>("the broken style must fail the snapshot") {
      runBlocking { state.snapshot(width = 10.dp, height = 10.dp, timeout = 30.seconds) }
    }

    state.baseStyle = GOOD_STYLE
    val image = runBlocking {
      state.snapshot(width = 10.dp, height = 10.dp, timeout = 60.seconds)
    }
    assertEquals(10, image.width)
  }

  private companion object {
    val GOOD_STYLE =
      BaseStyle.Json(
        """
        {"version":8,"sources":{},
         "layers":[{"id":"bg","type":"background","paint":{"background-color":"#00ff00"}}]}
        """
          .trimIndent()
      )
  }
}
