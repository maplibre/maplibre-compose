package org.maplibre.compose.map

import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.LayoutDirection
import kotlin.concurrent.Volatile
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.coroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.maplibre.compose.mlnffi.ComposeRenderBackend
import org.maplibre.compose.mlnffi.FfiTestPlatform
import org.maplibre.compose.mlnffi.MapRenderBackend
import org.maplibre.compose.mlnffi.MlnFfiGate
import org.maplibre.compose.mlnffi.MlnFfiMapHostSession
import org.maplibre.compose.mlnffi.MlnFfiRuntimeOptions
import org.maplibre.compose.mlnffi.RenderBackendPair
import org.maplibre.compose.mlnffi.TestLatch
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.StyleBinding
import org.maplibre.nativeffi.map.MapHandle

class PlatformMapAccessTest {
  @Test
  fun a_renderer_can_offer_a_surface_before_the_session_starts() = runBlocking {
    withNativeMapState { state, runtime ->
      fun newSession() =
        MlnFfiMapSession(
          lifecycleAuthority = state.lifecycle,
          callbacks = state.durableStyleCallbacks(),
          logger = null,
          renderBackend = MapRenderBackend.OpenGl,
          layoutDirection = LayoutDirection.Ltr,
          owner = runtime.nativeOwner,
        )
      val host =
        object : MlnFfiMapHostSession {
          override val isClosed = false
          override val backends =
            RenderBackendPair(MapRenderBackend.OpenGl, ComposeRenderBackend.OpenGl)

          override fun requestFrame() = Unit

          override fun <T> withRendererAccess(action: () -> T): T = action()

          override fun enqueueRenderer(action: () -> Unit): Boolean {
            action()
            return true
          }
        }
      val session = newSession()
      try {
        withContext(Dispatchers.Default) { session.onSurfaceAvailable(host) }
        assertNull(session.lifecycle.engine)
        state.close()
        state.awaitClosed()
        assertFalse(session.isClosing, "A surface offer must not adopt the session")
      } finally {
        session.onSurfaceLost(host)
        session.close()
        session.awaitClosed()
      }
      // A closed authority must neither run cleanup on a partially constructed session nor admit
      // it.
      val lateSession = newSession()
      lateSession.start()
      lateSession.awaitClosed()
      assertNull(lateSession.lifecycle.engine)
    }
  }

  @Test
  fun a_closing_session_refuses_the_reports_of_its_engine_and_style() = runBlocking {
    withNativeMapState { state, runtime ->
      val loaded = CompletableDeferred<StyleBinding>()
      val session =
        MlnFfiMapSession(
          lifecycleAuthority = state.lifecycle,
          callbacks =
            object : MapAdapter.Callbacks by EmptyMapAdapterCallbacks {
              override fun onStyleChanged(map: MapAdapter, style: StyleBinding?) {
                style?.let(loaded::complete)
              }
            },
          logger = null,
          renderBackend = MapRenderBackend.OpenGl,
          layoutDirection = LayoutDirection.Ltr,
          owner = runtime.nativeOwner,
        )
      try {
        session.setBaseStyle(BaseStyle.Empty)
        val engine = session.ensureEngine()
        val style = withTimeout(5_000L) { loaded.await() }.identity
        assertTrue(session.isCurrentEngine(engine))
        assertTrue(session.isCurrentStyle(style))
        val ownerHeld = CompletableDeferred<Unit>()
        val releaseOwner = MlnFfiGate()
        val holding =
          async(Dispatchers.Default) {
            session.readMap {
              ownerHeld.complete(Unit)
              releaseOwner.awaitUntilOpen()
            }
          }
        try {
          withTimeout(5_000L) { ownerHeld.await() }
          // An attachment waiting for the owner thread keeps the closure's cleanup queued, while
          // reports that the engine queued earlier can still run on main.
          session.start()
          session.close()

          assertFalse(session.isCurrentEngine(engine))
          assertFalse(session.isCurrentStyle(style))
        } finally {
          releaseOwner.open()
          holding.await()
        }
      } finally {
        session.close()
        session.awaitClosed()
      }
    }
  }

  @Test
  fun detached_native_access_creates_the_map_and_runs_on_its_owner_context() = runBlocking {
    withNativeMapState { state, runtime ->
      val zoom =
        withContext(Dispatchers.Default) {
          state.withPlatformMap {
            assertTrue(runtime.nativeOwner.isCurrent())
            val rawMap: MapHandle = map
            rawMap.camera.zoom
          }
        }

      assertEquals(state.cameraPosition.zoom, zoom)
      assertNull(state.currentMapAttachment)
    }
  }

