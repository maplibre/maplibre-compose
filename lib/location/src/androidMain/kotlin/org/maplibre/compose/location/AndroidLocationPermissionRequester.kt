package org.maplibre.compose.location

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.MainThread
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Observes and requests foreground location permission on Android.
 *
 * Android [LocationProvider] implementations hold a requester and expose it through
 * [LocationProvider.permission] and [LocationProvider.requestPermission].
 *
 * Call [close] when the requester is no longer needed. The resolved activity also closes it when
 * destroyed. Construct and use the requester on the main thread.
 */
@MainThread
public class AndroidLocationPermissionRequester
internal constructor(
  private val lifecycle: Lifecycle?,
  private val registry: ActivityResultRegistry?,
  private val readGrantedAccuracy: () -> LocationAccuracyAuthorization?,
  private val readRationale: () -> Boolean?,
) : AutoCloseable {

  /**
   * Reads permission from [context]. A context that cannot reach an activity cannot answer the
   * rationale check or launch a request.
   */
  public constructor(
    context: Context
  ) : this(
    lifecycle = (context.findActivityOrNull() as? LifecycleOwner)?.lifecycle,
    registry = (context.findActivityOrNull() as? ComponentActivity)?.activityResultRegistry,
    readGrantedAccuracy = { context.readGrantedAccuracy() },
    readRationale = { context.readRationale() },
  )

  private var closed = false
  private var pendingLauncher: ActivityResultLauncher<Array<String>>? = null
  private val observer = LifecycleEventObserver { _, event ->
    when (event) {
      Lifecycle.Event.ON_RESUME -> refresh()
      Lifecycle.Event.ON_DESTROY -> close()
      else -> Unit
    }
  }

  // In-memory only: Android can silently make the permission requestable again (auto-reset,
  // revocation in settings), and no API distinguishes that from a permanent denial, so a
  // persisted record could go stale forever.
  private var permanentlyDenied = false

  private val mutableStatus = MutableStateFlow(readStatus())

  /**
   * Current foreground location permission.
   *
   * Fine permission maps to [LocationPermission.Granted] at precise accuracy, and coarse at
   * approximate. Otherwise the status is [LocationPermission.NotGranted]:
   * - `shouldShowRationale = true` when Android advises explaining the request first.
   * - `canRequest = false` after a permanent denial that [requestForegroundPermission] recorded.
   * - `canRequest = null` when the supplied context cannot reach an activity for the rationale
   *   check.
   * - `canRequest = true` otherwise.
   *
   * The value refreshes when the resolved activity resumes.
   */
  public val status: StateFlow<LocationPermission> = mutableStatus

  init {
    if (lifecycle?.currentState == Lifecycle.State.DESTROYED) {
      closed = true
    } else {
      lifecycle?.addObserver(observer)
    }
  }

  /** Removes the activity observer and unregisters any pending permission callback. */
  override fun close() {
    if (closed) return
    lifecycle?.removeObserver(observer)
    closed = true
    pendingLauncher?.unregister()
    pendingLauncher = null
  }

  /**
   * Starts a foreground permission request and returns immediately. The result is published to
   * [status].
   *
   * The launcher registers on the activity's result registry at request time, so a directly
   * constructed requester works when the supplied context can reach a [ComponentActivity]. With a
   * context that cannot, such as a service context, this call does nothing and [status] stays
   * accurate.
   *
   * Android's rationale check returns `false` both before the first request and after a permanent
   * denial. This requester tells the two apart by recording a denial that arrives while the check
   * stays `false`. The record lives only in memory and clears once permission is granted or the
   * check returns `true`, so the first request in a fresh process may silently return a denial
   * before [status] reports `canRequest = false` again.
   *
   * @throws IllegalStateException if the requester is closed.
   */
  public fun requestForegroundPermission() {
    val current = refresh()
    if (closed || current is LocationPermission.Granted || pendingLauncher != null) return
    val registry = registry ?: return
    val launcher =
      registry.register(
        "org.maplibre.compose.location.permission.${nextKey.getAndIncrement()}",
        ActivityResultContracts.RequestMultiplePermissions(),
      ) { results ->
        val completedLauncher = pendingLauncher ?: return@register
        pendingLauncher = null
        completedLauncher.unregister()
        // The map holds one granted flag per permission; a fine request can still return a coarse
        // grant. An empty map is a cancelled request, not a user denial.
        val nothingGranted = results.isNotEmpty() && results.values.none { granted -> granted }
        if (nothingGranted && readRationale() == false) permanentlyDenied = true
        refresh()
      }
    pendingLauncher = launcher
    try {
      launcher.launch(
        arrayOf(
          Manifest.permission.ACCESS_FINE_LOCATION,
          Manifest.permission.ACCESS_COARSE_LOCATION,
        )
      )
    } catch (error: Throwable) {
      pendingLauncher = null
      launcher.unregister()
      throw error
    }
  }

  /**
   * Re-reads the permission status, publishes it to [status], and returns it.
   *
   * @throws IllegalStateException if the requester is closed.
   */
  public fun refresh(): LocationPermission {
    check(!closed) { "Location permission requester is closed" }
    val result = readStatus()
    mutableStatus.value = result
    return result
  }

  private fun readStatus(): LocationPermission {
    val granted = readGrantedAccuracy()
    val shouldShowRationale = readRationale()
    // A grant or a rationale proves the permission is requestable, so any record is stale.
    if (granted != null || shouldShowRationale == true) permanentlyDenied = false
    return resolveAndroidLocationPermission(granted, shouldShowRationale, permanentlyDenied)
  }

  private companion object {
    private val nextKey = AtomicInteger()
  }
}

/**
 * Maps the platform permission signals to [LocationPermission]. [granted] is the granted accuracy,
 * or null when permission is absent. [shouldShowRationale] is the platform rationale check, or null
 * when no activity can answer it. [permanentlyDenied] is the recorded permanent denial.
 */
internal fun resolveAndroidLocationPermission(
  granted: LocationAccuracyAuthorization?,
  shouldShowRationale: Boolean?,
  permanentlyDenied: Boolean,
): LocationPermission =
  when {
    granted != null -> LocationPermission.Granted(granted)
    shouldShowRationale == null -> LocationPermission.NotGranted(canRequest = null)
    shouldShowRationale ->
      LocationPermission.NotGranted(canRequest = true, shouldShowRationale = true)
    else -> LocationPermission.NotGranted(canRequest = !permanentlyDenied)
  }

internal tailrec fun Context.findActivityOrNull(): Activity? =
  when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivityOrNull()
    else -> null
  }

private fun Context.readGrantedAccuracy(): LocationAccuracyAuthorization? =
  when {
    checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
      PackageManager.PERMISSION_GRANTED -> LocationAccuracyAuthorization.Precise
    checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
      PackageManager.PERMISSION_GRANTED -> LocationAccuracyAuthorization.Approximate
    else -> null
  }

private fun Context.readRationale(): Boolean? =
  findActivityOrNull()?.let { activity ->
    activity.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION) ||
      activity.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION)
  }
