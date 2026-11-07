package com.zuozishi.callhelper.service

import android.telecom.Call
import android.telecom.CallScreeningService
import com.zuozishi.callhelper.CallHelperApplication
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Identification only: never replaces the dialer or blocks / silences a call. */
class CallIdentificationService : CallScreeningService() {
    override fun onScreenCall(details: Call.Details) {
        if (details.callDirection != Call.Details.DIRECTION_INCOMING) return
        respondToCall(details, CallResponse.Builder().setDisallowCall(false).setRejectCall(false).setSilenceCall(false).build())
        val app = application as CallHelperApplication
        if (!app.prefs.getBoolean("enabled", true)) return
        val epoch = app.callEpoch
        val number = details.handle?.schemeSpecificPart.orEmpty()
        app.scope.launch {
            withTimeoutOrNull(2500) { app.ready.await() } ?: return@launch
            if (epoch == app.callEpoch) app.overlay.show(number, app.directory.snapshot.value.lookup(number), isCall = true)
        }
    }
}
