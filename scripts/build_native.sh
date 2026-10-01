#!/bin/bash
# Master native build for CI (and local): builds amxmodx, metamod and the
# SMA compiler, then populates the gradle projects:
#   - cs16client jniLibs (libamxx_* + libmm_amxmodx + libmetamod per ABI)
#   - cs16client assets (base content from cs16client-extras + addons.zip)
#   - engine jniLibs (libamxxpc)
#   - engine assets (valve-addons.zip)
#   - out/*.zip (standalone addons packages)
set -e
ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"

echo "==================================================="
echo "== 1/4 amxmodx core + modules"
echo "==================================================="
bash glue/build_amxx.sh

echo "==================================================="
echo "== 2/4 metamod"
echo "==================================================="
bash glue/build_metamod.sh

echo "==================================================="
echo "== 3/4 libamxxpc (SMA compiler)"
echo "==================================================="
bash glue/build_amxxpc.sh

echo "==================================================="
echo "== 4/4 staging"
echo "==================================================="
bash glue/make_addons_zips.sh

CS16="$ROOT/cs16-client-main/android/app/src/main"
ENGINE="$ROOT/xash3d-fwgs-master/android/app/src/main"

# ---- cs16client jniLibs: unsuffixed names per ABI dir (engine's APK-path
# ---- fallback + the game's nativeLibraryDir both look these names up)
mkdir -p "$CS16/jniLibs/arm64-v8a" "$CS16/jniLibs/armeabi-v7a"
for abi in arm64-v8a armeabi-v7a; do
        cp glue/out/amxx/libs/$abi/libamxx_*.so          "$CS16/jniLibs/$abi/"
        cp glue/out/amxx/libs/$abi/libmm_amxmodx.so      "$CS16/jniLibs/$abi/"
done
cp glue/out/metamod/libmetamod_android_arm64.so   "$CS16/jniLibs/arm64-v8a/"
cp glue/out/metamod/libmetamod_android_armv7a.so  "$CS16/jniLibs/armeabi-v7a/"

# ---- cs16client assets: base content + addons.zip
mkdir -p "$CS16/assets"
cp -r cs16-client-main/3rdparty/cs16client-extras/. "$CS16/assets/"
rm -rf "$CS16/assets/addons"   # never ship the extracted dir, only the zip
cp out/cstrike-addons.zip "$CS16/assets/addons.zip"
echo "amxx-v10-repo" > "$CS16/assets/addons_version.txt"

# ---- engine jniLibs: libamxxpc
mkdir -p "$ENGINE/jniLibs/arm64-v8a" "$ENGINE/jniLibs/armeabi-v7a"
cp glue/out/amxxpc/libs/arm64-v8a/libamxxpc.so     "$ENGINE/jniLibs/arm64-v8a/"
cp glue/out/amxxpc/libs/armeabi-v7a/libamxxpc.so   "$ENGINE/jniLibs/armeabi-v7a/"

# ---- engine assets: valve addons zip
mkdir -p "$ENGINE/assets"
cp out/valve-addons.zip "$ENGINE/assets/valve-addons.zip"

echo ">> staging complete"
ls -la "$CS16/jniLibs/arm64-v8a" "$ENGINE/jniLibs/arm64-v8a" | head -20
