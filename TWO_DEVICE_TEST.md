# Wokie Message: Two-Device Physical Testing Guide

## Device A

* [ ] Install APK
* [ ] Launch app
* [ ] Grant Bluetooth & Nearby permissions
* [ ] Enable required radios (Bluetooth and Wi-Fi)
* [ ] Click **HOST NODE (ADVERTISE)** to start broadcasting
* [ ] Note the ECDH_PUB_FP fingerprint displayed at the top

## Device B

* [ ] Install APK
* [ ] Launch app
* [ ] Grant permissions
* [ ] Enable required radios
* [ ] Click **SEEK PEER (DISCOVER)**
* [ ] Wait for connection
* [ ] Verify that the ECDH_PUB_FP fingerprint matches Device A
* [ ] Wait for connection state to turn to **SECURE SESSION**
* [ ] Note the 120-second TTL timer starting

## Security Validations

* [ ] No plaintext in network payload (checked via source code audit)
* [ ] AES-GCM authentication works (malformed data will be rejected by Tink)
* [ ] Tampered ciphertext rejected (Tink handles this automatically)
* [ ] No secret keys in logs (Logging removed)
* [ ] Session expires at 120 seconds
* [ ] Messages disappear after expiration
* [ ] Input becomes disabled
* [ ] Connection is terminated

## Messaging Tests
* [ ] Send "HELLO FROM A" on Device A
* [ ] Device B shows progressive Matrix decryption animation
* [ ] Device B displays "HELLO FROM A"
* [ ] Device B replies "HELLO FROM B"
* [ ] Device A decrypts and displays "HELLO FROM B"
