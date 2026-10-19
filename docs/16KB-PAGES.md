# 16 KB page sizes

Android 15 and newer can run with 16 KB memory pages instead of 4 KB. A native
library built for 4 KB pages cannot be loaded on such a device, and Google Play
requires 16 KB support for apps targeting Android 15+.

> **Since #126 the Go binding is gone**, and with it `libgojni.so` and
> `scripts/bind-aar.sh`. Every native library left in the APK comes from an upstream
> AAR and reports `align 0x4000` (measured 2026-10-19 with `llvm-readelf -l` on all
> 18 `.so` files: sherpa-onnx, onnxruntime, litertlm), and the debug APK passes
> `zipalign -c -P 16 4`. The rest of this page is the history of the fix.

## What was wrong

`libgojni.so` (gomobile's Go binding) was linked by NDK r25, which aligns `LOAD`
segments to `0x1000` (4 KB). Android 17 on the Pixel 7 showed the compatibility
dialog — a warning there, because that phone runs 4 KB pages, but a hard failure
on a 16 KB device.

The sherpa-onnx AAR was already correct: its four `.so` files report `align
0x4000`. Only our Go binding was the gap.

## The fix

`scripts/bind-aar.sh` now exports

```
CGO_LDFLAGS="-Wl,-z,max-page-size=16384"
```

around `gomobile bind`. NDK r25's clang honours it on the external link. No NDK
bump was needed, which keeps the toolchain pin and every measured number intact.

## Proof

1. **Every ABI reports 16 KB** (`llvm-readelf -l` on each `libgojni.so`):
   `arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86` — all `0x4000`.
2. **The APK is aligned**: `zipalign -c -P 16 4 <apk>` exits 0.
3. **It runs on a real 16 KB system image**: the `android-35;google_apis_ps16k;x86_64`
   emulator reports `getconf PAGE_SIZE` = 16384, installs the APK, and logcat
   shows
   `nativeloader: Load …/lib/x86_64/libgojni.so … : ok`.
   The app reaches its onboarding screen with no compatibility dialog.

## Re-testing

```sh
# one-time
sdkmanager "system-images;android-35;google_apis_ps16k;x86_64"
avdmanager create avd -n vox16k -k "system-images;android-35;google_apis_ps16k;x86_64" --device pixel_7
emulator -avd vox16k -no-window -no-audio -no-snapshot -gpu swiftshader_indirect &

adb -s emulator-5554 shell getconf PAGE_SIZE      # must print 16384
cd android && ./gradlew assembleDebug             # debug carries all four ABIs
adb -s emulator-5554 install -r app/build/outputs/apk/debug/*.apk
adb -s emulator-5554 logcat -d | grep libgojni    # "… : ok"
```

The debug build includes `x86_64`; the release keeps `arm64-v8a` and
`armeabi-v7a` only.

## Reproducibility

The flag changes every native binary, so the two-build comparison in
`scripts/repro-check.sh` was re-run after it. All four architectures remain
byte-identical; the AAR digest moved to
`d9754393a2f4e16bd2923dcc6329e98c4aa8863a7602a0e31d2289bf745ea65b`, which is the
expected consequence of linking differently, not of losing determinism.
