package org.maplibre.compose.metrics

import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.relativeTo

/** A Kotlin file under a Gradle module's `src/<sourceSet>/kotlin` directory. */
data class SourceFile(
  val path: Path,
  /** Repository-relative path with `/` separators. */
  val relativePath: String,
  /** Repository-relative module directory, such as `lib/maplibre-compose`. */
  val module: String,
  val sourceSet: String,
) {
  val isTest: Boolean
    get() = sourceSet == "test" || sourceSet.endsWith("Test")
}

private val skippedDirectories =
  setOf("build", ".gradle", ".kotlin", "node_modules", ".git", ".claude")

/**
 * Finds every Kotlin file under `<root>/<scanRoot>/**/src/<sourceSet>/kotlin`, without descending
 * into build output.
 */
fun discoverSourceFiles(root: Path, scanRoots: List<String>): List<SourceFile> {
  val files = mutableListOf<SourceFile>()
  val visitor =
    object : SimpleFileVisitor<Path>() {
      override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
        if (dir.name in skippedDirectories) FileVisitResult.SKIP_SUBTREE
        else FileVisitResult.CONTINUE

      override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
        if (attrs.isRegularFile && file.name.endsWith(".kt"))
          sourceFile(root, file)?.let(files::add)
        return FileVisitResult.CONTINUE
      }
    }
  for (scanRoot in scanRoots.map(root::resolve).filter { it.isDirectory() }) {
    Files.walkFileTree(scanRoot, visitor)
  }
  return files.sortedBy { it.relativePath }
}

private fun sourceFile(root: Path, path: Path): SourceFile? {
  val relative = path.relativeTo(root)
  val segments = relative.map { it.name }
  val srcIndex = segments.lastIndexOf("src")
  if (srcIndex < 1 || srcIndex + 2 >= segments.size || segments[srcIndex + 2] != "kotlin")
    return null
  return SourceFile(
    path = path,
    relativePath = segments.joinToString("/"),
    module = segments.subList(0, srcIndex).joinToString("/"),
    sourceSet = segments[srcIndex + 1],
  )
}
