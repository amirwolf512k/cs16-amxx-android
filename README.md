# CS16Client + AMX Mod X — اندروید (نسخه ۱۰)

اجرای کامل **AMX Mod X + Metamod** روی **Counter-Strike 1.6 اندروید** با موتور **Xash3D FWGS** — بدون Termux، بدون روت.

این ریپو «پروژه کامل» است: موتور پچ‌شده + بازی پچ‌شده + سورس AMX Mod X + Metamod-P + اسکریپت‌های بیلد + **GitHub Actions برای کامپایل خودکار و تست کامل روی arm64**.

---

## ساختار ریپو

| پوشه | توضیح |
|------|-------|
| `xash3d-fwgs-master/` | موتور Xash3D FWGS با پچ AMXX (نسخه ۱۰) — `engine/common/lib_common.c` بخش `AMX MOD X SUPPORT` |
| `cs16-client-main/` | بازی cs16client (Velaron) پچ‌شده — پکیج `su.xash.cs16clientamxx` |
| `amxmodx-FWGS/` | سورس AMX Mod X 1.8.3 (فورک FWGS/a1ba) با پچ ماشین مجازی ۶۴ بیتی |
| `metamod-p-velaron/` | سورس Metamod-P 1.21p38 (فورک Velaron اندروید) |
| `metamod-fwgs/` | فورک متامد a1ba — هدرهای SDK متامد + hlsdk برای کامپایل AMXX |
| `filesystem_stdio_xash/` | فایل‌سیستم stdio موتور |
| `glue/` | فایل‌های چسب ndk-build (Android.mk و اسکریپت‌های بیلد) |
| `stage/` | درخت فایل‌های addons (کانفیگ‌ها/پلاگین‌ها/scripting) برای ساخت addons.zip |
| `scripts/` | اسکریپت‌های بیلد و تولید محتوای تست |
| `.github/workflows/` | **کامپایل و تست خودکار** |

## GitHub Actions — کامپایل خودکار

### build.yml — بیلد APK
هر push به `main` به‌صورت خودکار:
1. موتور + metamod + amxmodx + کامپایلر SMA را برای arm64 و armv7a می‌سازد
2. APK موتور (`Xash3D FWGS (AMXX)`) و APK بازی (`cs16client (amxx)`) را می‌سازد
3. دو zip افزونه‌ها (cstrike/valve) را می‌سازد
4. همه را به‌عنوان **Artifact** آپلود می‌کند و در `main` یک **Release** می‌سازد

اجبار: `Actions → Build APKs → Run workflow` هم می‌شود دستی اجرا کرد.

### test-arm64.yml — تست کامل arm64
بعد از هر بیلد موفق خودش اجرا می‌شود (یا دستی):
1. **شبیه‌ساز اندروید arm64** را بوت می‌کند (روی رانر `ubuntu-24.04-arm`)
2. هر دو APK را نصب می‌کند
3. یک **بسته محتوای تست مینیمال** می‌سازد: نقشه تولیدشده + مدل‌های جایگزین + addons کامل — **بدون فایل‌های کپی‌رایت‌دار بازی**
4. موتور را با زنجیره `metamod → amxmodx → ReGameDLL/YaPB` اجرا می‌کند: `+map amxx_test`
5. لاگ‌ها را چک می‌کند:
   - لود metamod و amxmodx
   - اتصال همه ماژول‌ها (بدون `was left pending`)
   - لود پلاگین‌ها (بدون `failed to load`)
   - اجرای `amxx.cfg` (شامل `amx_scrollmsg` — همان مسیری که قبلاً کرش می‌داد)
   - بدون `Crash: signal`
6. لاگ کامل + tombstone (اگر کرش شود) را به Artifact اضافه و نتیجه را به تلگرام می‌فرستد

### سِکرِت‌های تلگرام (اختیاری)
در تنظیمات ریپو: `Settings → Secrets and variables → Actions`:
- `TG_BOT_TOKEN` — توکن بات
- `TG_CHAT_ID` — چت آیدی

