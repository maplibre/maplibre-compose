package org.maplibre.compose.metrics

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    val files = discoverSourceFiles(root, listOf("lib"))

    assertEquals(
      listOf("commonMain" to false, "jvmTest" to true, "test" to true),
      files.map { it.sourceSet to it.isTest },
    )
    assertEquals("lib/a", files.first().module)
  }

  @Test
  fun `records declarations with effective visibility and modifiers`() {
    write(
      "lib/a/src/commonMain/kotlin/a/A.kt",
      """
      package a

      /** Documented. */
      public class Outer {
        public fun member() {}
        internal fun hidden() {}
        private class Nested {
          public fun unreachable() {}
        }
      }

      public expect fun platform(): Int

      public interface Listener {
        public fun onEvent()
      }

      public class Impl : Listener {
        override fun onEvent() {}
      }

      public enum class Color {
        RED,
        GREEN;

        public fun hex(): String = ""
      }

      public class WithInit {
        init {
          println()
        }
      }
      """,
    )

    val file = analyze().single()
    val byName = file.declarations.associateBy { it.name }

    assertEquals(
      listOf("Outer", "Outer.Nested", "Listener", "Impl", "Color", "WithInit"),
      file.types.map { it.name },
    )
    assertEquals("entry", byName.getValue("Color.RED").kind)
    assertEquals(3, file.types.single { it.name == "Color" }.publicMembers)
    assertEquals(0, file.types.single { it.name == "WithInit" }.publicMembers)
    assertEquals(listOf("WithInit"), file.declarations.map { it.name }.filter { "WithInit" in it })
    assertTrue(byName.getValue("Outer").hasKDoc)
    assertTrue(byName.getValue("Outer.member").isEffectivelyPublic)
    assertEquals(Visibility.INTERNAL, byName.getValue("Outer.hidden").visibility)
    assertEquals(false, byName.getValue("Outer.Nested.unreachable").isEffectivelyPublic)
    assertTrue(byName.getValue("platform").isExpect)
    assertTrue(byName.getValue("Impl.onEvent").isOverride)
    assertEquals(listOf("Listener"), file.types.single { it.name == "Impl" }.supertypes)
    assertEquals(1, file.types.single { it.name == "Outer" }.publicMembers)
  }

  @Test
  fun `measures functions and file lines`() {
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
      """,
    )

    val file = analyze().single()
    val branchy = file.functions.single { it.name == "branchy" }

    assertEquals(8, branchy.lines)
    assertEquals(2, branchy.parameters)
    assertEquals(4, branchy.cyclomaticComplexity)
    assertEquals(2, branchy.nestingDepth)
    assertEquals(1, file.functions.single { it.name == "flat" }.lines)
    assertEquals(13, file.loc)
    assertEquals(1, file.cloc)
  }

  @Test
  fun `counts anonymous, sam, and named implementations of abstractions`() {
    write(
      "lib/a/src/commonMain/kotlin/a/A.kt",
      """
      package a

      interface Handler
      fun interface Callback { fun call() }
      external interface JsThing
      sealed interface Shape
      class Circle : Shape
      sealed class Node
      class Leaf : Node()

      class NamedHandler : Handler
      val anonymous = object : Handler {}
      val callback = Callback { }
      """,
    )
    write(
      "lib/a/src/commonTest/kotlin/a/ATest.kt",
      """
      package a

      class FakeHandler : Handler
      val testCallback = Callback { }
      """,
    )

    val (summary, sections) = aggregate(analyze(), listOf("lib"), churn = null, top = 5)
    val byName = sections.abstractions.associateBy { it.name }

    assertEquals(setOf("Handler", "Callback", "Shape", "Node"), byName.keys)
    assertTrue(byName.getValue("Node").isSealed)
    assertEquals(1, byName.getValue("Node").mainImplementations)
    assertEquals(1, byName.getValue("Handler").mainImplementations)
    assertEquals(1, byName.getValue("Handler").mainAnonymousImplementations)
    assertEquals(1, byName.getValue("Handler").testImplementations)
    assertEquals(1, byName.getValue("Callback").mainSamImplementations)
    assertEquals(1, byName.getValue("Callback").testSamImplementations)
    assertEquals(2, summary.abstractions)
    assertEquals(1, summary.abstractionsWithSingleImplementation)
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

      import a.A.Companion.factory

      class B
      """,
    )
    write("lib/a/src/commonMain/kotlin/c/util/Helper.kt", "package c.util\n\nfun helper() = 1")
    write(
      "lib/a/src/commonTest/kotlin/c/util/HelperTest.kt",
      "package c.util\n\nimport a.A\n\nval ignored = A::class",
    )

    val (summary, sections) = aggregate(analyze(), listOf("lib"), churn = null, top = 5)
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
  fun `resolves nested and wildcard imports to the enclosing package`() {
    val known = setOf("a", "a.b")

    assertEquals("a.b", resolvePackage(Import("a.b.C.D.e", allUnder = false), known))
    assertEquals("a.b", resolvePackage(Import("a.b", allUnder = true), known))
    assertEquals("a", resolvePackage(Import("a.other.Thing", allUnder = false), known))
    assertNull(resolvePackage(Import("kotlin.collections.List", allUnder = false), known))
  }

  @Test
  fun `counts api surface only under api roots`() {
    write(
      "lib/a/src/commonMain/kotlin/a/A.kt",
      "package a\n\npublic fun api() {}\n\ninternal fun x() {}",
    )
    write("app/src/commonMain/kotlin/app/App.kt", "package app\n\nfun notApi() {}")

    val (summary, _) = aggregate(analyze(), listOf("lib"), churn = null, top = 5)

    assertEquals(1, summary.publicDeclarations)
    assertEquals(1, summary.internalDeclarations)
    assertEquals(2, summary.files)
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

  @Test
  fun `resolves supertypes through imports, aliases, enclosing types, and star imports`() {
    write(
      "lib/a/src/commonMain/kotlin/a/Abstractions.kt",
      """
      package a

      interface Imported
      interface Aliased
      interface Starred
      interface Local
      class Outer {
        interface Nested
        class Inner : Nested
      }
      """,
    )
    write(
      "lib/a/src/commonMain/kotlin/b/Starred.kt",
      "package b\n\ninterface Starred\n\ninterface Outer",
    )
    write("lib/a/src/commonMain/kotlin/c/Alias.kt", "package c\n\ntypealias Renamed = a.Aliased")
    write(
      "lib/a/src/commonMain/kotlin/d/Impls.kt",
      """
      package d

      import a.Imported
      import a.Local as L
      import a.Outer
      import a.*
      import b.*
      import c.Renamed
      import kotlin.io.Closeable

      class One : Imported, L, Renamed, Outer.Nested, Closeable
      class Two : Starred
      class Three : a.Starred
      """,
    )
    write(
      "lib/a/src/commonMain/kotlin/e/Impls.kt",
      """
      package e

      import a.*
      import b.*

      class Four : Outer.Nested
      """,
    )

    val (_, sections) = aggregate(analyze(), listOf("lib"), churn = null, top = 5)
    val byName = sections.abstractions.associateBy { "${it.packageName}.${it.name}" }

    assertEquals(1, byName.getValue("a.Imported").mainImplementations)
    assertEquals(1, byName.getValue("a.Local").mainImplementations)
    assertEquals(1, byName.getValue("a.Aliased").mainImplementations)
    assertEquals(3, byName.getValue("a.Outer.Nested").mainImplementations)
    assertEquals(false, byName.getValue("a.Outer.Nested").ambiguous)
    assertEquals(2, byName.getValue("a.Starred").mainImplementations)
    assertEquals(1, byName.getValue("b.Starred").mainImplementations)
    assertTrue(byName.getValue("a.Starred").ambiguous)
    assertTrue(byName.getValue("b.Starred").ambiguous)
  }

  @Test
  fun `resolves a qualified sam constructor call`() {
    write(
      "lib/a/src/commonMain/kotlin/a/A.kt",
      """
      package a

      class Owner {
        fun interface Callback { fun call() }
      }
      val direct = Owner.Callback { }
      val viaRun = run { Owner.Callback { } }
      """,
    )
    write(
      "lib/a/src/commonMain/kotlin/b/B.kt",
      "package b\n\nimport a.Owner\n\nval other = Owner.Callback { }",
    )

    val (_, sections) = aggregate(analyze(), listOf("lib"), churn = null, top = 5)

    assertEquals(3, sections.abstractions.single().mainSamImplementations)
  }
}
