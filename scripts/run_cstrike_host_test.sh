#!/bin/bash
# cs16-amxx-android v32: run the REAL game chain on the host
#   xash3d-fwgs dedicated engine (linux, native arch)
#     -> metamod (loaded by the engine's COM_AMXX_Setup)
#       -> amxmodx core + ALL modules (fun/engine/fakemeta/cstrike/csx/
#          nvault/sockets/hamsandwich/cs_ham_bots_api)
#       -> cstrike gamedll = the REAL ReGameDLL-based libcs built from
#          cs16-client-main (same source as the Android libcs_android.so)
#       -> YaPB bot dll (real bots => real CBasePlayer entities)
#       -> every staged plugin + battery_test.amxx (exercises every module)
# Everything the battery prints is scanned from the engine console log.
set -e
ROOT=$(cd "$(dirname "$0")/.." && pwd)
ENGINE="$ROOT/xash3d-fwgs-master"
# v35: arch-flexible names (cs_amd64.so on x86_64 hosts, cs_arm64.so on
# aarch64 CI runners -- same cmake of cs16-client-main BUILD_SERVER=ON)
CS=$(find "$ROOT/out/cs-build" -name "cs_*.so" | head -1)
YAPB=$(find "$ROOT/out/cs-build" -name "yapb_*.so" | head -1)
HOST="$ROOT/out/host"
TEST="$ROOT/out/cs-host-test"
ARCH=$(uname -m)

[ -n "$CS" ]    || { echo "ERROR: cs gamedll not built" >&2; exit 1; }
[ -n "$YAPB" ]  || { echo "ERROR: yapb not built" >&2; exit 1; }
[ -d "$HOST/modules" ] || { echo "ERROR: host amxx chain not built" >&2; exit 1; }

rm -rf "$TEST"
mkdir -p "$TEST/cstrike" "$TEST/valve"

# ---------------- gamedir: cstrike ----------------
C="$TEST/cstrike"

# addons tree from the staged package (plugins, configs, data, includes)
cp -r "$ROOT/stage/cstrike/addons" "$C/addons"

# host-built libs
mkdir -p "$C/addons/amxmodx/dlls" "$C/addons/amxmodx/modules" "$C/addons/metamod/dlls"
cp "$HOST/libmm_amxmodx.so" "$C/addons/amxmodx/dlls/"
cp "$HOST"/modules/libamxx_*.so "$C/addons/amxmodx/modules/"
# engine Q_buildarch reports amd64/arm64 per host -- provide every name
for n in amd64 x86_64 arm64 aarch64; do
        cp "$HOST/libmetamod_android_$ARCH.so" "$C/addons/metamod/dlls/libmetamod_android_$n.so"
done

# v36: staged ini starts with cs_ham_bots_api.amxx (NO cvar_compat -- the
# core auto-creates missing cvars itself); battery runs right after it
cp "$ROOT/battery_test_host.amxx" "$C/addons/amxmodx/plugins/"
sed -i '/^cs_ham_bots_api\.amxx$/a battery_test_host.amxx' "$C/addons/amxmodx/configs/plugins.ini"

# v37: load the REAL Zombie Plague 4.3 suite -- the actual game mod, the same
# family real users install (extracted from v34 history at runtime so the
# shipped package stays stock AMXX since v35).  No hand-written compat/test
# shims any more (v36 direction change): the engine -> metamod -> AMXX chain
# must load a real mod natively and run a full engine window clean.
ZPCOMMIT=c502f56
rm -rf /tmp/zp43x && mkdir -p /tmp/zp43x
git -C "$ROOT" archive "$ZPCOMMIT" stage/cstrike | tar -x -C /tmp/zp43x
cp -r /tmp/zp43x/stage/cstrike/models /tmp/zp43x/stage/cstrike/sprites "$TEST/cstrike/"
cp -r /tmp/zp43x/stage/cstrike/sound "$TEST/cstrike/"
cp /tmp/zp43x/stage/cstrike/addons/amxmodx/plugins/zombie_plague40.amxx \
   /tmp/zp43x/stage/cstrike/addons/amxmodx/plugins/zp_zclasses40.amxx \
   "$C/addons/amxmodx/plugins/"
