# Architecture and technical plan

## Target

Provide a phone remote for any reachable Android TV or Google TV that supports Android TV Remote Service v2. Device support is determined by the installed service and firmware behavior, not a brand allowlist. The Mi TV 4X is an intended test candidate, not a certified device.

The Android app uses Kotlin and framework UI components, with a minimum phone API level of 26. Protocol operations are kept off the UI thread.

## Components

| Component | Responsibility |
| --- | --- |
| Activity and UI | Keep remote controls on the main screen. The top-right TV icon opens TV settings with discovery, saved devices, connection state/actions and help. Collect the TV pairing code in a separate dialog. |
| Discovery | Use Android `NsdManager` to resolve `_androidtvremote2._tcp` advertisements to an address and remote-service port. Advertisements supply candidate addresses, not trusted identity. Manual entry and a saved TV's Update address action cover networks where advertisements cannot be resolved. |
| Connection controller | Serialize user actions, manage pairing and connection transitions, perform bounded recovery of the last used TV on foreground entry, and deliver state changes to the main thread. |
| Remote client | Establish TLS connections, perform code pairing, frame protocol-buffer messages, negotiate control, send key events, and handle incoming keepalives and state. |
| Local persistence | Keep a stable UUID for each saved TV, its endpoint and discovery hint, the last successful TV's UUID, and certificate pins associated with UUIDs. Only refresh an endpoint after authentication. Migrate the uniquely identified last-successful legacy pairing; preserve other records for explicit pairing. |

The production source is under `app/src/main/java/com/elvi/tvremote/`. `MainActivity.kt` owns the screen, `RemoteController.kt` owns state and the worker executor, `TvDiscovery.kt` resolves services, and `protocol/RemoteClient.kt` owns the network sessions. `ConnectionServices.kt` supplies injectable connection and discovery boundaries for controller tests. `SavedTvStore.kt` handles device records and their identity migration; `LastUsedTvStore.kt` tracks the last successful identity. The client identity lives in AndroidKeyStore; device metadata and TV certificate pins use private app preferences.

Discovery is advisory: a service name is not proof of identity, and an advertised endpoint can disappear. The client must complete pairing or authenticate a saved relationship before the UI reports that it is ready.

## Connection flow

```text
Discover or enter address
        |
        v
Select TV ---- saved pairing ----> Connect
        |                            |
        v                            v
Start pairing                   Protocol ready
        |                            |
        v                            v
TV displays code              Send remote keys
        |
        v
Validate code and finish pairing
        |
        +------------------------> Connect
```

Pairing uses TCP port 6467 by default. The normal remote session uses TCP port 6466 by default; discovery supplies the remote endpoint when available. Both connections use TLS with a client identity. Pairing proves knowledge of the code displayed on the TV and associates the client with that TV.

After opening the remote socket, the client must complete the protocol handshake and maintain the read loop. A successful TCP or TLS connection alone does not mean key injection is ready. Writes must preserve message boundaries, and the reader must handle partial and combined socket reads.

Disconnect, forget, and pairing cancellation detach their sessions synchronously. When initiated on the main thread, physical socket closure runs on a separate cleanup executor: closing a TLS socket can send network traffic. This executor is independent of the connection worker so it can interrupt blocked reads. Cleanup captures the old socket only, and cannot close a replacement session.

On each foreground entry, an idle or failed session makes one automatic recovery attempt to the last successfully connected TV. Active sessions and pairing attempts are left alone. A saved pairing allows a recovery window of up to 30 seconds with at most 16 distinct address/port candidates. Candidates come from the saved endpoint and local DNS-SD results; a matching discovery service name takes priority but does not establish trust. Duplicate endpoints are not retried within that attempt, and the app does not scan arbitrary network addresses.

Every candidate uses the selected TV's UUID-associated certificate pin, including when another saved TV now occupies the old address. Synchronous connection failures and asynchronous protocol-handshake failures can advance to another candidate. The controller commits a recovered endpoint, discovery hint and last-used selection only after both TLS authentication and protocol readiness. A discovered address cannot replace saved metadata just because its service name matches.

Each user operation has a generation, and each candidate has a separate attempt token. Late socket or discovery callbacks cannot revive a cancelled attempt or update a replacement connection. Disconnect, forget, pairing, selecting another TV, and controller shutdown invalidate pending recovery and its deadline. When recovery expires, the app preserves the saved pairing and offers a retry or **Update address**. This explicit address action authenticates the supplied endpoint with the existing pin and saves it only after readiness. A failed saved-TV connection never silently starts pairing or replaces its certificate.

