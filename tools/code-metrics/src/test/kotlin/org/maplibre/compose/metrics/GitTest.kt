package org.maplibre.compose.metrics

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

class GitTest {
  private val root: Path = Files.createTempDirectory("code-metrics-git")

  @AfterTest
  fun cleanUp() {
    root.toFile().deleteRecursively()
  }

  @Test
  fun `exports the roots that exist at the ref`() {
    fun git(vararg args: String) =
      run(
        "git",
        "-c",
        "user.name=t",
        "-c",
        "user.email=t@example.com",
        *args,
        workingDirectory = root,
      )
    git("init", "-q", "-b", "main")
    val file = root.resolve("lib/A.kt")
    file.parent.createDirectories()
    file.writeText("package a\n")
    git("add", ".")
    git("commit", "-q", "-m", "add")
    val into = Files.createTempDirectory("code-metrics-export")

    try {
      GitRepository(root).export("HEAD", listOf("lib", "missing"), into)
      assertTrue(Files.exists(into.resolve("lib/A.kt")))
    } finally {
      into.toFile().deleteRecursively()
    }
  }
}
