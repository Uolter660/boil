#!/bin/bash
# Builds a signed APK with the plain SDK tools (aapt2 + javac + dx + apksigner).
set -euo pipefail
cd "$(dirname "$0")"
SDK=/usr/lib/android-sdk
JAR=$SDK/platforms/android-23/android.jar
BT=$SDK/build-tools/debian
OUT=build
rm -rf $OUT && mkdir -p $OUT/classes $OUT/gen

aapt2 compile --dir res -o $OUT/res.zip
aapt2 link -o $OUT/base.apk -I $JAR --manifest AndroidManifest.xml $OUT/res.zip \
  --java $OUT/gen --min-sdk-version 21 --target-sdk-version 34 --version-code 7 --version-name 1.6

javac -nowarn -encoding UTF-8 -source 8 -target 8 -bootclasspath $JAR -classpath $JAR \
  -d $OUT/classes $(find src $OUT/gen -name '*.java') 2>&1 | grep -v "bootstrap class path\|^warning\|^1 warning\|options were not\|-Xlint:-options" || true
test -f $OUT/classes/com/boil/studio/EditorActivity.class

$BT/dx --dex --min-sdk-version=21 --output=$OUT/classes.dex $OUT/classes
cp $OUT/base.apk $OUT/unaligned.apk
python3 -c "import zipfile,sys; z=zipfile.ZipFile(sys.argv[1],'a',zipfile.ZIP_DEFLATED); z.write(sys.argv[2],'classes.dex'); z.close()" $OUT/unaligned.apk $OUT/classes.dex
zipalign -f -p 4 $OUT/unaligned.apk $OUT/aligned.apk

if [ ! -f boil.jks ]; then
  keytool -genkeypair -keystore boil.jks -storepass boilboil -keypass boilboil -alias boil \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Boil" >/dev/null 2>&1
fi
apksigner sign --ks boil.jks --ks-pass pass:boilboil --key-pass pass:boilboil --out Boil.apk $OUT/aligned.apk
apksigner verify Boil.apk && ls -la Boil.apk
