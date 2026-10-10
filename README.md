# Hotspot Tile

[Türkçe](README.tr.md)

A Quick Settings tile and settings panel for the Wi-Fi hotspot on Pixel phones. I built it on a Pixel 9 Pro XL running Android 17 and it targets Android 13 and up. There is no launcher icon. You use the tile, and a long press on it opens the panel. The package name is `com.mbaha.hotspottile`.

A short tap on the tile turns the hotspot on or off. The panel lets you change the network name, password, security (WPA2 or WPA3), band, channel, device limit, Wi-Fi 6 and 7, and MAC privacy. It shows a QR code you can scan to join (behind a fingerprint check), lists the connected devices, and lets you name, trust, disconnect or block each one. It can notify you when a new device joins, and it can block anything you haven't approved. The hotspot can switch itself off when idle, after a timer, or at a set time every day. The interface is in English and Turkish.

## Install the ready-made APK

Download `hotspottile-<version>.apk` from the [Releases](../../releases) page. The app manages the hotspot through a local adb connection, so you need a computer once to set that up.

1. On the phone, turn on Developer options and USB debugging, connect it by USB, and accept the computer's prompt.
2. On the computer, with `adb` installed:
   ```bash
   adb install -g hotspottile-0.1.2.apk
   adb shell pm grant com.mbaha.hotspottile android.permission.WRITE_SECURE_SETTINGS
   adb tcpip 5555
   adb shell am start -n com.mbaha.hotspottile/.PanelActivity
   ```
3. Answer the prompts on the phone:
   - "Allow USB debugging?" for `hotspot@tile`: tick "Always allow" and confirm.
   - "Add tile to Quick Settings": add the tile. If the prompt doesn't appear, open Quick Settings, edit the tiles and add Hotspot.
   - Notification permission: allow it if you want new-device alerts.

That's all. A short tap on the tile switches the hotspot, and a long press opens the panel. The app has no launcher icon, so the tile is the only way into the panel.

### What to do afterwards

You don't need to run `adb tcpip 5555` again after a reboot. When the phone joins any Wi-Fi network and port 5555 is closed, the app turns on wireless debugging for a moment, reopens the port itself, and turns wireless debugging off again. You can disable this under Advanced in the panel.

- The first time you use a new Wi-Fi network, Android asks "Allow wireless debugging on this network?". Choose "Always allow on this network" and it won't ask again for that network.
- If the phone has only used mobile data since the reboot, there is no Wi-Fi to trigger this. Run `adb tcpip 5555` from the computer in that case.
- To update, install the new APK over the old one with `adb install -r`. Settings and permissions stay as long as the APK is signed with the same key.
- To remove the app, run `adb uninstall com.mbaha.hotspottile`.

## Build it yourself

You need a Mac with the Android SDK in `~/Library/Android/sdk` (build-tools 36.1.0 and platforms/android-36), JDK 11 or newer, and `adb`.

```bash
./build.sh            # builds build/app.apk
./build.sh install    # builds, installs, and grants WRITE_SECURE_SETTINGS (phone connected by USB)
adb tcpip 5555        # once; later reboots are handled automatically
```

There is no Gradle. Set `VERSION_NAME` and `VERSION_CODE` to version a build, for example `VERSION_NAME=0.1.3 VERSION_CODE=4 ./build.sh`. If `keystore/local.jks` doesn't exist, the script creates one. It is not committed, so keep your own copy if you plan to publish updates. `DEBUG_MODE=1 ./build.sh` produces a debuggable build for `run-as`.

## Notes

- The app is a separate install from the older `dev.hotspot.tile` package and generates its own adb key. Remove the old one with `adb uninstall dev.hotspot.tile`.
- Whether 6 GHz works depends on the country. When the system reports no 6 GHz channels (it does not in Turkey), the 6 GHz option is hidden, because a request for it would silently fall back to 5 GHz.
- New-device alerts and "trusted devices only" rely on a small helper process that runs on the phone with shell permissions. It starts when needed and shows no notification.
- While port 5555 is open, any device on the same Wi-Fi can reach it, but it still needs an adb key you approved on the phone before it can connect.
