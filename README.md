# GraalMail

GraalMail is a cross platform email client written in Java. It runs on Linux
desktop (JavaFX) and on Android, and it shares the same core business logic
between the two platforms.

Supported providers:

| Provider | Authentication | Transport | Status |
|---|---|---|---|
| Gmail / Google Workspace | OAuth 2.0 (authorization code + PKCE) | IMAP + SMTP with XOAUTH2 | implemented, protocol path covered by tests |
| Outlook.com / Hotmail / Microsoft 365 | OAuth 2.0 (authorization code + PKCE) | Microsoft Graph (default) or IMAP + SMTP with XOAUTH2 | implemented, Graph path covered by unit tests |
| Yahoo Mail | App password (OAuth 2.0 optional, requires Yahoo approval) | IMAP + SMTP over TLS | implemented, protocol path covered by tests |
| Proton Mail (Linux) | Proton username + password (SRP) | Proton API, PGP decrypted locally | implemented, documented parity limits (sending not yet supported; Bridge mode still available) |
| Proton Mail (Android) | Proton username + password (SRP) | Proton API, PGP decrypted locally | implemented, same parity limits as desktop |
| Any other provider | password or app password | IMAP + SMTP over TLS/STARTTLS | implemented, covered by a GreenMail integration test |

Nothing in this repository is a mock: the IMAP and SMTP code paths are
exercised against a real GreenMail server in the test suite. Live accounts of
the four hosted providers were **not** available in the build environment, so
those paths are marked as *unverified against the live service* in
[PROVIDER_SUPPORT.md](PROVIDER_SUPPORT.md).

## Requirements

* JDK 25 (the build uses a Java 25 toolchain, the Android module targets Java
  17 bytecode).
* For the desktop application: a JavaFX capable runtime. The Gradle build
  resolves the platform specific JavaFX artifacts automatically.
* For the Android application: the Android SDK with platform 35 and a build
  tools installation.

## Build

```bash
# everything except the Android application
./gradlew build -x :android:build

# the desktop application
./gradlew :desktop:run

# the unit and integration tests
./gradlew test

# the debug APK (needs ANDROID_HOME or local.properties with sdk.dir)
./gradlew :android:assembleDebug

# a self contained Linux application image and its tar archive
./gradlew :desktop:packageAppImageTarball

# the desktop entry and the application icon, ready to be installed
./gradlew :desktop:stageDesktopFiles
```

The build works offline once the dependencies are in the Gradle cache; add
`--offline` to the commands above if the machine has no internet connection.

## Build the Android app

```bash
./gradlew :android:assembleDebug
```

The debug APK is written to `android/build/outputs/apk/debug/android-debug.apk`.
The build needs an Android SDK with platform 35; the location is read from
`local.properties` (`sdk.dir=…`) or from `ANDROID_HOME`.

On an ARM64 machine the `aapt2` of the build tools has to be replaced by a
native build, because Google publishes the build tools for x86_64 only. See
[android/README.md](android/README.md) for the details.

## Run the desktop client

```bash
./gradlew :desktop:run
```

The application keeps its data in `$XDG_DATA_HOME/GraalMail` (by default
`~/.local/share/GraalMail`):

* `graalmail.db` – the local SQLite database with accounts, mailboxes, cached
  messages, contacts and the queue of pending offline actions,
* `credentials.bin` – the encrypted credential store,
* `credentials.salt` and `master.passphrase` – the key material of the
  credential store.

Read [SECURITY.md](SECURITY.md) before you put real credentials into it.

## Package the desktop client

`./gradlew :desktop:packageAppImageTarball` builds an application image with
`jpackage`. The result is written to `desktop/build/dist`:

* `app-image/GraalMail` – the application with its own runtime, the launcher
  `bin/GraalMail` and the application libraries,
* `graalmail-0.1.0-linux-amd64.tar.gz` (or `…-linux-arm64.tar.gz` on ARM64) –
  the archive that is uploaded to a release.

`./gradlew :desktop:packageDeb` and `./gradlew :desktop:packageRpm` create
Debian and RPM packages with `jpackage`; the build machine needs `dpkg-deb`
or `rpmbuild` for the selected format. `packaging/build-all.sh` builds every
artifact that the local machine supports and collects them in
`packaging/dist`.

`./gradlew :desktop:stageDesktopFiles` writes the desktop entry
(`share/applications/graalmail.desktop`) and the icons
(`share/icons/hicolor/...`) into `desktop/build/dist/desktop-files`. Copy both
into the corresponding directories of the system to register the application
in the desktop environment.

The runtime image is a copy of the runtime of the build JDK and not a linked
image, because `jlink` refuses to run on a JDK whose security configuration
was changed by the vendor. The result therefore contains the tools of the JDK
and is larger than a linked image, but the packaging works on every machine.

## Use the desktop client

* `File ▸ New message` (or `Ctrl+N`) opens the compose window. The recipient
  fields are completed from the address book, the accounts and the recent
  conversations; the message is sent with `File ▸ Send` or with the *Send*
  button.
* `File ▸ Reply` (or `Ctrl+R`) answers the selected message and quotes it.
* `Tools ▸ Settings` holds the synchronisation interval, the page size and the
  desktop notifications. The values are stored in the local database.
* `Tools ▸ Diagnostics` shows the state of the installation, the providers
  that were found on the class path and the result of a connection test for
  every account. The report can be copied into the clipboard.

## Add an account

1. `File ▸ Add account`.
2. Enter the email address. The provider is detected from its domain
   (Gmail, Outlook/Hotmail, Yahoo, Proton Mail, or generic IMAP); use
   Change for a custom domain hosted elsewhere.
3. For Gmail, Microsoft and Yahoo OAuth: enter the OAuth client id and the
   redirect URI, press **Open sign in page**, sign in in the browser and paste
   the full redirect URL into the dialog.
4. For Yahoo and generic IMAP providers: enter the app password or the account
   password and the server settings.
5. For Proton Mail: enter the Proton username and password (plus the mailbox
   password with two-password mode, and a two-factor code when asked). No
   Bridge is needed; mail is decrypted locally.

The OAuth client registration is described in
[PROVIDER_SUPPORT.md](PROVIDER_SUPPORT.md).

## Documentation

* [ARCHITECTURE.md](ARCHITECTURE.md) – modules, provider abstraction, data flow
* [SECURITY.md](SECURITY.md) – threat model, credential storage, TLS rules
* [PROVIDER_SUPPORT.md](PROVIDER_SUPPORT.md) – compatibility matrix and setup
* [TESTING.md](TESTING.md) – what is tested and how to run it
* [CONTRIBUTING.md](CONTRIBUTING.md) – coding rules and review expectations
* [GUI_REDESIGN_PLAN.md](GUI_REDESIGN_PLAN.md) – the desktop GUI redesign plan

## Known limitations

* The desktop application was built from the sources and started from the
  application image. The user interface was not exercised against live mail
  accounts.
* The Android application was built and packaged in this environment
  (`android-debug.apk`), but it was never started: no emulator or device was
  available, so the screens were only compiled and linted.
* Proton Mail uses the same endpoints as Proton's own mail clients. Sending
  mail and saving drafts with a Proton account are not supported yet, and the
  account was never exercised against a live Proton mailbox in the build
  environment: the SRP handshake, key unlock and message decryption are
  covered by offline tests with generated keys instead.
* A message that was accepted by a submission server is reported as
  *accepted for delivery*, never as *delivered*.
