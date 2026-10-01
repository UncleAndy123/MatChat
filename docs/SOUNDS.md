# Bundled notification sounds

Drop custom notification sounds into **`app/src/main/res/raw/`** and they ship
inside the APK.

- **Formats:** `.ogg` (preferred), `.mp3` or `.wav`.
- **File names:** lowercase letters, digits and `_` only, starting with a letter,
  e.g. `chime_soft.ogg`. This is an Android resource-name rule; anything else
  fails the build. Two files may not share a name with different extensions.
- **Display name:** from the file name: `chime_soft.ogg` shows as "Chime soft".
- Only put sounds in this folder: every file in `res/raw/` is treated as one.

## How they reach the sound pickers

Android's sound picker can't see inside an APK, so `BundledSounds`
(`app/.../notify/BundledSounds.kt`) copies them into the phone's own
Notifications sound list, in a `MatChat` folder. From there they appear in both
pickers: Settings > Notifications > Sound (the app sound) and Room info >
Notification sound (one room).

- **Android 10 and later:** copied automatically when the app starts. No
  permission is needed.
- **Android 7–9:** this needs storage access. The first time the user opens
  either sound picker, MatChat asks for it, copies the sounds, then opens the
  picker. If the user says no, the picker still opens without MatChat's sounds,
  and the screen says why. It asks again next time.

Copying runs once per app version, and skips sounds already on the phone, so
sounds added in an update are copied after it. Removing a file from the folder
does not remove the copy already on a phone.
