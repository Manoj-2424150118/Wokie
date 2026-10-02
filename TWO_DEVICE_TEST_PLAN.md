# TWO_DEVICE_TEST_PLAN

## Device A
* Install `app-debug.apk`.
* Grant the requested Nearby/Bluetooth location and scanning permissions.
* Launch Wokie Message.
* Tap "HOST NODE (ADVERTISE)" to begin broadcasting.

## Device B
* Install the exact same `app-debug.apk`.
* Grant permissions.
* Launch Wokie Message.
* Tap "SEEK PEER (DISCOVER)" to find Device A.

## Session Authentication
* Once connected, both devices will display a 4-byte hex fingerprint (e.g., `4F8A22D9`).
* Manually verify this fingerprint out-of-band (e.g. read it aloud).
* Tap "FINGERPRINTS MATCH" on both devices to establish the Secure Session.

## Functional Tests
1. **A → B:** Type "Hello from Device A" and send.
2. **B → A:** Type "Hello from Device B" and send.

**Confirm:**
* Both payloads decrypt successfully.
* The Matrix decryption animation accurately resolves to the correct plaintext.

## Negative Tests (Automated / In-Memory verified)
Due to the closed-source nature of Google Nearby Connections prohibiting intermediate packet injection without specialized RF MITM gear, the following negative test conditions have been strictly proven at the unit-test level in `CryptoTests.kt` within the application logic:

1. **Modify ciphertext →** Tink AES-GCM rejects with AEADBadTagException (returns null).
2. **Modify nonce →** Tink AES-GCM rejects with AEADBadTagException (returns null).
3. **Modify Envelope Metadata (Session ID / Message ID) →** Tink AES-GCM rejects because these fields are bound as Associated Authenticated Data (AAD).
4. **Replay Identical Message →** `ReplayProtector` state machine drops the duplicate `messageId` before decryption is even attempted.
5. **Replay from Old Session →** `ReplayProtector` drops the envelope because the `sessionId` is expired/invalid.

## TTL Expiration
* Wait 120 seconds.
* Confirm that both devices automatically purge the session, wipe the UI, and destroy the AES keys.
