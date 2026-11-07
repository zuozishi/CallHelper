package com.zuozishi.callhelper

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.catalog.components.LiquidBottomTab
import com.kyant.backdrop.catalog.components.LiquidBottomTabs
import com.zuozishi.callhelper.domain.DirectorySnapshot
import com.zuozishi.callhelper.domain.Personnel
import coil3.compose.AsyncImage
import kotlinx.coroutines.*
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Blue = Color(0xFF3482FF)

class MainActivity : ComponentActivity() {
    private val app get() = application as CallHelperApplication
    private var revision by mutableIntStateOf(0)
    private var busy by mutableStateOf(false)
    private var message by mutableStateOf<String?>(null)
    private val external = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { revision++ }
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { app.observeCallState(); revision++ }
    private val pickCsv = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) app.scope.launch {
            busy = true; message = null
            try {
                app.ready.await()
                val input = withContext(Dispatchers.IO) { contentResolver.openInputStream(uri) } ?: error("无法读取文件")
                val count = app.directory.importCsv(input)
                app.prefs.edit().putString("directorySource", "manual").apply()
                app.markUpdated(); app.startupError.value = null; message = "已导入 $count 人"
            } catch (e: Exception) { message = if (e is IllegalArgumentException) e.message else "导入失败，原人员库已保留" }
            finally { busy = false; revision++ }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        setContent {
            val snapshot by app.directory.snapshot.collectAsStateWithLifecycle()
            val error by app.startupError.collectAsStateWithLifecycle()
            var theme by remember { mutableIntStateOf(app.prefs.getInt("theme", 0)) }
            val dark = when (theme) { 1 -> false; 2 -> true; else -> isSystemInDarkTheme() }
            val colors = (if (dark) darkColorScheme() else lightColorScheme()).copy(
                primary = Blue,
                background = if (dark) Color(0xFF101216) else Color(0xFFF5F5F5),
                surface = if (dark) Color(0xFF252A34) else Color.White,
                surfaceContainer = if (dark) Color(0xFF252A34) else Color.White,
            )
            MiuixTheme(colors = colors) {
                AppUi(snapshot, busy, message ?: error, revision, theme, dark,
                    onImport = { pickCsv.launch(arrayOf("text/*", "application/octet-stream", "application/csv")) },
                    onTheme = { theme = it; app.prefs.edit().putInt("theme", it).apply() },
                    onPreview = { person ->
                        if (!Settings.canDrawOverlays(this)) openOverlayPermission()
                        else if (!app.overlay.show(person?.phone.orEmpty(), listOfNotNull(person))) Toast.makeText(this, "请先开启悬浮窗", Toast.LENGTH_SHORT).show()
                    },
                    requestRole = {
                        val manager = getSystemService(RoleManager::class.java)
                        if (manager.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING) && !manager.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)) external.launch(manager.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING))
                    },
                    requestOverlay = ::openOverlayPermission,
                    requestPhone = { permissions.launch(arrayOf(Manifest.permission.READ_PHONE_STATE)) },
                    requestContacts = { permissions.launch(arrayOf(Manifest.permission.READ_CONTACTS)) })
            }
        }
    }
    private fun openOverlayPermission() { external.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
    override fun onResume() { super.onResume(); revision++; app.observeCallState() }

    @Composable
    private fun AppUi(
        directory: DirectorySnapshot, busy: Boolean, message: String?, revision: Int, theme: Int, dark: Boolean,
        onImport: () -> Unit, onTheme: (Int) -> Unit, onPreview: (Personnel?) -> Unit,
        requestRole: () -> Unit, requestOverlay: () -> Unit, requestPhone: () -> Unit, requestContacts: () -> Unit,
    ) {
        var tab by rememberSaveable { mutableIntStateOf(0) }
        var query by rememberSaveable { mutableStateOf("") }
        var enabled by remember { mutableStateOf(app.prefs.getBoolean("enabled", true)) }
        var department by remember { mutableStateOf(app.prefs.getBoolean("department", true)) }
        var largeText by remember { mutableStateOf(app.prefs.getInt("textSize", 28) > 28) }
        var detail by remember { mutableStateOf<Personnel?>(null) }
        var results by remember { mutableStateOf(directory.people) }
        LaunchedEffect(query, directory) { delay(120); results = withContext(Dispatchers.Default) { directory.search(query) } }
        val roleGranted = remember(revision) { getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_CALL_SCREENING) }
        val overlayGranted = remember(revision) { Settings.canDrawOverlays(this) }
        val phoneGranted = remember(revision) { checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED }
        val contactsGranted = remember(revision) { checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED }
        val backdrop = rememberLayerBackdrop()
        val colors = MiuixTheme.colorScheme
        Box(Modifier.fillMaxSize().background(colors.background)) {
            LazyColumn(Modifier.fillMaxSize().layerBackdrop(backdrop).background(colors.background).statusBarsPadding().imePadding(),
                contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 20.dp, bottom = 126.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(listOf("来电知人", "人员库", "数据同步", "悬浮窗")[tab], fontSize = 32.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        if (tab == 0) Action("设置", "settings") { tab = 3 }
                    }
                }
                if (message != null) item { SurfaceCard { Text(message, fontSize = 13.sp, color = colors.onSurfaceVariantSummary, modifier = Modifier.testTag("import-status")) } }
                when (tab) {
                    0 -> {
                        item {
                            Column(Modifier.fillMaxWidth().background(if (dark) Color(0xFF22324C) else Color(0xFFEAF2FF), RoundedCornerShape(28.dp)).padding(22.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Glyph("shield", Blue, Modifier.size(42.dp))
                                    Action("预览悬浮窗", "preview") { onPreview(directory.people.firstOrNull()) }
                                }
                                Spacer(Modifier.height(24.dp))
                                Text(when { !enabled -> "已暂停"; directory.people.isEmpty() -> "请导入人员库"; !roleGranted || !overlayGranted -> "待完成设置"; else -> "识别已开启" }, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                        item { Stats(directory, onImport) }
                        if (!roleGranted || !overlayGranted || !phoneGranted || !contactsGranted) item {
                            PermissionCard(roleGranted, overlayGranted, phoneGranted, contactsGranted, requestRole, requestOverlay, requestPhone, requestContacts)
                        }
                        item { TextField(query, { query = it }, label = "姓名、号码、单位", singleLine = true, modifier = Modifier.fillMaxWidth().testTag("home-search"), trailingIcon = { Action("查询", "search") { tab = 1 } }) }
                        if (query.isNotBlank()) items(results.take(5), key = { it.id }) { person -> PersonCard(person) { detail = person } }
                    }
                    1 -> {
                        item { TextField(query, { query = it }, label = "姓名、号码、单位", singleLine = true, modifier = Modifier.fillMaxWidth().testTag("search-input"), leadingIcon = { Glyph("search", colors.onSurfaceVariantSummary) }) }
                        item { Text("${results.size} 人", fontSize = 13.sp, color = colors.onSurfaceVariantSummary, modifier = Modifier.testTag("result-count")) }
                        if (results.isEmpty()) item { SurfaceCard { Text("未找到相关人员", color = colors.onSurfaceVariantSummary) } }
                        items(results, key = { it.id }) { person -> PersonCard(person) { detail = person } }
                    }
                    2 -> {
                        item { Stats(directory, onImport) }
                        item {
                            SurfaceCard {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text(if (busy) "正在导入" else "本地 CSV", modifier = Modifier.weight(1f))
                                    if (!busy) Action("导入 CSV", "download", onImport) else Text("…")
                                }
                                val updated = app.prefs.getLong("updated", 0)
                                if (updated > 0) Text(SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(updated)), fontSize = 12.sp, color = colors.onSurfaceVariantSummary)
                            }
                        }
                        item { SurfaceCard { Text("API 待配置", color = colors.onSurfaceVariantSummary) } }
                        item { PermissionCard(roleGranted, overlayGranted, phoneGranted, contactsGranted, requestRole, requestOverlay, requestPhone, requestContacts) }
                    }
                    3 -> {
                        item {
                            val person = directory.people.firstOrNull()
                            SurfaceCard {
                                if (person != null) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                        Avatar(person, Modifier.size(56.dp))
                                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Text(person.name, fontSize = if (largeText) 32.sp else 28.sp, fontWeight = FontWeight.SemiBold)
                                            Text(person.post.ifBlank { "岗位未提供" }, color = Blue)
                                        }
                                    }
                                    Text(person.topOrgName.ifBlank { "单位未提供" }, fontSize = 15.sp)
                                    if (department) Text(person.fullOrgName, fontSize = 12.sp, color = colors.onSurfaceVariantSummary)
                                } else Text("尚未导入人员库")
                                Action("预览悬浮窗", "preview") { onPreview(person) }
                            }
                        }
                        item {
                            SurfaceCard {
                                Toggle("显示来电悬浮窗", enabled) { enabled = it; app.prefs.edit().putBoolean("enabled", it).apply(); if (!it) app.overlay.dismiss() }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                    listOf("system" to "跟随系统", "sun" to "浅色", "moon" to "深色").forEachIndexed { i, (icon, label) ->
                                        Box(Modifier.background(if (theme == i) colors.surfaceVariant else Color.Transparent, RoundedCornerShape(16.dp))) { Action(label, icon) { onTheme(i) } }
                                    }
                                }
                                Toggle("显示组织路径", department) { department = it; app.prefs.edit().putBoolean("department", it).apply() }
                                Toggle("大号姓名", largeText) { largeText = it; app.prefs.edit().putInt("textSize", if (it) 32 else 28).apply() }
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text("锁屏隐藏详情", modifier = Modifier.weight(1f)); Glyph("shield", Blue) }
                            }
                        }
                        item { PermissionCard(roleGranted, overlayGranted, phoneGranted, contactsGranted, requestRole, requestOverlay, requestPhone, requestContacts) }
                    }
                }
            }
            Navigation(tab, backdrop, dark, Modifier.align(Alignment.BottomCenter)) { tab = it }
        }
        detail?.let { person ->
            Dialog(onDismissRequest = { detail = null }) {
                SurfaceCard {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) { Avatar(person, Modifier.size(52.dp)); Text(person.name, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)); Action("关闭", "close") { detail = null } }
                    Text(person.post.ifBlank { "岗位未提供" }, color = Blue)
                    Text(person.phone)
                    Text(person.topOrgName.ifBlank { "单位未提供" })
                    Text(person.fullOrgName, fontSize = 13.sp, color = colors.onSurfaceVariantSummary)
                    Action("预览此人来电悬浮窗", "preview") { detail = null; onPreview(person) }
                }
            }
        }
    }
}

