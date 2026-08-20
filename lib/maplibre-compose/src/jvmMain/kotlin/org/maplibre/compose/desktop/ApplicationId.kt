package org.maplibre.compose.desktop

/**
 * Derives the desktop cache namespace from the process `main` class.
 *
 * The package of that class is the default [MapLibre.configure] `applicationId`. Callers that move
 * `main` or share a package across apps should pass `applicationId` explicitly.
 */
internal fun inferredApplicationId(): String {
  val className = mainClassName()
  val id = className?.let(::applicationIdFromClassName)
  return id
    ?: throw IllegalStateException(
      "Could not infer applicationId from the process main class" +
        (className?.let { " '$it'" } ?: "") +
        ". Pass one explicitly: MapLibre.configure(applicationId = \"com.example.myapp\")."
    )
}

internal fun applicationIdFromClassName(className: String): String? {
  val pkg = className.substringBeforeLast('.', missingDelimiterValue = "")
  return pkg.takeIf { it.isNotEmpty() && APPLICATION_ID.matches(it) }
}

internal fun mainClassName(): String? =
  mainClassNameFromStackTraces(Thread.getAllStackTraces())
    ?: mainClassNameFromJavaCommand(System.getProperty("sun.java.command"))

internal fun mainClassNameFromStackTraces(traces: Map<Thread, Array<StackTraceElement>>): String? {
  val preferred = traces.entries.firstOrNull { it.key.name == "main" }
  val stacks = buildList {
    if (preferred != null) add(preferred.value)
    for ((thread, frames) in traces) {
      if (thread !== preferred?.key) add(frames)
    }
  }
  for (frames in stacks) {
    mainClassNameFromFrames(frames.asList())?.let {
      return it
    }
  }
  return null
}

internal fun mainClassNameFromFrames(frames: List<StackTraceElement>): String? =
  frames
    .asReversed()
    .firstOrNull { frame -> frame.methodName == "main" && isApplicationClass(frame.className) }
    ?.className

internal fun mainClassNameFromJavaCommand(command: String?): String? {
  val token = command?.trim()?.substringBefore(' ')?.takeIf { it.isNotEmpty() } ?: return null
  if ('/' in token || '\\' in token) return null
  if (token.endsWith(".jar", ignoreCase = true)) return null
  return token.takeIf(::isApplicationClass)
}

internal fun isApplicationClass(className: String): Boolean = LAUNCHER_PREFIXES.none {
  className.startsWith(it)
}

internal val APPLICATION_ID = Regex("[A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]+)*")

private val LAUNCHER_PREFIXES =
  arrayOf(
    "java.",
    "javax.",
    "jdk.",
    "sun.",
    "com.sun.",
    "kotlin.",
    "kotlinx.",
    "org.junit.",
    "org.gradle.",
    "worker.org.gradle.",
    "com.intellij.rt.",
  )
