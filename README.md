# GraalMail — complete source package

This is a buildable Android source project for a real email-client architecture using Kotlin, Jetpack Compose, Room, encrypted token storage, and Jakarta Mail XOAUTH2 IMAP/SMTP.

## Provider setup

The application does not contain third-party client secrets. Register your own OAuth applications.

### Gmail
Use a Google Cloud OAuth client, enable Gmail access, configure the Android app identity, and request the scopes required for your chosen Gmail integration. Full IMAP/SMTP access uses Gmail's `https://mail.google.com/` scope and XOAUTH2.

### Microsoft Outlook / Microsoft 365
Register an app in Microsoft Entra ID and configure delegated permissions for IMAP/SMTP OAuth, including IMAP.AccessAsUser.All and SMTP.Send where applicable. Request offline access for refresh tokens.

### Yahoo
Register a Yahoo developer application and obtain approved Mail OAuth access. Yahoo's current Mail OAuth approval and scopes are provider-controlled. IMAP is `imap.mail.yahoo.com`; SMTP is `smtp.mail.yahoo.com`.

## Build

Open in Android Studio with JDK 17 and Android SDK 35. Sync Gradle and run.

## What's included

* Kotlin / Jetpack Compose / Material 3
* Room local message/account persistence
* AndroidX encrypted token storage
* Real XOAUTH2 IMAP transport
* Real XOAUTH2 SMTP send transport
* Gmail, Microsoft and Yahoo endpoint selection
* Repository/ViewModel architecture
* Offline cached inbox foundation
* Message read/archive actions
* Compose UI and account/domain model

## Production integration requirements

OAuth browser flows, provider client IDs, redirect URI registration, refresh-token exchange, MIME multipart/attachment streaming, UID-based incremental synchronization, server-side search, drafts/folders, and background WorkManager scheduling should be wired to the provider credentials for the deployment. These are provider integration concerns rather than fake demo responses.
