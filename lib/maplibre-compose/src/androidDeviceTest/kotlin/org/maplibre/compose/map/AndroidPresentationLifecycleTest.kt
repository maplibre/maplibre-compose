package org.maplibre.compose.map

import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.interaction.ClickResult
import org.maplibre.compose.layers.BackgroundLayer
import org.maplibre.compose.mlnffi.FfiTestPlatform
import org.maplibre.compose.style.BaseStyle

/** Public ownership and failure behavior, including cleanup without an explicit presenter close. */
class AndroidPresentationLifecycleTest {
  @Test
  fun a_duplicate_presenter_does_not_detach_the_current_owner() = runBlocking {
    withLifecycleFixture { fixture ->
      fixture.awaitRendering()
      fixture.onMain {
        assertFailsWith<IllegalStateException> { newPresenter() }
        assertNotNull(state.viewport)
        assertNull(presenter.failure)
      }
      val framesBefore = fixture.consumer.frames.get()
      fixture.onMain { color = Color.Blue }
      fixture.await("the original presenter to render after a rejected duplicate") {
        consumer.frames.get() > framesBefore &&
          consumer.centerArgb.get() == android.graphics.Color.BLUE
      }
      fixture.onMain {
        assertNotNull(state.viewport)
        assertFalse(state.isClosed)
        assertNull(presenter.failure)
      }
    }
  }

  @Test
  fun closing_the_state_disposes_style_effects_and_presentation_without_explicit_close() =
    runBlocking {
      withLifecycleFixture { fixture ->
        fixture.awaitRendering()
        fixture.await("the style effect to start") { activeEffects.get() > 0 }
        fixture.onMain { state.close() }
        withTimeout(TIMEOUT_MILLIS) { fixture.state.awaitClosed() }
        fixture.await("state closure to dispose its presentation effects") {
          activeEffects.get() == 0
        }
        fixture.onMain {
          assertNull(state.viewport)
          assertTrue(consumer.surface.isValid)
          assertFailsWith<IllegalStateException> {
            presenter.attachSurface(consumer.surface, WIDTH, HEIGHT, 1f)
          }
        }
        // Check before the fixture's defensive close: state.close alone must stop this render
        // thread.
        awaitNoNewRenderThreads(fixture.initialRenderThreads)
      }
    }

  @Test
  fun lifecycle_stop_pauses_rendering_and_destroy_releases_the_state_for_another_host() =
    runBlocking {
      withLifecycleFixture { fixture ->
        fixture.awaitRendering()
        fixture.onMain {
          lifecycleOwner.lifecycle.currentState = Lifecycle.State.CREATED
          color = Color.Blue
        }
        fixture.await("style recomposition while the lifecycle is stopped") {
          composedColor.get() == android.graphics.Color.BLUE
        }
        val stoppedFrames = fixture.consumer.frames.get()
        fixture.onMain {
          assertEquals(android.graphics.Color.RED, consumer.centerArgb.get())
          lifecycleOwner.lifecycle.currentState = Lifecycle.State.STARTED
        }
        fixture.await("rendering the updated style after lifecycle start") {
          consumer.frames.get() > stoppedFrames &&
            consumer.centerArgb.get() == android.graphics.Color.BLUE
        }
        val initialCamera = fixture.onMain { state.cameraPosition }
        fixture.onMain {
          state.click(DpOffset((WIDTH / 2).dp, (HEIGHT / 2).dp))
          state.panBy(DpOffset(40.dp, 0.dp))
        }
        fixture.await("recognized input to reach the presented map") {
          clicks.get() == 1 && state.cameraPosition.target != initialCamera.target
        }
        val retainedLongitude = fixture.onMain {
          val longitude = state.cameraPosition.target.longitude
          lifecycleOwner.lifecycle.currentState = Lifecycle.State.DESTROYED
          longitude
        }
        fixture.await("lifecycle destruction to dispose presentation effects") {
          activeEffects.get() == 0
        }
        fixture.onMain {
          assertFalse(state.isClosed)
          assertNull(state.viewport)
          assertTrue(consumer.surface.isValid)
          // A closed binding ignores a late size callback instead of throwing.
          binding.update(WIDTH, HEIGHT, 1f)
          assertFailsWith<IllegalStateException> {
            presenter.attachSurface(consumer.surface, WIDTH, HEIGHT, 1f)
          }
          // Recognized gestures are ignored without a presentation.
          state.panBy(DpOffset(40.dp, 0.dp))
          assertEquals(retainedLongitude, state.cameraPosition.target.longitude)
        }
        awaitNoNewRenderThreads(fixture.initialRenderThreads)
        val previousFrames = fixture.consumer.frames.get()
        fixture.onMain {
          lifecycleOwner = PresentationLifecycleOwner()
          presenter = newPresenter()
          binding = presenter.attachSurface(consumer.surface, WIDTH, HEIGHT, 1f)
        }
        fixture.await("a new lifecycle host to present the retained map") {
          consumer.frames.get() > previousFrames &&
            consumer.centerArgb.get() == android.graphics.Color.BLUE &&
            state.viewport != null
        }
        fixture.onMain {
          assertEquals(retainedLongitude, state.cameraPosition.target.longitude, 1e-7)
          assertFalse(state.isClosed)
          assertNull(presenter.failure)
        }
      }
    }

