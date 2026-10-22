<p align="center">
  <img src="logo.png" width="376" height="128" alt="Winlator Mali Logo" />
</p>

# Winlator Mali (Bionic Edition)

[![Release](https://img.shields.io/badge/Release-1.2%20Bionic-blue.svg)](https://github.com/GunaCharanTeja/WinlatorMali/releases)
[![Default Branch](https://img.shields.io/badge/Branch-bionic--mali--1.2-brightgreen.svg)](https://github.com/GunaCharanTeja/WinlatorMali/tree/bionic-mali-1.2)
[![Android Support](https://img.shields.io/badge/Android-8.0%2B%20(SDK%2026--34)-orange.svg)](https://developer.android.com)
[![Architecture](https://img.shields.io/badge/Architecture-ARM64--v8a-purple.svg)](https://en.wikipedia.org/wiki/AArch64)
[![License](https://img.shields.io/badge/License-GPL%20v3%20%2F%20LGPL%20v2.1%20%2F%20MIT-lightgrey.svg)](LICENSE)

**Winlator Mali** is a specialized Android application designed to run Windows (x86 and x86_64) PC applications and games on ARM64 devices. While standard Winlator distributions target Qualcomm Adreno GPUs via Turnip, Winlator Mali is heavily architected and optimized from the ground up for **ARM Mali and Immortalis GPUs** (found in MediaTek Dimensity, Samsung Exynos, and Google Tensor SoCs), as well as supporting Qualcomm Adreno devices.

Built upon the high-performance **Android Bionic C runtime**, Winlator Mali replaces heavy PRoot syscall emulation with direct native execution for minimized overhead, lower latency, and enhanced stability.

---

## Key Features & Architecture

### 1. Hardware-Accelerated Vulkan BCn Emulation & Disk Cache
Desktop PC games almost universally rely on Direct3D Block Compression textures (BC1–BC7). Because mobile ARM Mali GPUs do not natively support hardware BCn decoding, running games on Mali historically resulted in black or missing textures.
* **Vulkan BCn Emulation Layer (by leegao)**: Transcodes desktop compressed textures directly to mobile hardware-native formats (ASTC and ETC2) on the fly.
* **Native Direct Toggle**: Allows bypassing texture translation on GPUs with native capabilities or for specific titles.
* **Persistent Disk Cache (`WRAPPER_USE_BCN_CACHE=1`)**: Caches decompressed and transcoded textures to local disk storage, eliminating micro-stutters and drastically reducing load times on subsequent launches.
* **Switchable Vulkan Wrapper Profiles**: Easily select between `Wrapper` (latest unified build), `Wrapper-1.1`, `Wrapper-v2`, `Wrapper-leegao`, and `Wrapper-gamenative` directly inside *Manage Graphics Drivers*.

### 2. ZeroFG Frame Generation Engine
Integrated frame interpolation engine powered by custom Vulkan compute shaders (`app/src/main/cpp/zerofg/`):
* **Mali-Tuned Shader Passes**: Utilizes subgroup motion estimation (`rc3_mali_d2`, `rc3_mali_d4`, `rc3_mali_d5a`, `rc3_mali_d5b`), optical flow lattice tracking, camera motion estimation, and split resolve passes optimized for ARM Mali tile-based memory architectures.
* **Fluid Gameplay**: Dynamically generates intermediate frames between rendered frames to elevate perceived fluidity in GPU-bound scenarios with minimal latency.

### 3. Real-Time Hardware Telemetry HUD (Winlator HUD)
Engineered for true hardware insight without relying on generic placeholders:
* **Real Mali GPU Utilization & Clocks**: Directly queries Mali kernel sysfs nodes (`/sys/class/misc/mali0/device/utilization`, `/sys/class/devfreq/...mali.../cur_freq`, `/sys/kernel/gpu/gpu_busy`) to show genuine GPU load (%) and operating clock frequency (MHz).
* **Accurate CPU Usage**: Calculates real per-process CPU core activity from `/proc/stat` and process cgroup thread metrics.
* **Battery Wattage**: Displays real-time discharge rate in Watts calculated from hardware voltage and amperage monitors.
* **Thermal & Frametime Metrics**: Tracks device thermal mitigation states, real-time frametime graphs, and 1% / 0.1% frametime lows.
* **Madeira Glass Aesthetics**: Rendered with high-contrast, semi-transparent glassmorphic styling matching WinMali Default on-screen touch controls.

### 4. Community Configs & Real-Time Cloud Ratings
Browse, download, and share game configurations directly within the app:
* **Automated GPU Badges**: Instantly highlights whether a shared profile was configured for Mali or Adreno GPUs.
* **Cloudflare Workers & KV Backend**: Real-time community feedback with **Helpful** (👍) and **Didn't Work** (👎) ratings with persistent, anonymous voter IDs and anti-spam rate limiting.
* **Unbundled Wine Downloader Flow**: Seamlessly downloads and associates missing Wine or Proton packages when importing a configuration.

### 5. Native USB Gamepad Support & Dual-Motor Vibration
* **Direct USB HID Attachment**: Connect gamepads via USB OTG with automatic device filtering (`usb_device_filter.xml`) and auto-grant persistent Android USB permissions.
* **Haptic Rumble**: True dual-motor force feedback support for Xbox, PlayStation (DualShock / DualSense), and X-Input compatible gamepads.
* **Complete UI Navigation**: Navigate the entire Winlator interface (dialogs, side navigation drawer, shortcut menus) using controller D-Pad and action buttons.

### 6. SteamGridDB Hero Artwork & Game Save Manager
* **16:9 SteamGridDB Hero Art**: Automatically scrapes high-resolution widescreen banners for shortcuts with a built-in interactive artwork picker.
* **Preloader Splash Screen**: Displays an immersive fullscreen splash screen while games load container environments.
* **Universal Game Save Manager**: Auto-detects and backs up game save directories across standard paths (`AppData`, `Saved Games`, `Documents`, `Steam/userdata`) as well as cracked release folders (`Goldberg`, `CODEX`, `FLT`, `EMPRESS`, `RUNE`, etc.) with one-click restore.

### 7. Post-Processing & Upscaling Pipeline
Comprehensive shader upscaling pipeline:
* **Upscalers**: AMD FidelityFX Super Resolution (FSR 1.0), Qualcomm Snapdragon Game Super Resolution (SGSR), and Lanczos2.
* **Shaders & Filters**: CRT, FXAA, HDR, NTSC, Toon, and Color Boost presets.

---

## Built-In Game Controller Profiles

Winlator Mali includes 41 pre-tuned input control profiles (`.icp`) in `input_controls/` ready for immediate import:

| Action / Adventure | Shooters | RPGs & Strategy | Platformers & Racing |
|---|---|---|---|
| GTA 5 | Metro 2033 | Dark Souls 2 | FlatOut 2 |
| Driver Parallel Lines | Bioshock | Fallout 3 | Sonic Mania |
| Spider-Man: Shattered Dimensions | Call of Juarez: Gunslinger | The Elder Scrolls IV: Oblivion | Shovel Knight |
| Second Sight | S.T.A.L.K.E.R.: Clear Sky | Mass Effect 2 | Rayman 3 |
| The Saboteur | Prey | Deus Ex: Human Revolution | Cyber Shadow |
| Hitman 2: Silent Assassin | Quake 4 | Divinity II: Developer's Cut | Gravity Circuit |
| IGI 2: Covert Strike | Wolfenstein | Final Fantasy VIII & Type-0 | SteamWorld Dig 2 |
| Alien vs. Predator | Singularity | Risen 2: Dark Waters | La-Mulana |
| Star Wars Jedi Knight II | RAGE | Ys Origin / Xanadu Next | Unepic |

---

## Project Structure

```
WinlatorMali/
├── app/
│   ├── build.gradle                   # Version 1.2 Bionic (code 22), multi-flavor build config
│   ├── src/main/
│   │   ├── AndroidManifest.xml        # Permissions, USB intent filters, orientations
│   │   ├── assets/
│   │   │   ├── fonts/                 # Inter Font (SIL OFL 1.1)
│   │   │   ├── graphics_driver/       # leegao_bcn, wrapper-1.1, wrapper-v2, wrapper-leegao, wrapper-gamenative
│   │   │   └── soundfonts/            # MIDI synthesizer soundbanks
│   │   ├── cpp/
│   │   │   ├── winlator/              # Mali GPU sysfs counters, postfx, frame gen bridges
│   │   │   └── zerofg/                # ZeroFG compute shaders & frame interpolation core
│   │   ├── java/com/winlator/cmod/
│   │   │   ├── MainActivity.java      # Main UI controller & updated 1.2 Bionic About dialog
│   │   │   ├── XServerDisplayActivity # Game execution container & input/renderer loop
│   │   │   ├── UsbAttachActivity.java # Direct USB controller attachment & permission persistence
│   │   │   ├── core/                  # Community configs, ControllerNavigation, SteamGridDB
│   │   │   ├── contents/              # Unbundled ContentDownloads & ContentDownloadService
│   │   │   ├── renderer/              # PostProcessing, GL/EGL native render passes
│   │   │   └── widget/                # WinlatorHUD, MaliGpuCounters, GlassPainter
│   │   └── res/                       # Layouts (dialogs, community configs, hero art picker)
├── android_sysvshm/                   # Android SysV Shared Memory helper library
├── audio_plugin/                      # ALSA audio plugin for low-latency sound routing
├── input_controls/                    # 41 pre-configured touch/controller profiles (.icp)
└── .github/workflows/build-apk.yml    # Automated GitHub Actions CI workflow
```

---

## Product Flavors

Winlator Mali supports three distinct application flavors defined in [app/build.gradle](app/build.gradle):

| Flavor | Package ID | Application Name | Purpose |
|---|---|---|---|
| `standard` | `com.winlator.mali` | **Winlator Mali** | Primary release for general gaming |
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
Clone the repository:
```bash
git clone https://github.com/GunaCharanTeja/WinlatorMali.git -b bionic-mali-1.2
cd WinlatorMali
```

Build the release APK using the Gradle wrapper:

**Windows (PowerShell):**
```powershell
.\gradlew.bat assembleStandardRelease
```

**Linux / macOS:**
```bash
chmod +x ./gradlew
./gradlew assembleStandardRelease
```

The compiled APK will be generated at:
`app/build/outputs/apk/standard/release/app-standard-release.apk`

---

## Installation & Getting Started

1. Download the latest **Winlator Mali 1.2 Bionic** release APK from [GitHub Releases](https://github.com/GunaCharanTeja/WinlatorMali/releases).
2. Install the APK on your Android device and grant storage and notification permissions.
3. On first launch, the app will automatically unpack the bundled root filesystem (`imagefs.tzst`).
4. **Create a Container**:
   * **For Mali GPUs**: In container settings, select **Mali / Zink** or **Turnip + Zink**, set the Graphics Driver to **Wrapper**, and configure BCn Emulation in the container settings.
   * **For Adreno GPUs**: Select **Turnip (Adreno)** with DXVK.
   * **Box64 / FEX Preset**: Select *Compatibility* or *Intermediate* according to game demands.
5. Launch your container or import pre-tested shortcuts from the **Community Configs** tab.

---

## Credits & Third-Party Authors

Winlator Mali is made possible thanks to the work of the open-source emulation and Linux community:

* **Winlator Mali**: Developed by [GunaCharanTeja (Fcharan)](https://github.com/GunaCharanTeja)
* **Winlator & Gladio**: Originally created by [Bruno (brunodev85)](https://github.com/brunodev85)
* **Winlator Bionic Fork**: Native Bionic EGL runtime by [Pipetto Cripto](https://github.com/Pipetto-crypto)
* **BCn Vulkan Layer & Transcode**: Architecture and transcode implementation by [leegao](https://github.com/leegao)
* **Winlator HUD & File Manager**: Ported from Winlator Ludashi by [stevenmxz](https://github.com/StevenMXZ)
* **Controller Navigation & Input Fixes**: Developed by [vivsi1](https://github.com/Vivsi1)
* **Theme System & CI Workflows**: Created by [Noysz](https://github.com/Noysz)
* **Community Configs Concept**: Original idea by [The412Banner](https://github.com/The412Banner)
* **GLIBC Fork Contributions**: Reference implementations by [longjunyu2](https://github.com/longjunyu2)
* **ZeroFG Frame Generation**: Upstream engine by [hy300leosquizz-ctrl](https://github.com/hy300leosquizz-ctrl/ZeroFG) (with Mali subgroup & resolve additions)
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
