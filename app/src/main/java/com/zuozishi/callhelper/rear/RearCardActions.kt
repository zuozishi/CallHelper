package com.zuozishi.callhelper.rear

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.zuozishi.callhelper.CallHelperApplication

class RearCardActions : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "com.zuozishi.callhelper.CLOSE_REAR") return
        val token = intent.data?.lastPathSegment ?: return
        (context.applicationContext as CallHelperApplication).rear.dismiss(token)
    }
}
