package org.maplibre.compose.metrics

import java.nio.file.Files
import java.nio.file.Path
import java.time.OffsetDateTime
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GitTest {
  private val root: Path = Files.createTempDirectory("code-metrics-git")

  @AfterTest
  fun cleanUp() {
    root.toFile().deleteRecursively()
  }

  @Test
  fun `counts touches under the latest path across renames`() {
    val log =
      """
      M	lib/New.kt
      R090	lib/Old.kt	lib/New.kt
      M	lib/Old.kt
      A	lib/Old.kt
      A	lib/Other.kt
      """
        .trimIndent()
        .lineSequence()

    assertEquals(mapOf("lib/New.kt" to 4, "lib/Other.kt" to 1), countTouches(log))
  }

  @Test
  fun `a file renamed away and back counts under its final path`() {
    val log =
      """
      R100	lib/B.kt	lib/A.kt
      M	lib/B.kt
      R100	lib/A.kt	lib/B.kt
      A	lib/A.kt
      """
        .trimIndent()
        .lineSequence()

    assertEquals(mapOf("lib/A.kt" to 4), countTouches(log))
  }

  @Test
  fun `reads renames from the repository`() {
    val git = GitRepository(root)
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
    val old = root.resolve("lib/Old.kt")
    old.parent.createDirectories()
    old.writeText("package a\n\nval a = 1\n")
    git("add", ".")
    git("commit", "-q", "-m", "add")
    old.writeText("package a\n\nval a = 2\n")
    git("commit", "-q", "-am", "edit")
    git("mv", "lib/Old.kt", "lib/New.kt")
    git("commit", "-q", "-m", "rename")

    val touches = git.commitsPerFile("HEAD", OffsetDateTime.now().minusDays(1), listOf("lib"))

    assertEquals(mapOf("lib/New.kt" to 3), touches)
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
