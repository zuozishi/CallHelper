package com.zuozishi.callhelper.rear

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import com.zuozishi.callhelper.CallHelperApplication

/** RemoteViews are available only to this app and the exact native rear host identity. */
class NativeRearProvider : ContentProvider() {
    companion object {
        const val TARGET = "com.xiaomi.subscreencenter"
        const val SUPPORTED_VERSION = "RELEASE-1.0.2609141923"
        val URI: Uri = Uri.parse("content://com.zuozishi.callhelper.rear")
    }
    override fun onCreate() = true
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val c = requireNotNull(context)
        val self = Binder.getCallingUid() == Process.myUid()
        if (!self) {
            val targetUid = runCatching { c.packageManager.getApplicationInfo(TARGET, 0).uid }.getOrDefault(-1)
            if (Binder.getCallingUid() != targetUid || callingPackage != TARGET) throw SecurityException("Unexpected caller")
        }
        val rear = (c.applicationContext as CallHelperApplication).rear
        return when (method) {
            "views" -> Bundle().apply {
                val card = rear.activeCard()
                putBoolean("active", card != null)
                putString("token", card?.token)
                putLong("show", card?.showAt ?: 0)
                putLong("expires", card?.expiresAt ?: 0)
                putParcelable("views", rear.render(false))
                putParcelable("aod", rear.render(true))
            }
            "report" -> {
                require(arg in setOf("READY", "UPDATED", "ATTACHED", "DETACHED", "ERROR"))
                rear.hostReport(arg!!, extras?.getString("token"), extras?.getBoolean("active") == true, extras?.getBoolean("attached") == true)
                Bundle.EMPTY
            }
            else -> throw IllegalArgumentException("Unknown method")
        }
    }
    override fun query(u: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = throw SecurityException()
    override fun getType(u: Uri): String? = null
    override fun insert(u: Uri, v: ContentValues?): Uri? = throw SecurityException()
    override fun delete(u: Uri, s: String?, a: Array<out String>?): Int = throw SecurityException()
    override fun update(u: Uri, v: ContentValues?, s: String?, a: Array<out String>?): Int = throw SecurityException()
}
