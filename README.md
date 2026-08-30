# Fastfetch Android

<p align="center">
  <strong>Fastfetch-style system information, natively on Android.</strong>
</p>

<p align="center">
  <img alt="Android 7.1+" src="https://img.shields.io/badge/Android-7.1%2B-3DDC84?logo=android&logoColor=white">
  <img alt="Version 1.0" src="https://img.shields.io/badge/version-1.0-blue">
  <img alt="Supported ABIs" src="https://img.shields.io/badge/ABIs-arm64%20%7C%20arm32%20%7C%20x86%20%7C%20x86__64-informational">
  <img alt="Kotlin and C++" src="https://img.shields.io/badge/Kotlin%20%2B%20C%2B%2B-native-7F52FF?logo=kotlin&logoColor=white">
</p>

Fastfetch Android brings detailed, Fastfetch-style device information to Android with a native app that works across phones, tablets, watches, TVs, VR headsets, emulators, and Android-on-PC setups.

It currently supports **Android 7.1 (API 25) and above** on `arm64-v8a`, `armeabi-v7a`, `x86`, and `x86_64`.

## Features

Fastfetch Android can report information including:

- Device, Android version, build, kernel, uptime, architecture, and runtime
- Display resolution, refresh rate, density, and orientation
- SoC, CPU, GPU, OpenGL ES, Vulkan, RAM, swap, and storage
- Network information including IPv4, IPv6, and DNS when available
- Battery status, temperature, voltage, power source, and thermal information
- SELinux, bootloader state, Verified Boot, and root status
- Device features such as Wi-Fi, Bluetooth, camera, touch, and GPS
- Multiple output modes, including compact, detailed, minimal, and watch-friendly layouts
- Customizable modules, output order, fonts, Android logo visibility, and automatic refresh
- Copy and share support for generated system information

## Download / Installation

Check the [GitHub Releases page](https://github.com/Kaydn913/Fastfetch-Android/releases) for packaged builds.

To build it yourself, clone the repository, open it in Android Studio, and build the `app` module.

### Requirements

- Android 7.1 / API 25 or newer
- One of the following ABIs:
  - `arm64-v8a`
  - `armeabi-v7a`
  - `x86`
  - `x86_64`

## Supported Platforms

| Platform | Status |
| --- | --- |
| Android phones | Tested |
| Android tablets | Tested |
| Wear OS watches | Tested |
| Android / Google TV | Tested |
| Android-based VR headsets | Tested |
| Android emulators | Tested |
| Android on PC | Tested |
| Android Automotive | Not tested yet |
| Android-based smart displays | Not tested yet |

## Tested Devices

<details open>
<summary><strong>Phones</strong></summary>

### Motorola

- Motorola Moto Z4
- Motorola moto g stylus - 2025

### Google Pixel

- Google Pixel 7 Pro

### Samsung

- Samsung Galaxy S9 (SM-G960U)
- Samsung Galaxy S21 (SM-G991U1)
- Samsung Galaxy A20e (SM-A202F)
- Samsung Galaxy S22+ (SM-S906U1)
- Samsung Galaxy Note 8 (SM-N950U)
- Samsung Galaxy Z Flip 7 (SM-F766U)
- Samsung Galaxy Z Fold2 5G (SM-F916B)

### Realme

- Realme GT 7T (RMX5085)

### Xiaomi

- Xiaomi 12T 5G (22071212AG)
- Xiaomi Redmi 9A (M2006C3LG)
- Xiaomi Redmi Note 14 4G (24117RN76E)
- Xiaomi Redmi Note 15 5G (25098RA98G)

### BLU

- BLU View 5 (B160V)

</details>

<details>
<summary><strong>Tablets</strong></summary>

### Samsung

- Samsung Galaxy Tab A11+ (SM-X230)
- Samsung Galaxy Tab S9 FE (SM-X516B)

### Lenovo

- Lenovo Tab M10 HD (2nd Gen) (TB-X306F)

### Xiaomi

- Xiaomi Redmi Pad SE 8.7 Wi-Fi (24075RP89G)

</details>

<details>
<summary><strong>VR headsets, watches, and TV devices</strong></summary>

### VR headsets

- Meta Quest 3S

### Watches

- Google Pixel Watch 2

### TV boxes

- onn. Full HD Streaming Device

</details>

<details>
<summary><strong>Emulators</strong></summary>

### BlueStacks

- BlueStacks emulator

### LDPlayer

- LDPlayer emulator

### Virtual Master

- Android 7.1.2 (arm32)
- Android 7.1.2 (arm64)
- Android 9.0 (arm64)
- Android 11.0 (arm64)

> Virtual Master-specific detection is not implemented yet.

</details>

<details>
<summary><strong>Android on PC</strong></summary>

### Bliss OS

- Bliss OS 14.10.3 (Android 11, x86_64)
  - VMware
  - Native hardware (MSI Thin A15 B7UC-473US)

### ChromeOS / ARCVM

- ChromeOS Android 13 (x86_64, ARCVM)
  - Native hardware (HP Chromebook 11 G9 EE / `dedede`)

### LineageOS TV x86_64

- LineageOS 21 (Android 14, x86_64, VMware)

> Native Bliss OS and ChromeOS ARCVM currently report their device type as `Tablet` because PC/laptop detection has not been implemented yet.

</details>

### Other Devices

These devices and platforms may also work, but have not been tested yet:

- Android Automotive
- Other Android-based VR headsets
- Other Android / Google TV devices
- Other Android watches
- Other Android tablets
- Other Android phones
- Android-based smart displays

## Screenshots

<p>
  <img src="screenshots/scrcpy_uLbBE72UBq.png" width="31%" alt="Fastfetch Android on Google Pixel 7 Pro">
  <img src="screenshots/scrcpy_Tf0RQxX0ci.png" width="31%" alt="Fastfetch Android on Samsung Galaxy S22+">
  <img src="screenshots/scrcpy_DyQDBGx4Z3.png" width="31%" alt="Fastfetch Android on Samsung Galaxy Tab A11+">
</p>

More screenshots are available in the **[full screenshot gallery](SCREENSHOTS.md)**, including Wear OS, Android TV, VR, emulators, Android on PC, and the settings interface.

## Known Limitations / Roadmap

- Virtual Master-specific emulator detection is not implemented yet.
- Native Android-on-PC installations and ChromeOS ARCVM can currently be classified as tablets.
- Some security or system information may be restricted by Android or the device manufacturer and may appear as unavailable or unknown.
- More Android form factors and emulator environments can be added as they are tested.

## Bug Reports

If you find a bug, please report what happened and include your device model and Android version. Screenshots or logs are also helpful when available.

You can report bugs through the repository's [Issues page](https://github.com/Kaydn913/Fastfetch-Android/issues).

## Device Testing

Want to try Fastfetch Android on something that is not listed above? Go right ahead!

If it works, let me know what device or platform you tested it on and I can add it to the tested devices list.

## Suggestions / Contributions

Suggestions for new information, customization options, device support, and other improvements are always welcome.

Thanks for checking out Fastfetch Android!
