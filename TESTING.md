# Testing

## Running the tests

```bash
./gradlew test                      # every JVM module
./gradlew :core:test                # a single module
./gradlew :provider-imap:test       # the IMAP/SMTP integration tests
./gradlew :android:testDebugUnitTest  # the Android unit tests (needs the SDK)
```

The test report is written to `build/reports/tests/test/index.html` of the
module.

## What is covered

| Area | Test | What it verifies |
|---|---|---|
| Address parsing | `core` `AddressTest` | display name and address parsing, validation |
| HTML safety | `core` `HtmlSanitizerTest` | scripts, event handlers, remote images and dangerous URL schemes are removed |
| Secret handling | `core` `RedactorTest` | passwords, bearer tokens and JWT like values are redacted |
| Retry behaviour | `core` `RetryPolicyTest` | the attempt limit and the capped exponential delay |
| Text helpers | `core` `TextToolsTest` | file name sanitisation, snippets, initials |
| Local database | `storage` `SqlMailStoreTest` | accounts, folders, messages, flags, moves, search, contacts, settings, pending actions, cascade delete, pagination |
| IMAP and SMTP | `provider-imap` `GenericImapProviderTest` | a real IMAP conversation against GreenMail: mailbox listing, message reading, read and flagged flags, and a real SMTP submission |
| Provider discovery | `desktop` `ProviderRegistryTest` | every provider is found through the service loader and declares its capabilities |
| Gmail label mapping | `provider-gmail` `GmailFolderTypesTest` | the Gmail system labels and user labels map to the neutral folder roles |
| Gmail server configuration | `provider-gmail` `GmailProviderTest` | the IMAP and SMTP endpoints of Gmail, the label mapping, the app password sign in configuration and the OAuth configuration kept for legacy accounts |
| Yahoo server configuration | `provider-yahoo` `YahooProviderTest` | the IMAP and SMTP endpoints of Yahoo, the app password sign in and the OAuth configuration kept for compatibility |
| Recipient fields | `core` `AddressParserTest` | the parsing of a recipient list, the formatting and the detection of invalid addresses |
| Desktop settings | `desktop` `DesktopSettingsTest` | the defaults, the persistence and the validation of the user settings |
| Android compose form | `android` `ComposeValidatorTest` | the recipients, the missing body and the invalid addresses are reported |
| Android search labels | `android` `SearchResultLabelerTest` | the label tells the user whether the results come from the server or from the cache |

## What is not covered

* **No live provider accounts.** No Gmail, Microsoft, Yahoo or Proton
  account was available in the build environment, so the OAuth consent flow,
  the token refresh against the real providers and the Proton Bridge handshake
  were not executed against the live services. They are implemented and
  covered by unit tests, but they are *unverified* in the sense of
  [PROVIDER_SUPPORT.md](PROVIDER_SUPPORT.md).
* **No Android instrumentation tests.** The debug APK is built and the unit
  tests run, but no emulator or device was available, so the screens were
  never executed.
* **No graphical test of the desktop interface.** The UI is not covered by
  automated tests; it was only compiled. The build machine has no usable X
  display, so the window could not be opened here either.
* **Proton Mail Bridge.** The bridge protocol path is implemented, but no
  bridge instance was available. The certificate probe and the trust decision
  are covered by unit tests in the `provider-proton` module.

## Test doubles

`testing` provides `FakeMailProvider`, a provider that implements every
interface of `provider-api` without touching the network. Use it for tests of
the synchronisation engine and of the user interface logic.

GreenMail provides a real IMAP and SMTP server for the protocol tests. It
binds to a dynamic port, so the tests can run in parallel and on a developer
machine without reserving a fixed port.

## Writing new tests

* A provider test must not need real personal credentials. Use GreenMail or a
  fake provider.
* Tests that touch the network have to be marked as such and are not part of
  the default task.
* Every failure message must be understandable without reading the source
  code.
