package com.zuozishi.callhelper.service

import android.app.KeyguardManager
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.*
import android.widget.*
import com.zuozishi.callhelper.domain.Personnel
import coil3.load
import coil3.request.Disposable
import androidx.core.content.ContextCompat

/** A bounded independent window. Outside touches and call controls remain with the system dialer. */
class OverlayController(private val context: Context) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private var view: View? = null
    private var showingCall = false
    private val imageRequests = mutableListOf<Disposable>()
    private data class Payload(val number: String, val people: List<Personnel>, val isCall: Boolean)
    private var payload: Payload? = null
    init {
        ContextCompat.registerReceiver(context, object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> dismiss()
                    Intent.ACTION_USER_PRESENT -> payload?.takeIf { it.isCall }?.let { show(it.number, it.people, true) }
                }
            }
        }, IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_USER_PRESENT) }, ContextCompat.RECEIVER_NOT_EXPORTED)
    }
    val isShowing get() = view != null
    private val hide = Runnable { dismiss() }
    private fun dp(n: Int) = (n * context.resources.displayMetrics.density).toInt()
    fun hideAfter(ms: Long) { handler.removeCallbacks(hide); handler.postDelayed(hide, ms) }
    fun dismissCall() { if (showingCall) dismiss() }
    fun dismiss() {
        handler.removeCallbacks(hide)
        imageRequests.forEach { it.dispose() }; imageRequests.clear()
        view?.let { runCatching { wm.removeViewImmediate(it) } }
        view = null; showingCall = false; payload = null
    }
    fun show(number: String, matches: List<Personnel>, isCall: Boolean = false): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (!Settings.canDrawOverlays(context) || !prefs.getBoolean("enabled", true)) return false
        dismiss()
        val systemDark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val dark = when (prefs.getInt("theme", 0)) { 1 -> false; 2 -> true; else -> systemDark }
        val ink = if (dark) Color.rgb(245, 247, 250) else Color.rgb(25, 30, 38)
        val muted = if (dark) Color.rgb(166, 179, 198) else Color.rgb(100, 115, 135)
        val accent = if (dark) Color.rgb(145, 185, 250) else Color.rgb(52, 130, 255)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(10), dp(20), dp(20))
            background = GradientDrawable().apply { setColor(if (dark) Color.rgb(37, 42, 52) else Color.rgb(252, 253, 255)); cornerRadius = dp(28).toFloat(); setStroke(dp(1), if (dark) 0xFF454C5A.toInt() else Color.WHITE) }
            elevation = dp(18).toFloat()
        }
        val drag = FrameLayout(context).apply { contentDescription = "拖动悬浮窗"; setOnClickListener { } }
        drag.addView(View(context).apply { background = GradientDrawable().apply { setColor(muted); cornerRadius = dp(2).toFloat() } }, FrameLayout.LayoutParams(dp(30), dp(3), Gravity.CENTER))
        root.addView(drag, LinearLayout.LayoutParams(-1, dp(24)))
        val heading = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        heading.addView(TextView(context).apply { text = "◇"; setTextColor(accent); textSize = 20f; contentDescription = "来电知人" }, LinearLayout.LayoutParams(0, dp(40), 1f))
        val close = ImageButton(context).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel); imageTintList = android.content.res.ColorStateList.valueOf(muted)
            setBackgroundColor(Color.TRANSPARENT); contentDescription = "关闭悬浮窗"; setOnClickListener { dismiss() }
        }
        heading.addView(close, LinearLayout.LayoutParams(dp(48), dp(48)))
        root.addView(heading)
        val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val locked = context.getSystemService(KeyguardManager::class.java).isKeyguardLocked
        fun text(value: String, size: Float, color: Int = ink, bold: Boolean = false) {
            body.addView(TextView(context).apply {
                this.text = value; textSize = size; setTextColor(color); setPadding(0, dp(5), 0, dp(4))
                if (bold) typeface = Typeface.DEFAULT_BOLD
            }, LinearLayout.LayoutParams(-1, -2))
        }
        if (locked) { text("来电信息", 22f, bold = true); text("解锁后查看", 14f, muted) }
        else if (matches.isEmpty()) {
            text(if (number.isBlank()) "号码未提供" else number, 23f, bold = true)
            text("未找到人员信息", 14f, muted)
        } else {
            if (matches.size > 1) text("匹配到 ${matches.size} 位人员", 13f, accent)
            matches.forEachIndexed { i, person ->
                if (i > 0) body.addView(View(context).apply { setBackgroundColor(if (dark) 0xFF454C5A.toInt() else 0xFFE8EDF5.toInt()) }, LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(14) })
                val identity = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, dp(6)) }
                val avatar = FrameLayout(context).apply { background = GradientDrawable().apply { setColor(if (dark) 0xFF333F55.toInt() else 0xFFE6EFFF.toInt()); cornerRadius = dp(19).toFloat() }; clipToOutline = true; contentDescription = "人员头像" }
                avatar.addView(TextView(context).apply { text = person.name.take(1); textSize = 23f; setTextColor(accent); gravity = Gravity.CENTER }, FrameLayout.LayoutParams(-1, -1))
                if (person.avatarUrl.isNotEmpty()) {
                    val image = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
                    avatar.addView(image, FrameLayout.LayoutParams(-1, -1))
                    imageRequests.add(image.load(person.avatarUrl))
                }
                identity.addView(avatar, LinearLayout.LayoutParams(dp(56), dp(56)).apply { marginEnd = dp(14) })
                val title = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                title.addView(TextView(context).apply { text = person.name; textSize = prefs.getInt("textSize", 28).toFloat(); setTextColor(ink); typeface = Typeface.DEFAULT_BOLD })
                title.addView(TextView(context).apply { text = person.post.ifBlank { "岗位未提供" }; textSize = 15f; setTextColor(accent); setPadding(0, dp(4), 0, 0) })
                identity.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
                body.addView(identity, LinearLayout.LayoutParams(-1, -2))
                text(person.topOrgName.ifBlank { "单位未提供" }, 15f)
                if (prefs.getBoolean("department", true) && person.fullOrgName != person.topOrgName) text(person.fullOrgName, 12f, muted)
            }
        }
        val maxBody = (context.resources.displayMetrics.heightPixels * .42f).toInt()
        val scroll = ScrollView(context).apply { addView(body); isFillViewport = false }
        body.measure(View.MeasureSpec.makeMeasureSpec(context.resources.displayMetrics.widthPixels - dp(76), View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        root.addView(scroll, LinearLayout.LayoutParams(-1, minOf(body.measuredHeight, maxBody)))
        val screenHeight = context.resources.displayMetrics.heightPixels
        val params = WindowManager.LayoutParams(context.resources.displayMetrics.widthPixels - dp(32), -2,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START; x = dp(16)
            y = (prefs.getFloat("overlayY", .30f) * screenHeight).toInt().coerceIn(dp(48), screenHeight - maxBody - dp(150))
        }
        var startY = 0f; var originY = 0
        drag.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { startY = event.rawY; originY = params.y; true }
                MotionEvent.ACTION_MOVE -> {
                    params.y = (originY + event.rawY - startY).toInt().coerceIn(dp(32), (screenHeight - root.height - dp(24)).coerceAtLeast(dp(32)))
                    runCatching { wm.updateViewLayout(root, params) }; true
                }
                MotionEvent.ACTION_UP -> { if (kotlin.math.abs(event.rawY - startY) < dp(8)) drag.performClick(); prefs.edit().putFloat("overlayY", params.y.toFloat() / screenHeight).apply(); true }
                else -> false
            }
        }
        return try { wm.addView(root, params); view = root; showingCall = isCall; payload = Payload(number, matches, isCall); hideAfter(30000); true }
        catch (_: Exception) { false }
    }
}
