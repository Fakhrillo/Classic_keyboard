# Classic Keyboard

An Android keyboard (input method) styled like a mechanical typewriter: round
metal-rimmed key caps on levers over a comb of type bars, keys that swing down
when struck, a red backspace, and typewriter sounds.

## Features

- QWERTY letters, two symbol pages and three emoji pages, all on typewriter caps
- Keys swing down on their levers on every strike
- Key click, space-bar thunk and carriage-return bell (can be turned off)
- Auto-capitalisation, double-tap shift for caps lock, double-space for ". "
- Hold backspace to repeat; hold the space bar to switch keyboards
- No permissions, no network access, nothing typed leaves the keyboard

## Install

Build a debug APK and install it:

```
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

Every push also builds the APK in GitHub Actions (artifact `classic-keyboard-debug`).

Then open **Classic Keyboard** on the phone and follow the two steps: enable it
in system settings, then choose it as the current keyboard.

## Layout of the code

| File | What it does |
| --- | --- |
| `TypewriterKeyboardView.kt` | Draws the pre-rendered artwork, pressed-key patches and legends; handles touch |
| `TypewriterIME.kt` | The input method service: text entry, shift, pages, sounds |
| `Layouts.kt` | Key definitions for each page |
| `MainActivity.kt` | Setup and settings screen |
| `tools/gen_sounds.py` | Synthesises the sound effects in `res/raw` |
| `prototype/index.html` | three.js scene the artwork is rendered from (open it in a browser to try the design) |
| `tools/sprites.js` | Renders `assets/kb/` (rest image, one pressed patch per key, `keys.txt`) from the prototype |

`./gradlew testDebugUnitTest` renders each page to `app/build/renders/` so the
look can be checked without a device.

## Artwork and fonts

The key caps and mechanism in `app/src/main/assets/kb/` are rendered from
`prototype/index.html` with blank faces; the legends are drawn at run time.
To regenerate them (needs Node with `playwright` and `three@0.128.0`):

```
node tools/sprites.js "$PWD/prototype/index.html" out/   # then copy out/* to app/src/main/assets/kb/
```

Legends use Gelasio and Source Serif 4 (`assets/fonts/`), both under the SIL
Open Font License.
