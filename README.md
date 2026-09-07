# GT8 LED Diagnostic

Android diagnostic app for inspecting camera flash/torch hardware exposed through Android's public Camera2 APIs.

## What it tests

- Enumerates all public camera IDs exposed by the phone.
- Shows lens facing, focal lengths, logical/physical camera IDs, and flash availability.
- Allows torch ON/OFF for every flash-capable camera ID.
- On Android 13+ (API 33+), shows and tests supported torch-strength levels.
- Performs a real still-camera `FLASH_MODE_SINGLE` capture and logs the reported `FLASH_STATE`.
- Generates a copyable diagnostic report.

## Important limitation

Android normally exposes a flash unit per camera ID, not individual physical emitters inside a dual-tone/multi-LED module. If Realme groups two physical LEDs behind one logical flash controller, a normal third-party APK cannot independently select LED A vs LED B. Comparing **Torch ON** with **Fire SINGLE camera flash** can still reveal whether the firmware drives the hardware differently in those modes.

## Privacy

The app has no Internet permission and does not upload or transmit diagnostic data. Images captured during the single-flash test are immediately discarded and are not saved.

## Build

A GitHub Actions workflow builds a debug APK automatically. Open **Actions** in this repository, choose **Build Android APK**, and download the `GT8-LED-Diagnostic-debug-apk` artifact from a successful run.
