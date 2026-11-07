package com.zuozishi.callhelper
import android.content.Context
import java.io.InputStream
object BundledData {
    fun open(context: Context): InputStream? = runCatching { context.assets.open("private/members.csv") }.getOrNull()
}
