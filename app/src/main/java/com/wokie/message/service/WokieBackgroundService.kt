package com.wokie.message.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.wokie.message.transport.WokieTransport
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * PHASE 24 - Background Persistence Architecture (Stub)
 *
 * Architecture Plan for Full Background Support:
 * 1. This Foreground Service holds a partial wake lock to keep the CPU active when the screen is off.
 * 2. It instantiates the active `WokieTransport` (e.g. NearbyTransport) instead of MainActivity.
 * 3. The `EncryptionManager` and `MeshRouter` instances are migrated to this Service.
 * 4. The Service exposes an `incomingMessages` StateFlow or SharedFlow.
 * 5. `MainActivity` binds to this service (or observes the Flow) when in the foreground.
 * 6. If `MainActivity` is in the background and a message arrives, this Service fires a local system Notification.
 */
class WokieBackgroundService : Service() {

    companion object {
        const val CHANNEL_ID = "WokieForegroundServiceChannel"
        // Flow to emit decrypted messages to the UI Layer
        val incomingMessagesFlow = MutableSharedFlow<String>(replay = 10)
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var activeTransport: WokieTransport? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Wokie::BackgroundRouter")
        wakeLock?.setReferenceCounted(false)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Wokie Secure Mesh")
            .setContentText("Maintaining peer-to-peer connection in background")
            .setSmallIcon(android.R.drawable.ic_secure)
            .build()

        startForeground(1, notification)

        // Keep the CPU awake while the service runs. The previous code acquired
        // the wake lock ONCE in onCreate with a 10-minute timeout, so a
        // START_STICKY service stopped holding it after 10 minutes and the
        // process could be frozen despite the foreground notification.
        wakeLock?.takeIf { !it.isHeld }?.acquire(60 * 60 * 1000L /*60 minutes, re-acquired on each start*/)

        // Role of this service today: background PERSISTENCE — the foreground
        // notification + wake lock keep the process (and the mesh stack owned
        // by MainActivity's composition) alive while backgrounded. The service
        // is now actually started from MainActivity.onCreate; previously it
        // was only declared in the manifest and never ran at all.
        //
        // Deliberately NOT done here: creating a second TransportCoordinator.
        // Nearby Connections is a per-app singleton, and a duplicate transport
        // stack would fight the activity's coordinator over advertising/
        // discovery. Moving transport ownership into this service (the full
        // Phase-24 plan in the class header) requires the activity to bind to
        // the service instead of constructing its own coordinator.

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        activeTransport?.disconnect()
        wakeLock?.takeIf { it.isHeld }?.release()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null // Using Flow/Broadcast instead of direct binding for MVP
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Wokie Background Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }
}
