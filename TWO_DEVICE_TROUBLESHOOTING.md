# Wokie Message: Troubleshooting Guide

## Devices cannot discover each other

**Check:**
* Ensure Android 12+ runtime permissions (`BLUETOOTH_SCAN`, `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT`) are granted in Settings > Apps.
* Ensure both Bluetooth and Wi-Fi are physically toggled ON. Nearby Connections requires both radios to be powered to negotiate the highest bandwidth connection.
* Verify both devices are within 10-20 meters of each other.
* Ensure both devices are running the exact same APK version (Service ID `com.wokie.message.secure_mesh` must match).

## Device discovered but cannot connect

**Check:**
* If the `ConnectionLifecycleCallback` instantly rejects the connection, ensure no previous ghost sessions are holding the port open. Force-close the app on both devices and try again.
* If testing on Emulators, Nearby Connections is NOT supported because emulators do not possess physical Bluetooth radios. Use real Android phones.

## Connection succeeds but messages fail

**Check:**
* Ensure the Key Exchange phase succeeded. The UI must say **SECURE SESSION** before you can send messages.
* If a message is sent before `encryptionManager.isReady` is true, the UI will ignore the send request.
* Verify the payload prefix bytes (`0x01` for keys, `0x02` for messages) are not being mangled.

## Encryption succeeds but decryption fails

**Check:**
* Did both devices successfully complete the ECDH key exchange? If the ECDH public key fingerprints (displayed at the top of the app) do not match, a man-in-the-middle attack or a dropped key packet may have occurred.
* If you see `[DECRYPTION FAILED]` in the chat list, Tink's AES-256-GCM authentication tag rejected the ciphertext due to tampering or an incorrect session key.
* Ensure `System.gc()` hasn't aggressively wiped the Tink instance unexpectedly (unlikely during active use, but possible on low-memory devices).

## App crashes on permission request

**Check:**
* If running on Android 11 or lower, the newer Android 12 Bluetooth permissions might throw a `SecurityException` if not handled carefully by the system compatibility layer. Ensure you accept the legacy Location permissions if prompted on older devices.
