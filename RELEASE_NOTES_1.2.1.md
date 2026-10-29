# Winlator Mali Bionic 1.2.1

A fixes-and-polish update to 1.2. **It installs over 1.2: your containers, games and settings are kept.**

## Fixes for 1.2 user reports
- Fixed a reboot on some MediaTek Mali phones (JM Mali).
- Fixed a crash when opening a shortcut.
- Fixed the controller binding editor.
- Fixed the stutter when holding D-pad right.
- Fixed File Manager not opening (it needed `libcdio.dll`), and File Manager hanging on x86_64 Wine (replaced with Bannerlator's newer `wfm.exe` / `winhandler.exe`).

## Graphics and performance
- **Fullscreen modes:** Fit, Stretch, Fill and Integer, plus screen alignment (Center, Top, Bottom half), set per container or per game and changeable live in Display & Session.
- **FPS limiter:** reworked on a sleeping scheduled timer (as in Winlator-Ludashi), so the limiter no longer spins a thread at maximum priority.
- **EGL stripes fixed:** CPU writes into the window buffer are flushed per X batch.
- **ZeroFG:** the default multiplier is capped at the display refresh rate (40 fps on a 60 Hz screen now gives 1.5x instead of 2x with dropped frames).
- **Gladio:** refuses OpenGL contexts above 3.3 again; libGL stripped of debug info (1.48 MB to 488 KB).
- **D7VK** is its own content type, with a DDraw wrapper option.

## Wine, Proton and containers
- Wine/Proton setup follows Bannerlator: each container uses its own prefix only, layer DLLs are copied and refreshed, `EnableLUA=0`, the prefix is updated before a session, and no always-present `event0`.
- The default container pattern is updated: AIO and controller tests, Pale Moon, libcdio, winlauncher, our install scripts and 7z.
- **Contents:** badges for DXVK/VKD3D (arm64ec or x86), FEXCore (unixlib or DLL), Box64/WOWBox64 and NTSync; same-named entries labelled by release folder; Wine/Proton without arm64ec in the name are marked x86_64; unknown content types (Turnip/Rutabaga driver zips) are no longer listed as Wine.

## Controls
- **Move Cursor to Touchpoint** (from brunodev85): a tap clicks where you touch, and a one-finger drag moves the cursor there.
- **Simulate touch screen** (from brunodev85), in Settings > Mouse under Cursor Speed.

## Log Manager (new)
Modelled on Bannerlator, in our UI:
- Per-game log folders with run history.
- Wine debug with channels, Box64/FEXCore, DXVK/VKD3D, logcat, crash reports and exit reasons.
- A viewer with find, wrap, copy and share, and View logs on each shortcut.
- A master Logs switch: off records nothing anywhere.
- It lives in Settings > Advanced.

## Interface
- Side menu follows every theme preset, with the version under the title, an inset selected pill and compact rows.
- Containers screen: cards on the preset surface, with arch chip, Wine/Proton version and screen size.
- Community Configs: same-named games merged (counts summed, configs of all ids listed), and Mali / Adreno badges spelled out.

## Flavors
- **Standard** `com.winlator.mali`: recommended.
- **Ludashi** `com.ludashi.benchmark`
- **AnTuTu** `com.antutu.ABenchMark`

Contact: [Telegram group](https://t.me/+RDjo9_ORqXgwODc1) · [Ko-fi](https://ko-fi.com/gunacharanteja)