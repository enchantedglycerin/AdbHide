# AdbHide

An LSPosed module that hides ADB and developer-mode settings from apps while keeping USB debugging available.

| Check | Result seen by apps |
| --- | --- |
| USB debugging | Disabled |
| Wireless debugging | Disabled |
| Developer options | Disabled |

AdbHide runs in Android System and does not load code into an apps.

## Install

1. Install the APK.
2. Enable AdbHide in LSPosed.
3. Select **System Framework** as the only scope (Do not select any target apps).
4. Reboot.

Requires Android 8.0 or newer and a working LSPosed installation. The correct scope is suggested automatically.

## Build

JDK 17 and the Android SDK are required.

```sh
./gradlew assembleDebug
```

On Windows, use `gradlew.bat assembleDebug`. The APK will be in `app/build/outputs/apk/debug/`.

## License

[MIT](LICENSE)
