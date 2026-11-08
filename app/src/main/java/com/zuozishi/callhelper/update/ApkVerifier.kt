package com.zuozishi.callhelper.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.android.apksig.ApkVerifier
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

internal class UpdateRejected(message: String) : Exception(message)

internal class UpdateApkVerifier(private val context: Context) {
    @Suppress("DEPRECATION")
    fun verify(file: File) {
        val pm = context.packageManager
        val installed = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val archive = pm.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: throw UpdateRejected("安装包无效，请重新下载")
        if (archive.packageName != context.packageName || archive.longVersionCode <= installed.longVersionCode ||
            !isNewerVersion(archive.versionName.orEmpty(), installed.versionName.orEmpty()))
            throw UpdateRejected("安装包与当前应用或版本不匹配")
        if (archive.applicationInfo?.minSdkVersion?.let { it > Build.VERSION.SDK_INT } == true)
            throw UpdateRejected("此版本不支持当前系统")
        // Verify APK cryptography before trusting any certificate parsed from the archive.
        val verified = ApkVerifier.Builder(file).setMinCheckedPlatformVersion(Build.VERSION.SDK_INT)
            .setMaxCheckedPlatformVersion(Build.VERSION.SDK_INT).build().verify()
        if (!verified.isVerified) throw UpdateRejected("安装包签名校验失败，请重新下载")
        fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).toList()
        val current = installed.signingInfo?.apkContentsSigners?.map { hash(it.toByteArray()) }?.toSet().orEmpty()
        val next = verified.signerCertificates.map { hash(it.encoded) }.toSet()
        if (current.isEmpty() || next != current) throw UpdateRejected("安装包签名与当前应用不同，无法覆盖安装")
        ZipFile(file).use { zip ->
            val abis = zip.entries().asSequence().map { it.name }.filter { it.startsWith("lib/") && it.endsWith(".so") }
                .map { it.split('/')[1] }.toSet()
            if (abis.isNotEmpty() && Build.SUPPORTED_ABIS.none { it in abis }) throw UpdateRejected("安装包不支持当前设备")
        }
    }
}
