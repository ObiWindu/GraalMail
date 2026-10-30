# Provider support

## Compatibility matrix

Legend: **yes** implemented · **partial** implemented with documented limits ·
**no** not possible · **unverified** implemented but not verified against the
live service in the build environment.

| Feature | Gmail | Microsoft | Yahoo | Proton (Linux) | Proton (Android) | Generic IMAP |
|---|---|---|---|---|---|---|
| Sign in with email + password | yes (app password) | no | yes (app password) | yes | yes | yes |
| Password sign in | no (account password rejected by Google) | no | no (account password rejected by Yahoo) | yes (Proton API) | yes (Proton API) | yes |
| App password | yes (needs 2-Step Verification) | no | yes | no | no | yes |
| OAuth sign in | no (legacy accounts keep working) | no (legacy accounts keep working) | no | — | — | no |
| Read mail | yes (IMAP) | yes (Graph) | yes (IMAP) | yes (API) | yes (API) | yes |
| Send mail | yes | yes | yes | no (not yet) | no (not yet) | yes |
| Server side search | yes | yes | yes | partial (metadata) | partial (metadata) | yes |
| Local search over the cache | yes | yes | yes | yes | yes | yes |
| Flags (read, starred) | yes | yes | yes | yes | yes | yes |
| Attachments | yes | yes | yes | partial (embedded) | partial (embedded) | yes |
| Drafts on the server | yes | yes | yes | no | no | partial |
| Labels as folders | yes | no | no | yes | yes | no |
| IMAP IDLE | yes | yes | yes | no | no | depends on the server |
| Offline actions | yes | yes | yes | yes | yes | yes |
| Unified inbox | yes | yes | yes | yes | yes | yes |
| Contacts | local only | local only | local only | local only | local only | local only |
| Push notifications | no | no | no | no | no | no |

Nothing in the table was verified against a live Gmail, Microsoft, Yahoo or
Proton account: no such account was available in the build environment. The
protocol paths are covered by the automated tests against a local GreenMail
server, and the provider specific logic (label mapping, folder mapping,
password sign in, error mapping) is covered by unit tests.

Every GraalMail sign in is **email address + password** (the account password,
or an app password where the provider forces one). No sign-in screen asks for
anything else. Providers that only accept OAuth tokens cannot be added as new
accounts; accounts that were added while OAuth was offered keep working with
their stored tokens until the token is revoked.

## Gmail and Google Workspace

* **Authentication**: the email address and a Gmail **app password**. Google
  rejects the account password for third-party mail clients, so the app
  password is the only supported path; it is available when 2-Step
  Verification is enabled on the Google account. GraalMail authenticates with
  the app password over IMAP and SMTP (plain password mechanism).
* **Transport**: `imap.gmail.com:993` (implicit TLS) and `smtp.gmail.com:587`
  (STARTTLS).
* **Labels**: `INBOX`, `[Gmail]/Sent Mail`, `[Gmail]/Drafts`, `[Gmail]/Trash`,
  `[Gmail]/Spam` and `[Gmail]/All Mail` are mapped to the inbox, sent, drafts,
  trash, spam and archive roles. User labels appear as custom folders.
* **Setup**: in the Google account security settings, enable 2-Step
  Verification and generate an app password (under *Security ▸ App
  passwords*), then enter it in the account setup dialog. If the app password
  is revoked GraalMail reports an authentication failure and asks for a new
  app password.
* **Limits**: Google Workspace administrators can restrict IMAP and SMTP
  access for the whole organisation. When the access is revoked the client
  reports a re-authentication request instead of a generic failure.
* **Legacy OAuth accounts**: accounts added while GraalMail still offered the
  Google OAuth 2.0 sign in keep working; their tokens are refreshed in the
  background. If the token is revoked remotely the account must be removed,
  because OAuth sign in is no longer offered.

## Outlook.com, Hotmail, Live.com and Microsoft 365

* **Authentication**: Microsoft disables basic password authentication for its
  mail endpoints and personal accounts have no app passwords — every token
  comes from an OAuth 2.0 flow against the Microsoft identity platform.
  GraalMail signs in with the email address and password only, so **new
  Microsoft accounts cannot be added**.
* **Existing accounts**: accounts added while the OAuth sign in was offered
  keep working. Their tokens are refreshed in the background over Graph or
  the IMAP and SMTP endpoints, exactly as before. If the token is revoked
  remotely the account must be removed.
* **Transport (existing accounts)**: the Microsoft Graph mail API by default.
  The IMAP and SMTP endpoints (`outlook.office365.com:993` and
  `smtp.office365.com:587`) are available when the tenant or the account
  requires the protocol based access. Select the transport with the account
  setting `transport` (`graph` or `imap`).
* **Folders (existing accounts)**: the well known Graph folder names
  (`inbox`, `sentitems`, `drafts`, `archive`, `deleteditems`, `junkemail`)
  are mapped to the neutral folder roles.

