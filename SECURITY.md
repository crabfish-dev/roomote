# Security policy

## Report a vulnerability privately

Email [santu0002@gmail.com](mailto:santu0002@gmail.com) with the subject **Roomote security report**. Please do not open a public issue containing exploit details or sensitive data.

Include the affected Roomote version or commit, the phone and TV software versions, a description of the impact, and the steps needed to reproduce it. A small proof of concept using your own devices or the local test fixture is helpful.

Do not send signing keys, pairing private keys, account passwords, service-account files, or personal data. Redact unrelated device details and network identifiers from logs.

The maintainer will assess the report and coordinate a fix and disclosure where needed. This project does not offer a guaranteed response time or a bug bounty. Security fixes are made on the current development branch; older releases do not have a separate backport schedule.

## Security boundaries

Roomote pairs with a TV through a code displayed on that TV. The app stores the authenticated TV certificate fingerprint and rejects an unexpected replacement. The phone's private pairing key stays in Android Keystore.

The app operates on a reachable local network. Discovery announcements and the public pairing certificate are not secrets; a TLS 1.2 handshake may expose the public certificate. Remote commands are carried through TLS. The app does not open a Roomote cloud account or send diagnostics to a Roomote backend.

Forgetting a TV removes its local saved details and certificate fingerprint, but does not revoke the phone's authorization on the TV. Use the TV's own settings for revocation. See the [privacy policy](https://roomote.crabfish.dev/privacy/) for data and retention details.

Only test devices and networks you own or have permission to assess. Avoid disrupting other people's TVs or exposing their pairing information.
