package org.maplibre.compose.material3.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpOffset

@Composable
@ReadOnlyComposable
internal fun Offset.toDpOffset(): DpOffset =
  with(LocalDensity.current) { DpOffset(x.toDp(), y.toDp()) }

@Composable
@ReadOnlyComposable
internal fun DpOffset.toOffset(): Offset = with(LocalDensity.current) { Offset(x.toPx(), y.toPx()) }
