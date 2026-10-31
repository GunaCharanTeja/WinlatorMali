<p align="center">
  <img src="logo.png" width="376" height="128" alt="Winlator Mali Logo" />
</p>

# Winlator Mali (Bionic Edition)

[![Release](https://img.shields.io/badge/Release-1.2.1%20Bionic-blue.svg)](https://github.com/GunaCharanTeja/WinlatorMali/releases)
[![Android](https://img.shields.io/badge/Android-8.0%2B-orange.svg)](https://developer.android.com)
[![Architecture](https://img.shields.io/badge/Arch-ARM64--v8a-purple.svg)](https://en.wikipedia.org/wiki/AArch64)
[![Support](https://img.shields.io/badge/Support-DXML-ff5e5b.svg)](https://kofi-tracker.teja44951.workers.dev/goal)

**Winlator Mali** runs Windows (x86 / x86_64) games on Android, built for devices with **ARM Mali and Immortalis GPUs** (MediaTek Dimensity, Samsung Exynos, Google Tensor), and still works on Qualcomm Adreno.

It is built on the Android Bionic runtime (from Pipetto Cripto's Winlator Bionic fork) for faster, cooler, battery-friendly gaming.

> **Status:** Winlator Mali is no longer being updated. **1.2.1 is the final release.** Development has moved to **DXML**, an independent, Mali-specialized Direct3D translation platform. See below.

---

## ❤️ Support DXML

**DXML** is an independent, Mali-specialized Direct3D translation platform, built from scratch for Mali GPUs instead of relying on DXVK. It is built by one person, for free, and the project fund is how you can help it along.

**My promise:** DXML updates will keep coming, whether or not the goal is reached.

[![Project fund](https://img.shields.io/endpoint?url=https://kofi-tracker.teja44951.workers.dev/donations/goal.json)](https://kofi-tracker.teja44951.workers.dev/goal)
[![Supporters](https://img.shields.io/endpoint?url=https://kofi-tracker.teja44951.workers.dev/donations/supporters.json)](https://kofi-tracker.teja44951.workers.dev/goal)

* 📊 **Live fund page**: [kofi-tracker.teja44951.workers.dev/goal](https://kofi-tracker.teja44951.workers.dev/goal)
* ☕ **Ko-fi**: [ko-fi.com/gunacharanteja](https://ko-fi.com/gunacharanteja)
* 🇮🇳 **UPI (India)**: `gunacharanteja@slc`
* 🪙 **Crypto (USDT)**: message me on [Telegram](https://t.me/+RDjo9_ORqXgwODc1) for the address and network. Please don't send anything before you've checked it with me.

Even a small amount helps. Sharing the project with other Mali users helps too. Thank you!

---

## Why Winlator Mali?

Mali GPUs don't natively support the BCn texture formats (BC1–BC7) that PC games rely on, which caused black textures and missing models. Winlator Mali fixes this and more:

* **BCn texture emulation** to ASTC / ETC2, with a persistent disk cache to remove repeat stutter
* **Native EGL renderer** with FSR, SGSR, FXAA and other PostFX
* **ZeroFG frame generation**, a Vulkan compute engine tuned for Mali
* **Real Mali telemetry HUD** (GPU load, clocks, CPU, battery watts, frametimes)
* **Touch controls, gyro, USB gamepads and rumble**, plus a live in-game controls editor
* **Community Configs** with ratings, **SteamGridDB** artwork and a **Save Manager**

---

## Final Release: 1.2.1 Bionic

Fixes and polish for 1.2. **Reinstall required.** Full notes: [RELEASE_NOTES_1.2.1.md](RELEASE_NOTES_1.2.1.md).

Older versions (1.0, 1.1, 1.2) are listed on the [Releases page](https://github.com/GunaCharanTeja/WinlatorMali/releases).

---

## Installation

1. Download the latest APK from [GitHub Releases](https://github.com/GunaCharanTeja/WinlatorMali/releases).
2. Install it and grant storage permissions.
3. On first launch, wait while the root filesystem unpacks.
4. Container setup:
   * **Mali GPUs**: choose **Mali / Zink** or **Turnip + Zink**, set Graphics Driver to **Wrapper**, and set BCn Emulation to ASTC or ETC2.
   * **Adreno GPUs**: choose **Turnip (Adreno)** with DXVK.
   * **Box64 / FEX preset**: start with *Intermediate* or *Compatibility*.
5. Launch a container, or import tested shortcuts from the **Community Configs** tab.

---

## Building from Source

Requires JDK 17, Android SDK 34 (Build-Tools 35.0.0), NDK 29.0.14206865 and CMake 3.22.1+.

```bash
git clone https://github.com/GunaCharanTeja/WinlatorMali.git -b bionic-mali-1.2
cd WinlatorMali
./gradlew assembleRelease        # Windows: .\gradlew.bat assembleRelease
```

APKs are written to `app/build/outputs/apk/<flavor>/release/` (flavors: `standard`, `ludashi`, `antutu`).

---

## Community

💬 [Telegram Group](https://t.me/+RDjo9_ORqXgwODc1) for help, testing, configs and updates.

---

## Credits

* **Winlator Mali**: [GunaCharanTeja (Fcharan)](https://github.com/GunaCharanTeja)
* **Winlator & Gladio**: [Bruno (brunodev85)](https://github.com/brunodev85)
* **Bionic fork**: [Pipetto Cripto](https://github.com/Pipetto-crypto)
* **BCn Vulkan layer**: [leegao](https://github.com/leegao)
* **HUD & File Manager**: [stevenmxz](https://github.com/StevenMXZ)
* **Controller fixes**: [vivsi1](https://github.com/Vivsi1)
* **Theme system & CI**: [Noysz](https://github.com/Noysz)
* **Community Configs idea**: [The412Banner](https://github.com/The412Banner)
* **Reference implementations**: [longjunyu2](https://github.com/longjunyu2)
* **ZeroFG**: [hy300leosquizz-ctrl](https://github.com/hy300leosquizz-ctrl/ZeroFG)
* **Touch controls styling**: inspired by [Madeira](https://github.com/willfaust/madeira)

## Licenses

Wine/Proton (LGPL v2.1+), Box64 (MIT), FEX-Emu (MIT), Mesa (MIT/LGPL), DXVK (zlib/libpng), VKD3D (LGPL v2.1), libadrenotools (GPL v3), ZeroFG (Apache 2.0 / BSD 3-Clause), Inter Font (SIL OFL 1.1), AOSP (Apache 2.0).

---

<p align="center">Building DXML for Mali users. <a href="https://kofi-tracker.teja44951.workers.dev/goal">Support the project fund ☕</a></p>
