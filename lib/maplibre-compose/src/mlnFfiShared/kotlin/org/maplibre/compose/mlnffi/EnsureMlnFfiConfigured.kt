package org.maplibre.compose.mlnffi

import androidx.compose.runtime.Composable

/** Applies the platform default configuration when [MlnFfiApplication] has none. */
@Composable internal expect fun EnsureMlnFfiConfigured()