  @Test
  fun a_failed_style_effect_is_observable_and_releases_the_state_for_a_new_presenter() =
    runBlocking {
      verifyFailureHandoff(FailurePhase.Effect)
    }

  @Test
  fun a_failed_recomposition_is_observable_and_releases_the_state_for_a_new_presenter() =
    runBlocking {
      verifyFailureHandoff(FailurePhase.Composition)
    }

  @Test
  fun an_initial_style_application_failure_disposes_effects_and_releases_the_state() = runBlocking {
    verifyFailureHandoff(FailurePhase.InitialApply, failInitially = true)
  }

  private suspend fun verifyFailureHandoff(phase: FailurePhase, failInitially: Boolean = false) =
    coroutineScope {
      val observerScope = this
      withLifecycleFixture(initialFailure = phase.takeIf { failInitially }) { fixture ->
        if (!failInitially) {
          fixture.awaitRendering()
        }
        val observedFailure = fixture.onMain {
          val failingPresenter = presenter
          observerScope.async(Dispatchers.Main.immediate, start = CoroutineStart.UNDISPATCHED) {
            snapshotFlow { failingPresenter.failure }.filterNotNull().first()
          }
        }
        if (!failInitially) {
          fixture.onMain { failurePhase = phase }
        }
        fixture.await("the style failure and effect cleanup") {
          presenter.failure != null && activeEffects.get() == 0
        }
        // The terminal write must reach an existing observer after the presentation's own snapshot
        // observer has been disposed. Do not produce an unrelated snapshot write to wake this up.
        val deliveredFailure = withTimeout(TIMEOUT_MILLIS) { observedFailure.await() }
        fixture.onMain {
          val failure = assertNotNull(presenter.failure)
          assertTrue(
            failure === deliveredFailure,
            "snapshotFlow did not deliver the terminal failure",
          )
          assertTrue(
            generateSequence(failure) { it.cause }.any { it === expectedFailure },
            "The public failure must retain the style failure cause: $failure",
          )
          assertFalse(state.isClosed)
          assertNull(state.viewport)
          assertTrue(consumer.surface.isValid)
        }
        awaitNoNewRenderThreads(fixture.initialRenderThreads)
        val framesBefore = fixture.consumer.frames.get()
        fixture.onMain {
          failurePhase = null
          presenter = newPresenter()
          presenter.attachSurface(consumer.surface, WIDTH, HEIGHT, 1f)
        }
        fixture.await("a new presenter to render the retained state after failure") {
          consumer.frames.get() > framesBefore && state.viewport != null
        }
        fixture.onMain {
          assertNull(presenter.failure)
          assertTrue(activeEffects.get() > 0)
        }
      }
    }
}

private enum class FailurePhase {
  Effect,
  Composition,
  InitialApply,
}

private class LifecycleFixture(val runtime: MapRuntime, val initialRenderThreads: Set<Thread>) {
  val consumer = LifecycleSurfaceConsumer()
  var lifecycleOwner = PresentationLifecycleOwner()
  val activeEffects = AtomicInteger()
  val composedColor = AtomicInteger()
  val clicks = AtomicInteger()
  val expectedFailure = IllegalStateException("deliberate presentation style failure")
  var color by mutableStateOf(Color.Red)
  var failurePhase by mutableStateOf<FailurePhase?>(null)
  val state =
    runtime.createMapState(BaseStyle.Empty) {
      DisposableEffect(Unit) {
        activeEffects.incrementAndGet()
        onDispose { activeEffects.decrementAndGet() }
      }
      if (failurePhase == FailurePhase.Composition) throw expectedFailure
      if (failurePhase == FailurePhase.InitialApply) {
        // setContent applies this after installing DisposableEffect, before returning to its
        // caller.
        SideEffect { throw expectedFailure }
      }
      LaunchedEffect(failurePhase) {
        if (failurePhase == FailurePhase.Effect) throw expectedFailure
      }
      val currentColor = color
      BackgroundLayer("background", color = const(currentColor))
      SideEffect { composedColor.set(currentColor.toArgb()) }
    }
  private val presenters = mutableListOf<AndroidMapPresentation>()
  lateinit var presenter: AndroidMapPresentation
  lateinit var binding: AndroidMapPresentation.SurfaceBinding

