package com.zuozishi.callhelper.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.zuozishi.callhelper.R

/** Shared 24dp, round-cap vector symbols. Action owners supply accessible names. */
internal enum class AppIconSymbol(@DrawableRes val resource: Int) {
    Scan(R.drawable.ic_scan),
    QrCode(R.drawable.ic_qr),
    Save(R.drawable.ic_save),
    Home(R.drawable.ic_home),
    People(R.drawable.ic_people),
    Search(R.drawable.ic_search),
    Shield(R.drawable.ic_shield),
    Preview(R.drawable.ic_preview),
    Sync(R.drawable.ic_sync),
    Arrow(R.drawable.ic_arrow),
    Back(R.drawable.ic_back),
    Phone(R.drawable.ic_phone),
    Folder(R.drawable.ic_folder),
    Close(R.drawable.ic_close),
    Check(R.drawable.ic_check),
    Sun(R.drawable.ic_sun),
    Moon(R.drawable.ic_moon),
    System(R.drawable.ic_system),
    Settings(R.drawable.ic_settings),
}

@Composable
internal fun AppIcon(symbol: AppIconSymbol, tint: Color, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(symbol.resource),
        contentDescription = null,
        colorFilter = ColorFilter.tint(tint),
        modifier = modifier.size(24.dp),
    )
}