@Composable private fun SurfaceCard(content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content) }
}
@Composable private fun Stats(directory: DirectorySnapshot, onImport: () -> Unit) {
    SurfaceCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text("人员库", fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f)); Action("导入 CSV", "download", onImport) }
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
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(label, modifier = Modifier.weight(1f), fontSize = 14.sp); if (granted) Glyph("check", Color(0xFF22A974)) else Action("授权$label", "arrow", action) }
        }
    }
}
@Composable private fun Navigation(selected: Int, backdrop: Backdrop, dark: Boolean, modifier: Modifier, onSelect: (Int) -> Unit) {
    val index = rememberUpdatedState(selected); val navigate = rememberUpdatedState(onSelect)
    val getIndex = remember { { index.value } }; val selectIndex = remember { { i: Int -> navigate.value(i) } }
    Box(modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 22.dp, vertical = 10.dp)) {
        key(dark) {
            LiquidBottomTabs(selectedTabIndex = getIndex, onTabSelected = selectIndex, backdrop = backdrop, tabsCount = 4,
                isLightTheme = !dark, modifier = Modifier.fillMaxWidth().selectableGroup().testTag("liquid-navigation")) {
                listOf("home" to "首页", "people" to "人员库", "database" to "同步", "settings" to "设置").forEachIndexed { i, (icon, label) ->
                    LiquidBottomTab(onClick = { onSelect(i) }, modifier = Modifier.semantics { contentDescription = label; this.selected = selected == i }.testTag("nav-$i")) {
                        Glyph(icon, if (dark) Color.White else Color.Black, Modifier.size(26.dp))
                    }
                }
            }
        }
    }
}
@Composable private fun Action(label: String, icon: String, onClick: () -> Unit) {
    Box(Modifier.size(48.dp).semantics { contentDescription = label; role = Role.Button }.clickable(onClick = onClick), contentAlignment = Alignment.Center) { Glyph(icon, Blue) }
}
@Composable private fun Glyph(name: String, tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(24.dp)) {
        val sx = size.width / 24f; val sy = size.height / 24f
        fun line(vararg points: Float) { val p = Path(); p.moveTo(points[0]*sx, points[1]*sy); for(i in 2 until points.size step 2) p.lineTo(points[i]*sx, points[i+1]*sy); drawPath(p, tint, style = Stroke(1.8f*sx)) }
        fun circle(x: Float, y: Float, r: Float) { drawCircle(tint, r*sx, Offset(x*sx,y*sy), style=Stroke(1.8f*sx)) }
        when(name) {
            "home" -> { line(3f,11f,12f,3f,21f,11f,21f,21f,15f,21f,15f,14f,9f,14f,9f,21f,3f,21f,3f,11f) }
            "people" -> { circle(9f,8f,3f); line(3f,21f,3f,17f,6f,14f,12f,14f,15f,17f,15f,21f);line(17f,5f,20f,8f,17f,11f);line(18f,15f,21f,18f,21f,21f) }
            "search" -> { circle(10f,10f,6f);line(15f,15f,21f,21f) }
            "database" -> { drawOval(tint, Offset(4*sx,3*sy), androidx.compose.ui.geometry.Size(16*sx,6*sy), style=Stroke(1.8f*sx));line(4f,6f,4f,19f,8f,22f,16f,22f,20f,19f,20f,6f);line(4f,13f,9f,16f,15f,16f,20f,13f) }
            "shield" -> { line(12f,2f,4f,6f,4f,13f,7f,18f,12f,22f,17f,18f,20f,13f,20f,6f,12f,2f);line(8f,11f,11f,14f,17f,8f) }
            "preview" -> { line(3f,5f,21f,5f,21f,19f,3f,19f,3f,5f);line(10f,9f,15f,12f,10f,15f,10f,9f) }
            "download" -> { line(12f,3f,12f,16f,7f,11f);line(12f,16f,17f,11f);line(4f,16f,4f,21f,20f,21f,20f,16f) }
            "arrow" -> line(9f,5f,16f,12f,9f,19f)
            "close" -> { line(6f,6f,18f,18f);line(6f,18f,18f,6f) }
            "check" -> line(5f,12f,10f,17f,20f,6f)
            "sun" -> { circle(12f,12f,4f);listOf(0.0,45.0,90.0,135.0,180.0,225.0,270.0,315.0).forEach { val a=Math.toRadians(it); line((12+8*kotlin.math.cos(a)).toFloat(),(12+8*kotlin.math.sin(a)).toFloat(),(12+11*kotlin.math.cos(a)).toFloat(),(12+11*kotlin.math.sin(a)).toFloat()) } }
            "moon" -> { circle(12f,12f,8f);line(12f,4f,9f,10f,11f,15f,16f,16f,20f,12f) }
            "system" -> { line(3f,4f,21f,4f,21f,17f,3f,17f,3f,4f);line(12f,17f,12f,21f,7f,21f,17f,21f) }
            else -> { circle(12f,12f,4f);circle(12f,12f,9f);line(12f,1f,12f,4f);line(12f,20f,12f,23f);line(1f,12f,4f,12f);line(20f,12f,23f,12f) }
        }
    }
}
