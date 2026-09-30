# GraalMail production checklist

## Implemented architecture
- Compose/Material 3
- Room cache
- encrypted token storage
- provider abstraction
- IMAP/SMTP XOAUTH2 transport
- MIME parsing model
- folders/attachments/pending-operation data models
- local search DAO
- conversation grouping
- WorkManager periodic sync
- OAuth refresh manager
- retry/backoff
- unit/UI test foundations

## Provider configuration
### Gmail
Register an OAuth client in Google Cloud. Use Gmail's documented OAuth scopes and XOAUTH2 for IMAP/SMTP. Configure the Android package/SHA-256 and redirect handling appropriate to the client type.

### Microsoft
Register in Microsoft Entra. Configure delegated IMAP/SMTP permissions and offline access. Configure the Android redirect URI.

### Yahoo
Register a Yahoo developer app and request the Mail scopes required for third-party Mail OAuth. Yahoo approval is controlled by Yahoo and cannot be bundled into source.

## Release
Use a real signing key for release builds. Store provider client configuration outside source control (Gradle properties, CI secrets, or a secure configuration service). Never commit OAuth client secrets or refresh tokens.

## Remaining provider-specific work
Exact OAuth browser callbacks, dynamic provider folder discovery, UIDVALIDITY-aware incremental synchronization, server-side search syntax translation, MIME attachment persistence, and complete queued-operation replay must be connected to the registered provider clients before store publication.
