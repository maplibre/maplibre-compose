package org.maplibre.compose.demoapp.benchmark

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import org.maplibre.compose.demoapp.DemoAppState
import org.maplibre.compose.demoapp.MapViewportInsets
import org.maplibre.compose.map.MapEvent
import org.maplibre.compose.map.MapGestures
import org.maplibre.compose.map.MapState
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.RenderOptions
import org.maplibre.compose.map.StyleLoadState
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.style.BaseStyle

private val benchLog = Logger.withTag(BenchmarkReport.LogPrefix)

/**
 * A map instance that exists only while the Benchmarks shell is open. Demo layers, camera, and
 * settings do not compose here.
 */
@Composable
internal fun BenchmarkMap(state: DemoAppState, viewportInsets: MapViewportInsets) {
  val scenario = state.selectedScenario
  val density = LocalDensity.current
  val prefetcher = rememberTilePrefetcher()
  val session =
    remember(prefetcher, density) {
      BenchmarkSession(
        ui = state.benchmark,
        prefetcher = prefetcher,
        density = density,
      )
    }
  val mapState =
    rememberMapState(
      runtime = state.mapRuntime,
      baseStyle = scenario.style.base,
      initialCameraPosition = scenario.camera,
    ) {
      scenario.MapContent(session)
    }
  val mapLoaded = remember(scenario.id) { CompletableDeferred<Unit>() }
  LaunchedEffect(mapState.style.loadState, mapLoaded) {
    when (val load = mapState.style.loadState) {
      StyleLoadState.Ready -> mapLoaded.complete(Unit)
      is StyleLoadState.Failed ->
        mapLoaded.completeExceptionally(IllegalStateException(load.reason ?: "Map failed to load"))
      StyleLoadState.Loading,
      StyleLoadState.Pending -> Unit
    }
  }
  val styleUrl = (scenario.style.base as BaseStyle.Uri).uri
  LaunchedEffect(scenario.id, mapState) {
    state.benchmark.abandonRun()
    mapState.setCameraPosition(scenario.camera)
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
      mapState.setCameraPosition(running.camera)
      ui.status = "Prefetching tiles"
      prefetcher.ensurePacked(
        scenarioId = running.id,
        styleUrl = styleUrl,
        bounds = running.region,
        minZoom = running.minZoom,
        maxZoom = running.maxZoom,
        camera = mapState,
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
              samplePin(mapState, session)
            }
          }
        }
        // Unconfined, so the mark carries no dispatch delay and a loaded run drops no event. The
        // collector only writes to the sample arrays, which is all that MapState.events allows on
        // an undispatched context.
        val frameJob =
          launch(Dispatchers.Unconfined) {
            mapState.events.filterIsInstance<MapEvent.FrameRendered>().collect {
              session.frames.recordMapFrame(TimeSource.Monotonic.markNow())
            }
          }
        try {
          running.run(mapState, session)
        } finally {
          composeJob.cancel()
          frameJob.cancel()
        }
      }
      val durationMs = started.elapsedNow().inWholeMilliseconds.toDouble()
      val frames = session.frames.stop()
      val gesture = session.gestures.stats().takeIf { it.samples > 0 }
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
              session.pin =
                mapState.positionFromScreenLocation(
                  with(density) { DpOffset(change.position.x.toDp(), change.position.y.toDp()) }
                )
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
        drawTrail(mapState, session)
      }
  ) {
    key(scenario.id) {
      MaplibreMap(
        state = mapState,
        cameraPadding = viewportInsets.asPaddingValues(),
        renderOptions = RenderOptions.Standard,
        gestures = scenario.gestures,
        contentWindowInsets = viewportInsets.asWindowInsets(),
      ) {}
    }

    Box(Modifier.fillMaxSize().padding(viewportInsets.asPaddingValues())) {
      Column(
        modifier = Modifier.align(Alignment.TopCenter).padding(8.dp),
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
}

private fun samplePin(mapState: MapState, session: BenchmarkSession) {
  val pin = session.pin ?: return
  val projected = mapState.screenLocationFromPosition(pin) ?: return
  val px = with(session.density) { Offset(projected.x.toPx(), projected.y.toPx()) }
  session.gestures.onMapProjection(px.x.toDouble(), px.y.toDouble())
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTrail(
  mapState: MapState,
  session: BenchmarkSession,
) {
  val composePoint = session.pointerPx
  val pin = session.pin
  val projected = pin?.let { mapState.screenLocationFromPosition(it) }
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

/** A scenario that drives the camera itself takes no gesture. */
internal val BenchmarkScenario.gestures: MapGestures
  get() = if (usesGestures) MapGestures.Standard else MapGestures.None
