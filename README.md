# Wokie — Offline Encrypted Mesh Messaging

[![Latest version](https://img.shields.io/badge/latest-v1.1.0-brightgreen)](https://github.com/Manoj-2424150118/Wokie/releases/tag/v1.1.0) [![Download the APK](https://img.shields.io/badge/download-Wokie--v1.1.0.apk-blue)](https://github.com/Manoj-2424150118/Wokie/releases/download/v1.1.0/Wokie-v1.1.0.apk)

**Latest version: v1.1.0** — see the [release page](https://github.com/Manoj-2424150118/Wokie/releases/tag/v1.1.0) for the APK and full notes.

Chat directly between Android phones with **no internet, no server and no account**. Messages are end-to-end encrypted and travel device-to-device over nearby radios.

## 📲 Download & Install

1. Go to the [**Releases**](https://github.com/Manoj-2424150118/Wokie/releases) page.
2. Download `Wokie-v1.1.0.apk` from the v1.1.0 release.
3. Open the APK on your Android phone and allow "Install from this source" when prompted.
4. Requires Android 8.1 (API 27) or newer — and a second phone with Wokie installed to chat with.

### Upgrading from v1.0 / v1.0.1

The v1.0 and v1.0.1 builds were signed with throwaway debug keys, so Android will **refuse to install v1.1.0 over them** ("App not installed"). **Uninstall the old Wokie app first**, then install v1.1.0. From v1.1.0 onward, releases are signed with a permanent release key, so future updates install in place — no uninstall needed.

## ✨ What's new in v1.1.0

- **Full Material 3 redesign** — a branded design system with light and dark themes that follow your phone's system setting, applied consistently across the peer hub, chat, app lock and settings screens.
- **Messenger-grade chat UI** — a live connection status banner, a peer list with per-peer status, chat bubbles with timestamps, and a rounded message input bar with a round send button.
- **Permanent release signing** — releases now use a dedicated signing key (see the upgrade note above), so updates install in place from this version on.
- **Includes the v1.0.1 fixes** — disconnecting on purpose no longer counts as a failed connection attempt (no reconnect backoff penalty for a manual disconnect), and PIN verification runs off the UI thread so unlocking no longer stutters.

## 🚀 How to Use

### 1. Get set up

Open Wokie on **both** phones and grant the Bluetooth / Nearby / Location permissions when asked (Android requires them for device discovery). Both phones must use the **same transport** — Nearby is the default, and Nearby/Wi-Fi Direct chat is the working path. You can change the transport any time via the gear icon (top right) → **Settings → Preferred Transport**, where every transport shows an honest status label (READY, EXPERIMENTAL, NOT CONFIGURED, or UNAVAILABLE on this device). BLE Mesh, Internet relay and Voice PTT remain **experimental**.

### 2. Watch the status banner

The banner at the top of the main screen always shows what Wokie is actually doing:

| Banner | Meaning |
|---|---|
| **Searching for peers** | Scanning for other Wokie phones; it also shows how many peers are currently available. |
| **Connecting…** | A connection attempt / key exchange is in progress ("Finding the best route and securing the session…"). You can tap **Cancel** to stop it. |
| **Network ready** | A route to the peer network is up (the banner names the transport in use), but no encrypted session has started yet. |
| **Secure session** | You're connected and chatting end-to-end encrypted. The banner shows the peer's name, the transport in use, a countdown of the time left in the session, and a **Disconnect** button. |

Snackbars also confirm the big transitions — e.g. "Secure session established", "Connection to the peer was lost", or "Session expired — reconnect to keep chatting".

### 3. Connect to a peer

Discovered phones appear under **Nearby peers** (with a "N found" count). Each peer row shows an avatar with the peer's initials, the transport it was found via, and its status — for example **Session expired** or **Connection lost**. Tap **Connect** to start a secure session, or **Reconnect** if that peer's previous session expired.

If nobody appears yet, Wokie keeps scanning — open Wokie on the other phone, keep both phones close, and make sure both use the same transport. The empty screen has a **Transport settings** shortcut if you need to change it.

### 4. Verify the fingerprint

Before any chat opens, a **Verify your peer** screen shows the peer's fingerprint — a code displayed in groups of four characters. **Read it out and compare it with the code on the other person's phone.** Only if the two codes are identical should you tap **They match**. If they differ, tap **Don't match** — the session is dropped. This comparison is what stops a man-in-the-middle from silently sitting between the two phones.

### 5. Chat

Once verified, the chat screen opens. Your messages appear as bubbles on the right, the peer's on the left, each with the time it was sent or received underneath. Type in the rounded **Message** field at the bottom and tap the round send button (or the keyboard's Send key). The list automatically scrolls to the newest message.

Sessions are time-boxed: the countdown chip in the banner shows the time remaining, and when a session expires Wokie resets the encryption keys and returns you to the peer list, where you can **Reconnect** (and verify the fingerprint again).

### 6. Disconnect

During a session, tap **Disconnect** in the status banner. Wokie asks first — **"End secure session?"** — and reminds you that chatting again means reconnecting and re-verifying the fingerprint. Tap **Disconnect** to confirm, or **Cancel** to keep chatting.

### 7. Lock the app (optional)

In **Settings → Security**, turn on **Protect Wokie when opening** (app lock). You can then choose:

- **Biometric / Device** — unlock with biometrics or your device credential. The prompt appears automatically on the lock screen; if you cancel or it fails, tap **Authenticate** to try again.
- **Wokie PIN** — set a PIN (at least 4 characters) and enter it on the lock screen's PIN field, then tap **Unlock**. After 5 wrong attempts the app locks for 60 seconds.

You can also pick **when** Wokie re-locks after you leave it, and turn on **Destroy active E2EE session** so encryption keys are purged whenever the app locks.

### 8. Settings worth knowing

- **Network** — Preferred Transport (with per-transport status labels), **Smart Fallback** (fall back to the best available transport if your preferred one fails), **Auto Connect**, and Nearby discovery options (visibility to others, auto-accept — off by default — and discovery duration).
- **Connection Diagnostics** — a collapsible card showing the preferred transport, active route, target peer, session state, fallback policy and crypto in use.
- **Privacy** — hide notification content, block screenshots, and clipboard protection.
- **Emergency** — export keys & logs to Logcat, and **Self-destruct** (wipes the current session and closes Wokie immediately, after a confirmation).

A foreground service keeps the connection alive while the app is in the background (you'll see a persistent "Wokie Secure Mesh" notification).

## 📡 Transports

| Transport | Status |
|---|---|
| Nearby Connections | ✅ Primary path |
| Wi-Fi Direct | ✅ Socket transport (4-byte framed) |
| Local Hotspot | ⚠️ Experimental — socket transport implemented; shown as experimental in Settings |
| Internet relay | ⚠️ Experimental — not configured; no public relay service |
| BLE Mesh | ⚠️ Experimental — advertising/discovery only; no GATT data path yet |
| Voice / PTT | ⚠️ Experimental |

## 🔒 Security notes

- Fingerprint verification compares the **peer's** public key hash — check it out-of-band before trusting a session.
- PIN hashing: PBKDF2-HMAC-SHA256, 210,000 iterations, with the iteration count stored per hash (older PINs upgrade automatically on unlock).
- Session keys are ephemeral per pairing and are reset when a session expires or becomes invalid; a background service holds a wake lock so the mesh stack isn't frozen by the OS.

## 🛠️ Build from source

```bash
./gradlew :app:assembleDebug        # debug APK
./gradlew :app:testDebugUnitTest    # JVM unit tests (framing, crypto, mesh engine)
```

Requires JDK 17 and Android SDK platform 34. CI (GitHub Actions) runs the unit tests and builds the APK on every push; release APKs are signed with Wokie's permanent release key.

## 📦 Tech

Kotlin • Jetpack Compose (Material 3) • Google Play Services Nearby • Tink (AES-GCM) • CameraX-free, server-free design

---

Built by [Manoj Kumar Sharma](https://github.com/Manoj-2424150118) — cybersecurity & software developer, Jaipur, India.
