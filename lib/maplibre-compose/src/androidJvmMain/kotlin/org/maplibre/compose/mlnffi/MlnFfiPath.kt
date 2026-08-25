package org.maplibre.compose.mlnffi

import java.io.File
import kotlinx.io.files.Path

internal actual fun normalizeMlnFfiPath(path: Path): Path =
  Path(lexicallyAbsolute(File(path.toString())).path)

/**
 * Collapses `.` and `..` on an absolute [File] without asking the filesystem, so a cache database
 * that does not exist yet still has one identity. `File.canonicalFile` cannot do this: it fails
 * when the last segment is missing, and it follows symlinks.
 */
private fun lexicallyAbsolute(file: File): File {
  val names = ArrayList<String>()
  var current = file.absoluteFile
  var parent = current.parentFile
  while (parent != null) {
    names.add(current.name)
    current = parent
    parent = current.parentFile
  }
  val normalized = ArrayList<String>(names.size)
  for (i in names.lastIndex downTo 0) {
    when (val name = names[i]) {
      "",
      "." -> Unit
      ".." -> if (normalized.isNotEmpty()) normalized.removeAt(normalized.lastIndex)
      else -> normalized.add(name)
    }
  }
  return normalized.fold(current) { dir, name -> File(dir, name) }
}
