# Roomote

<img src="branding/roomote-icon-512.png" alt="Roomote app icon" width="112" height="112">

An Android phone remote for Android TV and Google TV. Discover a compatible TV on your local network, pair with its on-screen code, and control it from your phone.

Roomote is free, with no ads, purchases, accounts, or analytics. The app talks directly to your TV; it does not need a Roomote server.

[Website](https://roomote.crabfish.dev/) · [Download the test APK](https://roomote.crabfish.dev/test/) · [Report an issue](https://github.com/crabfish-dev/roomote/issues) · [Support](https://roomote.crabfish.dev/support/) · [Privacy](https://roomote.crabfish.dev/privacy/)

The direct download page includes installation instructions. The test APK and Google Play builds use different signing certificates: switching between them requires uninstalling the existing copy and pairing your TVs again.

## Features

- Local TV discovery and manual IP address entry.
- Six-character TV-code pairing and saved devices.
- Automatic reconnection to the last successfully connected TV, including discovery-assisted recovery after its IP address changes.
- Directional pad, OK, Home, Back, Menu, and input selection.
- Volume, mute, playback, and power controls.
- Connection management, help, and privacy information in TV settings.

Key behavior depends on the TV firmware and active app. This version does not provide voice input, a text keyboard, casting, screen mirroring, Bluetooth or infrared control, or ADB control.

Roomote is an independent project and is not affiliated with Google or a TV manufacturer. Its client implements the community-documented Android TV Remote Service v2 protocol; it does not use an official Google remote SDK.

## Compatibility

| Device or configuration | Expected support |
| --- | --- |
| Android phone running Android 8.0 / API 26 or newer | App installation supported. |
| Android TV or Google TV with Android TV Remote Service v2 protocol support | Intended target. The v2 protocol was introduced in Remote Service v5; the installed service version matters independently of the TV's Android version. |
| A specific brand or TV model | Compatibility must be checked on that device. Roomote does not restrict access to a brand allowlist. |
| TV with only the older Remote Service v1 protocol | Not supported by this version of Roomote. Update the service through the TV's supported update mechanism if an update is available. |
| Fire TV, Roku, Samsung Tizen, LG webOS, or Android boxes without Google's Remote Service | Not supported by this implementation. |
| TV asleep with its network remote service still reachable | Power key may wake it; behavior depends on TV settings and firmware. |
| TV unplugged or in deep standby with networking off | Cannot be reached or powered on by this app. |

The phone and TV must be on the same reachable local network. A TV connected to the router by Ethernet can work with a phone on that router's Wi-Fi. Guest-network isolation, VPN routing, and multicast filtering can prevent discovery or control.

## Connect to a TV

1. Turn on the TV and connect the phone to the same local network.
2. Open Roomote and tap the TV icon at the top right to open **TV settings**. Select a discovered or saved TV. If discovery does not find it, enter the TV's IP address from its network settings.
3. Start pairing and enter the six-character code displayed on that TV. Codes can contain hexadecimal letters as well as digits.
4. After the connection is ready, use the directional pad, OK, Home, Back, volume, and playback controls.
5. Roomote automatically reconnects to the last successfully connected TV when you open it again. You can switch TVs from the TV icon. A router assigning a new IP address does not require another pairing code when Roomote can rediscover and authenticate the TV.

The main screen keeps the remote controls together. TV settings contains connection status, discovery, manual address entry, disconnect/reconnect, pairing, forgetting a TV, and connection help. For a saved TV, **Update address** lets you enter its new IP while keeping the existing pairing. A small indicator on the TV icon shows the current connection state.

Automatic reconnect makes one recovery attempt when the app opens or returns to the foreground, and leaves an existing connection or pairing in progress alone. With a saved pairing, it searches for up to 30 seconds and tries at most 16 distinct endpoints, using the saved address and local discovery results. Every candidate must authenticate as the saved TV. If discovery is blocked, use **Update address** for that saved TV instead of forgetting it. If the TV is unavailable, tap the TV icon to retry.

Disconnect cancels the recovery attempt and stays disconnected until the next foreground visit; forgetting the last TV also clears the automatic selection. TV names and IP addresses are discovery hints, not proof of identity. A changed certificate or revoked authorization still requires explicit pairing with a fresh on-screen code.

On upgrade, existing saved TVs receive stable local IDs and retain the last-used selection. The last successfully connected TV's pairing migrates when its recorded identity and successful address are unambiguous. Other TVs saved by older versions need one explicit pairing to establish their new identity; their saved entries remain available. This avoids assigning another TV's old IP-based certificate to them after addresses have changed. Subsequent IP changes use the stable pairing and do not require that migration step again.

Pairing with this protocol does not require enabling developer options, ADB, or USB debugging. Each phone has its own pairing identity.

## Build from source

Use **JDK 17**, the **Android SDK with platform 36**, and the included Gradle wrapper. The app compiles against and targets API 36; its minimum is API 26. Open the repository in Android Studio, or configure `ANDROID_HOME` to point to your SDK.

```sh
./gradlew :app:assembleDebug
```

On Windows, use `gradlew.bat` in place of `./gradlew`.

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. To install it on a connected development device or emulator:

```sh
./gradlew :app:installDebug
```

A debug build needs no publisher credentials. If another Roomote build with a different signing certificate is installed, use a separate development device or uninstall that copy first; uninstalling clears saved TVs.

The optional Fastlane setup exposes `fastlane debug` and `fastlane unit_test`. Release signing and publishing are maintainer operations that require separate private credentials. No signing keys or publishing credentials are included.

## Run checks

Build, run JVM tests, and check Android lint:

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

With a development device or emulator connected, run the instrumentation suite:

```sh
./gradlew :app:connectedDebugAndroidTest
```

Instrumentation tests cover Android TLS and keystore behavior, pairing, saved devices, automatic reconnection, and controller lifecycle handling. They use local test fixtures and do not require a real TV.

For manual UI checks without a TV, an optional simulator uses Node.js and OpenSSL:

```sh
node scripts/tv-simulator.mjs
```

In an Android emulator, enter `10.0.2.2` as the TV address and use the pairing code printed by the simulator. The fixture binds only to loopback and does not advertise discovery. Type `quit` to stop it and remove its temporary identity.

Emulator and simulator results do not establish compatibility with a real TV or its standby behavior. Test discovery, pairing, and key effects on physical TVs when changing the protocol or connection logic.

## Privacy and security

Saved TV details and certificate fingerprints remain in private app storage. Each saved TV has a stable local UUID, and its authenticated certificate is pinned to that identity rather than its current IP address. Roomote only saves a rediscovered or manually updated address after TLS authentication and the remote protocol handshake succeed. The phone's pairing private key is generated in Android Keystore. Pairing and remote control use TLS; the public pairing certificate can be visible in a TLS 1.2 handshake, and local discovery announcements are not encrypted.

**Forget TV** removes that TV's saved details and certificate fingerprint. It does not delete the phone's shared pairing identity or revoke authorization stored by the TV. Clear Roomote's storage or uninstall it to remove local app data; use the TV's own paired-device settings to revoke access there.

See the [privacy policy](https://roomote.crabfish.dev/privacy/) for details and [SECURITY.md](SECURITY.md) for private vulnerability reporting.

## Troubleshooting

| Symptom | What to check |
| --- | --- |
| No TVs appear | Confirm both devices share a reachable LAN. Try manual IP entry. Guest Wi-Fi or multicast filtering may block discovery. |
| Manual IP also cannot connect | Confirm the address, that the TV is awake, and that Android TV Remote Service is installed and enabled. V2 normally listens on TCP 6466 and 6467. |
| No pairing code appears | Check the Remote Service version and any available TV/service updates. An unsupported service cannot be fixed by changing the phone UI. |
| Pairing code is rejected | Use the current code shown by the selected TV, including any letters. Start a new pairing attempt if it has expired. |
| A saved TV's IP address changes | Reopen Roomote or select the saved TV to trigger bounded rediscovery. If multicast is blocked, use that TV's **Update address** action. The saved pairing is retained. |
| A saved TV still rejects the connection | Confirm the address and service availability. A reset certificate or revoked phone authorization requires explicit pairing with a new TV code. |
| Another previously saved TV needs pairing after an upgrade | Older versions did not retain enough identity information for every TV. Select its saved entry and pair once. The most recently used pairing migrates when its ownership can be established safely. |
| Connection works but a key has no effect | Try Home or a directional key. Input, volume, and media handling can vary with firmware, the current app, and an external audio system. |
| Power off works but power on does not | The TV may disable networking in standby. Review the TV's supported standby settings; the app cannot wake a network service that is offline. |

As a useful compatibility check, try pairing the same TV with the Google TV phone app. Successful pairing there is evidence that the TV exposes the service, but is not proof that every Roomote function has been validated.

## Contributing

TV compatibility reports, fixes, accessibility improvements, and documentation contributions are welcome. Start with [CONTRIBUTING.md](CONTRIBUTING.md). Include the phone and TV models, their software versions, and the Android TV Remote Service version when reporting a connection problem.

## Project guide

- [Architecture and technical plan](docs/architecture.md)
- [Small Kotlin integration example](docs/kotlin-example.md)
- [Validation checklist and current test boundaries](docs/validation.md)
- [Signing and releasing your own distribution](docs/releasing.md)
- [Branding source assets](branding/README.md)
- [Website source](website/)

Primary references: [Google's phone-remote setup guide](https://support.google.com/androidtv/answer/6122465), [community protocol documentation by its author](https://github.com/Aymkdn/assistant-freebox-cloud/wiki/Google-TV-%28aka-Android-TV%29-Remote-Control-%28v2%29), and [the androidtvremote2 reference implementation](https://github.com/tronikos/androidtvremote2).

## License

Roomote's original source and assets are available under the [MIT License](LICENSE), so you can use, modify, and redistribute them, including in commercial projects, while retaining the required notice. Third-party components retain their own licenses; see [third-party notices](THIRD_PARTY_NOTICES.md).
