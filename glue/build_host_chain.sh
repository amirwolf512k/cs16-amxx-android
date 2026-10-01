#!/bin/bash
# Build the AMXX chain for the HOST (linux, arm64 or x86_64) so the CI can
# run the REAL metamod -> amxmodx -> modules -> plugins chain natively on a
# real arm64 runner (same 64-bit-pointer/32-bit-cell code paths as the phone).
#
# Trick: everything is compiled with -D__ANDROID__ so the exact Android code
# paths (MM_GAMELIBDIR module loading, libamxx_<name>.so naming) are used —
# the binaries are real Linux ELF shared objects for the host arch.
set -e
HERE=$(cd "$(dirname "$0")/.." && pwd)/glue
ROOT=$(dirname "$HERE")
AMXX="$ROOT/amxmodx-FWGS"
MM="$ROOT/metamod-fwgs"
HLSDK="$MM/hlsdk"
FSINC="$ROOT/filesystem_stdio_xash/include"
OUT="$ROOT/out/host"
BUILD=$(uname -m)
mkdir -p "$OUT/modules"

CXX=${CXX:-g++}
CC=${CC:-gcc}
COMMON="-O2 -g -fPIC -fpermissive -Wno-error -w -D__ANDROID__ -DLINUX -DHAVE_STDINT_H \
  -include $HERE/compat_types.h \
  -Dstricmp=strcasecmp -D_stricmp=strcasecmp -Dstrnicmp=strncasecmp -D_snprintf=snprintf -D_vsnprintf=vsnprintf \
  -D_INCLUDE_SEQUENCE_H_ -D_GNU_SOURCE -std=gnu++11 \
  -I$AMXX/public/sdk -I$AMXX/public -I$AMXX/public/memtools -I$AMXX/public/amtl -I$AMXX/public/amtl/amtl \
  -I$AMXX/third_party/hashing -I$AMXX/third_party/utf8rewind -I$AMXX/third_party/pcre -I$AMXX/third_party/zlib \
  -I$AMXX/third_party/parson -I$AMXX/third_party -I$HERE -I$FSINC \
  -I$HLSDK/dlls -I$HLSDK/public -I$HLSDK/common -I$HLSDK/engine -I$HLSDK/pm_shared -I$MM/metamod -I$AMXX/amxmodx -I$HERE/android_shim"

echo "== building third_party static bits (hashing + utf8rewind) =="
HASHING=$(find $AMXX/third_party/hashing -name '*.c*' | grep -v test)
UTF8="$AMXX/third_party/utf8rewind/utf8rewind.c $AMXX/third_party/utf8rewind/unicodedatabase.c $AMXX/third_party/utf8rewind/internal/casemapping.c $AMXX/third_party/utf8rewind/internal/codepoint.c $AMXX/third_party/utf8rewind/internal/composition.c $AMXX/third_party/utf8rewind/internal/database.c $AMXX/third_party/utf8rewind/internal/decomposition.c $AMXX/third_party/utf8rewind/internal/seeking.c $AMXX/third_party/utf8rewind/internal/streaming.c"
THIRDPARTY_O=""
mkdir -p "$OUT/tp"
for f in $HASHING $UTF8; do
        o="$OUT/tp/$(basename $f | tr '/.' '__').o"
        $CXX $COMMON -c -o "$o" "$f"
        THIRDPARTY_O="$THIRDPARTY_O $o"
done

echo "== building amxmodx core =="
CORE="CCmd CDataPack CEvent CFlagManager CForward CGameConfigs CLang CLibrarySys CLogEvent CMenu CMisc CModule CPlugin CTask CTextParsers CVault CoreConfig CvarManager amx amxcore amxdbg amxmodx amxtime amxxfile amxxlog cvars datapacks datastructs debugger emsg fakemeta file float format gameconfigs libraries messages meta_api modules natives newmenus nongpl_matches optimizer power sorting srvcmd stackstructs string strptime textparse trie_natives util vault vector MemoryUtils detours asm mod_rehlds_api"
mkdir -p "$OUT/core"
CORE_O=""
for s in $CORE; do
        for src in "$AMXX/amxmodx/$s.cpp" "$AMXX/amxmodx/$s.c" "$AMXX/public/memtools/$s.cpp" "$AMXX/public/memtools/CDetour/$s.cpp" "$AMXX/public/memtools/CDetour/asm/$s.c" "$AMXX/public/resdk/$s.cpp"; do
                if [ -f "$src" ]; then
                        o="$OUT/core/$s.o"
                        $CXX $COMMON -c -o "$o" "$src"
                        CORE_O="$CORE_O $o"
                        break
                fi
        done
