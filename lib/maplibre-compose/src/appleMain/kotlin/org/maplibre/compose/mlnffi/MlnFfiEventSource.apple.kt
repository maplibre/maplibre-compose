@file:Suppress("INVISIBLE_REFERENCE")

package org.maplibre.compose.mlnffi

import org.maplibre.nativeffi.generated.MapHandle

// STOPGAP: see the expect declaration. Kotlin/Native has no reflection, so this reads the
// binding's internal handle directly; the build lets this file suppress that one error.
internal actual fun MapHandle.eventSource(): ULong = binding.issued().toULong()
