# Building AnkiDroid and running it on an Android emulator

This is the reproducible setup used for **Phase 0** of the USMLE Step 1 project:
build the AnkiDroid fork from source and run it on an **Android emulator** (no
physical device). Commands below were verified on **macOS (Intel / x86_64)**;
notes for Apple Silicon and Linux are inline.

## What an emulator is

An *emulator* runs a full virtual Android phone in software on your computer:
virtual screen, storage, sensors, networking. You install and run the app on it
exactly like a real phone. A specific configured virtual phone (model + Android
version) is called an **AVD (Android Virtual Device)**. Android Studio's "Device
Manager" is just a GUI around the same `emulator` binary and AVDs used below — it
is not required.

## Prerequisites

- ~10 GB free disk space (a booted emulator wants several GB of headroom).
- Hardware acceleration: macOS uses the built-in Hypervisor.framework
  (verify with `emulator -accel-check`); Linux uses KVM; Windows uses WHPX.

## 1. Install a JDK (21)

AnkiDroid builds with a JVM between 21 and 25.

```bash
brew install openjdk@21
export JAVA_HOME="/usr/local/opt/openjdk@21"          # Apple Silicon: /opt/homebrew/opt/openjdk@21
export PATH="$JAVA_HOME/bin:$PATH"
java -version                                          # expect openjdk 21.x
```

## 2. Install the Android SDK (command-line tools)

No Android Studio needed — just the command-line tools + `sdkmanager`.

```bash
export ANDROID_HOME="$HOME/Library/Android/sdk"        # Linux: $HOME/Android/Sdk
mkdir -p "$ANDROID_HOME/cmdline-tools"
cd "$ANDROID_HOME/cmdline-tools"

# macOS download (use the linux/windows zip on other OSes):
curl -fsSL -o clt.zip \
  https://dl.google.com/android/repository/commandlinetools-mac-11076708_latest.zip
mkdir tmp && unzip -q clt.zip -d tmp && mv tmp/cmdline-tools latest && rm -rf tmp clt.zip

export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
```

Install the packages this project needs (compileSdk 36, plus an x86_64 system
image for the emulator), and accept licenses:

```bash
yes | sdkmanager --licenses
sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0" \
           "emulator" "system-images;android-35;google_apis;x86_64"
```

> Apple Silicon: use `system-images;android-35;google_apis;arm64-v8a` instead.

Point Gradle at the SDK (this file is gitignored):

```bash
echo "sdk.dir=$ANDROID_HOME" > local.properties   # run from the repo root
```

## 3. Build the debug APK

The `full` flavor is the fully open build (no Play Services). From the repo root:

```bash
./gradlew :AnkiDroid:assembleFullDebug
```

Per-ABI APKs are written to:

```
AnkiDroid/build/outputs/apk/full/debug/
  AnkiDroid-full-x86_64-debug.apk     <- use this one on an x86_64 emulator
  AnkiDroid-full-arm64-v8a-debug.apk  <- use this one on an Apple Silicon emulator
  ...
```

## 4. Create an AVD (virtual phone)

```bash
echo no | avdmanager create avd -n usmle_step1 \
  -k "system-images;android-35;google_apis;x86_64" --device "pixel_7"
emulator -list-avds        # should print: usmle_step1
```

## 5. Boot the emulator and install the app

**Normal use (with a window you can see and click):**

```bash
emulator -avd usmle_step1 &           # opens the phone window
adb wait-for-device
# wait until it finishes booting, then install + launch:
adb install -r AnkiDroid/build/outputs/apk/full/debug/AnkiDroid-full-x86_64-debug.apk
adb shell monkey -p com.ichi2.anki.debug -c android.intent.category.LAUNCHER 1
```

**Headless verification (CI / scripted, no window):**

```bash
emulator -avd usmle_step1 -no-window -no-audio -no-boot-anim -no-snapshot \
         -gpu swiftshader_indirect &
adb wait-for-device
until [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = "1" ]; do sleep 3; done
adb install -r AnkiDroid/build/outputs/apk/full/debug/AnkiDroid-full-x86_64-debug.apk
adb shell monkey -p com.ichi2.anki.debug -c android.intent.category.LAUNCHER 1
adb exec-out screencap -p > emulator-screenshot.png   # capture proof
adb emu kill                                           # shut down
```

The debug app's package id is **`com.ichi2.anki.debug`**.

## Notes / gotchas

- `JAVA_HOME` and `ANDROID_HOME` must be exported in every shell (or add them to
  `~/.zshrc`). Gradle reads `ANDROID_HOME` / `local.properties` for the SDK path.
- First emulator cold boot takes ~1 minute; subsequent boots are faster with a
  saved snapshot (drop `-no-snapshot`).
- Disk space is the most common failure: a default AVD needs ~7 GB free or the
  emulator aborts with "Not enough space to create userdata partition".
