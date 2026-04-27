#!/bin/sh

set -eu

if [ "YES" = "${OVERRIDE_KOTLIN_BUILD_IDE_SUPPORTED:-}" ]; then
  echo "Skipping Gradle build task invocation due to OVERRIDE_KOTLIN_BUILD_IDE_SUPPORTED environment variable set to \"YES\""
  exit 0
fi

if ! java -version >/dev/null 2>&1 && [ -z "${JAVA_HOME:-}" ]; then
  if [ -x "$HOME/.sdkman/candidates/java/current/bin/java" ]; then
    export JAVA_HOME="$HOME/.sdkman/candidates/java/current"
  elif [ -x "/usr/libexec/java_home" ]; then
    JAVA_HOME_CANDIDATE="$(/usr/libexec/java_home 2>/dev/null || true)"
    if [ -n "$JAVA_HOME_CANDIDATE" ]; then
      export JAVA_HOME="$JAVA_HOME_CANDIDATE"
    fi
  fi
fi

if [ -n "${JAVA_HOME:-}" ]; then
  export PATH="$JAVA_HOME/bin:$PATH"
fi

if ! java -version >/dev/null 2>&1; then
  echo "Java runtime not found. Set JAVA_HOME or install a JDK so Gradle can run from Xcode."
  exit 1
fi

cd "$SRCROOT/.."
exec ./gradlew :demo-app:embedAndSignAppleFrameworkForXcode
