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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Foreground location permission controller for Android activities.
 *
 * Fine and coarse permission map to [LocationPermission.Granted] with precise and approximate
 * accuracy. When permission is absent,
 * [`Activity.shouldShowRequestPermissionRationale`](https://developer.android.com/reference/android/app/Activity#shouldShowRequestPermissionRationale(java.lang.String))
 * maps `true` to [LocationPermission.NotGranted] with `canRequest = true`. Android returns `false`
 * both before the first request and after a permanent denial, so both map to `canRequest = null`.
 */
public class AndroidLocationPermissionController
internal constructor(private val activity: Activity) : LocationPermissionController {
  private val mutableStatus = MutableStateFlow(readStatus())
  override val status: StateFlow<LocationPermission> = mutableStatus

  private var pendingRequest: CompletableDeferred<LocationPermission>? = null
  internal var launchRequest: () -> Unit = {}

  override suspend fun requestForegroundPermission(): LocationPermission {
    val current = refresh()
    if (current is LocationPermission.Granted) return current
    pendingRequest?.let {
      return it.await()
    }

    val result = CompletableDeferred<LocationPermission>()
    pendingRequest = result
    launchRequest()
    return result.await()
  }

  internal fun onRequestResult() {
    val result = refresh()
    pendingRequest?.complete(result)
    pendingRequest = null
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

/** Creates the permission controller used by Android location providers. */
@Composable
public fun rememberAndroidLocationPermissionController(
  context: Context = LocalContext.current
): AndroidLocationPermissionController {
  val activity = remember(context) { context.findActivity() }
  val controller = remember(activity) { AndroidLocationPermissionController(activity) }
  val lifecycleOwner = LocalLifecycleOwner.current
  val launcher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
      controller.onRequestResult()
    }

  SideEffect {
    controller.launchRequest = {
      launcher.launch(
        arrayOf(
          Manifest.permission.ACCESS_FINE_LOCATION,
          Manifest.permission.ACCESS_COARSE_LOCATION,
        )
      )
    }
  }
  DisposableEffect(lifecycleOwner, controller) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_RESUME) controller.refresh()
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    controller.refresh()
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }
  return controller
}

private tailrec fun Context.findActivity(): Activity =
  when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> error("Location permission requests require an Activity context")
  }
