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
 * Foreground location permission building block for Android.
 *
 * Fine and coarse permission map to [LocationPermission.Granted] with precise and approximate
 * accuracy. When permission is absent,
 * [`Activity.shouldShowRequestPermissionRationale`](https://developer.android.com/reference/android/app/Activity#shouldShowRequestPermissionRationale(java.lang.String))
 * maps `true` to [LocationPermission.NotGranted] with `canRequest = true`. Android returns `false`
 * both before the first request and after a permanent denial, so both map to `canRequest = null`. A
 * [context] that cannot reach an [Activity] also maps to `canRequest = null`, because the rationale
 * check requires an activity.
 *
 * Android [LocationProvider] implementations hold a requester and expose [status] and
 * [requestForegroundPermission] through [LocationProvider.permission] and
 * [LocationProvider.requestPermission].
 *
 * [requestForegroundPermission] registers a launcher on the activity's result registry at request
 * time, so a directly constructed requester is fully functional when [context] can reach a
 * [ComponentActivity]. When it cannot, such as with a service context,
 * [requestForegroundPermission] does nothing and [status] stays accurate. [status] refreshes when
 * the resolved activity resumes.
 *
 * @param context Any [Context]; the permission status is read from the application package.
 */
public class AndroidLocationPermissionRequester(private val context: Context) {
  private val mutableStatus = MutableStateFlow(readStatus())
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
      ) {
        requestPending = false
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

  public fun refresh(): LocationPermission {
    val result = readStatus()
    mutableStatus.value = result
    return result
  }

  private fun readStatus(): LocationPermission =
    when {
      context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED ->
        LocationPermission.Granted(LocationAccuracyAuthorization.Precise)
      context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED ->
        LocationPermission.Granted(LocationAccuracyAuthorization.Approximate)
      else ->
        LocationPermission.NotGranted(
          canRequest =
            context.findActivityOrNull()?.let { activity ->
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
            }
        )
    }

  private companion object {
    private val nextKey = AtomicInteger()
  }
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
