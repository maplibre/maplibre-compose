package org.maplibre.compose.style

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runAndroidComposeUiTest
import org.maplibre.android.MapLibre

@OptIn(ExperimentalTestApi::class)
class AndroidStyleNodeTest : StyleNodeTest() {
  override fun platformSetup() =
    runAndroidComposeUiTest<ComponentActivity> {
      activity!!.runOnUiThread { MapLibre.getInstance(activity!!) }
    }
}
