package com.zuozishi.callhelper.rear

import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.toBitmap
import com.zuozishi.callhelper.R
import com.zuozishi.callhelper.domain.Personnel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

data class RearCard(val token: String, val number: String, val people: List<Personnel>,
    val isCall: Boolean, val showAt: Long, val expiresAt: Long)

/** App-owned state; the scoped hook consumes RemoteViews through an authenticated provider. */
class RearCardController(private val context: Context, private val scope: CoroutineScope) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())
    private val mutableCard = MutableStateFlow<RearCard?>(null)
    val card = mutableCard.asStateFlow()
    private val mutableStatus = MutableStateFlow("模块待确认")
    val status = mutableStatus.asStateFlow()
    private var expiry: Job? = null
    private var avatarJob: Job? = null
    @Volatile private var avatar: Bitmap? = null
    private var confirmed = false
    @Volatile private var hostToken: String? = null
    @Volatile private var hostActive = false
    @Volatile private var hostAttached = false

    init {
        ContextCompat.registerReceiver(context, object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) { publish() }
        }, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        handler.post { refreshStatus() }
    }

    fun targetVersion(): String? = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(NativeRearProvider.TARGET, 0).versionName
    }.getOrNull()

    fun refreshStatus() {
        mutableStatus.value = when {
            !Build.MANUFACTURER.equals("Xiaomi", true) -> "未检测到小米背屏"
            targetVersion() != NativeRearProvider.SUPPORTED_VERSION -> "当前背屏版本暂不支持"
            confirmed -> if (hostActive && hostAttached) "原生背屏卡片已显示" else "LSPosed 模块已就绪"
            else -> "请在 LSPosed / Vector 启用模块，仅勾选背屏应用"
        }
        publish()
    }

    fun openModuleManager() {
        val intent = listOf("org.matrix.vector.manager", "org.lsposed.manager").firstNotNullOfOrNull {
            context.packageManager.getLaunchIntentForPackage(it)
        }
        if (intent != null) runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        else mutableStatus.value = "请打开 LSPosed / Vector，启用来电名片模块"
    }

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("rearEnabled", enabled).apply()
        if (!enabled) dismiss()
        refreshStatus()
    }

    fun show(number: String, people: List<Personnel>, isCall: Boolean = false) {
        dismiss()
        if (!prefs.getBoolean("rearEnabled", false) || !prefs.getBoolean("enabled", true)) return
        if (isCall && people.isEmpty() && prefs.getBoolean("hideUnmatched", true)) return
        if (targetVersion() != NativeRearProvider.SUPPORTED_VERSION) { refreshStatus(); return }
        val now = SystemClock.elapsedRealtime()
        val payload = RearCard(UUID.randomUUID().toString(), number, people, isCall, now, now + 30000)
        mutableCard.value = payload
        mutableStatus.value = "正在更新原生背屏卡片"
        publish()
        hideAfter(30000)
        val url = people.firstOrNull()?.avatarUrl.orEmpty()
        if (url.isNotBlank()) avatarJob = scope.launch {
            try {
                val result = context.imageLoader.execute(ImageRequest.Builder(context).data(url).size(96).build())
                if (mutableCard.value?.token == payload.token) {
                    avatar = result.image?.toBitmap()
                    publish()
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Initial remains visible if the optional image fails. */ }
        }
    }

    fun activeCard(): RearCard? = mutableCard.value?.takeIf {
        prefs.getBoolean("rearEnabled", false) && prefs.getBoolean("enabled", true) &&
            SystemClock.elapsedRealtime() < it.expiresAt
    }

    fun hideAfter(ms: Long) {
        val current = mutableCard.value ?: return
        val deadline = minOf(current.expiresAt, SystemClock.elapsedRealtime() + ms)
        mutableCard.value = current.copy(expiresAt = deadline)
        expiry?.cancel()
        expiry = scope.launch { delay((deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0)); dismiss(current.token) }
        publish()
    }

    fun dismissCall() { if (mutableCard.value?.isCall == true) dismiss() }
    fun dismiss(token: String? = null) {
        if (token != null && mutableCard.value?.token != token) return
        mutableCard.value = null
        expiry?.cancel(); expiry = null
        avatarJob?.cancel(); avatarJob = null; avatar = null
        publish()
    }

    fun publish() { context.contentResolver.notifyChange(NativeRearProvider.URI, null) }

    fun hostReport(event: String, token: String?, active: Boolean, attached: Boolean) {
        handler.post {
            confirmed = true; hostToken = token; hostActive = active; hostAttached = attached
            mutableStatus.value = when {
                event == "ERROR" -> "原生背屏卡片更新失败"
                active && attached -> "原生背屏卡片已显示"
                else -> "LSPosed 模块已就绪"
            }
        }
    }

    fun hostState() = Triple(hostToken, hostActive, hostAttached)

    fun render(aod: Boolean): RemoteViews {
        val payload = activeCard()
        val person = payload?.people?.firstOrNull()
        val locked = context.getSystemService(KeyguardManager::class.java).isKeyguardLocked
        val details = prefs.getBoolean("rearShowLocked", false) || (!locked && !aod)
        val views = RemoteViews(context.packageName, R.layout.rear_contact_card)
        val name = if (!details) "来电信息" else person?.name ?: payload?.number?.ifBlank { "号码未提供" }.orEmpty()
        views.setTextViewText(R.id.rear_name, name)
        views.setTextViewText(R.id.rear_initial, if (details) person?.name?.take(1).orEmpty() else "")
        fun text(id: Int, value: String) {
            views.setTextViewText(id, value)
            views.setViewVisibility(id, if (value.isBlank()) View.GONE else View.VISIBLE)
        }
        text(R.id.rear_post, if (!details) "解锁后查看" else person?.post ?: "未找到人员信息")
        text(R.id.rear_org, if (details) person?.topOrgName.orEmpty() else "")
        text(R.id.rear_path, if (details && prefs.getBoolean("department", true) && person?.fullOrgName != person?.topOrgName) person?.fullOrgName.orEmpty() else "")
        text(R.id.rear_more, if (details && (payload?.people?.size ?: 0) > 1) "另有 ${payload!!.people.size - 1} 个匹配身份" else "")
        val image = avatar?.takeIf { details }
        views.setImageViewBitmap(R.id.rear_avatar, image)
        views.setViewVisibility(R.id.rear_avatar, if (image != null) View.VISIBLE else View.GONE)
        if (payload != null) views.setOnClickPendingIntent(R.id.rear_close, PendingIntent.getBroadcast(context, 0,
            Intent(context, RearCardActions::class.java).setAction("com.zuozishi.callhelper.CLOSE_REAR")
                .setData(android.net.Uri.parse("callhelper://rear/${payload.token}")), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        return views
    }
}
