package org.maplibre.compose.map

import androidx.compose.ui.unit.LayoutDirection
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.coroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.maplibre.compose.mlnffi.FfiTestPlatform
import org.maplibre.compose.mlnffi.MapRenderBackend
import org.maplibre.compose.mlnffi.MlnFfiRuntimeOptions
import org.maplibre.compose.mlnffi.TestGate
import org.maplibre.compose.offline.OfflineStorageException
import org.maplibre.compose.resource.MlnFfiResourceProvider
import org.maplibre.compose.style.BaseStyle
import org.maplibre.nativeffi.generated.AnimationOptions

class MlnFfiEngineStartupTest {
  @Test
  fun settings_made_before_the_engine_reach_it_once_it_starts() = runBlocking {
    withNativeMapState { state, runtime ->
      val session = newSession(state, runtime)
      try {
        assertEquals(null, session.readMap { it.bounds.maxZoom })
        session.setCameraConstraints(CameraConstraints { maxZoom = 10.0 })
        session.ensureEngine()

        assertEquals(10.0, session.readMap { it.bounds.maxZoom })
      } finally {
        session.close()
        session.awaitClosed()
      }
    }
  }

  @Test
  fun failed_runtime_initialization_fails_the_attachment() = runBlocking {
    withNativeMapState(configure = { it.copy(maximumCacheSizeBytes = -1) }) { state, runtime ->
      val session = newSession(state, runtime)
      try {
        assertFailsWith<OfflineStorageException> { session.attachPresentation() }
        assertEquals(null, session.mapForTest)
      } finally {
        session.close()
        session.awaitClosed()
      }
    }
  }

  @Test
  fun closing_while_the_first_engine_waits_for_the_runtime_completes() = runBlocking {
    val entered = CompletableDeferred<Unit>()
    val release = TestGate()
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
        withTimeout(TimeoutMillis) { entered.await() }

        session.close()
        release.open()

        withTimeout(TimeoutMillis) { session.awaitClosed() }
        assertTrue(withTimeout(TimeoutMillis) { attach.await() }.isFailure)
      }
    } finally {
      release.open()
    }
  }

  @Test
  fun a_rejected_camera_start_reports_its_failure_and_preserves_the_previous_anchor() =
    runBlocking {
      withNativeMapState { state, runtime ->
        val session = newSession(state, runtime)
        val transitions = MlnFfiCameraTransitions { null }
        try {
          session.ensureEngine()
          val map = checkNotNull(session.mapForTest)
          val previous =
            async(start = CoroutineStart.UNDISPATCHED) {
              suspendCancellableCoroutine<Unit> { continuation ->
                transitions.start(map, AnimationOptions(), continuation, anchored = true) { _, _ ->
                  // A transition native accepted and has not finished.
                  CompletableDeferred()
                }
              }
            }
          val actual =
            assertFailsWith<IllegalArgumentException> {
              withTimeout(TimeoutMillis) {
                suspendCancellableCoroutine<Unit> { continuation ->
                  transitions.start(map, AnimationOptions(), continuation, anchored = true) { _, _
                    ->
                    throw IllegalArgumentException("rejected camera options")
                  }
                }
              }
            }
          assertEquals("rejected camera options", actual.message)
          transitions.cancelAnchor(map)
          withTimeout(TimeoutMillis) {
            assertFailsWith<CancellationException> { previous.await() }
          }
        } finally {
          transitions.releaseAll()
          session.close()
          session.awaitClosed()
        }
      }
    }

  @Test
  fun retiring_camera_completion_while_the_map_is_alive_rejects_late_work() = runBlocking {
    withNativeMapState { state, runtime ->
      val session = newSession(state, runtime)
      val ownerHeld = TestGate()
      val entered = CompletableDeferred<Long>()
      val transitions = MlnFfiCameraTransitions { null }
      try {
        session.ensureEngine()
        val map = checkNotNull(session.mapForTest)
        val movement =
          async(Dispatchers.Default) {
            suspendCancellableCoroutine<Unit> { continuation ->
              transitions.start(map, AnimationOptions(), continuation, anchored = true) { _, options
                ->
                entered.complete(requireNotNull(options.transitionId).toLong())
                ownerHeld.awaitUntilOpen()
                CompletableDeferred()
              }
            }
          }
        val id = withTimeout(TimeoutMillis) { entered.await() }
        // Failed renderer release can leave the map owner running while physical cleanup retires
        // completion. Hold a start in progress to exercise that handoff without leaking a renderer.
        transitions.releaseAll()
        ownerHeld.open()
        withTimeout(TimeoutMillis) { movement.await() }
        transitions.finished(id)
        transitions.eventsDrained()
        transitions.cancelAnchor(map)
        withTimeout(TimeoutMillis) {
          suspendCancellableCoroutine<Unit> { continuation ->
            transitions.start(map, AnimationOptions(), continuation, anchored = false) { _, _ ->
              error("A retired completion owner started another movement")
            }
          }
        }
      } finally {
        ownerHeld.open()
        session.close()
        session.awaitClosed()
      }
    }
  }

  private fun newSession(
    state: MapState,
    runtime: MapRuntime,
  ) =
    MlnFfiMapSession(
      lifecycleAuthority = state.lifecycle,
      callbacks = state.durableStyleCallbacks(),
      logger = null,
      renderBackend = MapRenderBackend.OpenGl,
      layoutDirection = LayoutDirection.Ltr,
      owner = runtime.nativeOwner,
    )

  private suspend fun withNativeMapState(
    configure: (MlnFfiRuntimeOptions) -> MlnFfiRuntimeOptions = { it },
    block: suspend (MapState, MapRuntime) -> Unit,
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
      withTimeout(TimeoutMillis) { runtime.awaitClosed() }
      TestMain.loop = null
      FfiTestPlatform.deleteCacheFile(cacheFile)
    }
  }

  private companion object {
    const val TimeoutMillis = 5_000L
  }
}
