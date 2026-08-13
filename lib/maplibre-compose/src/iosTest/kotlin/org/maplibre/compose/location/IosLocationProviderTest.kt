package org.maplibre.compose.location

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import platform.CoreLocation.kCLErrorDenied
import platform.CoreLocation.kCLErrorDomain
import platform.CoreLocation.kCLErrorLocationUnknown
import platform.CoreLocation.kCLErrorNetwork
import platform.Foundation.NSError

class IosLocationProviderTest {
  @Test
  fun filtersStaleLocationsUntilFirstAcceptableFix() {
    val filter = InitialFixAgeFilter(maximumInitialFixAge = 1.minutes)

    assertFalse(filter.accept(age = 2.minutes))
    assertFalse(filter.accept(age = 61.seconds))
    assertTrue(filter.accept(age = 30.seconds))
    assertTrue(filter.accept(age = 2.minutes))
  }

  @Test
  fun mapsCoreLocationErrorsByRecoverability() {
    assertEquals(
      LocationUnavailableReason.ServicesDisabled,
      coreLocationError(kCLErrorDenied).asUnavailableReason(locationServicesEnabled = false),
    )
    assertEquals(
      LocationUnavailableReason.PermissionDenied,
      coreLocationError(kCLErrorDenied).asUnavailableReason(locationServicesEnabled = true),
    )
    assertEquals(
      LocationUnavailableReason.TemporarilyUnavailable,
      coreLocationError(kCLErrorLocationUnknown).asUnavailableReason(),
    )
    assertEquals(
      LocationUnavailableReason.TemporarilyUnavailable,
      coreLocationError(kCLErrorNetwork).asUnavailableReason(),
    )
    assertEquals(
      LocationUnavailableReason.UnexpectedFailure,
      NSError.errorWithDomain("example.error", 1, null).asUnavailableReason(),
    )
  }

  private fun coreLocationError(code: Long): NSError =
    NSError.errorWithDomain(kCLErrorDomain, code, null)
}
