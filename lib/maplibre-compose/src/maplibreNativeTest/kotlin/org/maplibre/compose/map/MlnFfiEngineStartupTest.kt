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
import org.maplibre.compose.mlnffi.MlnFfiGate
import org.maplibre.compose.mlnffi.MlnFfiRuntimeOptions
import org.maplibre.compose.offline.OfflineManagerException
import org.maplibre.compose.resource.MlnFfiResourceProvider
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
  fun failed_runtime_initialization_releases_queued_map_work() = runBlocking {
    withNativeMapState(configure = { it.copy(maximumCacheSizeBytes = -1) }) { state, runtime ->
      val session = newSession(state, runtime)
      try {
        val abandoned = CompletableDeferred<Unit>()
        var ran = false
        session.loop.submit(onDropped = { abandoned.complete(Unit) }) { ran = true }

        assertFailsWith<OfflineManagerException> { session.attachPresentation() }
        assertTrue(abandoned.isCompleted, "Queued work was not released when initialization failed")
        assertFalse(ran)
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
    val entered = CompletableDeferred<Unit>()
    val release = MlnFfiGate()
    try {
      withNativeMapState(
        configure = { options ->
          options.copy(
            resourceProviderFactory = { logger, config ->
              entered.complete(Unit)
              release.awaitUntilOpen()
              MlnFfiResourceProvider(logger, config)
            }
          )
        }
      ) { state, runtime ->
        val session = newSession(state, runtime)
        val attach =
          async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { session.attachPresentation() }
          }
        withTimeout(TIMEOUT_MILLIS) { entered.await() }

        session.close()
        release.open()

        withTimeout(TIMEOUT_MILLIS) { session.awaitClosed() }
        assertTrue(withTimeout(TIMEOUT_MILLIS) { attach.await() }.isFailure)
      }
    } finally {
      release.open()
    }
  }

  private fun newSession(
    state: MapState,
    runtime: RuntimeImplementation,
  ) =
    MlnFfiMapSession(
      lifecycleAuthority = state.lifecycle,
      callbacks = state.durableStyleCallbacks(),
      logger = null,
      renderBackend = MapRenderBackend.OPENGL,
      layoutDirection = LayoutDirection.Ltr,
      owner = runtime.nativeOwner,
    )

  private suspend fun withNativeMapState(
    configure: (MlnFfiRuntimeOptions) -> MlnFfiRuntimeOptions = { it },
    block: suspend (MapState, RuntimeImplementation) -> Unit,
  ) {
    FfiTestPlatform.initialize()
    TestMain.loop = coroutineContext[ContinuationInterceptor] as CoroutineDispatcher
    val cacheFile = FfiTestPlatform.createCacheFile()
    val runtime =
      createNativeMapRuntime(
        configure(MlnFfiRuntimeOptions(cacheFile, mainDispatcher = TestMainDispatcher()))
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