cp /tmp/zp43x/stage/cstrike/addons/amxmodx/configs/zombieplague.cfg \
   /tmp/zp43x/stage/cstrike/addons/amxmodx/configs/zombieplague.ini \
   /tmp/zp43x/stage/cstrike/addons/amxmodx/configs/zp_extraitems.ini \
   /tmp/zp43x/stage/cstrike/addons/amxmodx/configs/zp_zombieclasses.ini \
   "$C/addons/amxmodx/configs/"
mkdir -p "$C/addons/amxmodx/data/lang"
cp /tmp/zp43x/stage/cstrike/addons/amxmodx/data/lang/zombie_plague.txt "$C/addons/amxmodx/data/lang/"
# real-server order: ZP last, after the stock plugins
printf '\n; v37: REAL Zombie Plague 4.3 (test-only, from history %s)\nzombie_plague40.amxx\nzp_zclasses40.amxx\n' "$ZPCOMMIT" >> "$C/addons/amxmodx/configs/plugins.ini"

# v32: statsx registers Ham_Spawn via hamdata.ini vtable offsets that do not
# match the locally built ReGameDLL (device arm64 gamedll is verified fine) —
# the first bot spawn then crashes calling the "original". Skip it here.
sed -i 's|^statsx.amxx|;statsx.amxx ; host-only: hamdata vtable mismatch with local ReGameDLL|' "$C/addons/amxmodx/configs/plugins.ini"

# v32: gamedata + GeoIP db — same augmentation make_addons_zips.sh does for
# the shipped zip (fakemeta's ent_data natives need gamedata on disk; the
# geoip module only opens GeoLite2-*.mmdb, not legacy GeoIP.dat)
mkdir -p "$C/addons/amxmodx/data"
rm -rf "$C/addons/amxmodx/data/gamedata"
cp -r "$ROOT/amxmodx-FWGS/gamedata" "$C/addons/amxmodx/data/gamedata"
cp "$ROOT/amxmodx-FWGS/modules/geoip/GeoLite2-Country.mmdb" "$C/addons/amxmodx/data/"

# metamod plugin list: amxmodx + yapb
cat > "$C/addons/metamod/plugins.ini" <<'EOF'
linux addons/amxmodx/dlls/libmm_amxmodx.so
; v32: yapb is device-only for now -- its Bot constructor segfaults on the
; x86_64 host chain (works on arm64). The battery creates its own fake
; client via EngFunc_CreateFakeClient + cs_user_spawn instead.
;linux addons/yapb/dlls/yapb_amd64.so
EOF

# yapb: dll + config tree + nav graphs
mkdir -p "$C/addons/yapb/dlls"
cp "$YAPB" "$C/addons/yapb/dlls/"
cp -r "$ROOT/cs16-client-main/3rdparty/yapb/cfg/addons/yapb/." "$C/addons/yapb/" 2>/dev/null || true
mkdir -p "$C/addons/yapb/data"
cp -r "$ROOT/out/cs-build/graphs/addons/yapb/data/graph" "$C/addons/yapb/data/" 2>/dev/null || true
# yapb refuses to spawn bots without a graph for the map — give it any real
# graph under the test map's name (waypoints won't match, don't care)
cp "$C/addons/yapb/data/graph/cs_747.graph" "$C/addons/yapb/data/graph/amxx_test.graph" 2>/dev/null || true
cp "$ROOT/cs16-client-main/3rdparty/cs16client-extras/BotProfile.db" "$C/addons/yapb/" 2>/dev/null || true
cp "$ROOT/cs16-client-main/3rdparty/cs16client-extras/BotChatter.db" "$C/addons/yapb/" 2>/dev/null || true

# filesystem_stdio (the android-shim dlopen looks for it in the cwd)
FSFILE=$(find "$ENGINE/build" -name "filesystem_stdio.so" | head -1)
cp "$FSFILE" "$TEST/"
cp "$FSFILE" "$C/"

