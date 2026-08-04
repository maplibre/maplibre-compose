package org.maplibre.compose.material3.util

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
      // Rebuilt from the language tag rather than read off the Compose locale, whose
      // `platformLocale` became internal in Compose 1.11. The tag is the public accessor and
      // NSLocale accepts a BCP-47 identifier, so this is the same locale by a supported route
      // rather than a fallback.
      it.locale = NSLocale(localeIdentifier = locale.toLanguageTag())
    }

  actual fun format(value: Number): String =
    format.stringFromNumber(value as NSNumber) ?: value.toString()
}
