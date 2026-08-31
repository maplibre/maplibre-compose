package org.maplibre.compose.map

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

internal fun mapRuntimeForTest(
  physicalScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
  closeResources: suspend () -> Unit = {},
): MapRuntime =
  RuntimeImplementation(
    platformOptions = null,
    resources = MapRuntimeResources(closeResources),
    logger = null,
    physicalScope = physicalScope,
  )
