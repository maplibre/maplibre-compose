package org.maplibre.compose.demoapp.benchmark

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import co.touchlab.kermit.Logger
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.maplibre.compose.camera.rememberCameraState
import org.maplibre.compose.demoapp.DemoAppState
import org.maplibre.compose.map.GestureOptions
import org.maplibre.compose.map.MapOptions
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.RenderOptions
import org.maplibre.compose.overlay.MapOverlay
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.rememberStyleState

private val benchLog = Logger.withTag(BenchmarkReport.LogPrefix)

/**
 * A map instance that exists only while the Benchmarks shell is open. Demo layers, camera, and
 * settings do not compose here.
 */
@Composable
internal fun BenchmarkMap(state: DemoAppState, sheetInsets: WindowInsets = WindowInsets(0)) {
  val scenario = state.selectedScenario
  val density = LocalDensity.current
  val prefetcher = rememberTilePrefetcher()
  val cameraState = rememberCameraState(firstPosition = scenario.camera)
  val styleState = rememberStyleState()
  val session =
    remember(cameraState, prefetcher, density) {
      BenchmarkSession(
        cameraState = cameraState,
        ui = state.benchmark,
        prefetcher = prefetcher,
        density = density,
      )
    }
  val mapLoaded = remember(scenario.id) { CompletableDeferred<Unit>() }
  val insets = WindowInsets.safeDrawing.union(sheetInsets)
  val styleUrl = (scenario.style.base as BaseStyle.Uri).uri

  LaunchedEffect(scenario.id) {
    state.benchmark.abandonRun()
    cameraState.position = scenario.camera
    session.geoJson = null
    session.pin = null
    session.pointerPx = null
  }

  LaunchedEffect(state.benchmark.runId) {
    if (state.benchmark.runId == 0) return@LaunchedEffect
    val ui = state.benchmark
    val running = state.selectedScenario
    ui.running = true
    session.geoJson = null
    session.pin = null
    session.pointerPx = null
    session.gestures.reset()
    try {
      ui.status = "Waiting for the map"
      mapLoaded.await()
      cameraState.position = running.camera
      ui.status = "Prefetching tiles"
      prefetcher.ensurePacked(
        scenarioId = running.id,
        styleUrl = styleUrl,
        bounds = running.region,
        minZoom = running.minZoom,
        maxZoom = running.maxZoom,
        camera = cameraState,
        onStatus = { ui.status = it },
      )
      ui.status = "Running ${running.title}"
      session.frames.start()
      val started = TimeSource.Monotonic.markNow()
      coroutineScope {
        val composeJob = launch {
          var lastNanos = 0L
          while (true) {
            withFrameNanos { now ->
              if (lastNanos != 0L) {
                session.frames.recordComposeFrameMs((now - lastNanos) / 1_000_000.0)
              }
              lastNanos = now
              samplePin(session)
            }
          }
        }
        try {
          running.run(session)
        } finally {
          composeJob.cancel()
        }
      }
      val durationMs = started.elapsedNow().inWholeMilliseconds.toDouble()
      val frames = session.frames.stop()
      val gesture = if (session.gestures.stats().samples > 0) session.gestures.stats() else null
      val report =
        BenchmarkReport(
          scenario = running.id,
          platform = benchmarkPlatformLabel,
          prefetch = prefetcher.mode,
          durationMs = durationMs,
          frames = frames,
          gesture = gesture,
        )
      logReport(report)
      ui.report = report
      ui.status = "Done"
    } catch (e: CancellationException) {
      session.frames.stop()
      throw e
    } catch (e: Throwable) {
      session.frames.stop()
      ui.status = e.message ?: "Failed"
      ui.report = null
    } finally {
      ui.running = false
    }
  }

  Box(
    Modifier.fillMaxSize()
      .pointerInput(session, scenario.usesGestures) {
        awaitPointerEventScope {
          while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (
              event.type != PointerEventType.Move &&
                event.type != PointerEventType.Press &&
                event.type != PointerEventType.Release
            ) {
              continue
            }
            val change = event.changes.firstOrNull() ?: continue
            val x = change.position.x.toDouble()
            val y = change.position.y.toDouble()
            if (scenario.usesGestures || session.gestures.capturing) {
              session.pointerPx = Offset(change.position.x, change.position.y)
            }
            if (change.pressed && session.pin == null && scenario.usesGestures) {
              val viewport = cameraState.viewport
              if (viewport != null) {
                session.pin =
                  viewport.positionFromScreenLocation(
                    with(density) { DpOffset(change.position.x.toDp(), change.position.y.toDp()) }
                  )
              }
            }
            session.gestures.onPointer(x, y, change.pressed)
            if (!change.pressed && scenario.usesGestures) {
              session.pointerPx = null
            }
          }
        }
      }
      .drawWithContent {
        drawContent()
        drawTrail(session)
      }
  ) {
    key(scenario.id) {
      MaplibreMap(
        baseStyle = scenario.style.base,
        cameraState = cameraState,
        styleState = styleState,
        options =
          MapOptions(
            renderOptions = RenderOptions.Standard,
            gestureOptions =
              if (scenario.usesGestures) GestureOptions.Standard else GestureOptions.AllDisabled,
          ),
        onFrame = { fps -> session.frames.recordMapFps(fps) },
        onMapLoadFinished = { mapLoaded.complete(Unit) },
        onMapLoadFailed = { reason ->
          mapLoaded.completeExceptionally(IllegalStateException(reason ?: "Map failed to load"))
        },
        contentWindowInsets = insets,
        overlay = MapOverlay.None,
      ) {
        scenario.MapContent(session)
      }
    }

    Column(
      modifier =
        Modifier.align(Alignment.TopCenter).padding(insets.asPaddingValues()).padding(8.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Text(
        text = state.benchmark.status,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier =
          Modifier.background(
              color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
              shape = RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
      )
    }
  }
}

private fun samplePin(session: BenchmarkSession) {
  val pin = session.pin ?: return
  val projected = session.cameraState.viewport?.screenLocationFromPosition(pin) ?: return
  val px = with(session.density) { Offset(projected.x.toPx(), projected.y.toPx()) }
  session.gestures.onMapProjection(px.x.toDouble(), px.y.toDouble())
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTrail(session: BenchmarkSession) {
  val composePoint = session.pointerPx
  val pin = session.pin
  val projected = pin?.let { session.cameraState.viewport?.screenLocationFromPosition(it) }
  val mapPoint = projected?.let { with(session.density) { Offset(it.x.toPx(), it.y.toPx()) } }
  if (composePoint != null) {
    val arm = 12.dp.toPx()
    val color = Color(0xFF1565C0)
    drawLine(
      color,
      Offset(composePoint.x - arm, composePoint.y),
      Offset(composePoint.x + arm, composePoint.y),
      2.dp.toPx(),
    )
    drawLine(
      color,
      Offset(composePoint.x, composePoint.y - arm),
      Offset(composePoint.x, composePoint.y + arm),
      2.dp.toPx(),
    )
  }
  if (mapPoint != null) {
    drawCircle(
      color = Color(0xFFE53935),
      radius = 6.dp.toPx(),
      center = mapPoint,
      style = Stroke(2.dp.toPx()),
    )
  }
  if (composePoint != null && mapPoint != null) {
    drawLine(Color(0x99E53935), composePoint, mapPoint, 2.dp.toPx())
  }
}

private fun logReport(report: BenchmarkReport) {
  val line = "${BenchmarkReport.LogPrefix} ${report.toJsonLine()}"
  benchLog.i { line }
  println(line)
}