  fun start(initialFailure: FailurePhase?) {
    failurePhase = initialFailure
    presenter = newPresenter()
    binding = presenter.attachSurface(consumer.surface, WIDTH, HEIGHT, 1f)
  }

  fun newPresenter(lifecycle: Lifecycle = lifecycleOwner.lifecycle): AndroidMapPresentation =
    AndroidMapPresentation(
        context = InstrumentationRegistry.getInstrumentation().targetContext,
        state = state,
        lifecycle = lifecycle,
        interactions =
          org.maplibre.compose.interaction.MapInteractions {
            callbacks {
              click {
                onEvent {
                  clicks.incrementAndGet()
                  ClickResult.Consume
                }
              }
            }
          },
      )
      .also { presenters += it }

  fun closePresenters() {
    presenters.forEach { it.close() }
  }

  suspend fun <T> onMain(action: LifecycleFixture.() -> T): T =
    withContext(Dispatchers.Main) { action() }

  suspend fun await(description: String, predicate: LifecycleFixture.() -> Boolean) {
    try {
      withTimeout(TIMEOUT_MILLIS) {
        while (!onMain(predicate)) delay(10)
      }
    } catch (error: Throwable) {
      throw AssertionError("Timed out waiting for $description", error)
    }
  }

  suspend fun awaitRendering() {
    await("initial Surface rendering") {
      presenter.failure?.let { throw AssertionError("Initial presentation failed", it) }
      consumer.frames.get() > 0 && state.viewport != null
    }
  }
}

private class PresentationLifecycleOwner : LifecycleOwner {
  override val lifecycle = LifecycleRegistry(this).apply { currentState = Lifecycle.State.STARTED }
}

private suspend fun withLifecycleFixture(
  initialFailure: FailurePhase? = null,
  action: suspend (LifecycleFixture) -> Unit,
) {
  val initialThreads = renderThreads()
  val cacheFile = FfiTestPlatform.createCacheFile()
  val runtime = createMapRuntime(MapRuntimeOptions(cacheFile = cacheFile))
  try {
    val fixture = withContext(Dispatchers.Main) { LifecycleFixture(runtime, initialThreads) }
    try {
      fixture.onMain { start(initialFailure) }
      action(fixture)
    } finally {
      try {
        fixture.onMain { closePresenters() }
      } finally {
        fixture.consumer.close()
      }
      assertEquals(
        0,
        fixture.activeEffects.get(),
        "Style effects remained after presentation close",
      )
    }
  } finally {
    runtime.close()
    try {
      withTimeout(TIMEOUT_MILLIS) { runtime.awaitClosed() }
    } finally {
      FfiTestPlatform.deleteCacheFile(cacheFile)
    }
    awaitNoNewRenderThreads(initialThreads)
  }
}

/** Drain every buffer so renderer teardown cannot block behind an unread ImageReader queue. */
private class LifecycleSurfaceConsumer : AutoCloseable {
  private val thread = HandlerThread("map-lifecycle-test-consumer").apply { start() }
  private val reader = ImageReader.newInstance(WIDTH, HEIGHT, PixelFormat.RGBA_8888, 3)
  val surface = reader.surface
  val frames = AtomicLong()
  val centerArgb = AtomicInteger()

  init {
    reader.setOnImageAvailableListener(
      { source ->
        source.acquireLatestImage()?.use { image ->
          val plane = image.planes.single()
          val offset = (image.height / 2) * plane.rowStride + (image.width / 2) * plane.pixelStride
          val bytes = plane.buffer
          centerArgb.set(
            android.graphics.Color.argb(
              bytes.get(offset + 3).toInt() and 0xff,
              bytes.get(offset).toInt() and 0xff,
              bytes.get(offset + 1).toInt() and 0xff,
              bytes.get(offset + 2).toInt() and 0xff,
            )
          )
          frames.incrementAndGet()
        }
      },
      Handler(thread.looper),
    )
  }

  override fun close() {
    reader.setOnImageAvailableListener(null, null)
    reader.close()
    surface.release()
    thread.quitSafely()
    thread.join(TIMEOUT_MILLIS)
    assertFalse(thread.isAlive, "Image consumer thread did not stop")
  }
}

private fun renderThreads(): Set<Thread> =
  Thread.getAllStackTraces()
    .keys
    .filter { it.isAlive && it.name == "maplibre-compose-render" }
    .toSet()

private suspend fun awaitNoNewRenderThreads(initial: Set<Thread>) {
  try {
    withTimeout(TIMEOUT_MILLIS) {
      while ((renderThreads() - initial).isNotEmpty()) delay(10)
    }
  } catch (error: Throwable) {
    throw AssertionError(
      "Presentation render threads remained alive: ${renderThreads() - initial}",
      error,
    )
  }
}

private const val WIDTH = 128
private const val HEIGHT = 96
private const val TIMEOUT_MILLIS = 10_000L
