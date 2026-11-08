package com.zuozishi.callhelper.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.zuozishi.callhelper.BuildConfig
import com.zuozishi.callhelper.R
import com.zuozishi.callhelper.update.*
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.Locale

private val Blue = Color(0xFF3482FF)
private val offlineReleases = listOf(AppRelease("v0.3.0", "2026-11-08", """
新增关于页，展示 GitHub 仓库、作者与更新记录。
启动静默检查应用更新，网络失败不提示；支持手动检查。
Release 信息与 APK 优先通过镜像访问，失败自动回退 GitHub。
下载完成后校验完整性、版本、签名和兼容性，自动打开系统安装器。
支持安装授权返回后继续；后台完成下载后返回应用继续安装。
""".trimIndent(), emptyList()), AppRelease("v0.2.0", "2026-11-08", """
支持 HTTPS 加密全量人员库同步，失败保留离线库。
支持同步配置手动录入、扫码、二维码图片导入与导出。
支持组织逐级浏览、全库搜索和人员详情拨号。
支持小米原生背屏联系人卡片，需 LSPosed / Vector 和适配版本。
更新界面图标，增加人员库超过 7 天未更新的提醒。
移除安装包内的人员数据、同步地址和解密密钥。
""".trimIndent(), emptyList()))

internal fun LazyListScope.aboutItems(
    updates: UpdateController, state: UpdateState, history: Boolean,
    onHistory: () -> Unit, onLink: (String) -> Unit,
) {
    if (history) {
        if (state.historyLoading) item { Text("正在加载", color = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
        if (state.historyError) item {
            AboutCard {
                Text(if (state.history.isEmpty()) "暂时无法获取更新记录" else "暂时无法刷新，显示已缓存记录", fontSize = 14.sp)
                UpdateButton("重试", primary = false, onClick = updates::loadHistory)
            }
        }
        val releases = (offlineReleases.filter { it.tag == "v${BuildConfig.VERSION_NAME}" } + state.history + offlineReleases)
            .distinctBy { stableVersion(it.tag) }
        items(releases, key = { "release-${it.tag}" }) { release ->
            AboutCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(release.tag.removePrefix("v"), fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                    if (stableVersion(release.tag) == stableVersion(BuildConfig.VERSION_NAME)) {
                        Text("当前版本", color = Blue, fontSize = 12.sp,
                            modifier = Modifier.background(Blue.copy(alpha = .1f), RoundedCornerShape(8.dp)).padding(8.dp))
                    }
                }
                if (release.date.isNotBlank()) Text(release.date, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                // Release markdown is shown as bounded plain text; no remote HTML is executed.
                Text(release.notes.ifBlank { "暂无更新说明" }, fontSize = 14.sp, modifier = Modifier.testTag("release-notes"))
            }
        }
        item { UpdateButton("在 GitHub 查看", primary = false) { onLink("$REPOSITORY/releases") } }
        return
    }
    item {
        Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Image(painterResource(R.drawable.ic_app), contentDescription = null, modifier = Modifier.size(80.dp))
            Spacer(Modifier.height(16.dp))
            Text("来电名片", fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
        }
    }
    item {
        AboutCard {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("当前版本", fontSize = 15.sp)
                Text("v${BuildConfig.VERSION_NAME}", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            val label = when {
                state.downloading -> "正在下载 · ${downloadProgress(state)}"
                state.ready -> "安装更新"
                state.downloadError != null -> "重试下载"
                state.checking && state.manualCheck -> "正在检查"
                state.release != null -> "下载更新 ${state.release.tag}"
                else -> "检查更新"
            }
            UpdateButton(label, enabled = !(state.checking && state.manualCheck), tag = "check-update") {
                when {
                    state.downloading -> updates.showDownload()
                    state.ready -> updates.requestInstall()
                    state.release != null -> updates.download()
                    else -> updates.check()
                }
            }
            state.message?.let { Text(it, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.testTag("update-message").semantics { liveRegion = LiveRegionMode.Polite }) }
            state.downloadError?.let { Text(it, fontSize = 13.sp, modifier = Modifier.testTag("download-error")) }
        }
    }
    item {
        AboutCard {
            AboutLink("GitHub", "zuozishi / CallHelper", AppIconSymbol.Folder) { onLink(REPOSITORY) }
            AboutLink("作者", "zuozishi", AppIconSymbol.People) { onLink("https://github.com/zuozishi") }
        }
    }
    item { AboutCard { AboutLink("更新记录", "", AppIconSymbol.Preview, onHistory) } }
}

@Composable
private fun AboutCard(content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
    }
}

@Composable
private fun AboutLink(title: String, value: String, symbol: AppIconSymbol, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick)
        .semantics(mergeDescendants = true) { role = Role.Button }, verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        AppIcon(symbol, MiuixTheme.colorScheme.onSurface)
        Text(title, fontSize = 15.sp)
        Spacer(Modifier.weight(1f))
        if (value.isNotBlank()) Text(value, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.weight(2f))
        AppIcon(AppIconSymbol.Arrow, MiuixTheme.colorScheme.onSurfaceVariantSummary, Modifier.size(18.dp))
    }
}

