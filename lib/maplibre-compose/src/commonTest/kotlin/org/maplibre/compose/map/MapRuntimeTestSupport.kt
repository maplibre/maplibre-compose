package org.maplibre.compose.map

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

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
