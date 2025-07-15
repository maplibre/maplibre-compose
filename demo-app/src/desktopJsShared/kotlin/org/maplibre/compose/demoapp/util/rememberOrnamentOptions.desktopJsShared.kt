package org.maplibre.compose.demoapp.util

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import org.maplibre.compose.core.OrnamentOptions

@Composable
actual fun rememberOrnamentOptions(padding: PaddingValues) = remember { OrnamentOptions.AllEnabled }
