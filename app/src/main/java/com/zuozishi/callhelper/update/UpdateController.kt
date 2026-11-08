package com.zuozishi.callhelper.update

import android.content.Context
import android.os.Build
import com.zuozishi.callhelper.BuildConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

internal enum class UpdateSheet { Release, Download, Permission }
internal data class UpdateState(
    val checking: Boolean = false,
    val manualCheck: Boolean = false,
    val message: String? = null,
    val release: AppRelease? = null,
    val sheet: UpdateSheet? = null,
    val downloading: Boolean = false,
    val downloaded: Long = 0,
    val total: Long = 0,
    val downloadError: String? = null,
    val ready: Boolean = false,
    val installRequested: Boolean = false,
    val history: List<AppRelease> = emptyList(),
    val historyLoading: Boolean = false,
    val historyError: Boolean = false,
)

/** Application lifetime state: navigation and Activity recreation never duplicate a transfer. */
internal class UpdateController(private val context: Context, private val scope: CoroutineScope, private val client: ReleaseClient = ReleaseClient()) {
    private val directory = File(context.filesDir, "updates").apply { mkdirs() }
    private val cache = File(directory, "releases.json")
    val apk = File(directory, "update.apk")
    private val part = File(directory, "update.part")
    private val verifier = UpdateApkVerifier(context)
    private val mutable = MutableStateFlow(UpdateState())
    val state = mutable.asStateFlow()
    private var startupChecked = false
    private var checkJob: Job? = null
    private var downloadJob: Job? = null
    private val installPrefs = context.getSharedPreferences("app-updates", Context.MODE_PRIVATE)
    var awaitingPermission = installPrefs.getBoolean("awaiting-permission", false)
        private set

    private val restored = scope.launch {
        withContext(Dispatchers.IO) {
            part.delete()
            runCatching { if (cache.exists() && cache.length() <= 2 * 1024 * 1024) parseHistory(cache.readText()) else emptyList() }
                .getOrNull()?.let { history -> mutable.update { it.copy(history = history) } }
            if (apk.exists()) {
                try { verifier.verify(apk); mutable.update { it.copy(ready = true) } }
                catch (_: Exception) { apk.delete() }
            }
        }
    }

    fun startupCheck() {
        if (startupChecked) return
        startupChecked = true
        check(false)
    }

    fun check(manual: Boolean = true) {
        if (state.value.downloading || state.value.ready) return
        if (checkJob?.isActive == true) {
            if (manual) mutable.update { it.copy(manualCheck = true) }
            return
        }
        mutable.update { it.copy(checking = true, manualCheck = manual, message = null) }
        checkJob = scope.launch {
            try {
                restored.join()
                if (state.value.ready) return@launch
                val release = client.latest()
                if (release != null && isNewerVersion(release.tag, BuildConfig.VERSION_NAME)) {
                    mutable.update { it.copy(release = release, sheet = UpdateSheet.Release) }
                } else mutable.update { it.copy(message = if (it.manualCheck) "已是最新版本" else null) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                // Automatic failures must not leak into a Toast, dialog, banner or badge.
                mutable.update { it.copy(message = if (it.manualCheck) "无法连接，请重试" else null) }
            } finally { mutable.update { it.copy(checking = false, manualCheck = false) } }
        }
    }

    fun showDownload() { mutable.update { it.copy(sheet = UpdateSheet.Download) } }
    fun dismiss() { mutable.update { it.copy(sheet = null) } }
    fun download() {
        if (downloadJob?.isActive == true) return
        val release = state.value.release ?: return
        val asset = release.apk(Build.SUPPORTED_ABIS.toList())
        if (asset == null) {
            mutable.update { it.copy(downloadError = "此版本没有兼容的安装包", sheet = UpdateSheet.Download) }
            return
        }
        mutable.update { it.copy(downloading = true, ready = false, downloaded = 0, total = asset.size,
            downloadError = null, sheet = UpdateSheet.Download, message = null) }
        downloadJob = scope.launch {
            try {
                restored.join()
                var lastProgress = 0L
                client.download(asset, part, { count, total ->
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - lastProgress >= 100 || count == 0L || count == total) {
                        lastProgress = now
                        mutable.update { it.copy(downloaded = count, total = total) }
                    }
                }) { file ->
                    verifier.verify(file)
                    @Suppress("DEPRECATION")
                    val version = context.packageManager.getPackageArchiveInfo(file.path, 0)?.versionName.orEmpty()
                    if (stableVersion(version) != stableVersion(release.tag)) throw UpdateRejected("安装包版本与发布信息不匹配")
                }
                withContext(Dispatchers.IO) { check(part.renameTo(apk)) }
                mutable.update { it.copy(ready = true, installRequested = true, sheet = null) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                mutable.update { it.copy(downloadError = if (error is UpdateRejected) error.message else "下载失败，请重试") }
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { part.delete() }
                mutable.update { it.copy(downloading = false) }
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        mutable.update { it.copy(sheet = null, downloadError = null) }
    }

    fun requestInstall() { if (state.value.ready) mutable.update { it.copy(installRequested = true, message = null) } }
    fun consumeInstallRequest() { mutable.update { it.copy(installRequested = false) } }
    fun needPermission() { mutable.update { it.copy(sheet = UpdateSheet.Permission) } }
    fun openPermission() {
        awaitingPermission = true
        installPrefs.edit().putBoolean("awaiting-permission", true).apply()
        dismiss()
    }
    fun permissionReturned(allowed: Boolean) {
        if (!awaitingPermission) return
        awaitingPermission = false
        installPrefs.edit().remove("awaiting-permission").apply()
        if (allowed) scope.launch { restored.join(); requestInstall() }
        else mutable.update { it.copy(message = "尚未允许安装应用，可稍后重试") }
    }
    fun installFailed() { mutable.update { it.copy(message = "无法打开安装器，请重试", sheet = null) } }

    fun loadHistory() {
        if (state.value.historyLoading) return
        mutable.update { it.copy(historyLoading = true, historyError = false) }
        scope.launch {
            try {
                restored.join()
                val raw = client.history()
                val history = withContext(Dispatchers.IO) {
                    val parsed = parseHistory(raw)
                    val temp = File(directory, "releases.tmp")
                    temp.writeText(raw)
                    check(temp.renameTo(cache))
                    parsed
                }
                mutable.update { it.copy(history = history) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.update { it.copy(historyError = true) } }
            finally { mutable.update { it.copy(historyLoading = false) } }
        }
    }
}
