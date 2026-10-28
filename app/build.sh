#!/bin/sh
# Builds MusicClock.apk without Gradle, using the Android SDK and Android Studio's JDK.
set -e
cd "$(dirname "$0")"
SDK=$HOME/Android/Sdk
BT=$SDK/build-tools/36.0.0
JAR=$SDK/platforms/android-34/android.jar
JAVA=$HOME/android-studio/jbr/bin
export PATH=$JAVA:$PATH  # d8 and apksigner need java on PATH
rm -rf build && mkdir -p build/classes build/dex

$BT/aapt2 link -o build/base.apk --manifest AndroidManifest.xml -I $JAR
$JAVA/javac --release 8 -nowarn -cp $JAR -d build/classes $(find src -name '*.java') 2>&1 | grep -v 'release 8' || true
$BT/d8 --min-api 21 --lib $JAR --output build/dex $(find build/classes -name '*.class')
cp build/base.apk build/unsigned.apk
python3 -c "import zipfile; zipfile.ZipFile('build/unsigned.apk', 'a', zipfile.ZIP_DEFLATED).write('build/dex/classes.dex', 'classes.dex')"
$BT/zipalign -f 4 build/unsigned.apk build/aligned.apk
$BT/apksigner sign --ks $HOME/.android/debug.keystore --ks-pass pass:android --out MusicClock.apk build/aligned.apk
echo "Built $(pwd)/MusicClock.apk"
