#!/bin/bash
# Assemble the host test environment, run the dedicated engine with the full
# AMXX chain and evaluate the log. This is the arm64 CI "full test":
# engine(patch) -> metamod -> amxmodx -> modules -> plugins -> map spawn.
set -e
ROOT=$(cd "$(dirname "$0")/.." && pwd)
ENGINE="$ROOT/xash3d-fwgs-master"
OUT="$ROOT/out/host"
TEST="$ROOT/out/host-test"
ARCH=$(uname -m)
rm -rf "$TEST"
mkdir -p "$TEST/valve"

# ---- content: valve gamedir ----
cp -r "$ROOT/stage/valve/addons" "$TEST/valve/addons"
# the committed stage tree has no dlls/modules dirs (libs are added here)
mkdir -p "$TEST/valve/addons/amxmodx/dlls" "$TEST/valve/addons/amxmodx/modules"
cp "$OUT/libmm_amxmodx.so" "$TEST/valve/addons/amxmodx/dlls/"
cp "$OUT"/modules/libamxx_*.so "$TEST/valve/addons/amxmodx/modules/"
mkdir -p "$TEST/valve/addons/metamod/dlls"
# engine's Q_buildarch may report amd64/arm64 depending on the host — provide all names
for n in amd64 x86_64 arm64 aarch64; do
        cp "$OUT/libmetamod_android_$ARCH.so" "$TEST/valve/addons/metamod/dlls/libmetamod_android_$n.so"
done

# host test: cstrike/csx/fakemeta modules reference ReGameDLL API symbols
# which only exist when the real CS gamedll is used; keep the HL test chain
# to modules that resolve against the HL gamedll.
sed -i -e '/^fakemeta$/d' "$TEST/valve/addons/amxmodx/configs/modules.ini"

# test map + stand-in models
mkdir -p "$TEST/valve/maps" "$TEST/valve/models" "$TEST/valve/gfx"
python3 "$ROOT/scripts/make_minimal_map.py" "$TEST/valve/maps/amxx_test.bsp"
python3 "$ROOT/scripts/make_minimal_models.py" "$TEST/valve/models/player.mdl"
python3 "$ROOT/scripts/make_minimal_wad.py" "$TEST/valve/gfx.wad" conchars CONBACK LAMBDA
python3 "$ROOT/scripts/make_minimal_wad.py" "$TEST/valve/decals.wad" '{break1 '{bullet1
python3 "$ROOT/scripts/make_delta_lst.py" "$ENGINE/engine/common/net_encode.c" "$TEST/valve/delta.lst"

# exercise the exact v8/v9 crash path: amx_scrollmsg server command
# (plugin_srvcmd -> executeForwards -> amx_Exec -> amx_Callback -> set_task)
# the AMXX core execs amxx.cfg itself after plugins load — same as the phone flow
printf 'amx_scrollmsg "AMXX arm64 CI test message" 10\namx_scrollmsg "second message" 12\n' \
        > "$TEST/valve/addons/amxmodx/configs/amxx.cfg"

# gamedll (libhl built by waf) + liblist.gam
GAMELIB=$(find "$ENGINE/3rdparty/hlsdk-portable/build" -name "hl_*.so" | head -1)
if [ -z "$GAMELIB" ]; then
        echo "ERROR: hl gamedll not built" >&2; exit 1
fi
cp "$GAMELIB" "$TEST/"
# metamod autodetects the real gamedll from the gamedir (dlls/<name>.so)
mkdir -p "$TEST/valve/dlls"
for n in hl_amd64.so hl_i386.so hl_arm64.so libserver.so; do
        cp "$GAMELIB" "$TEST/valve/dlls/$n"
done
cat > "$TEST/valve/liblist.gam" <<'EOF'
game "Half-Life"
gamedir "valve"
type "singleplayer_only"
dll "hl"
EOF

# ---- run ----
cd "$TEST"
# metamod compiles with -D__ANDROID__ (exact phone code paths), so its
# gamedll autodetection goes through XASH3D_GAMELIBDIR looking for
# libserver_hardfp.so — same mechanism as the phone (where GAMELIBDIR is
# the game APK's nativeLibraryDir). Provide it, or the chain runs with no
# gamedll and every entity fails with "No spawn function".
mkdir -p "$TEST/gamelibs"
cp "$GAMELIB" "$TEST/gamelibs/libserver_hardfp.so"
export XASH3D_GAMELIBDIR="$TEST/gamelibs"
export XASH3D_AMXX_LIBDIR="$TEST/amxxpriv"
export LD_LIBRARY_PATH="$ENGINE/build/filesystem:$ENGINE/3rdparty/hlsdk-portable/build/dlls:$LD_LIBRARY_PATH"
timeout 60 "$ENGINE/build/engine/xash" \
        -dev 2 -log -condebug \
        -dll "$TEST/$(basename "$GAMELIB")" \
        +map amxx_test +meta list \
        -noip -nojoy -nosteam > console.txt 2>&1 || true

echo "================= LOG (tail) ================="
tail -40 console.txt
echo "================= EVALUATION ================="
PASS=1; FAIL=""
grep -q "Metamod version" console.txt           || { PASS=0; FAIL="$FAIL metamod-not-loaded"; }
grep -q "AMX Mod X version" console.txt         || { PASS=0; FAIL="$FAIL amxmodx-not-loaded"; }
grep -q "Mapchange to amxx_test\|Spawn Server" console.txt || { PASS=0; FAIL="$FAIL map-not-spawned"; }
grep -q "Scrolling message" console.txt         || { PASS=0; FAIL="$FAIL scrollmsg-not-run"; }
grep -q "Crash: signal\|SIGSEGV\|Segmentation" console.txt && { PASS=0; FAIL="$FAIL CRASH"; }
grep -q "was left pending" console.txt          && { PASS=0; FAIL="$FAIL module-left-pending"; }
grep -q "failed to load: Module" console.txt    && { PASS=0; FAIL="$FAIL plugin-module-missing"; }
# v15: modules must actually ATTACH to metamod (LOAD_PLUGIN with an
# absolute path used to fail in resolve() and silently left every module
# unattached -> NULL api tables -> SIGSEGV in OnPluginsLoaded at spawn).
grep -q "registration failed\|LOAD_PLUGIN failed" console.txt && { PASS=0; FAIL="$FAIL module-metamod-attach"; }
MODULE_PLUGINS=$(grep -cE '\] .*RUN' console.txt || true)
[ "$MODULE_PLUGINS" -ge 3 ] || { PASS=0; FAIL="$FAIL meta-list-only-$MODULE_PLUGINS-plugins"; }
if [ "$PASS" = 1 ]; then echo "RESULT: PASS — full AMXX chain works on $ARCH ($MODULE_PLUGINS plugins incl. modules)"; else echo "RESULT: FAIL:$FAIL"; fi
exit $((1 - PASS))
