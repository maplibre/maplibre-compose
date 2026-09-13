package org.maplibre.compose.util

import androidx.compose.ui.text.intl.Locale
import platform.Foundation.NSLocale
import platform.Foundation.NSNumber
import platform.Foundation.NSNumberFormatter
import platform.Foundation.NSNumberFormatterDecimalStyle

internal actual class NumberFormatter
actual constructor(locale: Locale, maximumFractionDigits: Int) {

  private val format =
    NSNumberFormatter().also {
      it.numberStyle = NSNumberFormatterDecimalStyle
      it.maximumFractionDigits = maximumFractionDigits.toULong()
      // Rebuilt from the BCP-47 tag because Compose 1.11 made `platformLocale` internal.
      it.locale = NSLocale(localeIdentifier = locale.toLanguageTag())
    }

  // A boxed Kotlin number is an NSNumber subclass at runtime, but that is not a cast the compiler
  // can prove, so build the NSNumber instead. Double carries every value a scale bar can produce.
  actual fun format(value: Number): String =
    format.stringFromNumber(NSNumber(value.toDouble())) ?: value.toString()
}
