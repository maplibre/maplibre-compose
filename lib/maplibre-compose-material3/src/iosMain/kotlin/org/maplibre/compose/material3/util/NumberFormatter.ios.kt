package org.maplibre.compose.material3.util

import androidx.compose.ui.text.intl.Locale
import platform.Foundation.NSNumber
import platform.Foundation.NSNumberFormatter
import platform.Foundation.NSNumberFormatterDecimalStyle

internal actual class NumberFormatter
actual constructor(locale: Locale, maximumFractionDigits: Int) {

  private val format =
    NSNumberFormatter().also {
      it.numberStyle = NSNumberFormatterDecimalStyle
      it.maximumFractionDigits = maximumFractionDigits.toULong()
      it.locale = locale.platformLocale
    }

  actual fun format(value: Number): String =
    format.stringFromNumber(value as NSNumber) ?: value.toString()
}
