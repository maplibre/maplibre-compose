package org.maplibre.compose.style

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf

/** Desktop, iOS, and the browser have no animator duration scale; transitions play as declared. */
internal actual val systemAnimatorDurationScaleState: State<Float> = mutableFloatStateOf(1f)
