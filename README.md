<p align="center">
	<img src="logo.png" width="376" height="128" alt="Winlator Mali Logo" />  
</p>

# Winlator Mali

Winlator Mali is an Android application that lets you run Windows (x86_64) applications with Wine and Box86/Box64, optimized for **Mali GPU** devices (tested on Mali-G610 MC4 / Dimensity 7200).

> ⚠️ **First release coming July 5, 2026** — it's been a year, but we're back!

---

## Screenshots

| GTA IV | Batman: Arkham City |
|--------|---------------------|
| ![GTA IV](screenshots/gtaiv.jpg) | ![Batman Arkham City](screenshots/batman_ac.jpg) |

*Running on Mali-G610 MC4 (Dimensity 7200) via DXVK + Vulkan Wrapper*

---

## What's New in Winlator Mali

- **Multiple Vulkan Wrapper Selection** — Choose between `Wrapper`, `Wrapper-Leegao`, and `Wrapper V2` per container
- **ASTC Transcoding** — ASTC texture transcoding implemented directly in-app via leegao's bcn_layer
- **ETC2 Transcoding** — ETC2 texture transcoding implemented directly in-app via leegao's bcn_layer
- **Advanced HUD** — Enhanced in-game overlay based on Winlator Ludashi's HUD, further improved
- **Unified Control System** — Simplified from three separate modes (XInput, DInput, Exclusive) into one unified input system

---

## Installation

1. Download and install the APK from [GitHub Releases](https://github.com/GunaCharanTeja/WinlatorMali/releases)
2. Launch the app and wait for the installation to finish

---

## Useful Tips

- If you experience performance issues, try changing the Box86/Box64 preset in Container Settings → Advanced Tab.
- For applications that use .NET Framework, try installing Wine Mono from Start Menu → System Tools.
- If older games don't open, try adding `MESA_EXTENSION_MAX_YEAR=2003` in Container Settings → Environment Variables.
- Use per-game shortcuts from the Winlator home screen to set individual settings for each game.
- To speed up installers, set the Box86/Box64 preset to Intermediate in Container Settings → Advanced Tab.

---

## Based On

- [Winlator Bionic](https://github.com/Pipetto-crypto/winlator) by Pipetto-crypto
- [Controller Fix](https://github.com/Vivsi1/winlator/tree/pb_controller_fix) by Vivsi1

---

## Credits & Third-party

- [brunodev85](https://github.com/brunodev85) — Original Winlator
- [Pipetto-crypto](https://github.com/Pipetto-crypto) — Winlator Bionic base
- [Vivsi1](https://github.com/Vivsi1) — Controller fixes
- [leegao](https://github.com/leegao) — [bcn_layer](https://github.com/leegao/bcn_layer) (ASTC/ETC2 transcoding) & [bionic-vulkan-wrapper](https://github.com/leegao/bionic-vulkan-wrapper)
- [StevenMXZ](https://github.com/StevenMXZ) — [Winlator Ludashi](https://github.com/StevenMXZ/Winlator-Ludashi) HUD
- Ubuntu RootFs ([Focal Fossa](https://releases.ubuntu.com/focal))
- Wine ([winehq.org](https://www.winehq.org/))
- Box86/Box64 by [ptitSeb](https://github.com/ptitSeb)
- PRoot ([proot-me.github.io](https://proot-me.github.io))
- Mesa (Turnip/Zink/VirGL) ([mesa3d.org](https://www.mesa3d.org))
- DXVK ([github.com/doitsujin/dxvk](https://github.com/doitsujin/dxvk))
- VKD3D ([gitlab.winehq.org/wine/vkd3d](https://gitlab.winehq.org/wine/vkd3d))
- D8VK ([github.com/AlpyneDreams/d8vk](https://github.com/AlpyneDreams/d8vk))
- CNC DDraw ([github.com/FunkyFr3sh/cnc-ddraw](https://github.com/FunkyFr3sh/cnc-ddraw))
- 