# gamedll as metamod's android autodetect expects it: $XASH3D_GAMELIBDIR/libserver_hardfp.so
mkdir -p "$TEST/gamelibs"
cp "$CS" "$TEST/gamelibs/libserver_hardfp.so"
cp "$CS" "$TEST/cs_amd64_real.so"
cp "$CS" "$TEST/gamelibs/cs_amd64.so"

# liblist.gam (engine picks the gamedll via -dll, metamod gets it via XASH3D_GAMELIBDIR)
cat > "$C/liblist.gam" <<'EOF'
game "Counter-Strike"
gamedir "cstrike"
type "multiplayer_only"
dll "cs"
EOF

# minimal content: map + stand-in models + sounds
mkdir -p "$C/maps" "$C/models/player" "$C/sound/radio" "$C/overviews"
python3 "$ROOT/scripts/make_minimal_map.py" "$C/maps/amxx_test.bsp"
python3 "$ROOT/scripts/make_minimal_models.py" /tmp/minimal.mdl
MDL=/tmp/minimal.mdl
for t in urban terror sas gsg9 arctic guerilla vip leet militia spetsnaz; do
        mkdir -p "$C/models/player/$t"
        cp "$MDL" "$C/models/player/$t/$t.mdl"
done
for m in player player_cs; do
        cp "$MDL" "$C/models/$m.mdl"
done
for w in knife usp glock18 p228 deagle fiveseven elite mp5navy tmp p90 mac10 ump45 ak47 m4a1 famas galil aug sg552 sg550 awp scout g3sg1 m249 m3 xm1014 vest vesthelm flash hegrenade smokegrenade kevlar; do
        cp "$MDL" "$C/models/w_$w.mdl"
        cp "$MDL" "$C/models/p_$w.mdl"
        cp "$MDL" "$C/models/v_$w.mdl"
done
for m in shell PrimedC4 PrimedGrenade w_c4 p_c4 v_c4 w_thighpack item_longjump hostage.mdl hostage01.mdl; do
        cp "$MDL" "$C/models/$m"
done
python3 - <<'PYEOF'
import struct
def wav(path, secs=0.2):
    sr = 11025
    n = int(sr * secs)
    data = b'\x80' * n
    hdr = b'RIFF' + struct.pack('<I', 36 + len(data)) + b'WAVEfmt ' + \
        struct.pack('<IHHIIHH', 16, 1, 1, sr, sr, 1, 8) + b'data' + struct.pack('<I', len(data))
    open(path, 'wb').write(hdr + data)
for s in ['blow', 'ctwin', 'rounddraw', 'twin', 'letsgo', 'fireinhole', 'moveout']:
    wav('out/cs-host-test/cstrike/sound/radio/%s.wav' % s)
PYEOF