## بیلد محلی (روی PC لینوکس)

پیش‌نیاز: JDK 17، Android SDK، **NDK r29 (29.0.14206865)**، ninja، zip

```bash
# 1) سورس را بگیرید
git clone https://github.com/<user>/cs16-amxx-android
cd cs16-amxx-android

# 2) مسیر SDK و NDK
export ANDROID_HOME=/path/to/android-sdk
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/29.0.14206865
echo "sdk.dir=$ANDROID_HOME" > xash3d-fwgs-master/android/local.properties
echo "sdk.dir=$ANDROID_HOME" > cs16-client-main/android/local.properties

# 3) بیلد native (amxmodx + metamod + amxxpc + staging)
bash scripts/build_native.sh

# 4) بیلد موتور
cd xash3d-fwgs-master/android && ./gradlew :app:assembleContinuous && cd ../..

# 5) بیلد بازی
cd cs16-client-main/android && ./gradlew :app:assembleGitRelease && cd ../..

# خروجی‌ها:
# xash3d-fwgs-master/android/app/build/outputs/apk/.../app-continuous.apk
# cs16-client-main/android/app/build/outputs/apk/git/release/*.apk
```

### بیلد تک‌تک قطعات

```bash
bash glue/build_amxx.sh      # هسته AMXX + ۷ ماژول (libmm_amxmodx + libamxx_*)
bash glue/build_metamod.sh   # libmetamod_android_<abi>.so
bash glue/build_amxxpc.sh    # libamxxpc.so (کامپایلر SMA درون‌اپ)
bash glue/make_addons_zips.sh  # out/cstrike-addons.zip + out/valve-addons.zip
```

## معماری زنجیره (چطور کار می‌کند)

```
Xash3D FWGS (پچ COM_AMXX_Setup در lib_common.c)
  └─ به‌جای libserver، متامد را از addons/metamod/dlls لود می‌کند
       (فایل‌های .so از پوشه بازی به دایرکتوری خصوصی برنامه کپی می‌شوند
        چون اندرویدهای جدید dlopen از حافظه اشتراکی را ممنوع می‌کنند)
       └─ metamod (libmetamod_android_<abi>.so)
            ├─ gamedll اصلی: libyapb_android_arm64.so (ReGameDLL + YaPB)
            └─ AMX Mod X core: libmm_amxmodx.so (addons/amxmodx/dlls)
                 ├─ ماژول‌ها: libamxx_{fun,engine,fakemeta,cstrike,csx,nvault,sockets}.so
                 └─ پلاگین‌های .amxx از addons/amxmodx/plugins
```

نکته مهم v10: سلول‌های Pawn اندازه ۳۲ بیت دارند ولی پوینترهای arm64 ۶۴ بیتی — پچ‌های
`amx.cpp` (`AMX_CELL_TOO_SMALL`) این را با جدول جانبی پوینترها حل می‌کنند.

## امضاها

- موتور: `xash3d-fwgs-master/android/debug.keystore` (خودکار)
- بازی: `cs16-client-main/android/cs16client.keystore` — پسورد: `xash3damxx` (alias: `cs16client`)

## یادداشت‌های نسخه ۱۰

- فیکس اتصال ماژول‌های متامد (fun/engine/fakemeta/cstrike/csx):
  `attachMetamod` دیگر هندل ماژول را هنگام شکست LOAD_PLUGIN صفر نمی‌کند + دلیل شکست لاگ می‌شود + تلاش مجدد هنگام ورود نقشه
- زنجیره بیلد کامل روی GitHub Actions — بدون نیاز به هیچ ماشین محلی
- تست خودکار arm64 با شبیه‌ساز
- کل سورس شامل 3rdpartyها (به‌جز تست‌های SDL و traceهای gl4es) در همین ریپوست

---
• لایسنس‌ها مطابق پروژه‌های اصلی (GPL)
