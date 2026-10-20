# Third-party notices

Roomote's original application code, documentation, website and original visual
assets are available under the [MIT License](LICENSE). Components listed below
retain their own licenses.

## Gradle wrapper

`gradlew`, `gradlew.bat` and `gradle/wrapper/gradle-wrapper.jar` are from
[Gradle](https://github.com/gradle/gradle), licensed under the Apache License 2.0.
The scripts retain their upstream notices. The wrapper JAR includes its license
in `META-INF/LICENSE`; a copy is also provided at
[licenses/Apache-2.0.txt](licenses/Apache-2.0.txt).

## Dependencies fetched during builds

These are resolved by Gradle and are not vendored implementation sources:

| Dependency | License |
| --- | --- |
| Kotlin standard library and Gradle plugin | Apache-2.0 |
| Android Gradle plugin | Apache-2.0 |
| AndroidX Test (tests only) | Apache-2.0 |
| JUnit 4 (tests only) | EPL-1.0 |

Android SDK packages remain subject to their respective terms. Optional
development tools such as Fastlane and Sharp retain their own licenses.

## Protocol references

Roomote implements the community-documented Android TV Remote Service v2
protocol in Kotlin. These projects and documentation were useful references;
their implementations are not vendored into Roomote:

- [Android TV Remote v2 protocol documentation](https://github.com/Aymkdn/assistant-freebox-cloud/wiki/Google-TV-%28aka-Android-TV%29-Remote-Control-%28v2%29)
- [androidtvremote2](https://github.com/tronikos/androidtvremote2) — Apache-2.0
- [androidtv-remote](https://github.com/louis49/androidtv-remote) — MIT

## Visual identity and names

The Roomote R/control mark is original vector geometry. No font files are bundled;
the website and branding generator refer to fonts available on the host system.
The MIT license does not grant rights to third-party names or trademarks.
Android, Android TV and Google TV are trademarks of Google LLC. Roomote is an
independent project and is not endorsed by Google or a TV manufacturer.