  @Test
  fun closure_waits_for_a_started_native_callback_and_later_access_is_rejected() = runBlocking {
    withNativeMapState { state, _ ->
      val callbackStarted = CompletableDeferred<Unit>()
      val releaseCallback = MlnFfiGate()
      val access =
        async(Dispatchers.Default) {
          state.withPlatformMap {
            callbackStarted.complete(Unit)
            releaseCallback.awaitUntilOpen()
            map.hashCode()
            true
          }
        }
      callbackStarted.await()
      val closed = async { state.awaitClosed() }
      try {
        // A close request returns promptly and is visible at once, so admission refuses new
        // work while the admitted callback is still running.
        state.close()
        assertTrue(state.isClosed)
        assertFalse(closed.isCompleted)

        var callbackRan = false
        assertFailsWith<IllegalStateException> {
          state.withPlatformMap {
            callbackRan = true
            map
          }
        }
        assertFalse(callbackRan)
        assertFalse(closed.isCompleted)
      } finally {
        releaseCallback.open()
      }

      // The admitted callback finishes, and only then does the closure commit and tear down.
      assertTrue(access.await())
      closed.await()
      assertTrue(state.isClosed)
    }
  }

  @Test
  fun closure_requested_inside_a_native_callback_commits_after_it_returns() = runBlocking {
    withNativeMapState { state, _ ->
      val closed = async { state.awaitClosed() }
      val result = state.withPlatformMap {
        state.close()
        // The request is visible at once, but the commit waits for this callback to return.
        assertTrue(state.isClosed)
        assertFalse(closed.isCompleted)
        map.hashCode()
        true
      }

      assertTrue(result)
      assertTrue(state.isClosed)
      closed.await()
    }
  }

  @Test
  fun replacing_the_engine_before_a_queued_native_callback_rejects_it() = runBlocking {
    withNativeMapState { state, runtime ->
      state.withPlatformMap { map.hashCode() }
      val original = state.lifecycle.currentAdapter() as MlnFfiMapSession
      val ownerEntered = CompletableDeferred<Unit>()
      val releaseOwner = MlnFfiGate()
      original.loop.submit {
        ownerEntered.complete(Unit)
        releaseOwner.awaitUntilOpen()
      }

      ownerEntered.await()

      var callbackRan = false
      supervisorScope {
        val access =
          async(start = CoroutineStart.UNDISPATCHED) {
            state.withPlatformMap {
              callbackRan = true
              map.hashCode()
            }
          }

        val token = state.reservePresentation()
        val replacement =
          MlnFfiMapSession(
            lifecycleAuthority = state.lifecycle,
            callbacks = state.durableStyleCallbacks(),
            logger = runtime.logger,
            renderBackend = MapRenderBackend.OpenGl,
            scaleFactor = 2.0,
            layoutDirection = LayoutDirection.Ltr,
            owner = runtime.nativeOwner,
          )
        state.publishPresentation(token, replacement)
        releaseOwner.open()

        val failure = assertFailsWith<CancellationException> { access.await() }
        assertEquals("The native platform map changed before access could begin", failure.message)
      }
      assertFalse(callbackRan)
    }
  }

  @Test
  fun cancelling_a_queued_native_invocation_prevents_its_callback() = runBlocking {
    withNativeMapState { state, _ ->
      state.withPlatformMap { map.hashCode() }
      val session = state.lifecycle.currentAdapter() as MlnFfiMapSession
      val ownerEntered = CompletableDeferred<Unit>()
      val releaseOwner = MlnFfiGate()
      session.loop.submit {
        ownerEntered.complete(Unit)
        releaseOwner.awaitUntilOpen()
      }

      ownerEntered.await()

      var callbackRan = false
      try {
        supervisorScope {
          val access =
            async(start = CoroutineStart.UNDISPATCHED) {
              state.withPlatformMap {
                callbackRan = true
                map.hashCode()
              }
            }
          val ownerDrained = CompletableDeferred<Unit>()
          session.loop.submit { ownerDrained.complete(Unit) }

          access.cancel()
          assertFailsWith<CancellationException> { access.await() }
          releaseOwner.open()
          ownerDrained.await()
        }
      } finally {
        releaseOwner.open()
      }
      assertFalse(callbackRan)
    }
  }