done
$CXX $COMMON -shared -o "$OUT/libmm_amxmodx.so" $CORE_O $THIRDPARTY_O -lz -lpthread -ldl
echo "  => $OUT/libmm_amxmodx.so"

echo "== building modules =="
build_module() { # <name> <dir> <srcs...>
        local name=$1 dir=$2; shift 2
        local objs=""
        mkdir -p "$OUT/obj_$name"
        set -- "$@" amxxmodule MemoryUtils   # SDK glue is part of every module
        for s in "$@"; do
                for src in "$AMXX/modules/$dir/$s.cpp" "$AMXX/public/sdk/$s.cpp" "$AMXX/public/memtools/MemoryUtils.cpp"; do
                        if [ -f "$src" ]; then
                                o="$OUT/obj_$name/$(basename $s)_$name.o"
                                $CXX $COMMON -I"$AMXX/modules/$dir" -c -o "$o" "$src"
                                objs="$objs $o"
                                break
                        fi
                done
        done
        $CXX $COMMON -shared -o "$OUT/modules/libamxx_$name.so" $objs $THIRDPARTY_O
        echo "  => libamxx_$name.so"
}

build_module fun fun fun
build_module engine engine amxxapi engine entity forwards globals
build_module fakemeta fakemeta dllfunc engfunc fakemeta_amxx fm_tr fm_tr2 forward glb misc pdata pdata_entities pdata_gamerules pev
build_module cstrike cstrike/cstrike CstrikeHacks CstrikeItemsInfos CstrikeMain CstrikeNatives CstrikePlayer CstrikeUserMessages CstrikeUtils
build_module csx cstrike/csx CMisc CRank meta_api rank usermsg
build_module nvault nvault Binary Journal NVault amxxapi
build_module sockets sockets sockets

echo "== building metamod (host) =="
MMSRC="$ROOT/metamod-p-velaron/metamod"
mkdir -p "$OUT/obj_mm"
MMFLAGS="-O2 -g -fPIC -std=gnu++98 -fpermissive -fno-exceptions -fno-rtti -w -D__METAMOD_BUILD__ -DCOMPILE_TZ=\"UTC\" -D__ANDROID__ \
  -I$MMSRC -I$HLSDK/engine -I$HLSDK/common -I$HLSDK/dlls -I$HLSDK/pm_shared -I$HLSDK"
MMOBJS=""
for s in api_hook api_info commands_meta conf_meta dllapi engine_api engineinfo game_support game_autodetect \
        h_export linkgame linkplug log_meta meta_eiface metamod mlist mplayer mplugin mqueue mreg mutil osdep \
        osdep_p reg_support sdk_util studioapi support_meta thread_logparse vdate \
        osdep_linkent_linux osdep_detect_gamedll_linux xash_exports; do
        $CXX $MMFLAGS -c -o "$OUT/obj_mm/$s.o" "$MMSRC/$s.cpp"
        MMOBJS="$MMOBJS $OUT/obj_mm/$s.o"
done
$CXX -shared -o "$OUT/libmetamod_android_$BUILD.so" $MMOBJS -ldl -lm
echo "  => libmetamod_android_$BUILD.so"

echo "== hlsdk-portable server (gamedll) =="
if [ ! -f "$OUT/libserver.so" ]; then
        cmake -S "$ROOT/xash3d-fwgs-master/3rdparty/hlsdk-portable" -B "$OUT/hlsdk-build" \
                -DCMAKE_BUILD_TYPE=Release -DBUILD_CLIENT=OFF -DBUILD_LIBMENU=OFF -DBUILD_GAMEUI=OFF > /dev/null
        cmake --build "$OUT/hlsdk-build" -j"$(nproc)" > /dev/null 2>&1 || cmake --build "$OUT/hlsdk-build" -j"$(nproc)"
find "$OUT/hlsdk-build" -name "libserver.so" -exec cp {} "$OUT/libserver.so" \;
fi
ls -la "$OUT"/lib*.so "$OUT"/modules/ | head -15
echo "HOST BUILD DONE ($BUILD)"
