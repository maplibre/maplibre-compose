package org.maplibre.compose.mlnffi

import kotlinx.io.files.Path
import platform.Foundation.NSFileManager

/**
 * Collapses `.` and `..` on an absolute spelling of [path] without asking the filesystem, so a
 * cache database that does not exist yet still has one identity. A relative [path] resolves against
 * the process's current directory, as `File.absoluteFile` does on the JVM.
 */
internal actual fun normalizeMlnFfiPath(path: Path): Path {
  val raw = path.toString()
  val absolute =
    if (raw.startsWith("/")) raw
    else NSFileManager.defaultManager.currentDirectoryPath.trimEnd('/') + "/" + raw
  val names = mutableListOf<String>()
  for (name in absolute.split('/')) {
    when (name) {
      "",
      "." -> Unit
      ".." -> if (names.isNotEmpty()) names.removeAt(names.lastIndex)
      else -> names.add(name)
    }
  }
  return Path("/" + names.joinToString("/"))
}
