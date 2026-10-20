# Validation

## Recorded release checks — 20 October 2026

The 1.3.0 release passed 14 JVM tests, 23 Android instrumentation tests, Android
lint with no errors, and 15 manual checks of an APK derived from the signed AAB.
Manual checks covered cold start, pairing, rotation, remote keys, reconnect,
disconnect, forgetting a TV, large fonts, and connection failures. A cosmetic
heading wrap at 200% font remained; controls were reachable.

The signed-release protocol checks used an API 36 emulator and the local TV
fixture. Earlier physical-phone testing established pairing and reconnection
with one TV, and the user reported that most buttons worked. These results do
not establish compatibility with every model or standby mode. Private device
captures and publisher records are intentionally not included in this repository.

## What local checks establish

Compilation establishes that the app can be packaged. Local protocol tests can check encoding, parsing, pairing calculations, and error behavior without contacting a television. An Android phone emulator can check launch, layout, navigation, and error states.

The observed live connection establishes pairing with one local TV. Its exact model and firmware were not independently identified. The simulated tests do not establish compatibility with other TVs; key effects and standby still require the checklist below.

## Development checks

Run the project's checks from its root:

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

The results above apply to the checked revision. Rerun the appropriate checks after changing the implementation.

## Android TLS integration tests

With a development phone or emulator connected:

```sh
./gradlew :app:connectedDebugAndroidTest
```

To install and invoke the instrumented test APK explicitly through ADB:

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r com.elvi.tvremote.test/androidx.test.runner.AndroidJUnitRunner
```

If more than one Android device is connected, add `-s DEVICE_SERIAL` to each `adb` command.

`RemoteClientIntegrationTest` runs a loopback TLS server on the Android device. It creates a temporary RSA identity in AndroidKeyStore and uses its own protocol encoder and pairing-proof calculation. It exercises:

- Mutual TLS and the pairing transcript, including verification of the client proof and reconnecting from a newly created client instance with persisted credentials.
- Negotiation and readiness, fragmented frame prefixes, combined frames, a Home key event, a ping response, and volume/power callbacks.
- Rejection of a replacement server identity after pairing.
- Cancellation while the client is waiting for a pairing response.

Test server aliases and the `127.0.0.1` pairing pin are removed during cleanup. This checks Android's actual TLS and keystore integration but does not simulate a router's multicast behavior or establish interoperability with a real Remote Service.

## Physical device checklist

Record the phone model/Android version, TV model/firmware, and installed Android TV Remote Service version for each run.

| Check | Expected outcome |
| --- | --- |
| Discover an awake TV on a normal home LAN | TV appears with a usable address. |
| Select a new TV | TV displays a fresh code; controls remain unavailable until ready. |
| Enter a correct code | Pairing completes and a remote connection becomes ready. |
| Enter a wrong or expired code | An understandable failure appears; another pairing attempt is possible. |
| Cancel pairing | Attempt closes without saving a successful pairing. |
| Reopen the app and select the paired TV | Saved credentials reconnect without asking for a new code. |
| Use Home, Back, all directions, and OK | TV navigates as expected without duplicate commands. |
| Use volume, mute, media, menu, and input | Observe and record TV/app-specific behavior. |
| Power the TV off and try to wake it | Record whether its service remains reachable; do not assume every standby mode supports wake. |
| Disable TV network access while connected | App leaves the ready state and allows a subsequent retry. |
| Switch Wi-Fi or leave the app | No stale discovery results or callbacks create a false connected state. |
| Block multicast but keep unicast available | Manual IP entry can still reach a supported TV. |
| Reset pairing on the TV | Reconnection fails clearly and allows pairing again. |
| Connect to an endpoint presenting a different saved identity | App rejects the changed identity and requires an explicit new pairing. |

Also inspect layout and touch targets on a physical phone, including large font settings. Emulator screenshots alone cannot assess Wi-Fi discovery, vibration, or actual television response.
