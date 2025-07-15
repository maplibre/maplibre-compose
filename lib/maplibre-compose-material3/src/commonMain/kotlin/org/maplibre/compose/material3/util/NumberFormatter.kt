package org.maplibre.compose.material3.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.intl.Locale

internal expect class NumberFormatter(locale: Locale, maximumFractionDigits: Int) {
  /** Format the given [value] locale-aware. */
  fun format(value: Number): String
}

/** Create a new [NumberFormatter] instance with the given [locale] */
@Composable
internal fun rememberNumberFormatter(locale: Locale, maximumFractionDigits: Int = Int.MAX_VALUE) =
  remember(locale, maximumFractionDigits) { NumberFormatter(locale, maximumFractionDigits) }
