package org.maplibre.compose.map

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.maplibre.compose.camera.Viewport
import org.maplibre.compose.util.VisibleRegion
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Position

internal fun mapRuntimeForTest(
  physicalScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
  snapshotterAdapterFactory: SnapshotterAdapterFactory = UnsupportedSnapshotterAdapterFactory,
  styleEvaluator: StyleCompositionEvaluator = DefaultStyleCompositionEvaluator,
  closeResources: suspend () -> Unit = {},
): MapRuntime =
  RuntimeImplementation(
    platformOptions = null,
    resources = MapRuntimeResources(closeResources),
    logger = null,
    physicalScope = physicalScope,
    snapshotterAdapterFactory = snapshotterAdapterFactory,
    styleEvaluator = styleEvaluator,
  )

/** A viewport sized for [request], as a snapshotter adapter reports after applying the request. */
internal fun viewportFor(request: MapSnapshotRequest): Viewport =
  Viewport(
    size = DpSize(request.width.dp, request.height.dp),
    visibleBoundingBox = BoundingBox(Position(-1.0, -1.0), Position(1.0, 1.0)),
    visibleRegion =
      VisibleRegion(
        farLeft = Position(-1.0, 1.0),
        farRight = Position(1.0, 1.0),
        nearLeft = Position(-1.0, -1.0),
        nearRight = Position(1.0, -1.0),
      ),
    metersPerDpAtTarget = 1.0,
  )
