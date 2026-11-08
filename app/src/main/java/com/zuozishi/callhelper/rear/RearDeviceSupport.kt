package com.zuozishi.callhelper.rear

import android.os.Build
import java.util.Locale
import java.util.concurrent.TimeUnit

/** UI allowlist only; the native host's firmware compatibility check remains separate. */
internal object RearDeviceSupport {
    private val models = setOf("xiaomi17pro", "xiaomi17promax", "xiaomi18pro", "xiaomi18promax")

    fun current(): Boolean {
        if (!Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true)) return false
        if (matches(Build.MODEL)) return true
        // Xiaomi's Build.MODEL may be a regulatory number; the public market name identifies the model.
        val marketName = runCatching {
            val process = ProcessBuilder("/system/bin/getprop", "ro.product.marketname").redirectErrorStream(true).start()
            try {
                if (process.waitFor(500, TimeUnit.MILLISECONDS)) process.inputStream.bufferedReader().use { it.readLine().orEmpty() }
                else ""
            } finally {
                process.inputStream.close()
                process.outputStream.close()
                process.errorStream.close()
                process.destroy()
            }
        }.getOrDefault("")
        return matches(marketName)
    }

    private fun matches(name: String): Boolean = name.lowercase(Locale.ROOT)
        .replace("小米", "xiaomi").filterNot { it.isWhitespace() || it == '-' || it == '_' } in models
}
