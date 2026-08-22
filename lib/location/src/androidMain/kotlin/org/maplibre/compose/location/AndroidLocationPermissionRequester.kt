package org.maplibre.compose.location

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
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
 * @param context Any [Context]; the permission status is read from the application package. A
 *   context that cannot reach an [Activity] cannot answer the rationale check or launch a request.
 */
public class AndroidLocationPermissionRequester(private val context: Context) {

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
   * - `canRequest = null` when [context] cannot reach an activity for the rationale check.
   * - `canRequest = true` otherwise.
   *
   * The value refreshes when the resolved activity resumes.
   */
  public val status: StateFlow<LocationPermission> = mutableStatus

  private var requestPending = false

  init {
    (context.findActivityOrNull() as? LifecycleOwner)
      ?.lifecycle
      ?.addObserver(
        LifecycleEventObserver { _, event ->
          if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
      )
  }

  /**
   * Starts a foreground permission request and returns immediately. The result is published to
   * [status].
   *
   * The launcher registers on the activity's result registry at request time, so a directly
   * constructed requester works when [context] can reach a [ComponentActivity]. With a context that
   * cannot, such as a service context, this call does nothing and [status] stays accurate.
   *
   * Android's rationale check returns `false` both before the first request and after a permanent
   * denial. This requester tells the two apart by recording a denial that arrives while the check
   * stays `false`. The record lives only in memory and clears once permission is granted or the
   * check returns `true`, so the first request in a fresh process may silently return a denial
   * before [status] reports `canRequest = false` again.
   */
  public fun requestForegroundPermission() {
    val current = refresh()
    if (current is LocationPermission.Granted || requestPending) return
    val activity = context.findActivityOrNull() as? ComponentActivity ?: return
    requestPending = true
    lateinit var launcher: ActivityResultLauncher<Array<String>>
    launcher =
      activity.activityResultRegistry.register(
        "org.maplibre.compose.location.permission.${nextKey.getAndIncrement()}",
        ActivityResultContracts.RequestMultiplePermissions(),
      ) { results ->
        requestPending = false
        // The map holds one granted flag per permission; a fine request can still return a coarse
        // grant. An empty map is a cancelled request, not a user denial.
        val nothingGranted = results.isNotEmpty() && results.values.none { granted -> granted }
        if (nothingGranted && readRationale() == false) permanentlyDenied = true
        refresh()
        launcher.unregister()
      }
    try {
      launcher.launch(
        arrayOf(
          Manifest.permission.ACCESS_FINE_LOCATION,
          Manifest.permission.ACCESS_COARSE_LOCATION,
        )
      )
    } catch (error: Throwable) {
      requestPending = false
      launcher.unregister()
      throw error
    }
  }

  /** Re-reads the permission status, publishes it to [status], and returns it. */
  public fun refresh(): LocationPermission {
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

  private fun readGrantedAccuracy(): LocationAccuracyAuthorization? =
    when {
      context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED -> LocationAccuracyAuthorization.Precise
      context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED -> LocationAccuracyAuthorization.Approximate
      else -> null
    }

  private fun readRationale(): Boolean? =
    context.findActivityOrNull()?.let { activity ->
      activity.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION) ||
        activity.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION)
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

/** Remembers the permission requester used by Android location providers. */
@Composable
public fun rememberAndroidLocationPermissionRequester(
  context: Context = LocalContext.current
): AndroidLocationPermissionRequester =
  remember(context) { AndroidLocationPermissionRequester(context) }

internal tailrec fun Context.findActivityOrNull(): Activity? =
  when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivityOrNull()
    else -> null
  }
