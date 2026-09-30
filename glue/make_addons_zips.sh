#!/bin/bash
# Assemble the two AMXX addons zips from stage/ trees + freshly built .so files:
#   out/cstrike-addons.zip  → installed into <xash>/cstrike (shipped by cs16client APK as assets/addons.zip)
#   out/valve-addons.zip    → installed into <xash>/valve   (shipped by engine APK as assets/valve-addons.zip)
#
# The stage trees contain everything EXCEPT the native .so files, which are
# copied in from the build outputs (glue/out/...).
set -e
HERE=$(cd "$(dirname "$0")" && pwd)      # glue/
ROOT=$(dirname "$HERE")                  # repo root
STAGE="$ROOT/stage"
AMXXLIBS="$HERE/out/amxx/libs"
MMLIBS="$HERE/out/metamod"
OUT="$ROOT/out"
mkdir -p "$OUT"

install_libs() { # <stage-game-dir>
        local GAMEDIR=$1
        # metamod
        mkdir -p "$GAMEDIR/addons/metamod/dlls"
        cp "$MMLIBS/libmetamod_android_arm64.so"   "$GAMEDIR/addons/metamod/dlls/"
        cp "$MMLIBS/libmetamod_android_armv7a.so" "$GAMEDIR/addons/metamod/dlls/"
        # amxmodx core (abi-suffixed; the engine patch renames on copy to the private dir)
        mkdir -p "$GAMEDIR/addons/amxmodx/dlls" "$GAMEDIR/addons/amxmodx/modules"
        cp "$AMXXLIBS"/arm64-v8a/libmm_amxmodx.so     "$GAMEDIR/addons/amxmodx/dlls/libmm_amxmodx_arm64.so"
        cp "$AMXXLIBS"/armeabi-v7a/libmm_amxmodx.so   "$GAMEDIR/addons/amxmodx/dlls/libmm_amxmodx_armv7a.so"
        # modules
        for f in "$AMXXLIBS"/arm64-v8a/libamxx_*.so; do
                b=$(basename "$f" .so)            # libamxx_cstrike
                cp "$f" "$GAMEDIR/addons/amxmodx/modules/${b}_arm64.so"
        done
        for f in "$AMXXLIBS"/armeabi-v7a/libamxx_*.so; do
                b=$(basename "$f" .so)
                cp "$f" "$GAMEDIR/addons/amxmodx/modules/${b}_armv7a.so"
        done
}

rm -rf "$OUT/stage-cstrike" "$OUT/stage-valve"
cp -r "$STAGE/cstrike" "$OUT/stage-cstrike"
cp -r "$STAGE/valve"   "$OUT/stage-valve"

install_libs "$OUT/stage-cstrike"
install_libs "$OUT/stage-valve"

(cd "$OUT/stage-cstrike" && zip -qr9 "$OUT/cstrike-addons.zip" addons)
(cd "$OUT/stage-valve"   && zip -qr9 "$OUT/valve-addons.zip" addons)

# gameinfo.txt for valve (engine uses -dll @hl)
ls -la "$OUT"/*.zip
echo "DONE"
