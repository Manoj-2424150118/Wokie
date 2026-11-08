# Contributing to Wokie

Thanks for helping improve Wokie. This guide covers the setup, the test
suite, and the ground rules that keep an offline mesh messenger safe to
change. Everything here is grounded in `README.md`, `SECURITY.md`,
`TWO_DEVICE_TEST.md`, and the CI workflow (`.github/workflows/`,
`Android CI`).

## Setup

- Install **Android Studio** (JDK 17 toolchain) and clone the repository.
- Open the project and let Gradle sync.
- Platform: Android — minSdk 27 (Android 8.1), target/compileSdk 34,
  AGP 8.2, Gradle 8.2.

## Build and test

```bash
./gradlew assembleDebug        # debug APK
./gradlew testDebugUnitTest    # JVM unit tests (96 tests)
./gradlew assembleRelease      # release APK (requires the release keystore — maintainer builds)
```

- **96 JVM unit tests** live in `app/src/test/…`, covering the crypto
  layer (key agreement, encryption, replay protection, peer
  fingerprints), the protocol (message envelopes, physical packet
  framing), the mesh discovery state machine, session recovery, and a
  two-peer integration test.
- **Run `./gradlew testDebugUnitTest` before opening a PR, and keep it
  green.** CI (`Android CI`) runs the unit tests and builds the debug
  APK on every push to `main`, plus a weekly health check (Mondays
  03:45 UTC) that catches toolchain/dependency rot.
- If you add or remove a test, update the test count in `README.md`
  (it states "96 JVM unit tests") to match.

## Two-device testing

Mesh features (peer discovery, relay, delivery receipts) need **two
physical Android devices** to exercise — one phone alone cannot test
them. See `TWO_DEVICE_TEST.md` for the manual two-device test plan
(and `TWO_DEVICE_TROUBLESHOOTING.md` when a run misbehaves). If your
change touches discovery, transport, or relay, say in the PR whether
you ran the two-device plan or only the JVM unit tests.

Note: the raw-socket transport in the codebase points at an emulator
host address and exists for two-device automated testing — it is not a
user-facing path. Do not promote it to one.

## Ground rules

- **Never commit secrets.** No release keystore, keystore passwords,
  key aliases, or signing material of any kind. Release APKs are built
  by the maintainer; signed releases (v1.1.0 onward) use a permanent
  release key so updates install in place.
- **Do not roll your own crypto.** The encryption layer is ephemeral
  ECDH + HKDF-SHA256 session keys with AES-256-GCM payloads (Google
  Tink JCE). Changes touch parameters and plumbing, never primitives.
- **Report vulnerabilities privately.** Do not open a public issue for
  a security flaw — follow `SECURITY.md` (GitHub private vulnerability
  reporting).
- **Keep the docs honest.** The README documents the architecture, the
  threat model, and the honest limitations. If a change alters
  behaviour — including the release/version table (`versionName` /
  `versionCode` in `app/build.gradle.kts` and the "Current release" row
  in the README) — update the README section that describes it in the
  same PR.

## Pull requests

- Keep PRs focused: one change, one purpose.
- Use conventional commit messages (`feat:`, `fix:`, `docs:`, `test:`,
  `refactor:`, `perf:` with the `wokie` scope where it helps, e.g.
  `fix(wokie): …`).
- Describe what you tested (`./gradlew testDebugUnitTest` output, and
  whether the two-device plan was run) in the PR.
