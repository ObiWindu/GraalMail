# Provider support

## Compatibility matrix

Legend: **yes** implemented · **partial** implemented with documented limits ·
**no** not possible · **unverified** implemented but not verified against the
live service in the build environment.

| Feature | Gmail | Microsoft | Yahoo | Proton (Linux) | Proton (Android) | Generic IMAP |
|---|---|---|---|---|---|---|
| OAuth sign in | yes | yes | partial (needs Yahoo approval) | no | no | no |
| Password sign in | no | no | no | yes (Proton API) | yes (Proton API) | yes |
| App password | no | no | yes | no | no | yes |
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
server, and the provider specific logic (label mapping, folder mapping, OAuth
configuration, error mapping) is covered by unit tests.

## Gmail and Google Workspace

* **Authentication**: OAuth 2.0 authorization code flow with PKCE. The token
  endpoint is `https://oauth2.googleapis.com/token`, the authorization endpoint
  is `https://accounts.google.com/o/oauth2/v2/auth`.
* **Scopes**: `https://mail.google.com/`, `https://www.googleapis.com/auth/userinfo.email`
  and `openid` by default. The mail scope is required because the mailbox is
  accessed over IMAP and SMTP with XOAUTH2. A narrower scope set can be
  configured with the account setting `oauth.scopes`.
* **Transport**: `imap.gmail.com:993` (implicit TLS) and `smtp.gmail.com:587`
  (STARTTLS), authenticated with the XOAUTH2 SASL mechanism.
* **Labels**: `INBOX`, `[Gmail]/Sent Mail`, `[Gmail]/Drafts`, `[Gmail]/Trash`,
  `[Gmail]/Spam` and `[Gmail]/All Mail` are mapped to the inbox, sent, drafts,
  trash, spam and archive roles. User labels appear as custom folders.
* **Setup**: create an OAuth client in the Google Cloud console, enable the
  Gmail API and register the redirect URI. Put the client id into the account
  setting `oauth.clientId` and the redirect URI into `oauth.redirectUri`. A
  desktop client secret is optional; the Android client must not contain one.
* **Limits**: Google Workspace administrators can restrict IMAP and SMTP
  access for the whole organisation. When the access is revoked the client
  reports a re-authentication request instead of a generic failure.

## Outlook.com, Hotmail, Live.com and Microsoft 365

* **Authentication**: OAuth 2.0 against the Microsoft identity platform
  (`https://login.microsoftonline.com/{tenant}/oauth2/v2.0/authorize`).
  The tenant is `common` by default, which covers personal and organisational
  accounts. The scopes are `openid`, `email`, `profile`, `offline_access` and
  either `https://graph.microsoft.com/Mail.ReadWrite` or the protocol scopes
  `https://outlook.office.com/IMAP.AccessAsUser.All` and
  `https://outlook.office.com/SMTP.Send`.
* **Transport**: the Microsoft Graph mail API by default. The IMAP and SMTP
  endpoints (`outlook.office365.com:993` and `smtp.office365.com:587`) are
  available when the tenant or the account requires the protocol based access.
  Select the transport with the account setting `transport` (`graph` or
  `imap`).
* **Folders**: the well known Graph folder names (`inbox`, `sentitems`,
  `drafts`, `archive`, `deleteditems`, `junkemail`) are mapped to the neutral
  folder roles.
* **Setup**: register an application in the Microsoft Entra admin centre, add
  the redirect URI and grant the delegated permissions. Put the client id into
  `oauth.clientId` and the tenant into `oauth.tenant`.
* **Limits**: IMAP, SMTP and Graph access can be disabled by the tenant
  administrator, and personal accounts do not support the organisational
  policies. The client reports both cases with a distinct message.

## Yahoo Mail

* **Authentication**: Yahoo rejects the account password for third party
  clients. The supported path is an app password that the user generates in
  the Yahoo account security settings and that stays valid until it is
  deleted.
* **OAuth 2.0**: Yahoo supports OAuth 2.0 for IMAP and SMTP, but the mail
  scopes are not available for self service. A distribution has to apply for
  the commercial IMAP access. When a client id is configured the client
  offers the OAuth flow as well.
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
