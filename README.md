# tv-vibehack (Stremio & Peek Edition)

**Your TV, minus the ads — with a dynamic Stremio shelf and ultra-low peek dock.**
A lightweight, privacy-focused tvOS-inspired launcher fork for Android TV and Google TV sets.

This fork builds upon [Gathin23/cleanyourtv](https://github.com/Gathin23/cleanyourtv) with a rich media shelf, wallpaper peek physics, a frosted glass settings interface, and integrated calendar tracking.

<p align="center">
  <img src="docs/new-home.jpg" alt="Wallpaper-Focused tvOS Launcher on Android TV" width="100%">
</p>

### Before & After

<p align="center">
  <img src="docs/stock-vs-new-side.jpg" alt="Before and After: Stock TV vs Wallpaper-Focused Launcher" width="100%">
</p>

Everything is reversible: packages are disabled, never uninstalled, and a restore script puts the stock launcher back.

## Fork Features

- **Wallpaper-Focused Ambient Aesthetics:** Transforms your living room TV into an artful ambient display with dynamic Day/Night solar cycles.
- **Ultra-Low Dock Peek Mode:** Minimizes the dock down towards the bottom bezel when unfocused so your wallpapers and ambient art remain unobstructed. Seamlessly springs back up when pressing DPAD Down.
- **Stremio Integration & Media Shelf:** Adds an auto-populated shelf for continue-watching and trending Stremio media with cached posters and seamless leanback launching.
- **Frosted Glass Settings:** A leanback tvOS-style frosted glass settings overlay allowing you to toggle dock peek depth (Ultra / Balanced / None), pill styles (icon-only vs. text+logo), and themes.
- **Dynamic Day/Night Theme Cycle:** Automatically transitions wallpapers between day and night based on the local solar cycle.
- **Calendar & Schedule Overlay:** Lightweight, keyboard/dpad friendly month calendar with day tracking and agenda support.
- **Smart App Labels:** Keeps the interface clean by revealing app titles only under the currently focused item.
- **Remote Video Key Shortcut:** Accessibility service helper to map remote media/video shortcuts to launch Stremio directly.

| Dynamic Wallpaper & Peek Dock Mode | Dynamic Stremio Media Shelf |
|---|---|
| ![Ultra-low dock peek mode](docs/dock-peek-wallpaper.png) | ![Stremio shelf and dock apps](docs/stremio-shelf.png) |

| Frosted Glass Settings Overlay |
|---|
| ![Frosted glass settings](docs/settings-frosted.png) |

## Quick start

1. On the TV: Settings → Device Preferences → About → press OK on **Build** seven times → back →
   Developer options → turn on **USB debugging** (and Network/ADB debugging if your TV shows it).
   Keep the TV on and on the same Wi-Fi as your computer.
2. Open Claude Code (or your assistant of choice) on your computer and paste the prompt from
   [`PROMPT.md`](PROMPT.md), filling in your TV model.
3. Accept the "Allow USB debugging?" prompt on the TV when told to, then answer the checklist
   questions (which apps you use, which apps go in the dock).

That's it. The assistant backs up, debloats, builds and installs the launcher, and verifies each
step with screenshots from the TV. If you'd rather do it by hand, the pieces are below.

## What's in the repo

| Path | What it is |
|---|---|
| [`PROMPT.md`](PROMPT.md) | The prompt. Brand-neutral; the assistant discovers your TV's bloat. |
| [`launcher/`](launcher/) | The tvOS-style launcher with Stremio shelf and frosted settings. Configure in `app/src/main/res/values/config.xml`, then run `build-and-install.sh`. |
| [`tools/tv.py`](tools/tv.py) | Pure-Python ADB client (shell, batch, push, pull). Works where the stock `adb` is blocked. |
| [`tools/b64push.py`](tools/b64push.py) | Uploads larger files (APKs) as base64 chunks with an md5 check. |
| [`tools/disable.sh`](tools/disable.sh), [`tools/restore.sh`](tools/restore.sh) | Apply or undo a debloat list. |
| [`examples/`](examples/) | Per-TV debloat lists. Add yours, see [CONTRIBUTING.md](CONTRIBUTING.md). |

## The launcher

A native, snappy Android TV home screen (`com.gathin.tvhome`), optimized for sets with 1 GB of RAM:

- Pitch-black background or dynamic Day/Night wallpapers.
- Stremio shelf row with instant launch and poster previews.
- A dock at the bottom in a rounded band with the apps you choose. Ultra-low peek mode minimizes the dock to let wallpapers shine.
- Translucent frosted glass highlights with smooth focus physics.
- Top pills: **Inputs** (HDMI ports, AV and Live TV), **Wi-Fi** (network status), **Settings** overlay and **Calendar** overlay.
- Large thin clock and greeting top left, set in [Outfit](https://fonts.google.com/specimen/Outfit).
- Fast D-pad navigation, last focused tile remembered, responsive animations.

Configure it in [`launcher/app/src/main/res/values/config.xml`](launcher/app/src/main/res/values/config.xml):
dock order, hidden apps, Live TV package and columns. Build and install with

```bash
TV_HOST=192.168.1.50 launcher/build-and-install.sh
```

Needs JDK 17 and the Android SDK (platform 34, build-tools 34.0.0). On macOS:
`brew install openjdk@17 android-commandlinetools && sdkmanager "platforms;android-34" "build-tools;34.0.0"`.

## Talking to the TV by hand

```bash
python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt
export TV_HOST=192.168.1.50                                   # your TV's IP
tools/venv/bin/python -I tools/tv.py --probe                 # model, Android version, firmware
tools/venv/bin/python -I tools/tv.py 'pm list packages'      # one command
printf 'cmd1\ncmd2\n' | tools/venv/bin/python -I tools/tv.py --batch
tools/disable.sh examples/xiaomi-mi-tv-4a/debloat.txt        # apply a list
tools/restore.sh examples/xiaomi-mi-tv-4a/debloat.txt        # undo it, restore stock launcher
```

### Gotchas we hit

- **macOS: `adb connect` says "No route to host"** while the port is clearly open. Recent macOS
  treats the Google-signed `adb` as its own app under Local Network privacy and silently blocks it.
  `tools/tv.py` avoids the binary entirely.
- **No "wireless debugging" toggle** on some TVs (Xiaomi among them). USB debugging alone opens
  network ADB on port 5555.
- **Chained `pm` commands** (`pm disable a; pm disable b`) may silently stop after the first. Run
  one per shell call; `--batch` does this.
- **Large pushes hang** through the Python client. Use `tools/b64push.py` for APKs.
- **Disable the stock launcher only after the new one is installed**, or the Home button lands on
  a factory test app.
- **Third-party launchers** often charge for basics like a custom wallpaper. Hence `launcher/`.
- **No on-screen keyboard** after switching launchers (seen on Xiaomi): the default input method
  pointed at a Baidu IME that isn't installed. Fix with
  `tools/tv.py 'ime set com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME'`.

## Supported TVs

Anything running Android TV or Google TV: Xiaomi/Redmi, TCL, Hisense, Sony, OnePlus, Realme,
Nvidia Shield and most others. Amazon Fire TV works with a workaround for the locked home screen,
see [`docs/fire-tv.md`](docs/fire-tv.md). Not Samsung Tizen or LG webOS. Tested so far on the models in
[`examples/`](examples/).

## Contributing

Add your TV's debloat list, improve the launcher, or sharpen the prompt. See
[CONTRIBUTING.md](CONTRIBUTING.md).

## Licence

MIT, see [`LICENSE`](LICENSE). The Outfit typeface is under the SIL Open Font License, see
`launcher/FONT-LICENSE-OFL.txt`.
