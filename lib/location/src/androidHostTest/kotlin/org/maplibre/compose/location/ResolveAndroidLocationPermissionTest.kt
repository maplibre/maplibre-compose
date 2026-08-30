package org.maplibre.compose.location

import kotlin.test.Test
import kotlin.test.assertEquals

class ResolveAndroidLocationPermissionTest {

  @Test
  fun a_granted_permission_reports_its_accuracy() {
    assertEquals(
      LocationPermission.Granted(LocationAccuracyAuthorization.Precise),
      resolveAndroidLocationPermission(
        granted = LocationAccuracyAuthorization.Precise,
        shouldShowRationale = false,
        permanentlyDenied = false,
      ),
    )
  }

  @Test
  fun a_never_requested_permission_can_be_requested() {
    assertEquals(
      LocationPermission.Required(canRequest = true),
      resolveAndroidLocationPermission(
        granted = null,
        shouldShowRationale = false,
        permanentlyDenied = false,
      ),
    )
  }

  @Test
  fun a_rationale_reports_a_requestable_permission() {
    assertEquals(
      LocationPermission.Required(canRequest = true, shouldShowRationale = true),
      resolveAndroidLocationPermission(
        granted = null,
        shouldShowRationale = true,
        permanentlyDenied = false,
      ),
    )
  }

  @Test
  fun no_activity_reports_an_unknown_request_path() {
    assertEquals(
      LocationPermission.Required(canRequest = null),
      resolveAndroidLocationPermission(
        granted = null,
        shouldShowRationale = null,
        permanentlyDenied = true,
      ),
    )
  }

  @Test
  fun a_permanent_denial_blocks_further_requests() {
    assertEquals(
      LocationPermission.Required(canRequest = false),
      resolveAndroidLocationPermission(
        granted = null,
        shouldShowRationale = false,
        permanentlyDenied = true,
      ),
    )
  }
}
