# GraalMail — Implementation Plan & Status

Last updated: 2026-10-22

Status legend: `[ ]` not started · `[~]` in progress · `[x]` done · `[!]` blocked by environment

## Phase 1 — Architecture & feasibility

- [x] Inspect repository and module layout (`core`, `provider-api`, `provider-*`, `storage`, `security`, `sync`, `testing`, `desktop`, `android`)
- [x] Gradle multi-module build, Java toolchain, version catalog
- [x] Provider-neutral interfaces (`AccountProvider`, `AuthenticationProvider`, `MailboxService`, `MessageService`, `MessageSubmissionService`, `AttachmentService`, `SearchService`, `SynchronizationService`, `CredentialStore`, `ProviderCapabilities`)
- [x] Capability model + provider error mapping (`ProviderException`, `ErrorCode`)
- [x] Proton Mail feasibility resolved: Linux = Proton Mail Bridge (IMAP/SMTP over local TLS); Android = no official client-capable API → transparent limitation + safe hand-off to official app/web
- [x] Android SDK available in this environment (`/home/macewindu/ai-dev/android-sdk-linux-arm64`); the module is configured, builds and is linted

## Phase 2 — Foundation

- [x] `core` domain model (Account, Address, Attachment, Folder, Message, MessageDraft, SearchQuery, SyncResult)
- [x] `core` utilities: `HtmlSanitizer`, `TextTools`, `AddressParser`, `Redactor`, `Log`, `Ids`, `RetryPolicy`
- [x] `security`: AES-GCM secret protector, credential store (file + in-memory), OAuth client, PKCE, TLS contexts
- [x] `storage`: SQLite schema + `SqlMailStore`, pending action queue, `Contact` model, settings table
- [x] `provider-api` capability/session abstractions
- [x] `testing`: `FakeMailProvider` + fixtures
- [x] Unit tests for core (sanitizer, redactor, retry, text, address, recipient list)

## Phase 3 — Email engine

- [x] `provider-imap` configuration model (server config, security mode, folder-type resolver, error mapper)
- [x] Generic IMAP/SMTP client layer (angus-mail + jakarta.mail)
- [x] `sync` engine: `SyncEngine`, `SyncReport`, schedulers, pending-action replay, credential codec
- [x] Contacts store persistence + recipient suggestions (`RecipientSuggestionProvider`, ranked by `RecipientSuggester`)

## Phase 4 — Provider integrations

- [x] Microsoft: OAuth (PKCE, personal vs organizational), Graph client + Graph-backed services, IMAP/SMTP fallback clients, token manager
- [x] Proton: Bridge detection, TLS trust handling, platform support reporting, feature parity matrix
- [x] Gmail: OAuth 2.0 authorization (PKCE), token management and refresh, IMAP/SMTP over XOAUTH2, label↔folder mapping
- [x] Yahoo: app-password authentication, optional OAuth path, IMAP/SMTP integration
- [x] Generic IMAP/SMTP provider wired into `ProviderRegistry` end-to-end
- [x] Provider integration tests: GreenMail based IMAP/SMTP protocol test, offline configuration tests for Gmail and Yahoo, label mapping tests
- [!] Live provider verification: no Gmail/Microsoft/Yahoo/Proton credentials were available in this environment, so the OAuth consent flow and the token refresh were not executed against the live services (documented in `PROVIDER_SUPPORT.md`)

## Phase 5 — User interfaces

- [x] Android module skeleton, manifest, resources, compose/search validation utilities
- [x] Android UI: mailbox list, message list, reader, compose, account setup, settings, diagnostics, OAuth redirect, notifications and the background sync worker
- [x] Desktop (JavaFX) UI: three-pane layout, setup wizard, compose window with recipient completion, reply, settings, diagnostics, notifications
- [x] Notifications, diagnostics screen, settings persistence

## Phase 6 — Release readiness

- [x] Offline Gradle build resolves all JVM modules
- [x] Full `./gradlew build` green, including the Android module
- [x] Test suite green
- [x] Documentation complete (README, ARCHITECTURE, SECURITY, PROVIDER_SUPPORT, TESTING, CONTRIBUTING)
- [x] Linux packaging: jpackage application image, tar archive, desktop entry, icons
- [x] Android debug APK built (`android/build/outputs/apk/debug/android-debug.apk`)

## Current blocking issues

| Area | Issue | Status |
|---|---|---|
| android | The screens were never started: no emulator or device is available in this environment | blocked (environment) |
| desktop | No usable X display in the build environment, so the window could not be opened here; the launcher and the application image were built and inspected | blocked (environment) |
| providers | No live provider credentials, so the hosted provider flows are unverified against the real services | blocked (environment) |

## Notes

- The Android build needs a native `aapt2` on ARM64 machines, because Google
  publishes the build tools for x86_64 only. The override is set in the user
  configuration (`android.aapt2FromMavenOverride`), the details are in
  `android/README.md`.

- Offline build only: Gradle must run with `--offline`; all required artifacts are in the local cache.
- No real provider credentials are available in this environment, so live-provider verification remains **unverified** and is documented as such rather than claimed.
- The Linux application image uses a copy of the build runtime instead of a linked image, because `jlink` refuses to run on a JDK whose security configuration was changed by the vendor.
