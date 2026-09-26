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
    assertEquals(files, discoverSourceFiles(root, listOf("lib", "lib/a")))
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

      public expect class Platform(id: Int) {
        public val name: String
      }

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
      listOf("Outer", "Outer.Nested", "Platform", "Listener", "Impl", "Color", "WithInit"),
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
    assertTrue(byName.getValue("Platform.name").isExpect)
    assertTrue(byName.getValue("Platform.<init>").isExpect)
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

      import a.A

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
  fun `counts api surface only under api roots`() {
    write(
      "lib/a/src/commonMain/kotlin/a/A.kt",
      "package a\n\npublic fun api() {}\n\ninternal fun x() {}\n\ninternal class Box {\n  fun f() {}\n}",
    )
    write("app/src/commonMain/kotlin/app/App.kt", "package app\n\nfun notApi() {}")

    val (summary, _) = aggregate(analyze(), listOf("lib"), churn = null, top = 5)

    assertEquals(1, summary.publicDeclarations)
    assertEquals(3, summary.internalDeclarations)
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
      class Four : c.Renamed
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
    assertEquals(2, byName.getValue("a.Aliased").mainImplementations)
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

  @Test
  fun `counts primary constructors as declarations`() {
    write(
      "lib/a/src/commonMain/kotlin/a/A.kt",
      """
      package a

      /** Documented. */
      public class Point(val x: Int)
      public class Hidden internal constructor()
      public sealed class Shape(val sides: Int)
      public enum class Color(val rgb: Int) { RED(1) }
      public class Bare
      internal class Box(val v: Int, w: Int) {
        fun f() {}
      }
      public abstract class Base {
        protected abstract fun hook()
        private fun helper() {}
      }
      """,
    )

    val file = analyze().single()
    val byName = file.declarations.associateBy { it.name }

    assertEquals("constructor", byName.getValue("Point.<init>").kind)
    assertTrue(byName.getValue("Point.<init>").isEffectivelyPublic)
    assertTrue(byName.getValue("Point.<init>").hasKDoc)
    assertEquals("property", byName.getValue("Point.x").kind)
    assertTrue(byName.getValue("Point.x").isEffectivelyPublic)
    assertEquals(1, file.types.single { it.name == "Point" }.publicMembers)
    assertEquals(Visibility.INTERNAL, byName.getValue("Box.v").effectiveVisibility)
    assertEquals(Visibility.INTERNAL, byName.getValue("Box.f").effectiveVisibility)
    assertEquals(Visibility.PUBLIC, byName.getValue("Box.f").visibility)
    assertNull(byName["Box.w"])
    assertEquals(0, file.types.single { it.name == "Box" }.publicMembers)
    assertEquals(1, file.types.single { it.name == "Base" }.publicMembers)
    assertEquals(Visibility.INTERNAL, byName.getValue("Hidden.<init>").visibility)
    assertEquals(Visibility.PROTECTED, byName.getValue("Shape.<init>").visibility)
    assertEquals(Visibility.PRIVATE, byName.getValue("Color.<init>").visibility)
    assertNull(byName["Bare.<init>"])
  }

  @Test
  fun `measures methods of object literals without declaring them`() {
    write(
      "lib/a/src/commonMain/kotlin/a/A.kt",
      """
      package a

      interface Listener { fun onEvent(x: Int) }

      class Owner {
        val listener = object : Listener {
          override fun onEvent(x: Int) {
            if (x > 0) println(x)
          }
        }

        fun make(): Listener {
          fun local() = 1
          return object : Listener {
            override fun onEvent(x: Int) {
              if (x > 1) {
                println(local())
              }
            }
          }
        }
      }
      """,
    )

    val file = analyze().single()
    val anonymous = file.functions.filter { it.name == "Owner.<anonymous>.onEvent" }

    assertEquals(
      setOf("Listener.onEvent", "Owner.make", "Owner.<anonymous>.onEvent"),
      file.functions.map { it.name }.toSet(),
    )
    assertEquals(2, file.functions.single { it.name == "Owner.make" }.cyclomaticComplexity)
    assertEquals(listOf(2, 2), anonymous.map { it.cyclomaticComplexity }.sorted())
    assertEquals(listOf(3, 5), anonymous.map { it.lines }.sorted())
    assertEquals(listOf(0, 1), anonymous.map { it.nestingDepth }.sorted())
    assertEquals(0, file.functions.single { it.name == "Owner.make" }.nestingDepth)
    assertTrue(anonymous.none { it.isEffectivelyPublic })
    assertNull(file.declarations.firstOrNull { it.name.contains("<anonymous>") })
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

    val (_, sections) = aggregate(analyze(), listOf("lib"), churn = null, top = 5)

    assertEquals(
      listOf("lib/a/src/commonMain/kotlin/a/A.kt:3:f", "lib/a/src/commonMain/kotlin/a/A.kt:4:f"),
      sections.largest.functionsByLines.map { it.name }.sorted(),
    )
  }
}
