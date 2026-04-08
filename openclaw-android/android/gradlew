#!/bin/sh

#
# Gradle wrapper script - downloads Gradle if not present and runs it.
#

# Resolve links and find the script directory
APP_NAME="Gradle"
APP_BASE_NAME=$(basename "$0")
MAX_FD="maximum"

warn () {
    echo "$*"
} >&2

die () {
    echo
    echo "$*"
    echo
    exit 1
} >&2

# Determine the Java command to use to start the JVM
if [ -n "$JAVA_HOME" ] ; then
    if [ -x "$JAVA_HOME/jre/sh/java" ] ; then
        JAVACMD="$JAVA_HOME/jre/sh/java"
    else
        JAVACMD="$JAVA_HOME/bin/java"
    fi
    if [ ! -x "$JAVACMD" ] ; then
        die "ERROR: JAVA_HOME is set to an invalid directory: $JAVA_HOME"
    fi
else
    JAVACMD="java"
    which java >/dev/null 2>&1 || die "ERROR: JAVA_HOME is not set and no 'java' command found in PATH."
fi

# Setup the classpath
SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
CLASSPATH="$SCRIPT_DIR/gradle/wrapper/gradle-wrapper.jar"

# Determine the Gradle distribution URL from properties
WRAPPER_PROPERTIES="$SCRIPT_DIR/gradle/wrapper/gradle-wrapper.properties"

if [ ! -f "$WRAPPER_PROPERTIES" ]; then
    die "ERROR: Gradle wrapper properties not found at $WRAPPER_PROPERTIES"
fi

# If the wrapper jar doesn't exist, download it
if [ ! -f "$CLASSPATH" ]; then
    echo "Downloading Gradle wrapper jar..."
    WRAPPER_JAR_URL="https://raw.githubusercontent.com/gradle/gradle/v8.11.1/gradle/wrapper/gradle-wrapper.jar"
    if command -v curl >/dev/null 2>&1; then
        curl -fsSL -o "$CLASSPATH" "$WRAPPER_JAR_URL" || die "Failed to download gradle-wrapper.jar"
    elif command -v wget >/dev/null 2>&1; then
        wget -q -O "$CLASSPATH" "$WRAPPER_JAR_URL" || die "Failed to download gradle-wrapper.jar"
    else
        die "ERROR: Neither curl nor wget found. Install one of them or download gradle-wrapper.jar manually."
    fi
fi

exec "$JAVACMD" \
    $JAVA_OPTS \
    -classpath "$CLASSPATH" \
    org.gradle.wrapper.GradleWrapperMain \
    "$@"
