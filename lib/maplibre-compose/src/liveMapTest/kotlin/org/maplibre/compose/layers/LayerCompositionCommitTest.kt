package org.maplibre.compose.layers

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ControlledComposition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.style.MapNodeApplier
import org.maplibre.compose.style.RecordingStyleBinding
import org.maplibre.compose.style.StyleContent
import org.maplibre.compose.style.StyleNode
import org.maplibre.compose.style.StyleSnapshot

class LayerCompositionCommitTest {
  @Test
  fun recomposition_does_not_change_the_revision_or_mutate_a_snapshot_until_apply() = runTest {
    val tick = mutableIntStateOf(0)
    val root = StyleNode(RecordingStyleBinding(), backgroundScope)
    val recomposer = Recomposer(EmptyCoroutineContext)
    val composition = ControlledComposition(MapNodeApplier(root), recomposer)
    try {
      composition.setContent {
        CompositionLocalProvider(
          LocalDensity provides Density(1f),
          LocalLayoutDirection provides LayoutDirection.Ltr,
        ) {
          StyleContent(root) {
            Layer("layer", "plugin") { paint("value", const(tick.intValue)) }
          }
        }
      }
      val first = assertNotNull(root.takeRevision())
      tick.intValue = 1
      Snapshot.sendApplyNotifications()
      composition.recordModificationsOf(setOf(tick))
      val snapshot =
        Snapshot.takeMutableSnapshot(composition::recordReadOf, composition::recordWriteOf)
      try {
        snapshot.enter { composition.recompose() }
        snapshot.apply().check()
      } finally {
        snapshot.dispose()
      }
      root.commit()
      assertNull(root.takeRevision(), "recomposition must not change the revision before apply")
      composition.applyChanges()
      composition.applyLateChanges()
      composition.changesApplied()
      val applied = assertNotNull(root.takeRevision())
      fun value(declaration: StyleSnapshot) =
        declaration.layers.single().definition.value["paint"]!!.jsonObject["value"]
      assertEquals(JsonPrimitive(0f), value(first))
      assertEquals(JsonPrimitive(1f), value(applied))
    } finally {
      root.close()
      composition.dispose()
      recomposer.close()
    }
  }
}
