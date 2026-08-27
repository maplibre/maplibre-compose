package org.maplibre.compose.mlnffi

import androidx.compose.runtime.Composable

/** Applies the platform default configuration when [MlnFfiApplication] has none. */
internal expect fun ensureMlnFfiConfigured()

/** Records the platform context that [ensureMlnFfiConfigured] needs; only Android has one. */
@Composable internal expect fun CaptureMlnFfiPlatformContext()

/** The composable form, for a map that configures MapLibre as it enters the composition. */
@Composable
internal fun EnsureMlnFfiConfigured() {
  CaptureMlnFfiPlatformContext()
  ensureMlnFfiConfigured()
}
