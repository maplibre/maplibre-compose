package org.maplibre.compose.mlnffi

import androidx.compose.runtime.Composable

/** Applies the platform default configuration when [MlnFfiApplication] has none. */
@Composable internal expect fun EnsureMlnFfiConfigured()

/** The non-composable form, for acquisition outside a composition. */
internal expect fun ensureMlnFfiDefaultConfigured()
