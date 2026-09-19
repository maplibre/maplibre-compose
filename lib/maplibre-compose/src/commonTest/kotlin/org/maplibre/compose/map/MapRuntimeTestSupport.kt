package org.maplibre.compose.map

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.maplibre.compose.camera.Viewport
import org.maplibre.compose.util.VisibleBounds
import org.maplibre.compose.util.VisibleRegion
import org.maplibre.spatialk.geojson.Position

/**
 * A main dispatcher pinned to the `runTest` thread. Work posted from another thread drains on the
 * test scheduler, so a test that awaits it need not drain by hand.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun TestScope.testMainDispatcher(): TestMainDispatcher =
  TestMainDispatcher(loop = StandardTestDispatcher(testScheduler))

/**
 * A runtime whose main thread is the calling thread. Work posted from another thread waits for a
 * drain; [testMainDispatcher] drains it on the test scheduler.
 */
internal fun mapRuntimeForTest(
  physicalScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
  mainDispatcher: CoroutineDispatcher = TestMainDispatcher(),
  // Inline: unit tests observe reads synchronously.
  readDispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
  createSnapshotterAdapter: () -> SnapshotterAdapter = ::unsupportedSnapshots,
  styleEvaluator: StyleCompositionEvaluator = DefaultStyleCompositionEvaluator,
  closeResources: suspend () -> Unit = {},
): MapRuntime =
  RuntimeImplementation(
    platformContext = null,
    closeResources = closeResources,
    logger = null,
    physicalScope = physicalScope,
    mainDispatcher = mainDispatcher,
    readDispatcher = readDispatcher,
    createSnapshotterAdapter = createSnapshotterAdapter,
    styleEvaluator = styleEvaluator,
  )

/** A viewport sized for [request], as a snapshotter adapter reports after applying the request. */
internal fun viewportFor(request: MapSnapshotRequest): Viewport =
  Viewport(
    size = DpSize(request.width.dp, request.height.dp),
    visibleBounds = VisibleBounds(Position(-1.0, -1.0), Position(1.0, 1.0)),
    visibleRegion =
      VisibleRegion(
        farLeft = Position(-1.0, 1.0),
        farRight = Position(1.0, 1.0),
        nearLeft = Position(-1.0, -1.0),
        nearRight = Position(1.0, -1.0),
      ),
    metersPerDpAtTarget = 1.0,
  )
