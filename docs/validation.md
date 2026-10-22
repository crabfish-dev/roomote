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

## Address-change recovery — verified local checks, 22 October 2026

The working source adds stable saved-TV UUIDs and UUID-associated certificate
pins, migration of the eligible last-used pairing, bounded discovery-assisted recovery after
an address change, and an explicit **Update address** action. The 1.3.0 results
above predate this change and do not validate it. The final source build, Android
lint, 14 JVM tests and 65 Android instrumentation tests passed. Release guard
checks passed with 68 assertions, and the emulator crash buffer was empty.

The exact signed 1.4.0 APK (version code 6) was installed over the signed 1.3.0
copy and checked against the local TV fixture. The upgrade preserved the last-used
pairing without a new code. Home and volume commands reached the fixture. Using
**Update address** to move from `10.0.2.2` to `127.0.0.1` with the same fixture
certificate reconnected without a code; a cold start then automatically used
the new address. Disconnect, forget, and a cold start after forgetting completed
without a crash or automatic reconnection to the forgotten TV.

| Signed artifact record | Value |
| --- | --- |
| Version | 1.4.0 (6) |
| APK size | 78,172 bytes |
| APK SHA-256 | `93df0ea33806b558a884cc933185dcca944472b926232c3aa54bee2c2fc9f4de` |
| Matching mapping SHA-256 | `d7d5028d915611b93db509e5e78705a7fc19b79e17a45f0c78fb50a800dfde3e` |

These signed-artifact checks used an emulator and fixture; they did not change a
physical router's DHCP assignment or exercise its multicast behavior. A real TV
and router address-change test remains outstanding. These records establish
local verification, not website deployment or Google Play publication.

For the Play submission, Fastlane also froze the signed 1.4.0 (6) AAB with SHA-256
`09540b3f7e2ba000ec8c91a7adffeef0983b0a46b86c383556bae6b8be11bc9a` and the same
mapping hash above. It targets API 36 and contains no native libraries. An APK
derived from that exact bundle passed 10 runtime checks: a retained-pairing
upgrade from signed 1.3.0, cold-start reconnect, all 16 controls, address update,
disconnect and forget. Its compiled DEX matches the previously tested direct APK.
The temporary emulator and protocol fixture were stopped after testing.

Legacy migration preserves saved entries and the last-used selection. It copies
only the exact last-successful record's pin when its address ownership is
unambiguous. Other legacy entries need one explicit pairing; guessing their pins
from current IP addresses could connect to the wrong TV after an address swap.

The regression checklist covers:

- Preserve existing device records and last-used selection through migration;
  migrate only the eligible last-successful pin, retry an interrupted migration
  without changing UUIDs, and preserve records if writing a pin fails.
- With multiple legacy TVs, verify that missing successful-address evidence,
  ambiguous ownership and exchanged IP addresses never assign one TV's pin to
  another record. Non-last-used entries remain saved and require explicit pairing.
- Reconnect the selected TV at a new address with its existing certificate,
  without requesting a new pairing code; save the endpoint only after TLS and
  protocol readiness.
- Reject a different certificate at the old address, including another saved TV,
  then recover the original TV through discovery without replacing either pin.
- Treat matching, changed and duplicate discovery names as hints only; deduplicate
  endpoints and stay within one 30-second attempt with at most 16 candidates.
- Recover when discovery arrives after the first connection fails, and handle a
  protocol-handshake failure after the TLS connection has already succeeded.
- Retain one attempt across rotation; ignore stale callbacks after disconnect,
  forget, selecting another TV or shutdown, and end recovery cleanly when its
  deadline expires.
- Use the exact endpoint entered through **Update address**, retain the old saved
  endpoint on failure, and authenticate with the existing TV pin.

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
| Upgrade with several legacy saved TVs | The eligible last-used pairing reconnects; other saved entries remain present and require one explicit pairing before using stable identity recovery. |
| Change a paired TV's DHCP address, then reopen Roomote | Bounded rediscovery reconnects the same TV without forgetting or pairing again; its saved address changes only after the connection is ready. |
| Reassign the old address to a second paired TV | Roomote rejects that endpoint for the original TV and only connects to a candidate matching the original TV's pin. |
| Change a paired TV's advertised name as well as its address | Discovery can still recover it by validating candidate certificates; matching a name alone never authorizes a connection. |
| Cancel while searching for a moved TV | Search stops reconnecting; later discovery and socket callbacks do not revive the cancelled session. |
| Use Home, Back, all directions, and OK | TV navigates as expected without duplicate commands. |
| Use volume, mute, media, menu, and input | Observe and record TV/app-specific behavior. |
| Power the TV off and try to wake it | Record whether its service remains reachable; do not assume every standby mode supports wake. |
| Disable TV network access while connected | App leaves the ready state and allows a subsequent retry. |
| Switch Wi-Fi or leave the app | No stale discovery results or callbacks create a false connected state. |
| Block multicast but keep unicast available | New TVs can use manual entry. A saved TV can use **Update address** to reconnect at its new IP with the existing pairing. |
| Reset pairing on the TV | Reconnection fails clearly and allows pairing again. |
| Connect to an endpoint presenting a different saved identity | App rejects the changed identity and requires an explicit new pairing. |

Also inspect layout and touch targets on a physical phone, including large font settings. Emulator screenshots alone cannot assess Wi-Fi discovery, vibration, or actual television response.