  @Test
  fun a_closing_renderer_queue_does_not_count_as_released() = runBlocking {
    withNativeMapState { state, _ ->
      state.withPlatformMap { map.hashCode() }
      val session = state.lifecycle.currentAdapter() as MlnFfiMapSession
      val rejected = CompletableDeferred<Unit>()
      val host =
        object : MlnFfiMapHostSession {
          override val isClosed = false
          override val backends =
            RenderBackendPair(MapRenderBackend.OpenGl, ComposeRenderBackend.OpenGl)

          override fun requestFrame() = Unit

          override fun <T> withRendererAccess(action: () -> T): T = action()

          override fun enqueueRenderer(action: () -> Unit): Boolean {
            rejected.complete(Unit)
            return false
          }
        }
      session.onSurfaceAvailable(host)
      try {
        state.close()
        withTimeout(5_000L) { rejected.await() }
        val closed = async(start = CoroutineStart.UNDISPATCHED) { state.awaitClosed() }
        assertTrue(state.isClosed)
        assertFalse(closed.isCompleted, "The host still owns the renderer attachment")
        session.onSurfaceLost(host)
        withTimeout(5_000L) { closed.await() }
      } finally {
        session.onSurfaceLost(host)
      }
    }
  }

  @Test
  fun a_late_loss_and_offer_from_the_old_host_cannot_displace_its_replacement() = runBlocking {
    withNativeMapState { state, _ ->
      state.withPlatformMap { map.hashCode() }
      val session = state.lifecycle.currentAdapter() as MlnFfiMapSession
      val oldReleased = CompletableDeferred<Unit>()
      val finishOldLoss = TestLatch(1)
      val oldLossReleasedInTime = CompletableDeferred<Boolean>()
      val oldHost =
        object : MlnFfiMapHostSession {
          @Volatile override var isClosed = false
          override val backends =
            RenderBackendPair(MapRenderBackend.OpenGl, ComposeRenderBackend.OpenGl)

          override fun requestFrame() = Unit

          override fun <T> withRendererAccess(action: () -> T): T {
            val result = action()
            oldReleased.complete(Unit)
            oldLossReleasedInTime.complete(finishOldLoss.await(5_000L))
            return result
          }

          override fun enqueueRenderer(action: () -> Unit): Boolean {
            action()
            return true
          }
        }
      var newHostCommands = 0
      val newHost =
        object : MlnFfiMapHostSession {
          override val isClosed = false
          override val backends = oldHost.backends

          override fun requestFrame() = Unit

          override fun <T> withRendererAccess(action: () -> T): T = action()

          override fun enqueueRenderer(action: () -> Unit): Boolean {
            newHostCommands++
            action()
            return true
          }
        }
      session.onSurfaceAvailable(oldHost)
      val oldLoss = async(Dispatchers.Default) { session.onSurfaceLost(oldHost) }
      try {
        withTimeout(5_000L) { oldReleased.await() }
        session.onSurfaceAvailable(newHost)
        oldHost.isClosed = true
        finishOldLoss.countDown()
        oldLoss.await()
        assertTrue(oldLossReleasedInTime.await())
        session.onSurfaceAvailable(oldHost)
        session.queryRenderedFeatures(DpOffset.Zero, null, null)
        assertEquals(1, newHostCommands, "Late old-host callbacks cleared the replacement")
      } finally {
        finishOldLoss.countDown()
        oldLoss.await()
        session.onSurfaceLost(newHost)
      }
    }
  }

  private suspend fun withNativeMapState(block: suspend (MapState, RuntimeImplementation) -> Unit) {
    FfiTestPlatform.initialize()
    TestMain.loop = coroutineContext[ContinuationInterceptor] as CoroutineDispatcher
    val cacheFile = FfiTestPlatform.createCacheFile()
    val runtime =
      createNativeMapRuntime(MlnFfiRuntimeOptions(cacheFile, mainDispatcher = TestMainDispatcher()))
    val state = runtime.createMapState(baseStyle = BaseStyle.Empty)
    try {
      block(state, runtime)
    } finally {
      runtime.close()
      runtime.awaitClosed()
      TestMain.loop = null
      FfiTestPlatform.deleteCacheFile(cacheFile)
    }
  }
}
