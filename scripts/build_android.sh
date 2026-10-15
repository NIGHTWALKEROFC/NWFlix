#!/usr/bin/env bash
# Used by codemagic.yaml. Variables:  ABI = arm64-v8a | armeabi-v7a | x86_64 | universal   GRADLE_TASK = assembleRelease | bundleRelease
set -euo pipefail

ABI="${ABI:-universal}"
TASK="${GRADLE_TASK:-assembleRelease}"

# The Gradle "wrapper" (gradlew + a small .jar) is not stored in this repo, so create it here.
if [ ! -f gradle/wrapper/gradle-wrapper.jar ] || [ ! -f gradlew ]; then
  echo ">> Creating Gradle wrapper"
  curl -fsSL https://services.gradle.org/distributions/gradle-8.10.2-bin.zip -o /tmp/gradle.zip
  unzip -q /tmp/gradle.zip -d /tmp
  /tmp/gradle-8.10.2/bin/gradle wrapper --gradle-version 8.10.2 --distribution-type bin
fi
chmod +x ./gradlew

# Tell Gradle where the Android SDK is (Codemagic already sets ANDROID_SDK_ROOT).
if [ ! -f local.properties ]; then
  echo "sdk.dir=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}" > local.properties
fi

echo ">> Building: task=$TASK abi=$ABI"
./gradlew --no-daemon "$TASK" -Pabi="$ABI"
