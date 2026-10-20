package org.maplibre.compose.location

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

class LocationProviderTest {
  @Test
  fun unsupportedBackendEmitsUnavailableWithoutStartingRequestsEvenAfterClose() = runTest {
    val backend = TestBackend(LocationBackendAvailability.Unsupported)
    val provider = LocationProvider(backend)
    val expected = listOf(LocationEvent.Unavailable(LocationUnavailableReason.Unsupported))

    assertEquals(expected, provider.updates().toList())
    provider.close()
    assertEquals(expected, provider.updates().toList())
    assertEquals(0, backend.requests.size)
    assertEquals(1, backend.closeCalls)
    assertEquals(expected, UnsupportedLocationProvider.updates().toList())
  }

  @Test
  fun misconfiguredBackendThrowsWithCauseWithoutStartingRequests() = runTest {
    val cause = IllegalStateException("missing configuration")
    val backend = TestBackend(LocationBackendAvailability.Misconfigured(cause))
    val provider = LocationProvider(backend)
    val updates = provider.updates()

    val failure = assertFailsWith<IllegalStateException> { updates.toList() }
    assertSame(cause, failure.cause)
    assertEquals(0, backend.requests.size)
  }

  @Test
  fun availableBackendStartsColdRequestsAndReceivesCancellation() = runTest {
    val backend = TestBackend()
    val provider = LocationProvider(backend)
    val request = LocationRequest { accuracy = LocationAccuracy.Low }
    val updates = provider.updates(request)
    assertEquals(0, backend.requests.size)

    repeat(2) { assertEquals(backend.event, updates.first()) }
    assertEquals(listOf(request, request), backend.requests)
    assertEquals(2, backend.stoppedRequests)
  }

  @Test
  fun providerDelegatesIdentityPermissionAndDisposal() {
    val backend = TestBackend()
    val provider = LocationProvider(backend)
    assertEquals("test", provider.backendId)
    assertSame(backend.availability, provider.backendAvailability)
    assertSame(backend.permission, provider.permission)
    provider.requestPermission()
    assertEquals(1, backend.permissionRequests)
    provider.close()
    assertEquals(1, backend.closeCalls)
  }
}

private class TestBackend(
  override val availability: LocationBackendAvailability = LocationBackendAvailability.Available,
) : LocationBackend {
  override val id = "test"
  override val permission = MutableStateFlow<LocationPermission>(LocationPermission.NotDetermined)
  val event = LocationEvent.Unavailable(LocationUnavailableReason.TemporarilyUnavailable)
  val requests = mutableListOf<LocationRequest>()
  var stoppedRequests = 0
  var permissionRequests = 0
  var closeCalls = 0

  override fun updates(request: LocationRequest) = flow {
    requests += request
    try {
      emit(event)
      awaitCancellation()
    } finally {
      stoppedRequests++
    }
  }

  override fun requestPermission() {
    permissionRequests++
  }

  override fun close() {
    closeCalls++
  }
}
