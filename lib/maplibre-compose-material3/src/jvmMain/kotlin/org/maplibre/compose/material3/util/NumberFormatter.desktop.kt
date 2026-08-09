package org.maplibre.compose.material3.util

import androidx.compose.ui.text.intl.Locale
import java.text.NumberFormat

internal actual class NumberFormatter
actual constructor(locale: Locale, maximumFractionDigits: Int) {

  private val format =
    NumberFormat.getInstance(locale.platformLocale).also {
      it.maximumFractionDigits = maximumFractionDigits
    }

  actual fun format(value: Number): String = format.format(value)
}
