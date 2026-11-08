package com.zuozishi.callhelper

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.core.content.FileProvider
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.catalog.components.LiquidBottomTab
import com.kyant.backdrop.catalog.components.LiquidBottomTabs
import com.zuozishi.callhelper.ui.AppIcon
import com.zuozishi.callhelper.ui.AppIconSymbol
import com.zuozishi.callhelper.ui.SyncConfigurationPanel
import com.zuozishi.callhelper.ui.aboutItems
import com.zuozishi.callhelper.ui.UpdateDialog
import com.zuozishi.callhelper.domain.DirectorySnapshot
import com.zuozishi.callhelper.domain.Personnel
import com.zuozishi.callhelper.domain.isDirectoryUpdateOverdue
import com.zuozishi.callhelper.rear.RearDeviceSupport
import coil3.compose.AsyncImage
import kotlinx.coroutines.*
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import top.yukonga.miuix.kmp.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Blue = Color(0xFF3482FF)
internal fun personnelDialIntent(phone: String) = Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", phone, null))

class MainActivity : ComponentActivity() {
    private val app get() = application as CallHelperApplication
    private var revision by mutableIntStateOf(0)
    private var updateReminderVisible by mutableStateOf(false)
    private val external = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { revision++ }
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { app.observeCallState(); revision++ }
    private val installPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        app.updates.permissionReturned(packageManager.canRequestPackageInstalls())
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        app.updates.startupCheck()
        setContent {
            val snapshot by app.directory.snapshot.collectAsStateWithLifecycle()
            val error by app.startupError.collectAsStateWithLifecycle()
            val syncing by app.syncing.collectAsStateWithLifecycle()
            var theme by remember { mutableIntStateOf(app.prefs.getInt("theme", 0)) }
            val dark = when (theme) { 1 -> false; 2 -> true; else -> isSystemInDarkTheme() }
            val colors = (if (dark) darkColorScheme() else lightColorScheme()).copy(
                primary = Blue,
                background = if (dark) Color(0xFF101216) else Color(0xFFF5F5F5),
                surface = if (dark) Color(0xFF252A34) else Color.White,
                surfaceContainer = if (dark) Color(0xFF252A34) else Color.White,
            )
            MiuixTheme(colors = colors) {
                AppUi(snapshot, syncing, error, revision, theme, dark,
                    onSync = { if (!app.syncing.value) app.scope.launch { app.ready.await(); app.syncEmployees() } },
                    onTheme = { theme = it; app.prefs.edit().putInt("theme", it).apply() },
                    onPreview = { person ->
                        if (!Settings.canDrawOverlays(this)) openOverlayPermission()
                        else if (!app.overlay.show(person?.phone.orEmpty(), listOfNotNull(person))) Toast.makeText(this, "请先开启悬浮窗", Toast.LENGTH_SHORT).show()
                    },
                    requestRole = {
                        val manager = getSystemService(RoleManager::class.java)
                        if (manager?.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING) == true) {
                            if (!manager.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)) launchExternal(manager.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING))
                        } else Toast.makeText(this, "此设备不支持系统来电识别授权", Toast.LENGTH_SHORT).show()
                    },
                    requestOverlay = ::openOverlayPermission,
                    requestPhone = { permissions.launch(arrayOf(Manifest.permission.READ_PHONE_STATE)) },
                    requestContacts = { permissions.launch(arrayOf(Manifest.permission.READ_CONTACTS)) })
            }
        }
    }
    private fun launchExternal(intent: Intent) {
        try { external.launch(intent) }
        catch (_: ActivityNotFoundException) { Toast.makeText(this, "无法打开系统设置，请在系统中手动授权", Toast.LENGTH_LONG).show() }
        catch (_: SecurityException) { Toast.makeText(this, "系统限制了此授权页面，请在系统中手动授权", Toast.LENGTH_LONG).show() }
    }
    private fun openOverlayPermission() { launchExternal(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
    private fun openUpdateLink(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        catch (_: ActivityNotFoundException) { Toast.makeText(this, "未找到浏览器", Toast.LENGTH_SHORT).show() }
    }
    private fun authorizeUpdateInstall() {
        app.updates.openPermission()
        try { installPermission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))) }
        catch (_: Exception) { app.updates.permissionReturned(false) }
    }
    private fun installUpdate() {
        app.updates.consumeInstallRequest()
        if (!packageManager.canRequestPackageInstalls()) { app.updates.needPermission(); return }
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.updates", app.updates.apk)
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            app.updates.dismiss()
        } catch (_: Exception) { app.updates.installFailed() }
    }
    override fun onResume() {
        super.onResume(); revision++; app.observeCallState(); app.rear.refreshStatus()
        if (app.updates.awaitingPermission) app.updates.permissionReturned(packageManager.canRequestPackageInstalls())
    }
    override fun onStart() {
        super.onStart()
        lifecycleScope.launch {
            app.ready.await()
            updateReminderVisible = isDirectoryUpdateOverdue(app.lastUpdated.value, System.currentTimeMillis())
        }
    }

    @Composable
    private fun AppUi(
        directory: DirectorySnapshot, busy: Boolean, message: String?, revision: Int, theme: Int, dark: Boolean,
        onSync: () -> Unit, onTheme: (Int) -> Unit, onPreview: (Personnel?) -> Unit,
        requestRole: () -> Unit, requestOverlay: () -> Unit, requestPhone: () -> Unit, requestContacts: () -> Unit,
    ) {
        var tab by rememberSaveable { mutableIntStateOf(0) }
        var showHistory by rememberSaveable { mutableStateOf(false) }
        val updateState by app.updates.state.collectAsStateWithLifecycle()
        val lifecycleState by lifecycle.currentStateFlow.collectAsStateWithLifecycle()
        var query by rememberSaveable { mutableStateOf("") }
        var enabled by remember { mutableStateOf(app.prefs.getBoolean("enabled", true)) }
        var hideUnmatched by remember { mutableStateOf(app.prefs.getBoolean("hideUnmatched", true)) }
        var department by remember { mutableStateOf(app.prefs.getBoolean("department", true)) }
        var largeText by remember { mutableStateOf(app.prefs.getInt("textSize", 28) > 28) }
        var rearEnabled by remember { mutableStateOf(app.prefs.getBoolean("rearEnabled", false)) }
        var rearSettingsVisible by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { rearSettingsVisible = withContext(Dispatchers.IO) { RearDeviceSupport.current() } }
        var rearShowLocked by remember { mutableStateOf(app.prefs.getBoolean("rearShowLocked", false)) }
        val rearStatus by app.rear.status.collectAsStateWithLifecycle()
        var detail by remember { mutableStateOf<Personnel?>(null) }
        BackHandler(enabled = tab == 4 && showHistory) { showHistory = false }
        LaunchedEffect(updateState.installRequested, lifecycleState, updateReminderVisible, detail) {
            if (updateState.installRequested && lifecycleState == Lifecycle.State.RESUMED && !updateReminderVisible && detail == null) installUpdate()
        }
        var results by remember { mutableStateOf(emptyList<Personnel>()) }
        var settledQuery by remember { mutableStateOf("") }
        LaunchedEffect(query, directory) {
            results = emptyList(); settledQuery = ""
            if (query.isNotBlank()) {
                delay(180)
                results = withContext(Dispatchers.Default) { directory.search(query) }
            }
            settledQuery = query
        }
        var organizationKey by rememberSaveable { mutableStateOf("") }
        val organization = directory.organizations.nodes[organizationKey] ?: directory.organizations.root
        var resultLimit by remember(query, directory) { mutableIntStateOf(100) }
        val scrollStates = remember(directory) { mutableMapOf<String, LazyListState>() }
        val route = if (tab == 1) "directory:${organization.key}:${query.isNotBlank()}" else "tab:$tab:$showHistory"
        val listState = scrollStates.getOrPut(route) { LazyListState() }
        BackHandler(enabled = tab == 1 && (query.isNotBlank() || organization.parent != null)) {
            if (query.isNotBlank()) query = "" else organizationKey = organization.parent.orEmpty()
        }
        val roleGranted = remember(revision) { getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_CALL_SCREENING) == true }
        val overlayGranted = remember(revision) { Settings.canDrawOverlays(this) }
        val phoneGranted = remember(revision) { checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED }
        val contactsGranted = remember(revision) { checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED }
        val backdrop = rememberLayerBackdrop()
        val colors = MiuixTheme.colorScheme
        val syncConfig by app.syncConfig.collectAsStateWithLifecycle()
        val lastUpdated by app.lastUpdated.collectAsStateWithLifecycle()
        LaunchedEffect(lastUpdated) {
            if (!isDirectoryUpdateOverdue(lastUpdated, System.currentTimeMillis())) updateReminderVisible = false
        }
        if (updateReminderVisible) Dialog(onDismissRequest = { updateReminderVisible = false }) {
            SurfaceCard {
                Text("更新人员库", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Text("人员库已超过 7 天未更新，请更新数据。", fontSize = 15.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Text("稍后", modifier = Modifier.clickable { updateReminderVisible = false }.padding(16.dp))
                    Text(if (busy) "正在更新" else if (syncConfig == null) "前往配置" else "立即更新", color = Blue,
                        modifier = Modifier.testTag("stale-directory-update").clickable {
                            updateReminderVisible = false
                            tab = 2
                            if (!busy && syncConfig != null) onSync()
                        }.padding(16.dp))
                }
            }
        }
        Box(Modifier.fillMaxSize().background(colors.background)) {
            LazyColumn(Modifier.fillMaxSize().testTag("app-list").layerBackdrop(backdrop).background(colors.background).statusBarsPadding().imePadding(), state = listState,
                contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 20.dp, bottom = 126.dp),
                verticalArrangement = Arrangement.spacedBy(if (tab == 1 && query.isBlank()) 0.dp else 16.dp)) {
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        if (tab == 4 && showHistory) Action("返回关于", AppIconSymbol.Back) { showHistory = false }
                        Text(if (tab == 4 && showHistory) "更新记录" else listOf(getString(R.string.app_name), "人员库", "数据同步", "设置", "关于")[tab], fontSize = 32.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        if (tab == 0) Action("设置", AppIconSymbol.Settings) { tab = 3 }
                        if (tab == 1 && organization.parent != null) Action("返回上一级", AppIconSymbol.Back) { query = ""; organizationKey = organization.parent.orEmpty() }
                    }
                }
                if (message != null) item { SurfaceCard { Text(message, fontSize = 13.sp, color = colors.onSurfaceVariantSummary, modifier = Modifier.testTag("import-status")) } }
                when (tab) {
                    0 -> {
                        item {
                            Column(Modifier.fillMaxWidth().background(if (dark) Color(0xFF22324C) else Color(0xFFEAF2FF), RoundedCornerShape(28.dp)).padding(22.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    AppIcon(AppIconSymbol.Shield, Blue, Modifier.size(42.dp))
                                    Action("预览悬浮窗", AppIconSymbol.Preview) { onPreview(directory.people.firstOrNull()) }
                                }
                                Spacer(Modifier.height(24.dp))
                                Text(when { !enabled -> "已暂停"; directory.people.isEmpty() -> "请同步人员库"; !roleGranted || !overlayGranted -> "待完成设置"; else -> "识别已开启" }, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                        item { Stats(directory, onSync) }
                        if (!roleGranted || !overlayGranted || !phoneGranted || !contactsGranted) item {
                            PermissionCard(roleGranted, overlayGranted, phoneGranted, contactsGranted, requestRole, requestOverlay, requestPhone, requestContacts)
                        }
                        item { TextField(query, { query = it }, label = "姓名、号码、单位", singleLine = true, modifier = Modifier.fillMaxWidth().testTag("home-search"), trailingIcon = { Action("查询", AppIconSymbol.Search) { tab = 1 } }) }
                        if (query.isNotBlank()) items(results.take(5), key = { it.rowKey }) { person -> PersonCard(person) { detail = person } }
                    }
                    1 -> {
                        item {
                            TextField(query, { query = it }, label = "搜索全库姓名、号码、组织", singleLine = true,
                                modifier = Modifier.fillMaxWidth().testTag("search-input"),
                                leadingIcon = { AppIcon(AppIconSymbol.Search, colors.onSurfaceVariantSummary) },
                                trailingIcon = { if (query.isNotEmpty()) Action("清空搜索", AppIconSymbol.Close) { query = "" } })
                        }
                        item {
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("organization-path"), verticalAlignment = Alignment.CenterVertically) {
                                directory.organizations.path(organization.key).forEachIndexed { index, node ->
                                    if (index > 0) AppIcon(AppIconSymbol.Arrow, colors.onSurfaceVariantSummary, Modifier.size(14.dp))
                                    Text(node.name, fontSize = 13.sp, color = if (node.key == organization.key) Blue else colors.onSurfaceVariantSummary,
                                        modifier = Modifier.heightIn(min = 48.dp).clickable { query = ""; organizationKey = node.key }.wrapContentHeight().padding(horizontal = 6.dp))
                                }
                            }
                        }
                        if (query.isNotBlank()) {
                            item { Text(if (settledQuery != query) "正在搜索" else "${results.size} 人", fontSize = 13.sp, color = colors.onSurfaceVariantSummary, modifier = Modifier.testTag("result-count")) }
                            if (settledQuery == query && results.isEmpty()) item { SurfaceCard { Text("未找到相关人员", color = colors.onSurfaceVariantSummary) } }
                            items(results.take(resultLimit), key = { "search:${it.rowKey}" }, contentType = { "person" }) { person -> DirectoryPersonRow(person, true) { detail = person } }
                            if (results.size > resultLimit) item { Text("加载更多（已显示 $resultLimit / ${results.size}）", color = Blue, modifier = Modifier.fillMaxWidth().clickable { resultLimit += 100 }.padding(16.dp)) }
                        } else {
                            item {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(if (organization.key.isEmpty()) "组织" else organization.name, fontSize = 13.sp, modifier = Modifier.weight(1f))
                                    Text("${organization.children.size} 个组织 · ${organization.total} 人", fontSize = 12.sp, color = colors.onSurfaceVariantSummary)
                                }
                            }
                            items(organization.children, key = { "org:$it" }, contentType = { "folder" }) { key ->
                                val node = directory.organizations.nodes.getValue(key)
                                Row(Modifier.fillMaxWidth().padding(top = 2.dp).background(colors.surface, RoundedCornerShape(12.dp))
                                    .testTag("organization-folder").clickable { organizationKey = key }.padding(horizontal = 14.dp, vertical = 8.dp).heightIn(min = 40.dp),
                                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        AppIcon(AppIconSymbol.Folder, Color(0xFFE4AD48), Modifier.size(28.dp))
                                        Text(node.name, fontSize = 15.sp, modifier = Modifier.weight(1f))
                                        Text("${node.total} 人", fontSize = 12.sp, color = colors.onSurfaceVariantSummary)
                                        AppIcon(AppIconSymbol.Arrow, colors.onSurfaceVariantSummary, Modifier.size(16.dp))
                                }
                            }
                            if (organization.people.isNotEmpty() && organization.children.isNotEmpty()) item { Text("直属人员 · ${organization.people.size} 人", fontSize = 12.sp, color = colors.onSurfaceVariantSummary, modifier = Modifier.padding(top = 16.dp)) }
                            items(organization.people, key = { "direct:${it.rowKey}" }, contentType = { "person" }) { person ->
                                Box(Modifier.padding(top = 16.dp)) { DirectoryPersonRow(person) { detail = person } }
                            }
                            if (organization.total == 0) item { SurfaceCard { Text(if (organization.key.isEmpty()) "尚未同步人员库" else "暂无人员", color = colors.onSurfaceVariantSummary) } }
                        }
                    }
                    2 -> {
                        item { Stats(directory, onSync) }
                        item {
                            SurfaceCard {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text(if (busy) "正在同步" else "员工库", modifier = Modifier.weight(1f))
                                    if (!busy) Action("同步人员库", AppIconSymbol.Sync, onSync) else Text("…")
                                }
                                val updated = app.prefs.getLong("updated", 0)
                                Text(syncConfig?.let { java.net.URI(it.url).host } ?: "尚未配置", fontSize = 13.sp, color = colors.onSurfaceVariantSummary)
                                if (updated > 0) Text(SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(updated)), fontSize = 12.sp, color = colors.onSurfaceVariantSummary)
                                SyncConfigurationPanel(app, syncConfig, busy)
                            }
                        }
                        item { PermissionCard(roleGranted, overlayGranted, phoneGranted, contactsGranted, requestRole, requestOverlay, requestPhone, requestContacts) }
                    }
                    3 -> {
                        item {
                            SurfaceCard {
                                Toggle("来电识别", enabled) { enabled = it; app.prefs.edit().putBoolean("enabled", it).apply(); if (!it) { app.overlay.dismiss(); app.rear.dismiss() } }
                                Toggle("未匹配时不显示弹窗", hideUnmatched) { hideUnmatched = it; app.prefs.edit().putBoolean("hideUnmatched", it).apply() }
                                WindowDropdownPreference(
                                    items = listOf("跟随系统", "浅色", "深色"),
                                    selectedIndex = theme,
                                    title = "主题",
                                    modifier = Modifier.testTag("theme-dropdown"),
                                    insideMargin = PaddingValues(0.dp),
                                    onSelectedIndexChange = onTheme,
                                )
                                Toggle("显示组织路径", department) { department = it; app.prefs.edit().putBoolean("department", it).apply() }
                                Toggle("大号姓名", largeText) { largeText = it; app.prefs.edit().putInt("textSize", if (it) 32 else 28).apply() }
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text("主屏锁屏隐藏详情", modifier = Modifier.weight(1f)); AppIcon(AppIconSymbol.Shield, Blue) }
                            }
                        }
                        if (rearSettingsVisible) item {
                            SurfaceCard {
                                Toggle("背屏联系人卡片", rearEnabled) { rearEnabled = it; app.rear.setEnabled(it) }
                                if (rearEnabled) {
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Text(rearStatus, fontSize = 13.sp, modifier = Modifier.weight(1f).testTag("rear-status"))
                                        Action("打开 LSPosed 模块管理", AppIconSymbol.Shield) { app.rear.openModuleManager() }
                                        Action("预览背屏卡片", AppIconSymbol.Preview) {
                                            val person = directory.people.firstOrNull()
                                            app.rear.show(person?.phone.orEmpty(), listOfNotNull(person))
                                        }
                                    }
                                    Toggle("背屏锁屏显示详情", rearShowLocked) { rearShowLocked = it; app.prefs.edit().putBoolean("rearShowLocked", it).apply(); app.rear.publish() }
                                    if (rearShowLocked) Text("锁屏时背屏可见姓名、头像和单位", fontSize = 12.sp, color = colors.onSurfaceVariantSummary)
                                }
                            }
                        }
                        item { PermissionCard(roleGranted, overlayGranted, phoneGranted, contactsGranted, requestRole, requestOverlay, requestPhone, requestContacts) }
                    }
                    4 -> aboutItems(app.updates, updateState, showHistory,
                        onHistory = { showHistory = true; app.updates.loadHistory() }, onLink = ::openUpdateLink)
                }
            }
            Navigation(tab, backdrop, dark, Modifier.align(Alignment.BottomCenter)) { tab = it }
        }
        if (!updateReminderVisible && detail == null && lifecycleState == Lifecycle.State.RESUMED) {
            UpdateDialog(updateState, app.updates, ::authorizeUpdateInstall, ::openUpdateLink)
        }
        detail?.let { person ->
            Dialog(onDismissRequest = { detail = null }) {
                SurfaceCard {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) { Avatar(person, Modifier.size(52.dp)); Text(person.name, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)); Action("关闭", AppIconSymbol.Close) { detail = null } }
                    Text(person.post.ifBlank { "岗位未提供" }, color = Blue)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(person.phone, modifier = Modifier.weight(1f))
                        Action("拨号", AppIconSymbol.Phone) {
                            val intent = personnelDialIntent(person.phone)
                            try { startActivity(intent) } catch (_: android.content.ActivityNotFoundException) {
                                Toast.makeText(this@MainActivity, "未找到拨号应用", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    Text(person.topOrgName.ifBlank { "单位未提供" })
                    Text(person.fullOrgName, fontSize = 13.sp, color = colors.onSurfaceVariantSummary)
                    Action("预览此人来电悬浮窗", AppIconSymbol.Preview) { detail = null; onPreview(person) }
                    if (rearSettingsVisible && rearEnabled) Action("预览此人背屏卡片", AppIconSymbol.System) { detail = null; app.rear.show(person.phone, listOf(person)) }
                }
            }
        }
    }
}

