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

Modules (arm64 + armv7a): amxmodx, engine, fun, fakemeta, cstrike, csx, nvault, sockets, regex, geoip, sqlite, json, hamsandwich.

## What changed / added (summary)

- Full AMX Mod X + Metamod chain running inside the game on Android, with an on-device `.sma` compiler (`libamxxpc`).
- 13 AMXX modules for ARM64 + ARMv7, including hand-built hamsandwich ARM trampolines and regex/geoip/json/sqlite.
- **HTML MOTD like PC**: rendered in a sandboxed WebView dialog with an **OK button at the bottom** (covers `motd.txt` and `/top15`), with a plain-text fallback if the dialog can't be shown; complete `motd.txt` sample included in both packages.
- **Spray logo done right**: `logos/<cl_logofile>.bmp` loaded from the `logos/` folder (no forced default), shipped `AmirWolf512.bmp`, converted to a full-color WAD3 tempdecal — the old gradient path that tinted the whole logo monochrome is gone.
- **Voice privacy**: the microphone opens only while `+voicerecord` is held (no permanent "sending voice" indicator).
- **Server browser**: `Xash | Gold | Favorites | History` tabs — the Gold list is queried from the Valve GoldSrc master server (`hl1master.steamcontent.com`), the same internet list PC CS 1.6 uses.
- English console credit banner.
- Auto-updating versioned addons installer, crash-proof Android assets JNI, LP64-safe AMX virtual machine, automatic CI tests on a real arm64 emulator.

---
Licenses follow the upstream projects (GPL).
