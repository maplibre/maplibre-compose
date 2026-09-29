package org.maplibre.compose.map

import androidx.compose.ui.unit.LayoutDirection
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.coroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.maplibre.compose.mlnffi.FfiTestPlatform
import org.maplibre.compose.mlnffi.MapRenderBackend
import org.maplibre.compose.mlnffi.MlnFfiRuntimeOptions
import org.maplibre.compose.style.BaseStyle

class MlnFfiEngineStartupTest {
  @Test
  fun work_posted_before_the_engine_runs_in_order_once_it_starts() = runBlocking {
    withNativeMapState { state, runtime ->
      val session = newSession(state, runtime)
      try {
        val order = mutableListOf<String>()
        var maxZoomBefore: Double? = null
        var maxZoomAfter: Double? = null
        session.loop.submit { map ->
          order += "before constraints"
          maxZoomBefore = map.bounds.maxZoom
        }

        session.setCameraConstraints(CameraConstraints(maxZoom = 10.0))
        session.loop.submit { map ->
          order += "after constraints"
          maxZoomAfter = map.bounds.maxZoom
        }

        session.ensureEngine()
        val done = CompletableDeferred<Unit>()
        session.loop.submit {
          order += "after start"
          done.complete(Unit)
        }

        withTimeout(TIMEOUT_MILLIS) { done.await() }

        assertEquals(listOf("before constraints", "after constraints", "after start"), order)
        assertNotEquals(10.0, maxZoomBefore)
        assertEquals(10.0, maxZoomAfter)
      } finally {
        session.close()
        session.awaitClosed()
      }
    }
  }

  @Test
  fun a_failed_engine_creation_releases_queued_work_and_a_later_attach_starts_it() = runBlocking {
    withNativeMapState { state, runtime ->
      var attempts = 0
      val session =
        newSession(state, runtime) {
          if (attempts++ == 0) throw IllegalStateException("The runtime is not ready")
        }
      try {
        val abandoned = CompletableDeferred<Unit>()
        var abandonedWorkRan = false
        session.loop.submit(onDropped = { abandoned.complete(Unit) }) {
          abandonedWorkRan = true
        }

        val failure = assertFailsWith<IllegalStateException> { session.attachPresentation() }
        assertEquals("The runtime is not ready", failure.message)
        assertTrue(abandoned.isCompleted, "Queued work was not released when creation failed")

        val ran = CompletableDeferred<Unit>()
        session.loop.submit { ran.complete(Unit) }
        session.attachPresentation()
        withTimeout(TIMEOUT_MILLIS) { ran.await() }
        assertFalse(abandonedWorkRan)
        assertEquals(2, attempts)
      } finally {
        session.close()
        session.awaitClosed()
      }
    }
  }

  @Test
  fun closing_a_session_that_never_started_releases_its_queued_work() = runBlocking {
    withNativeMapState { state, runtime ->
      val session = newSession(state, runtime)
      val abandoned = CompletableDeferred<Unit>()
      var ran = false
      session.loop.submit(onDropped = { abandoned.complete(Unit) }) { ran = true }

      session.close()
      withTimeout(TIMEOUT_MILLIS) { session.awaitClosed() }

      assertTrue(abandoned.isCompleted, "Queued work was not released at close")
      assertFalse(ran)
      var refused = false
      session.loop.submit(onDropped = { refused = true }) {}
      assertTrue(refused, "A closed session accepted owner work")
    }
  }

  @Test
  fun closing_while_the_first_engine_waits_for_the_runtime_completes() = runBlocking {
    withNativeMapState { state, runtime ->
      val entered = CompletableDeferred<Unit>()
      val ready = CompletableDeferred<Unit>()
      val session =
        newSession(state, runtime) {
          entered.complete(Unit)
          ready.await()
        }
      val attach =
        async(start = CoroutineStart.UNDISPATCHED) { runCatching { session.attachPresentation() } }
      withTimeout(TIMEOUT_MILLIS) { entered.await() }

      session.close()
      ready.complete(Unit)

      withTimeout(TIMEOUT_MILLIS) { session.awaitClosed() }
      assertTrue(withTimeout(TIMEOUT_MILLIS) { attach.await() }.isFailure)
    }
  }

  @Test
  fun detaching_while_the_first_engine_waits_then_fails_leaves_the_session_usable() = runBlocking {
    withNativeMapState { state, runtime ->
      val entered = CompletableDeferred<Unit>()
      val ready = CompletableDeferred<Unit>()
      var attempts = 0
      val session =
        newSession(state, runtime) {
          if (attempts++ == 0) {
            entered.complete(Unit)
            ready.await()
            throw IllegalStateException("The runtime could not start")
          }
        }
      try {
        val attach =
          async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { session.attachPresentation() }
          }
        withTimeout(TIMEOUT_MILLIS) { entered.await() }
        val detach =
          async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { session.detachPresentation() }
          }

        ready.complete(Unit)

        withTimeout(TIMEOUT_MILLIS) { detach.await() }.getOrThrow()
        assertTrue(withTimeout(TIMEOUT_MILLIS) { attach.await() }.isFailure)
        withTimeout(TIMEOUT_MILLIS) { session.attachPresentation() }
        assertEquals(2, attempts)
      } finally {
        session.close()
        withTimeout(TIMEOUT_MILLIS) { session.awaitClosed() }
      }
    }
  }

  private fun newSession(
    state: MapState,
    runtime: RuntimeImplementation,
    awaitRuntimeReady: suspend () -> Unit = {},
  ) =
    MlnFfiMapSession(
      lifecycleAuthority = state.lifecycle,
      callbacks = state.durableStyleCallbacks(),
      logger = null,
      renderBackend = MapRenderBackend.OPENGL,
      layoutDirection = LayoutDirection.Ltr,
      cacheFile = runtime.nativeRuntimeOptions.cacheFile,
      awaitRuntimeReady = awaitRuntimeReady,
    )

  private suspend fun withNativeMapState(block: suspend (MapState, RuntimeImplementation) -> Unit) {
    FfiTestPlatform.initialize()
    TestMain.loop = coroutineContext[ContinuationInterceptor] as CoroutineDispatcher
    val cacheFile = FfiTestPlatform.createCacheFile()
    val runtime =
      RuntimeImplementation(
        platformContext = MlnFfiRuntimeOptions(cacheFile),
        closeResources = {},
        logger = null,
        mainDispatcher = TestMainDispatcher(),
      )
    val state = runtime.createMapState(baseStyle = BaseStyle.Empty)
    try {
      block(state, runtime)
    } finally {
      runtime.close()
      // A session that never finishes closing holds the runtime open; fail instead of hanging.
      withTimeout(TIMEOUT_MILLIS) { runtime.awaitClosed() }
      TestMain.loop = null
      FfiTestPlatform.deleteCacheFile(cacheFile)
    }
  }

  private companion object {
    const val TIMEOUT_MILLIS = 5_000L
  }
}