@Composable
internal fun UpdateButton(label: String, primary: Boolean = true, enabled: Boolean = true, tag: String = "", onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().heightIn(min = 50.dp).clip(RoundedCornerShape(16.dp))
        .background(if (primary) Blue.copy(alpha = if (enabled) 1f else .5f) else Color.Transparent)
        .clickable(enabled = enabled, onClick = onClick).semantics(mergeDescendants = true) { role = Role.Button }
        .testTag(tag).padding(horizontal = 12.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
        Text(label, fontSize = 16.sp, fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Normal,
            color = if (primary) Color.White else MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}

private fun megabytes(bytes: Long) = String.format(Locale.ROOT, "%.1f", bytes / 1048576.0)
private fun downloadProgress(state: UpdateState) = if (state.total > 0) "${state.downloaded * 100 / state.total}%" else "${megabytes(state.downloaded)} MB"

@Composable
internal fun UpdateDialog(state: UpdateState, updates: UpdateController, onAuthorize: () -> Unit, onLink: (String) -> Unit) {
    val sheet = state.sheet ?: return
    Dialog(onDismissRequest = updates::dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val view = LocalView.current
        SideEffect { (view.parent as? DialogWindowProvider)?.window?.setGravity(android.view.Gravity.BOTTOM) }
        Column(Modifier.fillMaxWidth().heightIn(max = 680.dp)
            .background(MiuixTheme.colorScheme.surface, RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp))
            .verticalScroll(rememberScrollState()).navigationBarsPadding().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.align(Alignment.CenterHorizontally).size(32.dp, 4.dp).background(Color.Gray.copy(alpha = .3f), RoundedCornerShape(2.dp)))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("应用更新", color = Blue, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).clickable { updates.dismiss() }
                    .semantics { contentDescription = if (state.downloading) "收起下载进度" else "关闭更新提示"; role = Role.Button }, contentAlignment = Alignment.Center) {
                    AppIcon(AppIconSymbol.Close, MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            }
            when (sheet) {
                UpdateSheet.Release -> {
                    val release = state.release ?: return@Column
                    Text("发现新版本", fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                    val asset = release.apk(android.os.Build.SUPPORTED_ABIS.toList())
                    Text("v${BuildConfig.VERSION_NAME} → ${release.tag}" + (asset?.let { " · ${megabytes(it.size)} MB" } ?: ""), fontSize = 14.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    Text(release.notes.ifBlank { "暂无更新说明" }, fontSize = 14.sp,
                        modifier = Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()))
                    UpdateButton(if (asset == null) "在 GitHub 查看" else "下载更新", tag = "download-update") {
                        if (asset == null) onLink("$REPOSITORY/releases") else updates.download()
                    }
                    UpdateButton("稍后", primary = false, onClick = updates::dismiss)
                }
                UpdateSheet.Download -> {
                    Text(when { state.downloading -> "正在下载"; state.ready -> "下载完成"; else -> "下载失败" }, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                    state.release?.let { Text(it.tag, fontSize = 14.sp) }
                    if (state.downloading) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(downloadProgress(state), fontSize = 32.sp, fontWeight = FontWeight.SemiBold)
                            Text("${megabytes(state.downloaded)} / ${megabytes(state.total)} MB", fontSize = 13.sp)
                        }
                        Box(Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(4.dp)).background(Blue.copy(alpha = .15f))
                            .semantics { progressBarRangeInfo = if (state.total > 0) ProgressBarRangeInfo(state.downloaded.toFloat() / state.total, 0f..1f) else ProgressBarRangeInfo.Indeterminate }) {
                            if (state.total > 0) Box(Modifier.fillMaxHeight().fillMaxWidth((state.downloaded.toFloat() / state.total).coerceIn(0f, 1f)).background(Blue))
                        }
                        Text("下载完成后自动打开安装器", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        UpdateButton("收起", onClick = updates::dismiss)
                        UpdateButton("取消下载", primary = false, onClick = updates::cancelDownload)
                    } else {
                        state.downloadError?.let { Text(it, fontSize = 14.sp) }
                        UpdateButton(if (state.ready) "安装更新" else "重试下载") { if (state.ready) updates.requestInstall() else updates.download() }
                        UpdateButton("在 GitHub 查看", primary = false) { onLink("$REPOSITORY/releases") }
                    }
                }
                UpdateSheet.Permission -> {
                    AppIcon(AppIconSymbol.Shield, Blue, Modifier.align(Alignment.CenterHorizontally).size(40.dp))
                    Text("允许安装更新", fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                    Text("安装包已下载。请在系统设置中允许「来电名片」安装应用。", fontSize = 15.sp)
                    UpdateButton("前往设置", onClick = onAuthorize)
                    UpdateButton("稍后", primary = false, onClick = updates::dismiss)
                }
            }
        }
    }
}
