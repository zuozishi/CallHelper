package com.zuozishi.callhelper

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import com.zuozishi.callhelper.data.DirectoryRepository
import com.zuozishi.callhelper.data.EncryptedDirectoryStore
import com.zuozishi.callhelper.service.OverlayController
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

class CallHelperApplication : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var directory: DirectoryRepository; private set
    lateinit var overlay: OverlayController; private set
    lateinit var ready: Deferred<Unit>; private set
    val startupError = MutableStateFlow<String?>(null)
    val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    var callEpoch = 0
    private var listening = false
    private var hadCall = false
    @Suppress("DEPRECATION")
    private val phoneListener = object : PhoneStateListener() {
        override fun onCallStateChanged(state: Int, phoneNumber: String?) {
            when (state) {
                TelephonyManager.CALL_STATE_RINGING -> hadCall = true
                TelephonyManager.CALL_STATE_OFFHOOK -> { hadCall = true; overlay.hideAfter(5000) }
                TelephonyManager.CALL_STATE_IDLE -> if (hadCall) { hadCall = false; callEpoch++; overlay.dismissCall() }
            }
        }
    }
    override fun onCreate() {
        super.onCreate()
        val store = EncryptedDirectoryStore(this)
        directory = DirectoryRepository(store)
        overlay = OverlayController(this)
        ready = scope.async {
            try {
                if (store.exists()) directory.load()
                if (BundledData.refresh(this@CallHelperApplication, directory)) markUpdated()
            } catch (_: Exception) { startupError.value = "人员库加载失败，请重新导入" }
        }
        observeCallState()
    }
    fun markUpdated() { prefs.edit().putLong("updated", System.currentTimeMillis()).apply() }
    @Suppress("DEPRECATION", "MissingPermission")
    fun observeCallState() {
        if (!listening && checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
            try { getSystemService(TelephonyManager::class.java).listen(phoneListener, PhoneStateListener.LISTEN_CALL_STATE); listening = true }
            catch (_: SecurityException) { /* Optional lifecycle permission may be revoked. */ }
        }
    }
}
