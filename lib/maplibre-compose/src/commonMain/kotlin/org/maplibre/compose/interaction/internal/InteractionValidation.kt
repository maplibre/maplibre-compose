package org.maplibre.compose.interaction.internal

import kotlin.time.Duration

internal fun requireNonnegativeFinite(value: Double, name: String) {
  require(value.isFinite() && value >= 0.0) { "$name must be finite and nonnegative" }
}

internal fun requireNonnegativeFinite(value: Duration, name: String) {
  require(value.isFinite() && !value.isNegative()) { "$name must be finite and nonnegative" }
}
