<p align="center">
  <img src="logo.png" width="376" height="128" alt="Winlator Mali Logo" />
</p>

# Winlator Mali (Bionic Edition)

[![Release](https://img.shields.io/badge/Release-1.2%20Bionic-blue.svg)](https://github.com/GunaCharanTeja/WinlatorMali/releases)
[![Default Branch](https://img.shields.io/badge/Branch-bionic--mali--1.2-brightgreen.svg)](https://github.com/GunaCharanTeja/WinlatorMali/tree/bionic-mali-1.2)
[![Android Support](https://img.shields.io/badge/Android-8.0%2B%20(SDK%2026--34)-orange.svg)](https://developer.android.com)
[![Architecture](https://img.shields.io/badge/Architecture-ARM64--v8a-purple.svg)](https://en.wikipedia.org/wiki/AArch64)
[![License](https://img.shields.io/badge/License-GPL%20v3%20%2F%20LGPL%20v2.1%20%2F%20MIT-lightgrey.svg)](LICENSE)

**Winlator Mali** is a dedicated Windows (x86 / x86_64) emulation environment for Android, specifically architected and optimized to bring PC gaming to devices powered by **ARM Mali and Immortalis GPUs** (MediaTek Dimensity, Samsung Exynos, and Google Tensor SoCs), while retaining full compatibility with Qualcomm Adreno devices.

Built upon the high-performance **Android Bionic C runtime** (originating from Pipetto Cripto's Winlator Bionic fork), Winlator Mali bypasses traditional PRoot container and syscall-translation overhead, achieving near-native execution speed, reduced CPU bottlenecking, and superior thermal efficiency.

---

## The Story Behind Winlator Mali

Standard Winlator distributions have traditionally centered on Qualcomm Snapdragon SoCs due to the availability of the open-source **Turnip** Vulkan driver. In contrast, hundreds of millions of Android users on MediaTek Dimensity, Samsung Exynos, and Google Tensor devices with ARM Mali / Immortalis GPUs were left facing severe hurdles:
1. **Missing Desktop Texture Formats**: Desktop Direct3D games rely heavily on BCn Block Compression (BC1–BC7). ARM Mali hardware does not natively decompress these formats, historically causing black textures, missing character models, and broken environments.
2. **Heavy Emulation Latency**: Running desktop games inside a virtual PRoot rootfs with Glibc translation layers added massive CPU overhead, causing stutters and heavy battery drain.
3. **Lack of GPU-Aware Telemetry**: Generic overlays failed to read real Mali GPU load or clock frequencies, making it difficult for players to optimize settings.

**Winlator Mali was created to solve these exact problems.** By implementing hardware-level BCn texture transcoding to mobile ASTC/ETC2 formats, integrating persistent disk caching, engineering custom Mali-tuned Vulkan compute shaders for frame generation, adding direct kernel sysfs hardware telemetry, and migrating to Android's native Bionic libc runtime, Winlator Mali transforms MediaTek and Exynos gaming from barely functional to smooth and playable.

---

## Release History & Evolution

### 🚀 Winlator Mali Bionic 1.2 *(Current Release — Branch `bionic-mali-1.2`)*
The definitive 1.2 release brings persistent caching, next-generation frame interpolation, kernel-direct telemetry, cloud ecosystem upgrades, and full controller navigation:
* **Persistent BCn Texture Disk Cache (`WRAPPER_USE_BCN_CACHE=1`)**: Eliminates the persistent stutter on game boot by caching transcoded ASTC/ETC2 textures to disk storage. Subsequent launches load pre-transcoded textures instantly.
* **Native Direct Bypass Toggle**: Added a per-container and per-game toggle to bypass BCn translation entirely for titles that do not require it or on GPUs with native capabilities.
* **Restored & Unified Vulkan Wrapper Suite**: Brought back all 5 switchable Vulkan wrapper profiles directly manageable inside *Manage Graphics Drivers* (`Wrapper` latest unified build, `Wrapper-1.1`, `Wrapper-v2`, `Wrapper-leegao`, and `Wrapper-gamenative`).
* **ZeroFG Frame Generation Engine**: Complete upgrade to the ZeroFG frame interpolation engine, customized with Mali-specific compute shaders (`rc3_mali_d2`, `rc3_mali_d4`, `rc3_mali_d5a`, `rc3_mali_d5b`, subgroup motion search, optical flow lattice, and split resolve passes) for high perceived fluidity.
* **Kernel-Direct Hardware Telemetry (Winlator HUD)**: Reads actual Mali GPU load (%) and clock frequency (MHz) straight from Linux kernel sysfs interfaces (`/sys/class/misc/mali0/device/utilization`, `/sys/class/devfreq/...cur_freq`), calculates real per-tick game CPU usage, displays accurate battery discharge wattage (Watts), thermal state, 1% and 0.1% frametime lows, a live frametime graph, and Madeira Glass styling.
* **Native USB Gamepad & Dual-Motor Vibration**: Direct USB controller attachment via Android USB Host mode (`usb_device_filter.xml`) with persistent auto-grant permissions, true dual-motor haptic rumble (Xbox, PlayStation DualShock/DualSense, and X-Input), and complete D-Pad / Controller UI navigation across all dialogs and menus.
* **Community Configs Cloud Ratings & Downloader**: Integrated Cloudflare Workers & KV voting system (**Helpful** 👍 / **Didn't Work** 👎) with persistent anonymous voter UUIDs, anti-spam rate limiting, automated Mali/Adreno compatibility badges, and an interactive unbundled Wine downloader flow.
* **SteamGridDB 16:9 Hero Art & Universal Save Manager**: Automated scraping and selection of 16:9 widescreen hero banners, fullscreen preloader splash screens, and universal save backup/restoration with automatic detection for standard paths and cracked game releases (`Goldberg`, `CODEX`, `FLT`, `EMPRESS`, `RUNE`, etc.).
* **In-App About Dialog Overhaul**: Fully scrollable in-app About dialog showcasing branch features, full developer credits, and clickable open-source licenses.

---

### ⚡ Winlator Mali Bionic 1.1
* **Hybrid x86 FEXCore**: Custom hybrid translation mode that enables software emulation only where strictly necessary (e.g. game physics calculations), while running the rest of the x86 code natively. Resolved broken physics in titles like *Assassin's Creed Brotherhood* and *Assassin's Creed Revelations* without needing to disable x86 Reduced Precision globally.
* **Unreal Engine 3 Preset**: Dedicated optimization profile tested on titles such as *X-Men Origins: Wolverine*.
* **Display & Post-Processing**: Added Fake HDR, Universal FPS Limiter, and adjustable Sharpness controls.
* **Vulkan & Translation Updates**: Added the GameNative Vulkan Wrapper, container-level automatic `dxvk.conf` generation based on selected presets, and new DXVK versions.
* **GTA V Optimization**: Added a one-click GTA V optimization toggle in Shortcut Settings.
* **Performance & Fixes**: Replaced imagefs and Wine archive formats for drastically faster extraction and installation, fixed memory retention on app exit, resolved APEX frame generation visual artifacts, and fixed screen effect stretching.

---

### 🌟 Winlator Mali Bionic 1.0
The foundational release that introduced the Bionic runtime and made playable gaming on Mali a reality:
* **Migration to Android Bionic Runtime**: Transitioned from PRoot/Glibc to native Android Bionic libc (forked from Pipetto Cripto), slashing CPU translation overhead.
* **Vulkan BCn Texture Emulation (ASTC & ETC2)**: Integrated modified `bcn_layer` by leegao, providing hardware-accelerated ASTC and ETC2 texture transcoding to fix missing/black textures on Mali GPUs.
* **Multiple Vulkan Wrappers**: Selectable per-container Vulkan wrappers (`Wrapper`, `Wrapper-Leegao`, `Wrapper V2`).
* **Graphics Driver Manager**: Enabled importing and managing custom Vulkan wrappers and BCn layers from local storage with user priority overrides.
* **Apex Frame Generation**: Integrated Motion Compute Frame Interpolation (MCFI) for smoother framerates.
* **Advanced HUD & Integrated File Manager**: Ported and adapted from Winlator Ludashi by stevenmxz.
* **Community Configs & SteamGridDB**: Built-in community config client (inspired by The412Banner / BannerHub) and automatic cover art scraping from SteamGridDB.
* **Unified Controls & Gyro View**: Unified XInput, DInput, and Exclusive modes into a single input system with gyroscope camera steering.
* **Default Runtime**: Shipped with Proton 10 ARM64EC by default.

---

### 📜 Early Era (Winlator Mali 1.0 – 2.0 PRoot & Glibc)
Before the Bionic migration, Winlator Mali began as an experimental PRoot and GLIBC distribution focused on squeezing performance out of standard Linux containers:
* Custom Box64 builds and tuning flags (`BOX64_MMAP32`, custom presets).
* Experimental WineD3D wrappers (including ThirteenAG's D3D9 wrapper).
* Custom game-specific presets (*Skyrim*, *Oblivion*, *Virtua Tennis 3 & 4*, *Prince of Persia*, *Silent Hill: Homecoming*).
* Container backup tools, custom Visual C++ runtime installers, XNA Framework integration, and Direct3D test scenes.

---

## Key Architectural Highlights

```
                                  +------------------------------------+
                                  |    Direct3D 9 / 10 / 11 / 12 Game   |
                                  +------------------------------------+
                                                    |
                                      [ DXVK / VKD3D / D8VK ]
                                                    |
                                                    v
                                  +------------------------------------+
                                  |    Vulkan Graphics Translation     |
                                  +------------------------------------+
                                                    |
                         +--------------------------+--------------------------+
                         |                                                     |
                         v                                                     v
          [ ARM Mali / Immortalis GPU ]                             [ Qualcomm Adreno GPU ]
                         |                                                     |
         +---------------+---------------+                                     v
         |                               |                             [ Turnip Driver ]
         v                               v                                     |
+------------------+           +-------------------+                           |
| BCn Layer Transcode |           | ZeroFG Frame Gen  |                           |
| (ASTC / ETC2)    |           | (Vulkan Compute)  |                           |
+------------------+           +-------------------+                           |
         |                               |                                     |
         +---------------+---------------+                                     |
                         |                                                     |
                         v                                                     v
          +--------------------------------------------------------------------+
          |             Native Android Bionic libc Runtime (ARM64)             |
          +--------------------------------------------------------------------+
                         |                                                     |
                         v                                                     v
          +-----------------------------+                       +-----------------------------+
          |      Direct Kernel Sysfs    |                       |      USB HID Controller     |
          |  (Real GPU MHz / Load / W)  |                       |  (Rumble & Auto-Permission) |
          +-----------------------------+                       +-----------------------------+
```

1. **Vulkan BCn Emulation & Disk Cache**:
   Desktop compressed textures (BC1–BC7) are dynamically transcoded into hardware-native ASTC or ETC2 blocks. Transcoded textures are committed to a persistent disk cache (`WRAPPER_USE_BCN_CACHE=1`), allowing subsequent game runs to bypass transcoding entirely.
2. **ZeroFG Frame Interpolation**:
   Low-latency Vulkan compute shaders calculate optical flow vectors and perform motion search tailored to Mali tile memory architectures, synthesizing intermediate frames to elevate perceived FPS in demanding scenes.
3. **Hardware Telemetry HUD**:
   Queries `/sys/class/misc/mali0/device/utilization`, `/sys/class/devfreq/...mali.../cur_freq`, and `/sys/kernel/gpu/gpu_busy` directly to report genuine Mali GPU load (%) and clock speeds (MHz), accompanied by per-process CPU usage, real battery wattage, thermal status, and 1% / 0.1% lows in a Madeira Glass overlay.
4. **USB Controller Attachment & Dual Rumble**:
   Direct USB OTG gamepad connection with persistent auto-grant permissions, true dual-motor vibration feedback, and full controller navigation across all UI elements.
5. **Universal Game Save Manager**:
   Scans and restores game save data across standard Windows user paths and major cracked release structures (`AppData`, `Saved Games`, `Documents`, `Steam/userdata`, `Goldberg`, `CODEX`, `FLT`, `EMPRESS`, `RUNE`, etc.).

---

## Product Flavors

Winlator Mali is built with three distinct application flavors in [app/build.gradle](app/build.gradle):

| Flavor | Package ID | Application Name | Purpose |
|---|---|---|---|
| `standard` | `com.winlator.mali` | **Winlator Mali** | Main release for general gaming |
| `ludashi` | `com.ludashi.benchmark` | **Winlator Mali Ludashi** | Benchmark identification profile |
| `antutu` | `com.antutu.ABenchMark` | **Winlator Mali AnTuTu** | Performance benchmarking profile |

---

## Building from Source

### Prerequisites
* **Java Development Kit (JDK)**: Java 17
* **Android SDK**: API Level 34 (Android 14) with Build-Tools `35.0.0`
* **Android NDK**: Version `29.0.14206865`
* **CMake**: Version `3.22.1` or higher

### Build Instructions
```bash
git clone https://github.com/GunaCharanTeja/WinlatorMali.git -b bionic-mali-1.2
cd WinlatorMali
```

Build the standard release APK:

**Windows (PowerShell):**
```powershell
.\gradlew.bat assembleStandardRelease
```

**Linux / macOS:**
```bash
chmod +x ./gradlew
./gradlew assembleStandardRelease
```

The APK will be generated at:
`app/build/outputs/apk/standard/release/app-standard-release.apk`

---

## Installation & Setup

1. Download the latest **Winlator Mali 1.2 Bionic** release APK from [GitHub Releases](https://github.com/GunaCharanTeja/WinlatorMali/releases).
2. Install the APK and grant required storage permissions.
3. On first launch, the app will automatically unpack the bundled root filesystem (`imagefs.tzst`).
4. **Container Recommendations**:
   * **ARM Mali GPUs**: In Container Settings, select **Mali / Zink** or **Turnip + Zink**, set Graphics Driver to **Wrapper**, and configure BCn Emulation (ASTC or ETC2).
   * **Qualcomm Adreno GPUs**: Select **Turnip (Adreno)** with DXVK.
   * **Box64 / FEX Preset**: Start with *Intermediate* or *Compatibility* based on title requirements.
5. Launch your container or import pre-tested shortcuts from the **Community Configs** tab.

---

## Community & Support

Join the community for support, testing, game configurations, and updates:

* 💬 **Telegram Group**: [Winlator Mali Community](https://t.me/+RDjo9_ORqXgwODc1)
* ☕ **Ko-fi**: [Support GunaCharanTeja](https://ko-fi.com/gunacharanteja)
* 🇮🇳 **UPI (India)**: `gunacharanteja@ybl`

---

## Credits & Third-Party Authors

Winlator Mali stands on the shoulders of brilliant contributors across the open-source emulation landscape:

* **Winlator Mali**: Developed and maintained by [GunaCharanTeja (Fcharan)](https://github.com/GunaCharanTeja)
* **Winlator & Gladio**: Originally created by [Bruno (brunodev85)](https://github.com/brunodev85)
* **Winlator Bionic Fork**: Native Bionic runtime architecture by [Pipetto Cripto](https://github.com/Pipetto-crypto)
* **BCn Vulkan Layer & Transcode**: Block compression translation implementation by [leegao](https://github.com/leegao)
* **Winlator HUD & File Manager**: Ported and adapted from Winlator Ludashi by [stevenmxz](https://github.com/StevenMXZ)
* **Controller Fixes & Navigation**: Implemented by [vivsi1](https://github.com/Vivsi1)
* **Theme System & CI Workflows**: Created by [Noysz](https://github.com/Noysz)
* **Community Configs Concept**: Original idea by [The412Banner](https://github.com/The412Banner)
* **GLIBC Fork Contributions**: Reference implementations by [longjunyu2](https://github.com/longjunyu2)
* **ZeroFG Frame Generation**: Upstream engine by [hy300leosquizz-ctrl](https://github.com/hy300leosquizz-ctrl/ZeroFG)
* **Touch Controls Styling**: Inspired by [Madeira](https://github.com/willfaust/madeira) by willfaust

---

## Open Source Licenses

* **Wine / Proton**: GNU LGPL v2.1+ ([winehq.org](https://www.winehq.org))
* **Box64**: MIT License ([ptitSeb/box64](https://github.com/ptitSeb/box64))
* **FEX-Emu**: MIT License ([FEX-Emu/FEX](https://github.com/FEX-Emu/FEX))
* **Mesa (Turnip / Zink / Wrapper)**: MIT / LGPL ([mesa3d.org](https://www.mesa3d.org))
* **DXVK**: zlib/libpng License ([doitsujin/dxvk](https://github.com/doitsujin/dxvk))
* **VKD3D**: GNU LGPL v2.1 ([gitlab.winehq.org/wine/vkd3d](https://gitlab.winehq.org/wine/vkd3d))
* **D8VK, dxwrapper, CNC DDraw**: zlib / GPL / MIT
* **libadrenotools**: GNU GPL v3 ([bylaws/libadrenotools](https://github.com/bylaws/libadrenotools))
* **ZeroFG**: Apache 2.0 / BSD 3-Clause
* **Inter Font**: SIL Open Font License 1.1 ([rsms/inter](https://github.com/rsms/inter))
* **Android Open Source Project (AOSP)**: Apache 2.0
