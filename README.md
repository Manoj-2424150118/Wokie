# Wokie — Offline Encrypted Mesh Messaging

Chat directly between Android phones with **no internet, no server and no account**. Messages are end-to-end encrypted and travel device-to-device over nearby radios.

## 📲 Download & Install

1. Go to the [**Releases**](https://github.com/Manoj-2424150118/Wokie/releases) page.
2. Download `Wokie-v1.0.apk` from the latest release.
3. Open the APK on your Android phone and allow "Install from this source" when prompted.
4. Requires Android 8.1 (API 27) or newer — and a second phone with Wokie installed to chat with.

## 🚀 How to Use

1. Open Wokie on **both** phones and grant the Bluetooth / Nearby / Location permissions when asked (Android requires them for device discovery).
2. Pick the **same transport** on both phones (Nearby is the default) and tap **CONNECT** next to the other device.
3. When the fingerprint screen appears, **compare the 16-character code on both phones out loud** — they must match before you tap accept. This is what stops a man-in-the-middle.
4. Chat. Messages are end-to-end encrypted (P-256 ECDH + HKDF-SHA256 + AES-256-GCM) and replay-protected, and travel directly between devices.
5. Optional: set an app PIN (Settings → App Lock). After 5 wrong attempts the app locks for 60 seconds; unlock with biometrics or device credential is also supported.
6. A foreground service keeps the connection alive while the app is in the background (you'll see a persistent "Wokie Secure Mesh" notification).

## 📡 Transports

| Transport | Status |
|---|---|
| Nearby Connections | ✅ Primary path |
| Wi-Fi Direct | ✅ Socket transport (4-byte framed) |
| Local Hotspot | ✅ Socket transport (4-byte framed) |
| Internet relay | ⚠️ Experimental — expects a relay endpoint; not a public service |
| BLE Mesh | ⚠️ Experimental — advertising/discovery only; no GATT data path yet |
| Voice / PTT | ⚠️ Experimental |

## 🔒 Security notes

- Fingerprint verification compares the **peer's** public key hash — check it out-of-band before trusting a session.
- PIN hashing: PBKDF2-HMAC-SHA256, 210,000 iterations, with the iteration count stored per hash (older PINs upgrade automatically on unlock).
- Session keys are ephemeral per pairing; a background service holds a wake lock so the mesh stack isn't frozen by the OS.

## 🛠️ Build from source

```bash
./gradlew :app:assembleDebug        # debug APK
./gradlew :app:testDebugUnitTest    # JVM unit tests (framing, crypto, mesh engine)
```

Requires JDK 17 and Android SDK platform 34. CI (GitHub Actions) runs the unit tests and builds the debug APK on every push.

## 📦 Tech

Kotlin • Jetpack Compose • Google Play Services Nearby • Tink (AES-GCM) • CameraX-free, server-free design

---

Built by [Manoj Kumar Sharma](https://github.com/Manoj-2424150118) — cybersecurity & software developer, Jaipur, India.
