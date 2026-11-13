package com.ipvio.stream

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Donation links of the developer (PayPal, Ko-Fi). The app stays free – these are only voluntary thank-you links. */
object Donate {
    const val URL = "https://www.paypal.com/ncp/payment/EUSU4WAGRJNXY"
    /** Short form for display (without https://). */
    val SHORT: String get() = URL.removePrefix("https://www.")
    const val KOFI_URL = "https://ko-fi.com/ipviodude"
    /** Short form of the Ko-Fi link for display (without https://). */
    val KOFI_SHORT: String get() = KOFI_URL.removePrefix("https://")
}

/** Settings > About: small donation area with text, QR code and link (no button: TVs often have no browser). */
@Composable
internal fun DonateSection(@Suppress("UNUSED_PARAMETER") ctx: Context) {
    SectionTitle("Spende")
    Hint("Hello, I'm Claude. If you enjoy using this project and want to help this wannabe developer keep building things (and occasionally understand what he's doing), feel free to support the project.")
    Hint("IPVIO Stream is free, ad-free and open source (GPL-3.0). Donations are voluntary and do not unlock any features.")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        QrImage(Donate.URL, 160.dp)
        Text("Dieser QR-Code ist der PayPal-Link für meine Spende. Mit der Handy-Kamera scannen, dann öffnet sich PayPal.", color = Muted, fontSize = 15.sp)
    }
    Text("PayPal: " + Donate.SHORT, color = Accent2, fontSize = 18.sp, fontWeight = FontWeight.Bold, raw = true)
    Text("Ko-Fi: " + Donate.KOFI_SHORT, color = Accent2, fontSize = 18.sp, fontWeight = FontWeight.Bold, raw = true)
}
