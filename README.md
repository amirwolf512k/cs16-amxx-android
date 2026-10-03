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

- **v34: the release engine APK had NO working MOTD dialog (plain-text HUD fallback) — fixed.** The engine `proguard-rules.pro` keep-rule still listed the v20 signature `boolean showMOTD(byte[])`, while the real v30+ method is `showMOTD(byte[], byte[])`. R8 therefore stripped the method from every *release* build (it has no Java callers — it is resolved via JNI `GetMethodID`), the engine logged `showMOTD=0x0` and fell back to plain HUD text: no window, no OK button. The keep rule now matches the real signature; debug/continuous builds were never affected.
- **v34: hamsandwich trampoline fixes (zombie infection depends on it).** Ham hooks patch the gamedll's C++ vtables and need one page of executable memory per hook. (1) The non-ARM fallback of our libffcall shim **returned the callback itself** as the "trampoline", so the `hook` global was never populated and the first hook fire dereferenced NULL — this was the real cause of the "ZP + fake client hangs the host test" note in v32/v33 (fixed with a real x86_64 SysV backend; other arches now return NULL + log instead of a guaranteed crash). (2) The Android 10+ memfd path now maps RW → memcpy → `__clear_cache` → `mprotect(RX)`; clearing the cache *after* switching to RX-only can fault on aarch64 (`IC IVAU` needs store permission). (3) Hook install now logs `[HAM] hooked <class>::<func> ... (patch verified)` and the first hook fire logs once — the v33 device log showed ZERO hook fires, so any remaining device-side failure will name itself in the log.
- **v34: the bundled Zombie Plague configs/language were from a different fork — replaced.** The v33 package shipped the fix5a `zombieplague.cfg/.ini`, `zp_extraitems.ini`, `zp_zombieclasses.ini` and `zombie_plague.txt` (assassin/sniper keys like `zp_assa_allow_respawn`) next to the fix6a plugin: the server console spammed `Unknown command zp_...` on every map load and menus referenced missing language keys (`MENU_EXTRA`, `MOTD_INFO` → `ML_NOTFOUND` garbage). All five files now come from the same fix6a source as the compiled plugins, and `zp_zclasses40.sma`/`zombieplague.inc` were re-synced to fix6a (the disconnect-guard from fix5a is kept).
- **v34: ZP join-time "Run time error 10 (cs_get_user_team)" eliminated.** `TeamInfo` fires *before* `ClientPutInServer`, so calibrating from the message hook called the team native on a not-yet-ingame player and aborted the hook. The calibration oracle is now the team string the game just announced in that very message (`get_msg_arg_string(2)`) — zero error-capable natives during the join race; the spawn-post path still uses `cs_get_user_team` (safe there and it doubles as a cross-check).
- **v34: the host test now gates on the Ham pipeline itself.** The battery plugin registers `Ham_Spawn` (post) and the runner FAILS unless the probe fires on the fake client's spawn (`[HAMPROBE] spawn_post ...`) — the exact layer the v33 device log proved silently dead on the phone.
- **v33: Zombie Plague 4.3 is fixed on the 64-bit (arm64) gamedll and ships with the package.** Root cause of "nobody becomes a zombie / buy menu never appears / money & deaths broken": ZP 4.3 hardcodes classic x86 CBasePlayer pdata offsets (`OFFSET_CSTEAMS 114+5`, `OFFSET_CSMENUCODE 205+5`, `OFFSET_CSDEATHS 444+5`, ...) while the device gamedll is the cs16-client ReGameDLL fork built for arm64, where the real fields live at completely different offsets (`m_iTeam=576`, `m_iMenu=964`, `m_iDeaths=1992`, `m_modelIndexPlayer=2180`, `m_pActiveItem=1696`, `CBasePlayerItem::m_pPlayer=232`, ... — measured with `offsetof` from the same source and cross-checked against the amxmodx `offsets-cstrike-replugged.arm64.txt` gamedata). The shipped `zombie_plague40.amxx` now **self-calibrates at runtime**: with the first team-assigned player it compares the pdata reads against the gamedata-driven `cs_get_user_team()` oracle and switches the whole offset table to the LP64 set (classic values on 32-bit builds, so the plugin stays portable); the pain-shock write is skipped on the LP64 layout (field removed in the fork). `zp_zclasses40.amxx`, configs, language file and all zombie resources are bundled and enabled by default.
- **v33: AMXX core `param_convert` fixed for 64-bit builds** — the dynamic-native (register_native) string argument conversion truncated each 64-bit pointer to 32 bits *before* subtracting, producing a garbage delta whenever the two plugin heaps land more than 4 GiB apart; the difference is now computed on the full pointers and truncated once (what the 32-bit pawn cell actually needs).
- **v33: MOTD failure forensics on the engine side** — `Android_ShowMOTD` prints *why* it refused the dialog (JNI not ready / engine app too old for HTML MOTD, allocation failure, pending JNI exception) instead of a bare `MOTD dialog failed`; a pending JNI exception no longer discards an already-shown dialog. This pins down the "MOTD comes as plain text" reports: the most common cause is an outdated engine app whose `XashActivity` predates the HTML MOTD Java API — update both APKs together.
- **v33: team select menu no longer covers the MOTD fallback** — when the platform dialog is unavailable and the MOTD renders as HUD text (its strip-HTML fallback), the team select menu is now held back until the fallback MOTD is dismissed (+attack), exactly like the dialog flow (`menu.cpp` deferral + `HUD_Frame` release condition extended with `gHUD.m_MOTD.m_bShow`).
- Full AMX Mod X + Metamod chain running inside the game on Android, with an on-device `.sma` compiler (`libamxxpc`).
- 14 AMXX modules for ARM64 + ARMv7, including hand-built hamsandwich ARM trampolines, regex/geoip/json/sqlite and **cs_ham_bots_api** (zombie plague mods now load).
- **MOTD window = the ORIGINAL xash3d-fwgs design**: a faithful 1:1 copy of the classic HL1 VGUI MOTD window (`vgui_MOTDWindow.cpp` `CMessageWindowPanel`) — translucent black window with the thin orange border (178,119,0), the **server name as a small orange title in the top-left corner**, scrollable content and the **OK button in the bottom-left corner** (160x30, PC geometry); XRES/YRES scales exactly like the PC game on widescreen (74%..94% wide on phones); **HTML MOTDs render for real** in the sandboxed WebView, plain-text MOTDs are styled with the original HL1 "Briefing Text" tan; plain-text HUD fallback only if the window can't be shown; the team select menu appears only **after** the MOTD is closed; complete `motd.txt` sample included in both packages.
- **v31: AMX Mod X and YaPB are independent switches** (Game settings): both can be enabled at the same time (metamod loads both from `plugins.ini`); turning bots on/off no longer touches AMX Mod X and vice versa, and bots only join when the **YaPB** switch is on. Each switch gates its own `plugins.ini` line through a marker file (`addons/metamod/{amxmodx,yapb}.disabled`), so the setting also survives direct launches of the game app.
- **v32: BaseBuilder plugins are no longer bundled** (user request). The v31 copies (including the still-crashing `bbzones.amxx`) are removed from the package AND deleted from the game folder on update, so your own ProBaseBuilder install runs clean.
- **v32: the whole game chain is now verified by a REAL run on a live server** (`scripts/run_cstrike_host_test.sh`): xash3d-fwgs dedicated -> metamod -> AMXX + all 13 modules -> the real ReGameDLL-based CS gamedll built from this repo -> a real ingame player (engine fake client driven through the full gamedll connect chain) -> a battery plugin that exercises every module's natives. Current result: **32 PASS / 0 FAIL**.
- **v32: fakemeta `pev()` byref int reads fixed** — the FWGS fork converted int fields (movetype/solid/rendermode/body/...) to float bits on read (`amx_ftoc`), silently breaking plugins that read back int entvars (BaseBuilder grab, zombie mods). Raw ints are returned now.
- **v32: cstrike module matches its official build** (`-DNO_HACKS`): `cs_set_user_model` works on Xash3D (pure-engfuncs model update path; HLDS `svs`/`sv` globals do not exist in Xash and their ReHLDS struct layout must not be used), and `cs_set_user_model(.., update_index=true)` returns success via `ModelIndex` instead of erroring — zombie/team model changes apply properly.
- **v32: fixed the crash on every server stop/changelevel** — the AMXX core's Xash3D fallback marked the (never created) `SV_DropClient` detour as available, so `Meta_Detach` called `NULL->Destroy()` (SIGSEGV in `CDetour::Destroy` on "starting new server").
- **v32: MOTD hardening** — if the sandboxed WebView can't be created, the dialog falls back to a styled `Html.fromHtml` TextView (still returns success, so the client never degrades to raw-text HUD garbage); every Java-side failure now prints into the game console (`nativeConsolePrintf`) instead of vanishing into logcat; the HUD fallback strips `<style>`/`<script>` bodies so no raw CSS garbage shows.
- **v32: server browser refresh restarts the list** — the visible model is flushed on every refresh (LAN + internet), so re-queried servers no longer get appended after the old rows.
- **v31: unambiguous release APK names** — the game APK ships as `cs16client-*.apk` (it used to be `app-git-release.apk`, indistinguishable from an engine build, so client-side updates were easily missed).
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
