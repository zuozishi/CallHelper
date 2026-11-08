package com.zuozishi.callhelper.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.zuozishi.callhelper.CallHelperApplication
import com.zuozishi.callhelper.data.DirectorySyncConfig
import kotlinx.coroutines.*
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal fun configurationQrBitmap(config: DirectorySyncConfig): Bitmap {
    val matrix = QRCodeWriter().encode(config.toQrContent(), BarcodeFormat.QR_CODE, 1024, 1024)
    return Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888).apply {
        val pixels = IntArray(matrix.width * matrix.height) { index ->
            if (matrix[index % matrix.width, index / matrix.width]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        }
        setPixels(pixels, 0, matrix.width, 0, 0, matrix.width, matrix.height)
    }
}

@Composable
internal fun SyncConfigurationPanel(app: CallHelperApplication, saved: DirectorySyncConfig?, busy: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(saved?.url.orEmpty()) }
    var key by remember { mutableStateOf(saved?.keyBase64.orEmpty()) }
    var status by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    var exported by remember { mutableStateOf<Bitmap?>(null) }
    var editing by remember { mutableStateOf(false) }
    val colors = MiuixTheme.colorScheme
    // Secrets stay out of saved instance state, screenshots and the recent-app thumbnail.
    DisposableEffect(context, editing) {
        val window = (context as? Activity)?.window
        val alreadySecure = ((window?.attributes?.flags ?: 0) and WindowManager.LayoutParams.FLAG_SECURE) != 0
        if (editing) window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { if (editing && !alreadySecure) window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    LaunchedEffect(saved) { url = saved?.url.orEmpty(); key = saved?.keyBase64.orEmpty() }
    fun importContent(content: String) {
        try {
            val config = DirectorySyncConfig.fromQrContent(content)
            url = config.url; key = config.keyBase64; status = "已导入，请核对后保存"
        } catch (_: Exception) { status = "不是有效的人员库同步配置二维码" }
    }
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let(::importContent)
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            working = true
            try {
                val content = withContext(Dispatchers.IO) {
                    val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        val scale = maxOf(info.size.width, info.size.height) / 2048f
                        if (scale > 1) decoder.setTargetSize((info.size.width / scale).toInt().coerceAtLeast(1), (info.size.height / scale).toInt().coerceAtLeast(1))
                    }
                    try {
                        val pixels = IntArray(bitmap.width * bitmap.height)
                        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                        val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
                        MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source)), mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true)).text
                    } finally { bitmap.recycle() }
                }
                importContent(content)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { status = "未能读取配置二维码，请选择清晰的二维码图片" }
            finally { working = false }
        }
    }
    val saveImage = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        val bitmap = exported
        if (uri != null && bitmap != null) scope.launch {
            working = true
            try {
                withContext(Dispatchers.IO) {
                    requireNotNull(context.contentResolver.openOutputStream(uri)).use { output ->
                        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                    }
                }
                status = "二维码已导出"
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { status = "二维码保存失败，请重试" }
            finally { working = false }
        }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text("同步配置", modifier = Modifier.weight(1f))
        ConfigAction("编辑同步配置", AppIconSymbol.Settings, !busy) {
            url = saved?.url.orEmpty(); key = saved?.keyBase64.orEmpty(); status = null
            editing = true
        }
    }
    if (editing) Dialog(
        onDismissRequest = { if (!working) editing = false },
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
    ) {
      Card {
       Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("同步配置", modifier = Modifier.weight(1f))
            ConfigAction("关闭同步配置", AppIconSymbol.Close, !working) { editing = false }
        }
        TextField(url, { url = it; status = "修改尚未保存" }, label = "同步 URL", singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("sync-url"))
        Text("解密密钥（Base64）", fontSize = 13.sp)
        BasicTextField(key, { key = it; status = "修改尚未保存" }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            textStyle = TextStyle(color = colors.onSurface, fontSize = 16.sp),
            modifier = Modifier.fillMaxWidth().background(colors.background).padding(16.dp).semantics { contentDescription = "解密密钥 Base64" }.testTag("sync-key"))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            ConfigAction("扫描二维码", AppIconSymbol.Scan, !working && !busy) {
                scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setBeepEnabled(false).setOrientationLocked(false).setPrompt("扫描人员库同步配置二维码"))
            }
            ConfigAction("从图片导入二维码", AppIconSymbol.Folder, !working && !busy) { imagePicker.launch("image/*") }
            ConfigAction("导出配置二维码", AppIconSymbol.QrCode, !working && !busy) {
                scope.launch {
                    working = true
                    try {
                        val config = DirectorySyncConfig.create(url, key)
                        exported = withContext(Dispatchers.Default) { configurationQrBitmap(config) }
                    } catch (_: IllegalArgumentException) { status = "请先填写有效的 HTTPS URL 和 32 字节 Base64 密钥" }
                    finally { working = false }
                }
            }
            ConfigAction("保存同步配置", AppIconSymbol.Check, !working && !busy) {
                scope.launch {
                    working = true
                    try {
                        val config = DirectorySyncConfig.create(url, key)
                        app.ready.await()
                        app.saveSyncConfig(config)
                        status = "配置已保存"
                        editing = false
                    } catch (e: CancellationException) { throw e }
                    catch (e: IllegalArgumentException) { status = e.message }
                    catch (_: Exception) { status = "配置保存失败，请重试" }
                    finally { working = false }
                }
            }
        }
        status?.let { Text(it, fontSize = 13.sp, modifier = Modifier.testTag("sync-config-status")) }
       }
      }
    }
    exported?.let { bitmap ->
        Dialog(onDismissRequest = { if (!working) exported = null }) {
            Card {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("二维码包含解密密钥，仅向可信人员提供", fontSize = 14.sp)
                    Image(bitmap.asImageBitmap(), contentDescription = "人员库同步配置二维码", modifier = Modifier.fillMaxWidth().aspectRatio(1f))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        ConfigAction("保存二维码图片", AppIconSymbol.Save, !working) { saveImage.launch("人员库同步配置.png") }
                        ConfigAction("关闭二维码", AppIconSymbol.Close, !working) { exported = null }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfigAction(label: String, icon: AppIconSymbol, enabled: Boolean, action: () -> Unit) {
    androidx.compose.foundation.layout.Box(
        Modifier.size(48.dp).semantics { contentDescription = label; role = Role.Button }.clickable(enabled = enabled, onClick = action),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) { AppIcon(icon, MiuixTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else .35f)) }
}
