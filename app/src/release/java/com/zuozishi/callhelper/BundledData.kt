package com.zuozishi.callhelper
import android.content.Context
import java.io.InputStream
object BundledData {
    fun open(context: Context): InputStream? = null
    suspend fun refresh(context: Context, repository: com.zuozishi.callhelper.data.DirectoryRepository): Boolean = false
}
