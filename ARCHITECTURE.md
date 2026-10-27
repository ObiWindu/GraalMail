# Architecture

## Module overview

```
core          domain model, error model, text utilities, retry policy
provider-api  the provider neutral interfaces and the capability model
provider-imap the generic IMAP/SMTP implementation (jakarta.mail + angus-mail)
provider-gmail, provider-microsoft, provider-yahoo, provider-proton
security      OAuth 2.0 client, PKCE, credential store, AES-GCM protector, TLS helpers
storage       SQLite schema, mail store, pending action queue
sync          synchronisation engine, pending action replay, schedulers
testing       fixtures and fake providers
desktop       JavaFX application
android       native Android application (activities, adapters, background worker)
```

The dependency direction is strict: `core` and `provider-api` know nothing
about any provider, about the database or about a user interface. Providers
depend on `provider-api` only, the platform modules depend on everything.

## The Android module

The Android client reuses the domain, the providers, the storage and the
synchronisation engine of the other modules unchanged. Only the user interface
is Android specific:

* `AppContext` opens the local database, the credential store and the
  synchronisation engine. The credential store is protected with a key of the
  Android key store, so the secrets cannot be read from a copy of the
  application data.
* The activities of the `ui` package are the screens: mailbox list, message
  list, message view, compose, account setup, settings and diagnostics.
* `SyncWorker` runs the background synchronisation through WorkManager and
  shows the notification about the new messages.

## The provider abstraction

`AccountProvider` is the entry point of a provider. It reports its
`ProviderCapabilities` and opens a `ProviderSession`. A session bundles the
services a user interface needs:

| Service | Responsibility |
|---|---|
| `MailboxService` | list folders, list messages, refresh counters |
| `MessageService` | read a message, change flags, move, delete, spam |
| `MessageSubmissionService` | send a draft, save a draft |
| `AttachmentService` | open and download an attachment |
| `SearchService` | search, server side where the provider supports it |
| `SynchronizationService` | synchronize one folder or all folders |

`ProviderCapabilities` describes what a provider can actually do: the
authentication methods, whether search runs on the server, whether drafts and
attachments are synchronised, whether IMAP IDLE is available, which platforms
are supported, and which restrictions apply. The user interface uses these
flags to hide actions that would fail and to explain why an action is
unavailable, instead of pretending that every provider behaves the same.

Providers are discovered with the JDK service loader
(`META-INF/services/com.graalmail.provider.api.AccountProvider`), so a
provider module stays independent of the platform modules and can be removed
without touching the code around it.

## Message flow

```
provider session  ──►  SyncEngine  ──►  MailStore (SQLite)
      │                                       │
      │                                       ▼
      └──►  MessageSubmissionService       offline queue (pending_actions)
```

* The synchronisation engine opens a session per account, reads the folders,
  stores them, caches the message metadata and replays the pending action
  queue.
* A queued action stays *pending* until the server confirmed it. The user
  interface therefore shows offline actions as pending, not as completed.
* A send operation returns a `SendResult`. `accepted` means the submission
  server took the message; it never means the message was delivered.

## Storage

The database is SQLite (`storage` module, `SqlSchema` holds the migrations,
`SqlMailStore` implements the `MailStore` interface). It stores accounts,
folders, cached messages, contacts, settings and the pending action queue.
The `MailStore` interface is deliberately narrow so that the storage can be
replaced without touching the providers.

## Security boundaries

* Secrets live in the platform credential store, never in the account record
  and never in the database. `CredentialCodec` serialises them for the store.
* The desktop store encrypts the credentials with AES-GCM. The key is derived
  from a passphrase with PBKDF2-HMAC-SHA256. See [SECURITY.md](SECURITY.md)
  for the limits of that scheme.
* TLS verification is never switched off. `SecurityMode` rejects a
  configuration that would downgrade an implicit TLS port to a plain
  connection.

## Proton Mail

Proton Mail is deliberately not implemented as a normal IMAP provider:

* The client signs in directly with the Proton username and password using
  SRP, on desktop and on Android. Subjects and bodies are PGP-encrypted on
  the server and are decrypted locally with the mailbox password; sending is
  not supported yet.
* The legacy Bridge mode (`proton.transport=bridge`, Linux only) talks to a
  locally running Proton Mail Bridge over its IMAP and SMTP endpoints. The
  Bridge certificate is pinned on first use; the trust decision is scoped to
  that certificate and never disables the platform trust store.
* The official Proton Mail application links remain available from the
  Android setup screen as alternatives.
* Human-verification (CAPTCHA) checks are completed in a real browser
  window (Playwright-managed Firefox, or the system browser), never in an
  embedded view: the embedded JavaFX WebView is rejected by Proton with an
  explicit “Unsupported browser” page (verified end to end in our own stack
  on 2026-10-27, including a Chrome-UA spoof attempt that never settles),
  and typing passwords into embedded views would normalize a
  phishing-shaped habit. Embedding a third-party engine was evaluated and
  rejected — JCEF/JxBrowser are Chromium-only with per-platform native
  bundles and a painful JavaFX bridge, GeckoView is Android-only, XULRunner
  embedding is dead, and the JavaFX-specific CEFFX wrapper (checked
  2026-10-27) ships no releases (snapshot-only from a third-party repo),
  has no clear license, and provides no linux-arm64 natives, which this
  project supports.

`ProtonFeatureParity` lists the differences between Proton Mail and a
normal IMAP mailbox (no drafts, no server side search, no contacts, labels
instead of folders, no end to end encryption guarantee in the client).
