package org.maplibre.compose.style

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runAndroidComposeUiTest
import org.maplibre.compose.mlnffi.AndroidMlnFfiPlatform

// Migrated to the v2 runner together with the rest of the test suite, in #861.
@Suppress("DEPRECATION")
@OptIn(ExperimentalTestApi::class)
class AndroidStyleNodeTest : StyleNodeTest() {
  override fun platformSetup() =
    runAndroidComposeUiTest<ComponentActivity> {
      activity!!.runOnUiThread { AndroidMlnFfiPlatform.initialize(activity!!) }
    }
}
