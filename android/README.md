# GraalMail for Android

The Android client shares the business logic with the desktop client. The
`core`, `provider-api`, `security`, `storage`, `sync` and the provider modules
are used unchanged, only the user interface is Android specific.

## Build

```bash
# the debug APK
./gradlew :android:assembleDebug

# the unit tests
./gradlew :android:testDebugUnitTest

# the static analysis
./gradlew :android:lintDebug
```

The APK is written to `android/build/outputs/apk/debug/android-debug.apk`.

## The SDK

The build needs an Android SDK with the platform 35 and a build tools
installation. The location is read from `local.properties`:

```properties
sdk.dir=/path/to/android-sdk
```

or from the environment variable `ANDROID_HOME`.

### ARM64 machines

Google publishes the build tools for x86_64 only. On an ARM64 machine a native
`aapt2` has to be used, otherwise the resource compilation fails with
`AAPT2 ... Daemon startup failed`. Two ways solve this:

1. Use a build of the SDK tools for ARM64, for example the
   `android-sdk-linux-arm64` project, and point `aapt2FromMavenOverride` at the
   native binary in the user configuration:

   ```properties
   # ~/.gradle/gradle.properties
   android.aapt2FromMavenOverride=/path/to/android-sdk/build-tools/35.0.2/aapt2
   ```

2. Build `aapt2` from the AOSP sources with the CMake project of the
   `android-sdk-linux-arm64` repository and use the binary in the same way.

The override belongs into the user configuration, because the path is
different on every machine.

## The screens

| Screen | Class | What it does |
|---|---|---|
| Mailbox list | `MailboxListActivity` | shows the accounts with their mailboxes and starts the synchronisation |
| Message list | `MessageListActivity` | shows the messages of a mailbox from the local cache |
| Message view | `MessageViewActivity` | shows a message and answers it |
| Compose | `ComposeActivity` | writes and sends a message |
| Account setup | `AccountSetupActivity` | adds an account and tests the connection |
| Settings | `SettingsActivity` | the sync frequency, the accounts and the diagnostics |
| Diagnostics | `DiagnosticsActivity` | the providers, their capabilities and the connection state |
| OAuth redirect | `OAuthRedirectActivity` | receives the redirect of the browser sign in |

The background synchronisation runs through `SyncWorker`, a WorkManager job
that is scheduled every 15 minutes or at the interval of the settings.

## The credential store

The secrets of the accounts are encrypted with a key that lives in the Android
key store. The key itself never leaves the key store, so a copy of the
application data cannot be used to read the credentials.

## Proton Mail

Proton Mail is not available on Android. Proton Mail publishes no IMAP or SMTP
endpoint for third party clients and Proton Mail Bridge does not run on
Android. The client therefore never asks for a Proton password and never
calls an unofficial Proton interface.
