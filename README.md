<p align="center">
  <img src="logo.png" width="376" height="128" alt="Winlator Mali Logo" />
</p>

# Winlator Mali (Bionic Edition)

[![Release](https://img.shields.io/badge/Release-1.2.1%20Bionic-blue.svg)](https://github.com/GunaCharanTeja/WinlatorMali/releases)
[![Default Branch](https://img.shields.io/badge/Branch-bionic--mali--1.2-brightgreen.svg)](https://github.com/GunaCharanTeja/WinlatorMali/tree/bionic-mali-1.2)
[![Android Support](https://img.shields.io/badge/Android-8.0%2B%20(SDK%2026--34)-orange.svg)](https://developer.android.com)
[![Architecture](https://img.shields.io/badge/Architecture-ARM64--v8a-purple.svg)](https://en.wikipedia.org/wiki/AArch64)
[![License](https://img.shields.io/badge/License-GPL%20v3%20%2F%20LGPL%20v2.1%20%2F%20MIT-lightgrey.svg)](LICENSE)

**Winlator Mali** is a dedicated Windows (x86 and x86_64) emulation environment for Android, specifically architected and optimized to bring PC gaming to devices powered by **ARM Mali and Immortalis GPUs** (MediaTek Dimensity, Samsung Exynos, and Google Tensor SoCs), while retaining full compatibility with Qualcomm Adreno devices.

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

### 🛠️ Winlator Mali Bionic 1.2.1 *(Current Release)*
A fixes-and-polish update to 1.2. **Reinstall required.** Highlights: fixes for 1.2 user reports (JM Mali reboot, shortcut crash, binding editor, D-pad stutter, File Manager), fullscreen modes and screen alignment, EGL stripe fix, a capped ZeroFG default, the Log Manager, Simulate touch screen, and updated Wine/Proton container setup and Contents badges. Full notes: [RELEASE_NOTES_1.2.1.md](RELEASE_NOTES_1.2.1.md).

### 🚀 Winlator Mali Bionic 1.2 *(Branch `bionic-mali-1.2`)*
The 1.2 Bionic release represents a comprehensive architectural overhaul over v1.1, transitioning the core rendering system to a native EGL architecture, replacing legacy APEX with ZeroFG Vulkan compute frame interpolation, overhauling the UI and touch controls with Madeira Liquid Glass, integrating persistent BCn disk caching, and adding comprehensive hardware performance tools:

#### 🖥️ Native EGL Rendering Engine & Gladio Integration (Replaces 1.1 Renderer)
* **High-Performance Native EGL Architecture**: Migrated from the legacy 1.1 rendering stack (`GLRenderer`, `DisplayX`, `VirGL`, `blit_converter`, and Java `EffectComposer`) to Pipetto-crypto's native C++ EGL renderer (`app/src/main/cpp/winlator/renderer/egl.cpp`).
* **Elimination of Legacy Overhead**: Completely removed the legacy `DisplayX` layer (`displayx.cpp`, `DisplayXView`), `VirGL` (`VirGLRendererComponent`), and `blit_converter.cpp`—eliminating over 3,500 lines of redundant CPU blit and synchronization bottlenecks.
* **Gladio Native OpenGL Engine**: Directly integrated the native Bionic `libGL.so.1.7.0` Gladio engine with cached blit targets and native synchronization fences, eliminating heavy Mesa Zink bloat.
* **Cross-API Zero-Copy Hardware Buffer Sharing**: Shares rendered OpenGL/EGL frames directly with a high-priority Vulkan compute device via Android Hardware Buffers (`AHardwareBuffer`), backed by native Linux sync files for zero-copy frame interpolation.
* **Modern X Server Infrastructure**: Added full support for **RandR** (`RandrExtension`), **Present 1.2** (`PresentConfigureNotify`), and **ReparentNotify** with enhanced DRI3 event routing.
* **Unified Graphics Enhancements UI**: Replaced the separate screen effects and display dialogs with a unified `GraphicsEnhancementsDialog` housing PostFX, Frame Generation, and FPS Limiter settings.
* **Native C++ PostFX Pipeline**: Replaced Java-based compositing with an optimized C++ shader pipeline:
  * Upscalers: AMD FidelityFX Super Resolution (FSR 1.0), Qualcomm Snapdragon Game Super Resolution (SGSR), and Lanczos2.
  * Shaders & Filters: Fake HDR, FXAA, CRT, NTSC, Toon, Sharpening, and Ludashi Color Boost presets.

#### 🎨 Complete UI Revamp & Modernization
* **All-New Theme System**: Integrated custom theme engine (`ThemeManager`) with Material styling, custom dark and light themes, and user-configurable accent palettes (`CustomThemeDialog`).
* **Inter Typography**: Standardized the entire UI, controls, and dialogs on **Inter SemiBold** (`Inter.ttf`, SIL Open Font License 1.1) for high legibility across high-DPI displays.
* **Modern Card-Based Views**: Redesigned Adrenotools, Save Manager, Input Controls, Manage Graphics Drivers, and Shortcut lists with clean card boundaries, soft drop-shadows, and confirmation prompts.
* **Overhauled Scrollable About Section**: Redesigned scrollable dialog showcasing architecture highlights, complete developer credits, and clickable open-source licenses.
* **Fullscreen Cinematic Preloader**: Displays custom 16:9 SteamGridDB hero banners with a refined loading scrim while games initialize.
* **Official Vector Component Icons**: Added official branded vector logos and icons for Wine, Box64, FEXCore, DXVK, VKD3D, and Proton.

#### ⚡ ZeroFG Replaces APEX Frame Generation
* **Legacy APEX / MCFI Removed**: Completely stripped out the older APEX frame generation system and its legacy shader materials.
* **ZeroFG Frame Generation Engine**: Integrated custom Vulkan compute frame interpolation engine (`zerofg`) optimized specifically for ARM Mali tile architectures:
  * Mali-tuned compute passes (`rc3_mali_d2`, `rc3_mali_d4`, `rc3_mali_d5a`, `rc3_mali_d5b`).
  * Subgroup motion estimation, optical flow lattice tracking, camera motion estimation, and split resolve passes.
  * Fractional multipliers with display refresh rate synchronization, isolated exclusively to the game rendering window.
  * Generates fluid intermediate frames to double perceived framerates with minimal latency.

#### 🎮 On-Screen Controls & Input Engine Overhaul
* **Madeira Liquid Glass Style (WinMali Default)**: Replaced old touch buttons with the new Madeira Liquid Glass theme featuring realistic light-catching rims, lens edges, highlights, and subtle drop shadows.
* **Xbox & PlayStation Visual Profiles**: Pre-rendered cached bitmap controller skins offering high tactile clarity without cluttering game visibility.
* **High-Visibility Minimum Alpha**: Touch controls never fade below 55% opacity, guaranteeing constant visibility in dark and bright game scenes alike.
* **Steam Deck-Style Radial Action Wheels**: Built-in 8-slice pie menu activated via trigger hold, featuring full analog stick and touch dragging, custom slice icons, and vibration confirmation.
* **Live In-Game Controls Editor**: Transparent overlay editor allowing buttons to be repositioned, resized, and bound live without pausing or exiting the running game.
* **4-Player Multi-Controller Slots**: Dedicated multi-gamepad manager with manual slot pinning, per-player deadzones, and LED color indicators.
* **Universal Icon Packs & InputBridge Support**: Full support for Bannerlator `.icpx` custom icon packs, Base64 embedded profiles, and InputBridge (`.ibp`) profile translation with 512x512 anti-aliased icon rendering.
* **Precision Gyroscope Subsystem**: Full motion-to-aim controls with independent X/Y sensitivity, axis inversion, dynamic acceleration curves, and steering support.

#### 🚀 Hardware Optimization & Performance Suite
* **Show Adreno Tools on Unsupported Devices**: Added an option in Settings (`show_adrenotools_unsupported`) allowing users on Mali or other non-Qualcomm devices to access Adreno Tools to view and manage custom graphics driver layers.
* **Dedicated In-Game Performance Dialog**: Access real-time hardware performance toggles directly during gameplay:
  * **Game Mode Signal**: Signals Android Game Mode for system priority.
  * **Thread Priority**: Boosts game and translation thread scheduling priorities.
  * **Big Cores Affinity**: Pins execution threads to high-performance CPU cores.
  * **Sustained Performance Mode**: Prevents aggressive thermal clock throttling.
  * **Samsung Game SDK / SPerf Boost**: Integrates Samsung Game SDK / SPerf driver for Samsung devices.
* **RAM Booster (LMK Trigger)**: Proactive Low Memory Killer memory cleaner with 7 selectable profiles (**Smart Auto**, **Low**, **Medium**, **High**, **Aggressive**, **Max**, and **Manual**), customizable Crisis / Pre-Crisis thresholds, and toast notifications.
* **PulseAudio-GN (GameNative) Audio Driver**: Added new low-latency GameNative PulseAudio driver alongside ALSA and standard PulseAudio.
* **Custom FPS Limiter**: Set any arbitrary target frame rate limit using the new "Custom..." FPS option.

#### 🧱 Vulkan BCn Emulation & Persistent Disk Cache
* **Persistent Disk Cache (`WRAPPER_USE_BCN_CACHE=1`)**: Caches decompressed and transcoded ASTC/ETC2 textures to local storage, permanently eliminating shader/texture compilation stutters on subsequent launches.
* **Native Direct Bypass Toggle**: Allows bypassing texture translation on GPUs with native capabilities or for titles that do not require BCn transcoding.
* **Restored & Unified Vulkan Wrapper Suite**: All 5 wrapper variants selectable in *Manage Graphics Drivers* (`Wrapper` unified default, `Wrapper-1.1`, `Wrapper-v2`, `Wrapper-leegao`, and `Wrapper-gamenative`).

#### 📊 Kernel-Direct Telemetry HUD (Winlator HUD)
* **Real Mali GPU Load & Clocks**: Directly queries Linux kernel sysfs nodes (`/sys/class/misc/mali0/device/utilization`, `/sys/class/devfreq/...cur_freq`) to report real Mali GPU utilization (%) and operating clock frequency (MHz).
* **Accurate CPU Usage**: Calculates genuine per-process CPU core activity from `/proc/stat` and process cgroup threads.
* **Battery Wattage**: Displays live discharge power in Watts calculated from hardware voltage and amperage monitors.
* **Thermal & Frametime Metrics**: Real-time frametime histogram graph, 1% and 0.1% lows, and thermal mitigation status.
* **Madeira Glass Overlay**: Styled with high-contrast semi-transparent glassmorphic borders matching on-screen controls.

#### 🔌 USB Gamepad Attachment & Dual Rumble
* **Direct USB HID Host Mode**: Connect gamepads via USB OTG with automatic device filtering (`usb_device_filter.xml`) and auto-grant persistent Android USB permissions.
* **Dual-Motor Haptic Rumble**: Real force feedback support for Xbox, PlayStation (DualShock / DualSense), and X-Input gamepads.
* **Full D-Pad / Controller UI Navigation**: Full system navigation across all dialogs, menus, and side drawers using physical controller buttons.

#### ☁️ Community Configs & Cloud Ecosystem
* **Cloudflare Workers & KV Backend**: Real-time community feedback with **Helpful** (👍) and **Didn't Work** (👎) ratings, backed by persistent anonymous voter UUIDs and anti-spam cooldowns.
* **Automated GPU Badges**: Automatically detects and tags configs as Mali or Adreno compatible.
* **Unbundled Wine Downloader Flow**: Interactive downloader that automatically fetches and installs missing Wine/Proton packages when importing community configs.

#### 💾 SteamGridDB Hero Art & Game Save Manager
* **16:9 Widescreen Hero Banners**: Automatically fetches high-resolution banners from SteamGridDB with a built-in interactive artwork selector.
* **Universal Save Manager**: Automatically scans and restores game saves across standard paths (`AppData`, `Saved Games`, `Documents`, `Steam/userdata`) and cracked game folders (`Goldberg`, `CODEX`, `FLT`, `EMPRESS`, `RUNE`, etc.).
* **Windows PE Detection**: Automatic game executable version extraction (`PEParser`), embedded icon extraction, and playtime tracking.

---

#### 🛠️ Bug Fixes, Stability & Refinements
* **Crash Fixes**: Fixed crash when opening the integrated File Manager.
* **Storage Info Dialog**: Fixed the 0% usage calculation bug and clipped circular progress indicator.
* **USB Controller Reconnection**: Handled background/resume transitions cleanly without locking USB device handles, and fixed Android 12+ immutable permission flags.
* **Touch & Mouse Pointers**: Resolved cursor getting trapped in corners caused by NaN cursor speeds, and fixed touch-event forwarding.
* **Touchscreen & Radial Wheel Disentanglement**: Fixed an issue where touch controls could be disabled by default or inadvertently conflict with radial wheel triggers.
* **Controls Profile Reset**: Made profile reset non-destructive, preserving custom icons, button placements, and imported `.icp`/`.icpx` assets.
* **Icon Rendering Pipeline**: Fixed integer ID overflow, restored 100% icon opacity for custom imported assets, and added proper AndroidSVG vector parsing.
* **Audio Routing**: Fixed PulseAudio-GN identifier mismatch and library loader paths.
* **Multiplayer LAN**: Fixed LAN multiplayer local IP detection.
* **Container Environment Variables**: Ensured default environment variable migrations run strictly once so that user deletions and custom edits permanently persist.

#### 📝 Many More Things to Discover!
In addition to the highlights above, **hundreds of smaller fixes, performance optimizations, memory leak resolutions, layout adjustments, and internal refinements** have been incorporated across the entire codebase that you will discover while navigating the app and playing your favorite games.

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

Build all signed release flavors using Gradle:

**Windows (PowerShell):**
```powershell
.\gradlew.bat assembleRelease
```

**Linux / macOS:**
```bash
chmod +x ./gradlew
./gradlew assembleRelease
```

The compiled APKs will be generated at:
* `app/build/outputs/apk/standard/release/app-standard-release.apk`
* `app/build/outputs/apk/ludashi/release/app-ludashi-release.apk`
* `app/build/outputs/apk/antutu/release/app-antutu-release.apk`

---

## Installation & Setup

1. Download the latest **Winlator Mali 1.2.1 Bionic** release APK from [GitHub Releases](https://github.com/GunaCharanTeja/WinlatorMali/releases).
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
