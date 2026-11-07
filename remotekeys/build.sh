#!/usr/bin/env bash
# Runs INSIDE the cleanyourtv-build container (eclipse-temurin:17 + Android SDK 34). Output: out/remotekeys.apk
set -euo pipefail
cd /workspace
BT=$ANDROID_HOME/build-tools/34.0.0; JAR=$ANDROID_HOME/platforms/android-34/android.jar
rm -rf out/build && mkdir -p out/build/classes out/build/res
$BT/aapt2 compile --dir res -o out/build/res.zip
$BT/aapt2 link -I $JAR --manifest AndroidManifest.xml -o out/build/unsigned.apk out/build/res.zip
javac -nowarn -source 8 -target 8 -cp $JAR -d out/build/classes $(find src -name '*.java') 2>&1 | grep -v 'bootstrap classpath' || true
$BT/d8 --lib $JAR --min-api 26 --output out/build $(find out/build/classes -name '*.class')
(cd out/build && $BT/aapt add unsigned.apk classes.dex >/dev/null)
$BT/zipalign -f -p 4 out/build/unsigned.apk out/build/aligned.apk
[ -f keystore.jks ] || keytool -genkeypair -keystore keystore.jks -storepass remotekeys -keypass remotekeys \
  -alias remotekeys -keyalg RSA -keysize 2048 -validity 36500 -dname "CN=gilgamezh remotekeys" >/dev/null
$BT/apksigner sign --ks keystore.jks --ks-pass pass:remotekeys --out out/remotekeys.apk out/build/aligned.apk
$BT/apksigner verify out/remotekeys.apk && ls -l out/remotekeys.apk