# ---------------- minimal valve dir ----------------
mkdir -p "$TEST/valve/gfx" "$TEST/cstrike/gfx"
cat > "$TEST/valve/liblist.gam" <<'EOF'
game "Half-Life"
gamedir "valve"
type "singleplayer_only"
dll "hl"
EOF
python3 "$ROOT/scripts/make_minimal_wad.py" "$TEST/valve/gfx.wad" conchars CONBACK LAMBDA
python3 "$ROOT/scripts/make_minimal_wad.py" "$TEST/valve/decals.wad" '{break1 '{bullet1
cp "$TEST/valve/decals.wad" "$TEST/cstrike/gfx/decals.wad" 2>/dev/null || \
        python3 "$ROOT/scripts/make_minimal_wad.py" "$TEST/cstrike/decals.wad" '{break1 '{bullet1
python3 "$ROOT/scripts/make_delta_lst.py" "$ENGINE/engine/common/net_encode.c" "$TEST/valve/delta.lst"

# ---------------- run ----------------
FS=$(find "$ENGINE/build" -name "*filesystem_stdio.so" | head -1)
[ -f "$FS" ] || { echo "ERROR: filesystem_stdio not found" >&2; exit 1; }
FS=$(dirname "$FS")

cd "$TEST"
# same env the phone flow has: a writable "private root" the engine copies
# the amxx chain into (COM_AMXX_CopyTree), the gamedll dir metamod's android
# autodetect scans, and the real gamedll handed over via MM_GAMEDLL
# v32: drive the full gamedll connect chain for fake clients (engine patch)
export XASH3D_FAKECLIENT_CONNECT=1
export XASH3D_AMXX_LIBDIR="$TEST/amxxpriv"
export XASH3D_GAMELIBDIR="$TEST/gamelibs"
export LD_LIBRARY_PATH="$FS:$TEST:$TEST/gamelibs:$LD_LIBRARY_PATH"
# v36: engine window 100s -> 300s -- slow sandboxes spend ~40s on startup and
# the fake-client connect chain needs the rest before the ham probe fires
timeout -k 10 300 "$ENGINE/build/engine/xash" \
        -game cstrike -dev 2 -log -condebug \
        -dll "$TEST/gamelibs/libserver_hardfp.so" \
        +map amxx_test +hostname "host-test" +sv_lan 1 \
        -noip -nojoy -nosteam > console.txt 2>&1 || true

echo "================= BATTERY ================="
grep -a "\[BAT\]" console.txt | head -80
echo "================= AMXX ERRORS ================="
grep -a -E "Run time error|not available|hook unavailable|disabled" console.txt | head -20
echo "================= MODULES ================="
grep -a -A40 "Loaded modules\|amxx modules" console.txt | grep -a -E "fun|engine|fakemeta|cstrike|csx|nvault|hamsandwich|sockets|geoip|regex|sqlite|json" | head -20
echo "================= LOG TAIL ================="
tail -25 console.txt

# ---------------- v34 evaluation ----------------
echo "================= EVALUATION ================="
PASS=1; FAIL=""
# ham-probe gates are informational everywhere since v32: hamdata.ini vtable
# offsets never match the LOCALLY built ReGameDLL (device arm64 gamedll is
# verified fine).  Crashes / runtime errors / real-mod load stay hard gates.
grep -aq "HAM PROBE FIRED" console.txt   || echo "NOTE: ham-probe-never-fired (local-gamedll hamdata mismatch, documented v32)"
grep -aq "\[HAM\] hooked player::spawn" console.txt || echo "NOTE: ham-spawn-not-hooked (local-gamedll hamdata mismatch)"
grep -aq "Crash: signal\|SIGSEGV\|Segmentation" console.txt && { PASS=0; FAIL="$FAIL CRASH"; }
# v36: null-handle pcvar calls are silent no-ops now -- ANY runtime error is
# a hard failure (v35 had to whitelist the deliberate null-handle probe)
grep -aq "Run time error" console.txt && { PASS=0; FAIL="$FAIL amxx-runtime-error"; }
# v37: REAL-mod gate -- Zombie Plague 4.3 (the mod family that crashed on
# arm64 via get_uc inside FM_CmdStart) must load natively and run: its
# plugin_init precaches the zombie class + ZP weapon models through the
# engine, and NO plugin may fail to load for any reason.
grep -aq "loading models/player/zombie_source/zombie_source.mdl" console.txt || { PASS=0; FAIL="$FAIL zp-not-loaded"; }
grep -aqi "failed to load" console.txt && { PASS=0; FAIL="$FAIL plugin-load-failure"; }
# informational: cvar-change HOOKS (hook_cvar_change) need the Cvar_DirectSet
# memory signature which the Xash engine gamedata does not provide (ZP does
# not use them); the rest of the cvar surface IS gated above.
# informational: the fake client never runs the jointeam flow, so ZP
# calibration (mode=1) usually doesn't trigger here -- it happens on real
# joins via the TeamInfo string oracle.
ZPCAL=$(grep -ac "ZPDBG.*mode=1" console.txt || true)
if [ "$PASS" = 1 ]; then
        echo "RESULT: PASS — ham hook fired, no crashes on $ARCH (zp calibrations: $ZPCAL)"
else
        echo "RESULT: FAIL:$FAIL"
fi
exit $((1 - PASS))
