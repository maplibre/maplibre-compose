package org.maplibre.compose.style

/** Desktop, iOS, and the browser have no animator duration scale; transitions play as declared. */
internal actual fun animatorDurationScale(): Float = 1f
