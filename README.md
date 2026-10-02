# CS16Client + AMX Mod X for Android

Run full **AMX Mod X 1.8.3 + Metamod-P** on **Counter-Strike 1.6 (cs16client)** with the **Xash3D FWGS** engine — no root, no Termux. This repo is the complete project: patched engine + patched game + AMXX/Metamod sources + build glue + GitHub Actions CI (auto-build + arm64 emulator test).

## Build

Prerequisites: JDK 17, Android SDK, **NDK r29 (29.0.14206865)**, ninja, zip.

```bash
# 1) get the source
git clone https://github.com/amirwolf512k/cs16-amxx-android
cd cs16-amxx-android

# 2) SDK / NDK paths
export ANDROID_HOME=/path/to/android-sdk
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/29.0.14206865
echo "sdk.dir=$ANDROID_HOME" > xash3d-fwgs-master/android/local.properties
echo "sdk.dir=$ANDROID_HOME" > cs16-client-main/android/local.properties

# 3) native part: AMXX core + modules, metamod, SMA compiler, addons zips
bash scripts/build_native.sh

# 4) engine APK
cd xash3d-fwgs-master/android && ./gradlew :app:assembleContinuous && cd ../..

# 5) game APK
cd cs16-client-main/android && ./gradlew :app:assembleGitRelease && cd ../..
```

Outputs:
- engine: `xash3d-fwgs-master/android/app/build/outputs/apk/.../app-continuous.apk`
- game: `cs16-client-main/android/app/build/outputs/apk/git/release/*.apk`
- addons: `out/cstrike-addons.zip` + `out/valve-addons.zip`

Individual steps:

```bash
bash glue/build_amxx.sh         # AMXX core + modules (libmm_amxmodx + libamxx_*)
bash glue/build_metamod.sh      # libmetamod_android_<abi>.so
bash glue/build_amxxpc.sh       # libamxxpc.so (in-app .sma compiler)
bash glue/make_addons_zips.sh   # out/cstrike-addons.zip + out/valve-addons.zip
```

Signing: engine uses its auto debug keystore; game uses `cs16-client-main/android/cs16client.keystore` (store pass `xash3damxx`, alias `cs16client`).

CI: every push to `main` runs **Build APKs** (both APKs + addons zips + a Release) and then **Test arm64** (boots an arm64 emulator, installs both APKs, runs the engine with metamod → amxmodx on generated minimal test content and checks that every module/plugin loads without a crash). Telegram report secrets `TG_BOT_TOKEN` / `TG_CHAT_ID` are optional.

## How the chain works

```
Xash3D FWGS (AMXX patch in engine/common/lib_common.c)
  └─ loads metamod from addons/metamod/dlls instead of libserver
      └─ metamod (libmetamod_android_<abi>.so)
          ├─ AMX Mod X core: libmm_amxmodx.so
          │   ├─ modules: libamxx_*.so
          │   └─ plugins: addons/amxmodx/plugins/*.amxx
```

Pawn cells are 32-bit while arm64 pointers are 64-bit — handled by pointer side-table patches in `amx.cpp`.

Modules (arm64 + armv7a): amxmodx, engine, fun, fakemeta, cstrike, csx, nvault, sockets, regex, geoip, sqlite, json, hamsandwich, cs_ham_bots_api.

## What changed / added (summary)

- Full AMX Mod X + Metamod chain running inside the game on Android, with an on-device `.sma` compiler (`libamxxpc`).
- 14 AMXX modules for ARM64 + ARMv7, including hand-built hamsandwich ARM trampolines, regex/geoip/json/sqlite and **cs_ham_bots_api** (zombie plague mods now load).
- **MOTD window = the ORIGINAL xash3d-fwgs design**: a faithful 1:1 copy of the classic HL1 VGUI MOTD window (`vgui_MOTDWindow.cpp` `CMessageWindowPanel`) — translucent black window with the thin orange border (178,119,0), the **server name as a small orange title in the top-left corner**, scrollable content and the **OK button in the bottom-left corner** (160x30, PC geometry); XRES/YRES scales exactly like the PC game on widescreen (74%..94% wide on phones); **HTML MOTDs render for real** in the sandboxed WebView, plain-text MOTDs are styled with the original HL1 "Briefing Text" tan; plain-text HUD fallback only if the window can't be shown; the team select menu appears only **after** the MOTD is closed; complete `motd.txt` sample included in both packages.
- **HTML MOTD in valve (Half-Life) too**: the bundled hlsdk-portable client is patched at CI build time (`glue/hlsdk-portable-motd.patch`) to route the server MOTD through the same sandboxed dialog window as the CS client.
- **Real crash forensics**: on a native crash the engine now prints the faulting PC/LR resolved to `library + offset` straight from ucontext, the loaded module map and a best-effort stack walk into `crash.log` / `engine.log`, so a mod-induced crash (e.g. a zombie plague plugin) can be pinned to the exact module; unstripped `.so` symbols ship as CI artifacts for offset mapping.
- **Spray logo done right**: `logos/<cl_logofile>` loaded from the `logos/` folder (no forced default); `AmirWolf512` ships as a **PNG** like the built-in neocat/fwgs/blobfox/neofox logos, so the spray color option can no longer tint it (BMP logos stay colorable); indexed logos also pack as full-color WAD3 decals instead of the old monochrome gradient.
- **Voice privacy**: the microphone opens only while `+voicerecord` is held (no permanent "sending voice" indicator).
- **Server browser**: `Xash | Gold | Favorites | History` tabs — the Gold list queries live community GoldSrc master servers (`ms1.cs-exes.ru`, `valve-master-server.com`, `ms2.cs-best.org.ua`, `ms.cs16.net`) with the real A2M protocol, since Valve shut their master down; hundreds of CS 1.6 servers show up.
- English console credit banner.
- `top15` / `rank` also work in chat **without the slash** on the bundled server (easier on a phone keyboard).
- Auto-updating versioned addons installer, crash-proof Android assets JNI, LP64-safe AMX virtual machine, automatic CI tests on a real arm64 emulator.

---
Licenses follow the upstream projects (GPL).