@Composable private fun SurfaceCard(content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content) }
}
@Composable private fun Stats(directory: DirectorySnapshot, onSync: () -> Unit) {
    SurfaceCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text("人员库", fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f)); Action("同步人员库", AppIconSymbol.Sync, onSync) }
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) { Text("${directory.people.size}", fontSize = 30.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("person-count")); Text("人员", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
            Column(Modifier.weight(1f)) { Text("${directory.unitCount}", fontSize = 30.sp, fontWeight = FontWeight.SemiBold); Text("单位", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
        }
    }
}
@Composable private fun PersonCard(person: Personnel, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Avatar(person, Modifier.size(46.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(person.name, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text(person.post.ifBlank { "岗位未提供" }, fontSize = 13.sp, color = Blue)
                Text(person.topOrgName.ifBlank { "单位未提供" }, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
    }
}
@Composable private fun DirectoryPersonRow(person: Personnel, showPath: Boolean = false, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().testTag("directory-person").clickable(onClick = onClick)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Avatar(person, Modifier.size(44.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(person.name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                if (person.post.isNotBlank()) Text(person.post, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                Text(person.phone, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                if (showPath) Text(person.fullOrgName.ifBlank { person.topOrgName.ifBlank { "未归属组织" } }, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            AppIcon(AppIconSymbol.Arrow, MiuixTheme.colorScheme.onSurfaceVariantSummary, Modifier.size(16.dp))
        }
    }
}
@Composable private fun Avatar(person: Personnel, modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(16.dp)).background(Blue.copy(alpha = .10f)).semantics { contentDescription = "人员头像" }, contentAlignment = Alignment.Center) {
        Text(person.name.take(1), color = Blue, fontSize = 22.sp)
        if (person.avatarUrl.isNotEmpty()) AsyncImage(model = person.avatarUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
    }
}
@Composable private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 54.dp), verticalAlignment = Alignment.CenterVertically) { Text(label, modifier = Modifier.weight(1f)); Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.semantics { contentDescription = label }) }
}
@Composable private fun PermissionCard(role: Boolean, overlay: Boolean, phone: Boolean, contacts: Boolean, requestRole: () -> Unit, requestOverlay: () -> Unit, requestPhone: () -> Unit, requestContacts: () -> Unit) {
    SurfaceCard {
        listOf(Triple("来电识别", role, requestRole), Triple("悬浮窗", overlay, requestOverlay), Triple("通话结束监听", phone, requestPhone), Triple("识别通讯录来电", contacts, requestContacts)).forEach { (label, granted, action) ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(label, modifier = Modifier.weight(1f), fontSize = 14.sp); if (granted) AppIcon(AppIconSymbol.Check, Color(0xFF22A974), Modifier.semantics { contentDescription = "$label 已授权" }) else Action("授权$label", AppIconSymbol.Arrow, action) }
        }
    }
}
@Composable private fun Navigation(selected: Int, backdrop: Backdrop, dark: Boolean, modifier: Modifier, onSelect: (Int) -> Unit) {
    val index = rememberUpdatedState(selected); val navigate = rememberUpdatedState(onSelect)
    val getIndex = remember { { index.value } }; val selectIndex = remember { { i: Int -> navigate.value(i) } }
    Box(modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 22.dp, vertical = 10.dp)) {
        // Platform blur is available from Android 12. Keep older systems on a solid surface.
        if (Build.VERSION.SDK_INT < 31) {
            Row(Modifier.fillMaxWidth().height(64.dp).background(if (dark) Color(0xFF252A34) else Color.White, RoundedCornerShape(32.dp)).selectableGroup().testTag("liquid-navigation")) {
                listOf(AppIconSymbol.Home to "首页", AppIconSymbol.People to "人员库", AppIconSymbol.Sync to "同步", AppIconSymbol.Settings to "设置", AppIconSymbol.Info to "关于").forEachIndexed { i, (icon, label) ->
                    Box(Modifier.weight(1f).fillMaxHeight().padding(4.dp).clip(RoundedCornerShape(28.dp))
                        .background(if (selected == i) Blue.copy(alpha = .14f) else Color.Transparent)
                        .semantics { contentDescription = label; this.selected = selected == i; role = Role.Tab }
                        .testTag("nav-$i").clickable { onSelect(i) }, contentAlignment = Alignment.Center) {
                        AppIcon(icon, if (selected == i) Blue else if (dark) Color.White else Color.Black, Modifier.size(26.dp))
                    }
                }
            }
            return@Box
        }
        key(dark) {
            LiquidBottomTabs(selectedTabIndex = getIndex, onTabSelected = selectIndex, backdrop = backdrop, tabsCount = 5,
                isLightTheme = !dark, modifier = Modifier.fillMaxWidth().selectableGroup().testTag("liquid-navigation")) {
                listOf(AppIconSymbol.Home to "首页", AppIconSymbol.People to "人员库", AppIconSymbol.Sync to "同步", AppIconSymbol.Settings to "设置", AppIconSymbol.Info to "关于").forEachIndexed { i, (icon, label) ->
                    LiquidBottomTab(onClick = { onSelect(i) }, modifier = Modifier.semantics { contentDescription = label; this.selected = selected == i }.testTag("nav-$i")) {
                        AppIcon(icon, if (selected == i) Blue else if (dark) Color.White else Color.Black, Modifier.size(26.dp))
                    }
                }
            }
        }
    }
}
@Composable private fun Action(label: String, icon: AppIconSymbol, onClick: () -> Unit) {
    Box(Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).semantics { contentDescription = label; role = Role.Button }.clickable(onClick = onClick), contentAlignment = Alignment.Center) { AppIcon(icon, Blue) }
}
