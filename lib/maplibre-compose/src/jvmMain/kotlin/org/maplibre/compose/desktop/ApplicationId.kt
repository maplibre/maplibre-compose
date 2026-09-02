package org.maplibre.compose.desktop

/** Package of the process `main` class, used as the default runtime application ID. */
internal fun inferredApplicationId(): String {
  val className = mainClassName()
  val id = className?.let(::applicationIdFromClassName)
  return id
    ?: throw IllegalStateException(
      "Could not infer applicationId from the process main class" +
        (className?.let { " '$it'" } ?: "") +
        ". Create a runtime with MapRuntimeOptions(applicationId = \"com.example.myapp\")."
    )
}

internal fun applicationIdFromClassName(className: String): String? {
  val pkg = className.substringBeforeLast('.', missingDelimiterValue = "")
  return pkg.takeIf { it.isNotEmpty() && APPLICATION_ID.matches(it) }
}

internal fun mainClassName(): String? = mainClassNameFromStackTraces(Thread.getAllStackTraces())

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
