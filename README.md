# Wall LED

Control a Bluetooth LED strip from your PC or Android phone, and turn it into a music light show.

Made for the cheap **LEDDMX** controllers (they advertise as `LEDDMX-00-xxxx` / `LEDDMX-03-xxxx`) that normally come with the *LED LAMP* phone app.

- **On / off, color, brightness, presets**
- **210 built-in effects** of the controller
- **Music light show**: listens to the microphone (or, on the PC, to the computer's own sound) and drives the strip in real time
  - **Show** mode flashes on every kick drum, cycles through a color theme, hits white on beat 1, breathes during breakdowns and strobes on the drop
  - **Strobe**, **Pulse** and **Spectrum** modes
  - Color themes: Neon, Fire, Ocean, Jungle, Police, Rainbow, or your own color
  - Tempo lock: detects the BPM and fires each flash slightly early to cancel out Bluetooth delay
  - DJ controls: hold Space to strobe, hold B for blackout, press D for a drop, 1–7 for themes, M for the next mode

No server, no account, no install on the PC: it's one HTML file.

## Use it on a PC (Windows, macOS, Linux, ChromeOS)

1. Open the GitHub Pages site of this repository (the link in the **About** box) in **Chrome** or **Edge** (they support Web Bluetooth). You can also download [`led-control.html`](led-control.html) and open it from disk.
2. Close the LED LAMP app on your phone first. The controller accepts only one connection at a time.
3. Click **Connect** and pick your `LEDDMX-…` strip.

To drive the show from music playing on the PC, choose **Computer sound**, then pick *Entire screen* and turn on *Share system audio* when the browser asks.

## Use it on Android

Download the APK from the [latest release](../../releases/latest), open it on your phone and allow installing from your browser or file manager. It needs Android 12 or newer.

The app shows the same page, with Bluetooth handled natively, because Android's WebView has no Web Bluetooth. On the phone the show listens through the microphone, and the screen stays on while it runs.

### Build it yourself

You need JDK 17 and the Android SDK (platform 35).

```bash
cd android
./gradlew assembleRelease
```

The APK lands in `android/app/build/outputs/apk/release/`. The build copies `../led-control.html` into the app automatically, so both versions always match.

## Protocol

The controller exposes GATT service `ffe0` with characteristic `ffe1` (write without response). Every command is 9 bytes:

| Command | Bytes |
|---|---|
| Power on | `7B 00 04 01 00 00 FF 00 BF` |
| Power off | `7B 00 04 00 00 00 FF 00 BF` |
| Color | `7B FF 07 RR GG BB 00 FF BF` |
| Brightness (pct 0–100) | `7B FF 01 (pct*32/100) pct 00 FF FF BF` |
| Built-in effect (1–210) | `7B FF 03 idx FF FF FF FF BF` |

Verified on an `LEDDMX-03` controller. The on/off frames differ from the ones documented elsewhere for these controllers (`7B FF 04 03/02 …`), which did nothing on this unit.

Thanks to [JENSLINJEMZ/ScreenSYNC](https://github.com/JENSLINJEMZ/ScreenSYNC) and [user154lt/LEDDMX-00](https://github.com/user154lt/LEDDMX-00) for documenting the color, brightness and effect commands.

## Project layout

```
led-control.html          the whole PC app (UI, Bluetooth, music show)
android/                  Android wrapper
  app/src/main/java/com/wallled/app/
    MainActivity.kt       WebView host, microphone permission
    LedBle.kt             native Bluetooth bridge exposed to the page as window.AndroidBle
```

## License

[MIT](LICENSE). Not affiliated with the makers of the LED LAMP app or the controllers.
