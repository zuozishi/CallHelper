package com.zuozishi.callhelper

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import com.zuozishi.callhelper.data.DirectoryRepository
import com.zuozishi.callhelper.data.EncryptedDirectoryStore
import com.zuozishi.callhelper.data.EmployeeExportDecryptor
import com.zuozishi.callhelper.data.EmployeeExportTransport
import com.zuozishi.callhelper.data.DirectorySyncConfig
import com.zuozishi.callhelper.service.OverlayController
import com.zuozishi.callhelper.rear.RearCardController
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class CallHelperApplication : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var directory: DirectoryRepository; private set
    lateinit var overlay: OverlayController; private set
    lateinit var rear: RearCardController; private set
    lateinit var ready: Deferred<Unit>; private set
    lateinit var initialSync: Deferred<Unit>; private set
    val startupError = MutableStateFlow<String?>(null)
    val syncing = MutableStateFlow(false)
    val syncConfig = MutableStateFlow<DirectorySyncConfig?>(null)
    private val configStore by lazy { EncryptedDirectoryStore(this, "sync-config") }
    private val syncMutex = Mutex()
    val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    val lastUpdated by lazy { MutableStateFlow(prefs.getLong("updated", 0)) }
    var callEpoch = 0
    private var listening = false
    private var hadCall = false
    @Suppress("DEPRECATION")
    private val phoneListener = object : PhoneStateListener() {
        override fun onCallStateChanged(state: Int, phoneNumber: String?) {
            when (state) {
                TelephonyManager.CALL_STATE_RINGING -> hadCall = true
                TelephonyManager.CALL_STATE_OFFHOOK -> { hadCall = true; overlay.hideAfter(5000); rear.hideAfter(5000) }
                TelephonyManager.CALL_STATE_IDLE -> if (hadCall) { hadCall = false; callEpoch++; overlay.dismissCall(); rear.dismissCall() }
            }
        }
    }
    override fun onCreate() {
        super.onCreate()
        val store = EncryptedDirectoryStore(this)
        directory = DirectoryRepository(store)
        overlay = OverlayController(this)
        rear = RearCardController(this, scope)
        ready = scope.async {
            try {
                syncConfig.value = withContext(Dispatchers.IO) {
                    if (configStore.exists()) DirectorySyncConfig.fromQrContent(configStore.read().toString(Charsets.UTF_8)) else null
                }
            } catch (_: Exception) { startupError.value = "同步配置读取失败，请重新配置" }
            syncLog("restore-start")
            try {
                if (prefs.getString("directorySource", null) == "bundled") {
                    withContext(Dispatchers.IO) { store.delete() }
                    prefs.edit().remove("updated").remove("fixtureSha256").remove("directorySource").apply()
                }
                if (store.exists()) directory.load()
            } catch (_: Exception) { startupError.value = "人员库加载失败，请重新同步" }
            syncLog("restore-complete count=${directory.snapshot.value.people.size}")
        }
        // Incoming calls wait only for local restoration, never for the network.
        initialSync = scope.async { ready.await(); if (syncConfig.value != null) syncEmployees() }
        observeCallState()
    }
    fun markUpdated() {
        val now = System.currentTimeMillis()
        prefs.edit().putLong("updated", now).apply()
        lastUpdated.value = now
    }
    private fun syncLog(stage: String) { if (BuildConfig.DEBUG) android.util.Log.i("EmployeeSync", stage) }
    suspend fun saveSyncConfig(config: DirectorySyncConfig) = syncMutex.withLock {
        withContext(Dispatchers.IO) { configStore.write(config.toQrContent().toByteArray(Charsets.UTF_8)) }
        syncConfig.value = config
        startupError.value = null
    }
    suspend fun syncEmployees() = syncMutex.withLock {
        syncLog("sync-start")
        syncing.value = true
        startupError.value = null
        try {
            withContext(Dispatchers.IO) {
                val config = requireNotNull(syncConfig.value) { "请先在数据同步页配置同步 URL 和解密密钥" }
                syncLog("config-ready")
                directory.sync(EmployeeExportTransport(config.url), EmployeeExportDecryptor(config.keyBase64))
            }
            prefs.edit().putString("directorySource", "remote").remove("fixtureSha256").apply()
            markUpdated()
            syncLog("sync-success count=${directory.snapshot.value.people.size}")
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            syncLog("sync-failed type=${e.javaClass.simpleName}")
            startupError.value = when (e) {
                is IllegalArgumentException -> e.message ?: "导出数据校验失败"
                is java.net.SocketTimeoutException -> "同步超时，请重试"
                is java.io.IOException -> "无法连接人员接口，请检查网络后重试"
                else -> "同步失败，请重试"
            }
        } finally { syncing.value = false }
    }
    @Suppress("DEPRECATION", "MissingPermission")
    fun observeCallState() {
        if (!listening && checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
            try { getSystemService(TelephonyManager::class.java).listen(phoneListener, PhoneStateListener.LISTEN_CALL_STATE); listening = true }
            catch (_: SecurityException) { /* Optional lifecycle permission may be revoked. */ }
        }
    }
}
