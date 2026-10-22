# Security

## Threat model

GraalMail handles private communications. The assets worth protecting are:

1. the account credentials (passwords, app passwords, OAuth tokens),
2. the cached message bodies and attachments on the device,
3. the metadata of the accounts (addresses, folder names, message counts).

The threats that are addressed:

| Threat | Measure |
|---|---|
| Credentials in logs | `Redactor` removes passwords, tokens and bearer headers before a message reaches the log; providers never log a token value |
| Credentials on disk | the credential store encrypts the secrets with AES-GCM; the account record in the database holds no secret |
| Credentials in configuration files | OAuth client secrets are read from the environment or from the credential store, never from a committed file |
| Man in the middle | TLS with full certificate and hostname verification; the verification is never disabled |
| Downgrade attacks | `ImapServerConfig` and `SmtpServerConfig` reject a plain connection on an implicit TLS port |
| Malicious mail content | HTML is sanitised with jsoup, scripts and event handlers are removed, remote images are blocked by default |
| Unsafe attachments | attachments are never executed and never opened automatically; file names are sanitised with `TextTools.safeFileName` |
| SQL injection | every statement uses prepared statements with bound parameters |
| Credential leakage in the interface | the diagnostic screen reports state, never values |

Threats that are **not** addressed and that the user must consider:

* A compromised operating system can read the running process.
* The desktop credential store is a passphrase protected file, not an operating
  system keyring. See below.
* The cache is not encrypted. Anyone with read access to the data directory can
  read the cached message bodies.

## Credential storage

### Linux desktop

`DesktopSecretStore` encrypts the credential file with AES-256-GCM. The key is
derived from a master passphrase with PBKDF2-HMAC-SHA256 and 120 000
iterations over a random 16 byte salt.

The master passphrase is read from the environment variable
`GRAALMAIL_PASSPHRASE`. When that variable is not set, a random passphrase is
generated once and stored next to the credential file with owner only
permissions. That file is a convenience, **not** a secret: anybody who can
read it can decrypt the credentials. Deployments that need a real protection
boundary must set `GRAALMAIL_PASSPHRASE` from their own secret management and
protect the data directory.

GraalMail does not integrate with libsecret or the Secret Service API and does
not claim to.

### Android

The Android credential store encrypts the credentials with a key that lives in
the Android Keystore. The key material never leaves the keystore.

## Transport security

* IMAP and SMTP connections use implicit TLS (`ssl`) or STARTTLS.
* `mail.imap.ssl.checkserveridentity` and `mail.smtp.ssl.checkserveridentity`
  stay enabled, so the hostname is verified against the certificate.
* A provider that publishes its own certificate authority (Proton Mail
  Bridge) can be trusted additionally through an explicit certificate file.
  That trust is added to the platform trust store, it never replaces it.
* Certificate problems are reported as `TLS_ERROR` or
  `HOSTNAME_VERIFICATION_FAILED` with a message that explains what to do. The
  client never falls back to an unencrypted connection to "make it work".

## OAuth

* Authorization code flow with PKCE (S256) for the public desktop and Android
  clients.
* The state parameter is generated randomly and verified when the redirect
  arrives. A mismatching state is rejected.
* Client secrets are optional and never compiled into the Android application.
* Refresh tokens are stored in the credential store, and access tokens are
  refreshed shortly before they expire.
* Revoking the account deletes the local credential and asks the provider to
  revoke the token when the provider supports it.

## Proton Mail

* On Linux the client authenticates against the local Proton Mail Bridge. The
  Bridge password is the account credential and is stored like every other
  credential.
* The Bridge certificate is shown to the user with its fingerprint before it
  is trusted. The trust applies to that certificate only.
* **GraalMail does not provide end to end encryption.** With the Bridge the
  messages are decrypted by the Bridge before the client sees them. GraalMail
  never claims otherwise.
* On Android no Proton credential is collected, because there is no supported
  integration that would need one.

## Logging

`Log` and `Redactor` make sure that an email address, a token or a password
does not end up in a log file. Provider errors are mapped to stable error
codes with user facing messages that do not contain server internals.

## Data at rest

The local database is not encrypted. It contains account addresses, folder
names, message headers, cached bodies and attachments. The data directory
should therefore be protected by the file system permissions of the operating
system. `FileCredentialStore` and the passphrase file are set to owner only
where the file system supports it.
