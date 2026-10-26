package com.wokie.message.ui

import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.wokie.message.security.AppLockManager
import com.wokie.message.security.AuthMethod
import com.wokie.message.ui.theme.WokieSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun AppLockScreen(
    activity: FragmentActivity,
    appLockManager: AppLockManager,
    onUnlockSuccess: () -> Unit
) {
    var pinInput by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
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

    fun tryUnlockWithPin() {
        // PBKDF2 at 210k iterations takes a few hundred ms —
        // keep it off the UI thread
        val pin = pinInput
        scope.launch {
            val ok = withContext(Dispatchers.Default) { appLockManager.verifyPin(pin) }
            if (ok) {
                onUnlockSuccess()
            } else {
                errorMessage = "Incorrect PIN"
                pinInput = ""
            }
        }
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

    val isPinMethod = appLockManager.authMethod == AuthMethod.WOKIE_PIN

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = WokieSpacing.ExtraLarge),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(72.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Filled.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(WokieSpacing.Medium))
            Text(
                "Wokie is locked",
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(WokieSpacing.Small))
            Text(
                text = if (isPinMethod) {
                    "Enter your PIN to access your encrypted messages."
                } else {
                    "Use biometrics or your device credential to access your encrypted messages."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(WokieSpacing.ExtraLarge))

            if (isPinMethod) {
                OutlinedTextField(
                    value = pinInput,
                    onValueChange = { pinInput = it },
                    label = { Text("PIN") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.NumberPassword,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(onDone = { tryUnlockWithPin() }),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(WokieSpacing.Medium))
                Button(
                    onClick = { tryUnlockWithPin() },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                ) {
                    Text("Unlock")
                }
            } else {
                Button(
                    onClick = { biometricAttempt++ }, // Re-triggers the biometric prompt
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                ) {
                    Icon(Icons.Filled.Lock, contentDescription = null)
                    Spacer(modifier = Modifier.width(WokieSpacing.Small))
                    Text("Authenticate")
                }
            }

            if (errorMessage.isNotEmpty()) {
                Spacer(modifier = Modifier.height(WokieSpacing.Medium))
                Text(
                    errorMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
