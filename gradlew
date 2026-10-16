#!/bin/sh
#
# Gradle start-up script for POSIX shells (Linux / macOS / Codemagic).
# It starts the small wrapper jar (gradle/wrapper/gradle-wrapper.jar) which downloads
# the Gradle version written in gradle/wrapper/gradle-wrapper.properties.

app_path=$0
# Resolve symlinks
while [ -h "$app_path" ]; do
    ls=$( ls -ld "$app_path" )
    link=${ls#*' -> '}
    case $link in
      /*) app_path=$link ;;
      *)  app_path=${app_path%/*}/$link ;;
    esac
done

case $app_path in
  */*) app_dir=${app_path%/*} ;;
  *)   app_dir=. ;;
esac
APP_HOME=$( cd "$app_dir" > /dev/null && pwd -P ) || exit 1

CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar
if [ ! -f "$CLASSPATH" ]; then
    echo "ERROR: $CLASSPATH is missing. Add gradle/wrapper/gradle-wrapper.jar to the repository (see README step 2)." >&2
    exit 1
fi

if [ -n "$JAVA_HOME" ]; then
    if [ -x "$JAVA_HOME/jre/sh/java" ]; then JAVACMD=$JAVA_HOME/jre/sh/java; else JAVACMD=$JAVA_HOME/bin/java; fi
    if [ ! -x "$JAVACMD" ]; then
        echo "ERROR: JAVA_HOME is set to an invalid directory: $JAVA_HOME" >&2
        exit 1
    fi
else
    JAVACMD=java
    if ! command -v java >/dev/null 2>&1; then
        echo "ERROR: JAVA_HOME is not set and no 'java' command could be found." >&2
        exit 1
    fi
fi

exec "$JAVACMD" -Xmx64m -Xms64m $JAVA_OPTS $GRADLE_OPTS \
    "-Dorg.gradle.appname=gradlew" \
    -classpath "$CLASSPATH" \
    org.gradle.wrapper.GradleWrapperMain "$@"