`LastUsedTvStore` resolves only the saved UUID after migration. Forgetting that TV clears the automatic choice without selecting another saved TV. For upgrades from versions that did not track use order, exactly one saved TV is an unambiguous initial choice.

Disconnects, expired pairing attempts, unavailable services, and mismatched saved identities become recoverable UI states. After an automatic connection failure, the main screen offers access to TV settings for manual retry. The app does not continuously retry or replay a key after a new connection because a repeated power or navigation command can have a different effect.

## Protocol selection

Remote Service v5 introduced the v2 protocol used here. The TV operating-system version does not identify the installed service version because the service can be updated separately. There is no generic publicly documented Google controller SDK used by this app: this client follows community protocol descriptions and reference implementations.

ADB is deliberately outside the implementation scope. It requires a debugging relationship and has different setup requirements, especially on older TV firmware. Chromecast is also separate from navigation through the remote service; media casting is not part of this app.

## Pairing identity and trust

The TV generally presents a self-signed certificate, so ordinary public web certificate validation is not sufficient for this protocol. Pairing binds the on-screen code to the client and server public keys. The authenticated certificate fingerprint is stored under the saved TV's UUID rather than its IP address. UUIDs identify local records; the pinned certificate authenticates the remote peer. Neither a UUID, an advertised name, nor a familiar address alone proves which TV is responding. A changed certificate or TV-side authorization reset requires explicit pairing with a fresh code.

Migration creates a persisted UUID plan for every existing device record. Earlier versions could overwrite a saved endpoint from discovery before authentication, so even a unique current address cannot establish which TV owns an old address-based pin. Only an exact, unique last-used record with a recorded successful address is eligible for automatic pin migration. If another saved record now claims that address, migration treats the pin ownership as ambiguous and does not copy it.

The eligible pin is durably copied before the device schema and last-used selection advance together. Retrying an interrupted migration reuses its UUID plan; a failed pin write prevents the original records from being replaced. Existing stable pins are never overwritten. Other legacy records remain saved, but need one explicit pairing to associate an authenticated certificate with their new UUID. This also applies when the successful-address evidence or legacy pin is missing. Once established, the stable pairing supports later address changes without another migration or pairing code.

Pairing secrets and private key material must not appear in logs, documentation, or version control. Their persistence must remain private to the installed application and be excluded from backup where appropriate. Removing local saved state does not necessarily revoke that client's identity on the television; TV-side revocation depends on the service and its settings.

## Capability boundaries

| Capability | Behavior |
| --- | --- |
| Directional pad, OK, Home, Back | Send Android key codes through the negotiated remote session. |
| Volume and media | Send key events; honor the TV's reported state when available. External audio routing may change behavior. |
| Menu and input | Send the corresponding key. Firmware may ignore it or show a TV-specific screen. |
| Power | Send a power key to an already reachable service. Deep standby and hard power-off cannot be solved through an offline socket. |
| Text, voice, app launch shortcuts | Possible protocol extensions, not implemented in this version. |
| Casting, infrared, Bluetooth, Remote v1, ADB | Separate transports or protocols, not implemented. |

## Delivery and validation plan

1. Build the phone UI and connection state handling.
2. Implement discovery and manual address selection.
3. Implement pairing, saved identity checks, protocol negotiation, and basic remote commands.
4. Build the APK, run focused local tests, and inspect the app on an Android phone emulator.
5. Validate pairing and controls on a physical Android TV, including the Mi TV 4X if available.
6. Before a public release, test additional brands and service versions, assess current Android target-SDK requirements, and complete release signing and store work separately.

Only completed validation should be reported as passed. A build, unit test, or emulator smoke test does not replace a live pairing test. See [the validation checklist](validation.md).

## Sources

- [Android NSD documentation](https://developer.android.com/develop/connectivity/wifi/use-nsd) explains local service discovery and lifecycle cleanup.
- [Android TV Remote v2 protocol description](https://github.com/Aymkdn/assistant-freebox-cloud/wiki/Google-TV-%28aka-Android-TV%29-Remote-Control-%28v2%29) documents pairing, remote framing, and the v5 service transition.
- [androidtvremote2](https://github.com/tronikos/androidtvremote2) is a maintained Python reference implementation of this protocol.
- [androidtv-remote](https://github.com/louis49/androidtv-remote) explicitly identifies its client as an implementation of reverse-engineered protocols.
- [Android ADB documentation](https://developer.android.com/tools/adb#wireless) describes the separate debugging connection and older-device setup.
- [Android local network permission documentation](https://developer.android.com/privacy-and-security/local-network-permission) should be revisited when raising the app's target SDK; discovery and direct sockets are both subject to evolving platform rules.
