package dev.sargunv.maplibrecompose.material3.util

import androidx.compose.ui.text.intl.Locale

internal interface NumberFormatter {
  /** Format the given [value] locale-aware. */
  fun format(value: Number): String
}

/** Create a new [NumberFormatter] instance with the given [locale] */
internal expect fun NumberFormatter(
  locale: Locale,
  maximumFractionDigits: Int = Int.MAX_VALUE,
): NumberFormatter
