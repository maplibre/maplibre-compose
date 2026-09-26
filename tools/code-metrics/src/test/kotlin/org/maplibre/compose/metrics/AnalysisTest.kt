package org.maplibre.compose.metrics

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AnalysisTest {
  private val root: Path = Files.createTempDirectory("code-metrics-test")
  private val parser = KotlinParser()

  @AfterTest
  fun cleanUp() {
    parser.close()
    root.toFile().deleteRecursively()
  }

  private fun write(relativePath: String, text: String) {
    val file = root.resolve(relativePath)
    file.parent.createDirectories()
    file.writeText(text.trimIndent())
  }

  private fun analyze(): List<FileFacts> =
    discoverSourceFiles(root, listOf("lib", "app")).map {
      analyzeFile(it, parser.parse(it.relativePath, Files.readString(it.path)))
    }

  @Test
  fun `discovers source sets under src and skips build output`() {
    write("lib/a/src/commonMain/kotlin/a/A.kt", "package a")
    write("lib/a/src/jvmTest/kotlin/a/ATest.kt", "package a")
    write("lib/a/src/test/kotlin/a/BTest.kt", "package a")
    write("lib/a/build/generated/kotlin/a/Gen.kt", "package a")
    write("lib/a/src/commonMain/resources/notes.kt", "package a")
    write("lib/a/src/commonMain/kotlin/a/src/InSrc.kt", "package a.src")

    val files = discoverSourceFiles(root, listOf("lib"))

    assertEquals(
      listOf("commonMain" to false, "commonMain" to false, "jvmTest" to true, "test" to true),
      files.map { it.sourceSet to it.isTest },
    )
    assertEquals("lib/a", files.first().module)
    assertEquals(files, discoverSourceFiles(root, listOf("lib", "lib/a")))
  }

  @Test
  fun `records types by nested name and counts expect and actual declarations`() {
    write(
      "lib/a/src/commonMain/kotlin/a/A.kt",
      """
      package a

      class Outer {
        class Nested
        companion object {}
        init {}
      }

      enum class Color { RED, GREEN }

      expect fun platform(): Int

      expect class Platform(id: Int) {
        val name: String
      }

      actual class Handle actual constructor(val id: Int)
      """,
    )

    val file = analyze().single()

    assertEquals(
      listOf("Outer", "Outer.Nested", "Outer.Companion", "Color", "Platform", "Handle"),
      file.types.map { it.name },
    )
    assertEquals(TypeKind.COMPANION, file.types.single { it.name == "Outer.Companion" }.kind)
    assertEquals(5, file.types.single { it.name == "Outer" }.lines)
    assertEquals(2, file.expectDeclarations)
    assertEquals(2, file.actualDeclarations)
  }

  @Test
  fun `measures every named function as detekt does`() {
    write(
      "lib/a/src/commonMain/kotlin/a/A.kt",
      """
      package a

      // A comment.
      fun branchy(x: Int, y: Int): Int {
        if (x > 0) {
          for (i in 0 until y) {
            if (i == 3) return i
          }
        }

        return 0
      }

      fun flat() = 1

      val anonymous = fun(x: Int) = x

      class Owner {
        val listener = object : Runnable {
          override fun run() {
            fun local() = 1
            if (local() > 0) println()
          }
        }
      }
      """,
    )

    val file = analyze().single()
    val byName = file.functions.associateBy { it.name }
    val branchy = byName.getValue("branchy")

    assertEquals(
      listOf("branchy", "flat", "<anonymous>", "Owner.<anonymous>.run", "Owner.<anonymous>.local"),
      file.functions.map { it.name },
    )
    assertEquals(
      listOf(4, 8, 2, 4, 6, 1, 4),
      listOf(
        branchy.line,
        branchy.lines,
        branchy.parameters,
        branchy.cyclomaticComplexity,
        branchy.cognitiveComplexity,
        byName.getValue("flat").lines,
        byName.getValue("Owner.<anonymous>.run").lines,
      ),
    )
    assertEquals(25, file.loc)
    assertEquals(1, file.cloc)
  }

  @Test
  fun `builds the package graph from imports and finds cycles`() {
    write(
      "lib/a/src/commonMain/kotlin/a/A.kt",
      """
      package a

      import b.B
      import c.util.helper
      import kotlin.collections.List

      class A(val b: B)
      """,
    )
    write(
      "lib/a/src/commonMain/kotlin/b/B.kt",
      """
      package b

      import a.A

      class B
      """,
    )
    write("lib/a/src/commonMain/kotlin/c/util/Helper.kt", "package c.util\n\nfun helper() = 1")
    write(
      "lib/a/src/commonTest/kotlin/c/util/HelperTest.kt",
      "package c.util\n\nimport a.A\n\nval ignored = A::class",
    )

    val (summary, sections) = aggregate(analyze(), top = 5)
    val graph = sections.packageGraph

    assertEquals(
      listOf(PackageEdge("a", "b", 1), PackageEdge("a", "c.util", 1), PackageEdge("b", "a", 1)),
      graph.edges,
    )
    assertEquals(listOf(listOf("a", "b")), graph.cycles)
    assertEquals(listOf(listOf("a", "b")), graph.bidirectionalPairs)
    assertEquals(2, summary.packagesInCycles)
    val a = sections.packages.single { it.name == "a" }
    assertEquals(listOf("b", "c.util"), a.dependsOn)
    assertEquals(listOf("b"), a.dependedOnBy)
    assertEquals(1, a.externalImports)
    assertEquals(0.0, sections.packages.single { it.name == "c.util" }.instability)
  }

  @Test
  fun `resolves imports through known packages and types only`() {
    val index =
      PackageIndex(
        packages = setOf("a", "a.b"),
        typePackages = mapOf("a.b.C" to "a.b", "a.b.C.D" to "a.b", "a.b.lower" to "a.b"),
      )

    assertEquals("a.b", index.packageOf(Import("a.b.C.D.e", allUnder = false)))
    assertEquals("a.b", index.packageOf(Import("a.b", allUnder = true)))
    assertEquals("a.b", index.packageOf(Import("a.b.C", allUnder = true)))
    assertEquals("a.b", index.packageOf(Import("a.b.lower.Member", allUnder = false)))
    assertNull(index.packageOf(Import("a.other.Thing", allUnder = false)))
    assertNull(index.packageOf(Import("a.b.Unknown.Member", allUnder = false)))
    assertNull(index.packageOf(Import("kotlin.collections.List", allUnder = false)))
  }

  @Test
  fun `identifies functions by line so overloads stay distinct`() {
    write(
      "lib/a/src/commonMain/kotlin/a/A.kt",
      """
      package a

      fun f(x: Int) = x
      fun f(x: String) = x
      """,
    )

    val (_, sections) = aggregate(analyze(), top = 5)

    assertEquals(
      listOf("lib/a/src/commonMain/kotlin/a/A.kt:3:f", "lib/a/src/commonMain/kotlin/a/A.kt:4:f"),
      sections.largest.functionsByLines.map { it.name }.sorted(),
    )
  }

  @Test
  fun `distribution uses nearest rank percentiles`() {
    val values = (1..100).map { Ranked("v$it", it) }

    val distribution = distribution(values)

    assertEquals(50, distribution.p50)
    assertEquals(90, distribution.p90)
    assertEquals(99, distribution.p99)
    assertEquals(100, distribution.max)
    assertEquals("v100", distribution.maxName)
    assertEquals(Distribution(0, 0.0, 0, 0, 0, 0, null), distribution(emptyList()))
  }

  @Test
  fun `strongly connected components`() {
    val components =
      stronglyConnectedComponents(
        listOf("a", "b", "c", "d"),
        mapOf("a" to listOf("b"), "b" to listOf("c"), "c" to listOf("a"), "d" to listOf("a")),
      )

    assertEquals(setOf(setOf("a", "b", "c"), setOf("d")), components.map { it.toSet() }.toSet())
  }
}
