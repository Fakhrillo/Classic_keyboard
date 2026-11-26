# Classic Keyboard

An Android keyboard (input method) styled like a mechanical typewriter: round
glossy key caps on levers, a comb of type bars across the top that kick when
you strike a key, a red backspace, and typewriter sounds.

## Features

- QWERTY letters, two symbol pages and three emoji pages, all on typewriter caps
- Keys sink and their type bar jumps on every strike
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
| `TypewriterKeyboardView.kt` | Draws the whole keyboard on one canvas and handles touch |
| `TypewriterIME.kt` | The input method service: text entry, shift, pages, sounds |
| `Layouts.kt` | Key definitions for each page |
| `MainActivity.kt` | Setup and settings screen |
| `tools/gen_sounds.py` | Synthesises the sound effects in `res/raw` |

`./gradlew testDebugUnitTest` renders each page to `app/build/renders/` so the
look can be checked without a device.
