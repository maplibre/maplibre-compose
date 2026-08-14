package org.maplibre.compose.location

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Foreground location permission requester for Android activities.
 *
 * Fine and coarse permission map to [LocationPermission.Granted] with precise and approximate
 * accuracy. When permission is absent,
 * [`Activity.shouldShowRequestPermissionRationale`](https://developer.android.com/reference/android/app/Activity#shouldShowRequestPermissionRationale(java.lang.String))
 * maps `true` to [LocationPermission.NotGranted] with `canRequest = true`. Android returns `false`
 * both before the first request and after a permanent denial, so both map to `canRequest = null`.
 */
public class AndroidLocationPermissionRequester
internal constructor(private val activity: Activity) : LocationPermissionRequester {
  private val mutableStatus = MutableStateFlow(readStatus())
  override val status: StateFlow<LocationPermission> = mutableStatus

  private var requestPending = false
  internal var launchRequest: () -> Unit = {}

  override fun requestForegroundPermission() {
    val current = refresh()
    if (current is LocationPermission.Granted || requestPending) return
    requestPending = true
    try {
      launchRequest()
    } catch (error: Throwable) {
      requestPending = false
      throw error
    }
  }

  internal fun onRequestResult() {
    refresh()
    requestPending = false
  }

  public fun refresh(): LocationPermission {
    val result = readStatus()
    mutableStatus.value = result
    return result
  }

  private fun readStatus(): LocationPermission =
    when {
      activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED ->
        LocationPermission.Granted(LocationAccuracyAuthorization.Precise)
      activity.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED ->
        LocationPermission.Granted(LocationAccuracyAuthorization.Approximate)
      else ->
        LocationPermission.NotGranted(
          canRequest =
            if (
              activity.shouldShowRequestPermissionRationale(
                Manifest.permission.ACCESS_FINE_LOCATION
              ) ||
                activity.shouldShowRequestPermissionRationale(
                  Manifest.permission.ACCESS_COARSE_LOCATION
                )
            ) {
              true
            } else {
              null
            }
        )
    }
}

/** Creates the permission requester used by Android location providers. */
@Composable
public fun rememberAndroidLocationPermissionRequester(
  context: Context = LocalContext.current
): AndroidLocationPermissionRequester {
  val activity = remember(context) { context.findActivity() }
  val requester = remember(activity) { AndroidLocationPermissionRequester(activity) }
  val lifecycleOwner = LocalLifecycleOwner.current
  val launcher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
      requester.onRequestResult()
    }

  SideEffect {
    requester.launchRequest = {
      launcher.launch(
        arrayOf(
          Manifest.permission.ACCESS_FINE_LOCATION,
          Manifest.permission.ACCESS_COARSE_LOCATION,
        )
      )
    }
  }
  DisposableEffect(lifecycleOwner, requester) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_RESUME) requester.refresh()
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    requester.refresh()
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }
  return requester
}

@Composable
public actual fun rememberDefaultLocationPermissionRequester(): LocationPermissionRequester =
  rememberAndroidLocationPermissionRequester()

private tailrec fun Context.findActivity(): Activity =
  when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> error("Location permission requests require an Activity context")
  }
