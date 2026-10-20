# Building and releasing your own distribution

The public repository includes build and publishing helpers, but no signing
material, Play credentials, private device captures or Console audit records.
Ordinary development only needs the [debug build instructions](../README.md).

Before distributing a fork, choose your own application ID, publisher details,
support contact, privacy policy and hosting configuration. Update these package
settings together:

- `applicationId` in `app/build.gradle.kts`.
- `PACKAGE` in `scripts/release_support.rb`, which controls signing paths,
  bundle validation and release manifests.
- The per-app credential path in `fastlane/Appfile` and package settings in
  your private Fastlane environment.
- The package directory and certificate identity in `scripts/create-upload-key.py`,
  if using that optional helper.

Also review the app manifest, in-app support links, Fastlane metadata,
`fastlane/publisher.json` and website content. Do not submit a fork under
Roomote's existing Google Play identity.

## Signing

Use your own upload key and preserve it for future updates. Keep the keystore,
passwords and Play service-account JSON outside this checkout. The supplied
helpers conventionally use:

```text
~/.android-dev/secrets/<package>/upload.jks
~/.android-dev/secrets/<package>/signing.env
~/.android-dev/secrets/<package>/play-service-account.json
```

The private `signing.env` contains `ANDROID_KEYSTORE_FILE`,
`ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` and `ANDROID_KEY_PASSWORD`.
Restrict its permissions to the owner. Do not place passwords in command-line
arguments or logs. `fastlane/.env.example` documents the configuration; its local
`.env` counterpart is ignored.

`scripts/create-upload-key.py` is a maintainer-specific, one-time helper that
refuses to replace an existing key. It has no command-line options. Adapt its
package directory and certificate identity for your own distribution before
running it.

## Build once, test and preserve

Install Fastlane with Bundler (`bundle install`). Review the existing
`fastlane/Fastfile` and use explicit version values:

```sh
bundle exec fastlane freeze_release version_code:6 version_name:1.4.0 output:releases/6-1.4.0-candidate
```

Replace the example version with your distribution's next version. The lane
builds a signed bundle, validates its package/version/target and signer, then
freezes the AAB, obfuscation mapping, current metadata and a SHA-256 manifest.
It refuses to overwrite a destination.

Derive installable APKs from that exact AAB with Android's `bundletool`, then test
cold start, pairing, controls, reconnect, disconnect, forgetting a TV and error
states. Capture store screenshots from the actual release. Record the AAB hash
and keep the matching mapping file.

If screenshots or listing text changed after freezing the candidate, the
`finalize_listing` lane creates a new snapshot around identical tested AAB and
mapping bytes. Supply the candidate manifest, tested AAB SHA-256 and a new output
directory. It does not rebuild.

## Publishing

Use your own Play Console account and app configuration. Verify current target
SDK requirements, privacy declarations, audience, app access instructions,
pricing, countries and signing setup against the actual app and SDKs.

The first bundle may require Console upload. For API publishing, the service
account must have explicit access to your app. Authentication alone does not
establish that permission.

Before any upload or API edit commit, inspect pending Console changes and the
exact release contents. Copy `fastlane/precommit-audit.example.json` outside the
frozen snapshot and fill it with actual verification evidence. The guarded
`upload_prepared` lane requires that audit and verifies every frozen file.
It never rebuilds, increments versions or silently retries a different review
flag. `deploy`, `internal`, `promote` and `metadata` also use these safeguards.

Distinguish upload, review submission, approval and publication. Verify the public
listing before announcing a Play release.

## Direct APKs and Google Play signing

A locally signed APK may use a different certificate from the APK delivered by
Play App Signing. Android will not normally update one over the other. Switching
can require uninstalling, which clears saved TVs and pairing state.

Publish immutable, versioned download URLs and verify downloaded bytes against
the tested APK's SHA-256. The [website guide](../website/README.md) describes the
static hosting setup.
