# Contributing to Roomote

Contributions can be small: a reproducible TV compatibility report, clearer instructions, an accessibility fix, or a focused code change all help.

## Report a problem

Open an issue in this repository with:

- Roomote version and whether it came from a source build or the test download.
- Phone model and Android version.
- TV model, firmware/Android version, and Android TV Remote Service version.
- Steps to reproduce, expected behavior, and the error or observed result.
- Whether discovery, manual IP entry, pairing, and basic navigation work.
- Relevant network conditions, such as guest Wi-Fi, Ethernet, or a VPN.

Remove personal details, network addresses, device identifiers, and pairing codes from public logs and screenshots. Never include private keys, passwords, or service-account files. Report suspected vulnerabilities privately as described in [SECURITY.md](SECURITY.md).

For a feature that changes the app's scope or protocol, open an issue first so the approach can be discussed.

## Make a change

1. Fork the repository and create a branch for one focused change.
2. Follow the build instructions in [README.md](README.md). Use JDK 17, Android SDK platform 36, and the included Gradle wrapper.
3. Keep network work off the main thread, preserve saved-device and pairing behavior, and handle cancellation and stale callbacks.
4. Run checks appropriate to the change and document what you tested.
5. Open a pull request explaining the problem, resulting behavior, and any remaining limitations. Include screenshots for visible UI changes.

Keep credentials and machine-specific configuration outside version control. Debug builds do not need access to the publisher's signing key, Google Play account, or hosting account.

## Check your work

For Android changes:

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

For connection, persistence, lifecycle, or Android platform behavior, also run the instrumentation tests with a development device or emulator connected:

```sh
./gradlew :app:connectedDebugAndroidTest
```

These tests use local fixtures. Add a focused regression test when fixing behavior that the existing suite would miss. Documentation-only changes do not require an Android build.

Protocol and discovery changes should also be checked with a physical TV when available. State clearly when validation was limited to a simulator. For UI changes, check large fonts, rotation, Back navigation, pairing dialogs, disconnected states, and the TV settings panel.

Use only devices you own or have permission to test. Target the intended device if several are connected, and stop any simulator or emulator you started when finished.

## Project conventions

- Use Kotlin and the existing framework UI approach; keep dependencies purposeful.
- Keep the core remote free of accounts, ads, and analytics.
- Treat TV compatibility as an observed result, not a claim based only on a brand or Android version.
- Preserve code-based pairing and saved-certificate validation. A connection error must not silently replace a saved TV identity.
- Update the relevant documentation when behavior or setup changes.
- Keep generated build output, release binaries, logs with device details, and private publishing evidence out of pull requests.

See [the architecture](docs/architecture.md) for the connection model and [the validation guide](docs/validation.md) for the physical TV checklist.
