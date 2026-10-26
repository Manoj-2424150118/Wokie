package com.wokie.message.ui

import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.wokie.message.security.AppLockManager
import com.wokie.message.security.AuthMethod

@Composable
fun AppLockScreen(
    activity: FragmentActivity,
    appLockManager: AppLockManager,
    onUnlockSuccess: () -> Unit
) {
    var pinInput by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf("") }
    var showingBiometric by remember { mutableStateOf(false) }
    // Incrementing this re-runs the auto-launch effect below (retry support).
    var biometricAttempt by remember { mutableStateOf(0) }

    fun launchBiometric() {
        if (showingBiometric) return
        showingBiometric = true
        val executor = ContextCompat.getMainExecutor(activity)
        val biometricPrompt = BiometricPrompt(activity, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    errorMessage = "Authentication error: $errString"
                    showingBiometric = false
                }
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    onUnlockSuccess()
                }
                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    errorMessage = "Authentication failed"
                    showingBiometric = false
                }
            })

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Wokie")
            .setSubtitle("Authenticate to access your encrypted messages")
            .setAllowedAuthenticators(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG or androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            .build()

        biometricPrompt.authenticate(promptInfo)
    }

    // Auto-launch once on entry, and again whenever the user taps AUTHENTICATE.
    // Previously this was LaunchedEffect(Unit) + a button that only reset a
    // state flag, so after one cancel/failure the Unit-keyed effect never
    // re-ran and the user was locked out until the process restarted.
    LaunchedEffect(biometricAttempt) {
        if (appLockManager.authMethod == AuthMethod.DEVICE_CREDENTIAL_OR_BIOMETRIC && appLockManager.canUseBiometricsOrDeviceCredential()) {
            launchBiometric()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF050505)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("WOKIE IS LOCKED", color = Color(0xFF00F3FF), fontSize = 24.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            Spacer(modifier = Modifier.height(32.dp))

            if (appLockManager.authMethod == AuthMethod.WOKIE_PIN) {
                OutlinedTextField(
                    value = pinInput,
                    onValueChange = { pinInput = it },
                    label = { Text("Enter Wokie PIN", color = Color.Gray) },
                    visualTransformation = PasswordVisualTransformation(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF00F3FF),
                        unfocusedBorderColor = Color.DarkGray,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = {
                        if (appLockManager.verifyPin(pinInput)) {
                            onUnlockSuccess()
                        } else {
                            errorMessage = "Incorrect PIN"
                            pinInput = ""
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00F3FF).copy(alpha = 0.2f)),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF00F3FF)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("UNLOCK", color = Color(0xFF00F3FF))
                }
            } else {
                Button(
                    onClick = { biometricAttempt++ }, // Re-triggers the biometric prompt
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00F3FF).copy(alpha = 0.2f)),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF00F3FF)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("AUTHENTICATE", color = Color(0xFF00F3FF))
                }
            }

            if (errorMessage.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(errorMessage, color = Color.Red, fontSize = 14.sp)
            }
        }
    }
}
