package org.maplibre.compose.mlnffi

import androidx.compose.runtime.Composable

/**
 * Installs the platform default [MlnFfiApplication] configuration if the caller has not already
 * called `MapLibre.configure`. Must run during composition, before anything reads
 * [MlnFfiApplication.options].
 */
@Composable internal expect fun EnsureMlnFfiConfigured()
