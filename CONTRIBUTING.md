# Contributing

Thanks for working on GraalMail. This document describes the rules that keep
the project reviewable and safe.

## Build and test

```bash
./gradlew build -x :android:build   # compile and test every JVM module
./gradlew test                       # the tests only
./gradlew :desktop:run               # the desktop client
```

The build has to stay green. A change that breaks the build is not ready for
review, even when it is only a documentation change.

## Coding rules

* Java 17 is the language level of the sources, the build uses a Java 25
  toolchain.
* Four spaces for indentation, no tabs, no wildcard imports, no lines longer
  than 120 characters.
* Every public type and every public method carries a short Javadoc comment
  that explains what it does, not what the code obviously does.
* No `System.out` and no `System.err` in the library modules. Use
  `com.graalmail.core.util.Log` so that the output is redacted and routed
  through the logging configuration.
* No new dependency without a reason in the pull request description and an
  entry in `gradle/libs.versions.toml`.
* Keep the modules independent. `core` must not depend on a provider module,
  a provider module must not depend on `desktop` or `android`.

## Security rules

These rules are not negotiable:

* Never log a secret. Pass every string through `Redactor` before it is
  logged; the log helper does that already.
* Never store a password, a token or a client secret in the local database.
  Credentials belong in the credential store of the platform module.
* Never disable a TLS check, never accept an unverified certificate and never
  log the content of a message body.
* Every network access has to go through the provider abstraction, so that the
  error mapping and the retry policy apply.

## Provider rules

* A provider must implement the interfaces of `provider-api` and must be
  registered in `META-INF/services/com.graalmail.provider.api.AccountProvider`.
* A provider must declare its capabilities honestly. A capability that is
  declared but not implemented is a bug that the user only notices when an
  action fails.
* A provider test must not need real personal credentials. Use GreenMail, a
  local stub server or the fake provider of the `testing` module.
* A provider that needs a third party registration must document the
  registration in `PROVIDER_SUPPORT.md`.

## Pull requests

* One topic per pull request. Refactoring and behaviour changes are easier to
  review separately.
* Describe what changed, how it was tested and what a reviewer has to watch
  out for.
* Update `PLAN.md` when a task of the plan moves to *done* or when a new
  limitation appears.
* Update `PROVIDER_SUPPORT.md` when the behaviour of a provider changes.

## Reporting a security issue

Do not open a public issue for a security problem. Report it privately to the
maintainers first, so that a fix can be prepared before the details are
public.
