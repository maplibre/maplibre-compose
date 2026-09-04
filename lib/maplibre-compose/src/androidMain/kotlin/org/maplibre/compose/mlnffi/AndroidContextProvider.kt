package org.maplibre.compose.mlnffi

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri

/**
 * Captures the application context at process start, so library functions that read the package
 * need no context parameter. Declared in the library manifest, the same way Compose Resources
 * obtains its context.
 */
internal class AndroidContextProvider : ContentProvider() {
  override fun onCreate(): Boolean {
    applicationContext = checkNotNull(context) { "The content provider has no context" }
    return true
  }

  override fun query(
    uri: Uri,
    projection: Array<out String>?,
    selection: String?,
    selectionArgs: Array<out String>?,
    sortOrder: String?,
  ): Cursor? = null

  override fun getType(uri: Uri): String? = null

  override fun insert(uri: Uri, values: ContentValues?): Uri? = null

  override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

  override fun update(
    uri: Uri,
    values: ContentValues?,
    selection: String?,
    selectionArgs: Array<out String>?,
  ): Int = 0

  internal companion object {
    @Volatile private var applicationContext: Context? = null

    /** The application context, or throws when the library manifest was not merged. */
    val context: Context
      get() =
        checkNotNull(applicationContext) {
          "MapLibre Compose has no application context; the library manifest was not merged"
        }
  }
}
