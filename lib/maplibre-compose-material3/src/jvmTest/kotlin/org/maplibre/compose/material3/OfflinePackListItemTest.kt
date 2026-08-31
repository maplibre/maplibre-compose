package org.maplibre.compose.material3

import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class OfflinePackListItemTest {
  @Test
  fun swipeRequiresConfirmationBeforeDeleting() = runComposeUiTest {
    var deleteCount = 0
    setContent {
      MaterialTheme {
        ConfirmedOfflinePackSwipeToDelete(
          deleteKey = Unit,
          onDelete = { deleteCount++ },
          modifier = Modifier.testTag("offline pack"),
        ) {
          ListItem(headlineContent = { Text("Offline pack") })
        }
      }
    }

    swipeOfflinePack()
    onNodeWithText("Delete offline map?").assertIsDisplayed()
    runOnIdle { assertEquals(0, deleteCount) }

    onNodeWithText("Cancel").performClick()
    waitForIdle()
    onAllNodesWithText("Delete offline map?").assertCountEquals(0)

    swipeOfflinePack()
    onNodeWithText("Delete").performClick()
    waitForIdle()
    runOnIdle { assertEquals(1, deleteCount) }
  }

  @Test
  fun failedDeletionKeepsConfirmationOpen() = runComposeUiTest {
    setContent {
      MaterialTheme {
        ConfirmedOfflinePackSwipeToDelete(
          deleteKey = Unit,
          onDelete = { error("Offline database rejected deletion") },
          modifier = Modifier.testTag("offline pack"),
        ) {
          ListItem(headlineContent = { Text("Offline pack") })
        }
      }
    }

    swipeOfflinePack()
    onNodeWithText("Delete").performClick()
    waitForIdle()

    onNodeWithText("Deletion failed: Offline database rejected deletion").assertIsDisplayed()
    onNodeWithText("Delete offline map?").assertIsDisplayed()
  }

  private fun androidx.compose.ui.test.ComposeUiTest.swipeOfflinePack() {
    onNodeWithTag("offline pack").performTouchInput {
      swipe(start = centerRight, end = centerLeft, durationMillis = 200)
    }
    waitForIdle()
  }
}