## Yahoo Mail

* **Authentication**: Yahoo rejects the account password for third party
  clients. Sign in uses an app password that the user generates in the Yahoo
  account security settings and that stays valid until it is deleted. The
  Yahoo OAuth flow is no longer offered: it requires a distribution-approved
  application and GraalMail signs in with a password only.
* **Transport**: `imap.mail.yahoo.com:993` (implicit TLS) and
  `smtp.mail.yahoo.com:465` (implicit TLS) or `:587` (STARTTLS).
* **Setup**: generate the app password in the Yahoo account security page
  under *External connections ▸ Create app password* and enter it in the
  account setup dialog.
* **Limits**: an app password is bound to the application that requested it.
  When Yahoo revokes it the client reports an authentication failure and asks
  for a new app password.

## Proton Mail

Proton accounts sign in directly with the Proton username and password; no
Bridge is needed on any platform:

1. In GraalMail, add a Proton account with the Proton username and password.
2. With two-password mode, also enter the mailbox password. With two-factor
   authentication, enter the authenticator code when asked.
3. GraalMail authenticates with SRP, unlocks the mailbox keys locally and
   decrypts subjects and bodies on this machine; the server only ever sees
   ciphertext.
4. If Proton answers with a CAPTCHA check, either open
   https://account.proton.me/login in a browser, sign in once to complete
   the check, then try again — or use “Verify in private window” right in
   the setup dialog, which opens Proton's login page in a private Firefox
   window (one-time ~90 MB download, kept for reuse). Closing that window
   retries the sign-in automatically. No CAPTCHA can be solved inside the
   app itself, and retrying blindly can lock the account.
5. If Proton reports temporarily limited access after detecting unusual
   activity, stop signing in — further attempts extend the lock, which
   typically lifts within a day. Background sync pauses automatically for
   24 hours and resumes on its own; use Proton's official apps meanwhile.

**Parity limits** (`ProtonFeatureParity`): sending mail and saving drafts are
not supported yet. Proton labels appear as folders, contacts are not
available, and server-side search matches metadata while subjects and senders
are filtered locally after decryption. Separately stored attachments are
reported as unavailable; attachments embedded in messages work. GraalMail
does **not** provide end to end encryption of its own and never claims it
does; it decrypts received mail locally with the mailbox password.

### Legacy Bridge mode (Linux desktop only)

The previous integration through **Proton Mail Bridge** is still available by
setting `proton.transport` to `bridge`:

1. Install Proton Mail Bridge for Linux and log in with the Proton account.
2. Enable the IMAP and SMTP server in the Bridge settings.
3. In GraalMail, add a Proton account with the Proton login (the Proton Mail
   address) and the Bridge password shown in Proton Mail Bridge.
4. GraalMail finds the local Bridge ports and the TLS mode automatically,
   verifies the login, and pins the Bridge certificate for the account. The
   pin is created only together with a successful login and only for a Bridge
   running on the same machine; a previously stored pin is always verified
   strictly. If the Bridge is reinstalled with a new certificate, remove the
   Proton account in GraalMail and add it again.

The Bridge needs the user to be logged in, and it needs a paid Proton plan
for some of the features. The Bridge is not available on Android.

**Parity limits** (`ProtonFeatureParity`): the Bridge exposes a limited IMAP
view of the mailbox. Drafts are not synchronised, there is no server side
search, contacts are not available, labels appear as folders and the Proton
encryption is terminated by the Bridge. GraalMail does **not** provide end to
end encryption and never claims it does.

### Android

Proton accounts work on Android through the same direct login: the setup
screen asks for the Proton username and password (plus the mailbox password
for two-password mode, and a two-factor code when needed).

The screen still offers the official Proton Mail application and the web
client as alternatives below the sign-in fields.

## Generic IMAP and SMTP

Any standards compliant server can be used. The setup dialog asks for:

| Setting | Key | Example |
|---|---|---|
| IMAP host | `imap.host` | `imap.example.com` |
| IMAP port | `imap.port` | `993` |
| IMAP security | `imap.security` | `ssl`, `starttls`, `none` |
| SMTP host | `smtp.host` | `smtp.example.com` |
| SMTP port | `smtp.port` | `587` |
| SMTP security | `smtp.security` | `starttls`, `ssl`, `none` |
| User name | `auth.username` | `me@example.com` |
| Connection timeout | `connect.timeoutMs` | `15000` |
| Read timeout | `read.timeoutMs` | `30000` |
| Maximum attachment size | `attachment.maxBytes` | `26214400` |

The security mode is derived from the port when it is not set explicitly.
Selecting `none` on the implicit TLS ports 993 and 465 is rejected.

Folder roles are detected from the special use attributes (`\Sent`,
`\Drafts`, `\Trash`, `\Junk`, `\Archive`) and, when the server does not
report them, from well known mailbox names. Everything else becomes a custom
folder.
