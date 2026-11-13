package com.ipvio.stream

import androidx.media3.common.C
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

/**
 * Retry rules for loading streams. IPTV servers often drop a connection for a moment (overloaded, connection switch,
 * Wi-Fi hiccup). The player's default of 3 attempts (one after the other, 0/1/2 s) then gives up too early and the
 * viewer sees a stall plus a restart; here it tries up to 6 times with a growing pause (at most 5 s) before it reports an error.
 *
 * No retries where they cannot help (login, subscription or address wrong, certificate does not match): the app's own
 * error handling then switches to another source or shows the message at once.
 */
class IptvLoadErrorPolicy : DefaultLoadErrorHandlingPolicy(RETRIES) {
    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val e = loadErrorInfo.exception
        if (e is HttpDataSource.InvalidResponseCodeException && isFinalStatus(e.responseCode)) return C.TIME_UNSET
        if (TlsFallback.isHostMismatch(e)) return C.TIME_UNSET
        return super.getRetryDelayMsFor(loadErrorInfo)
    }

    companion object {
        const val RETRIES = IptvRetryRules.RETRIES
        fun isFinalStatus(code: Int): Boolean = IptvRetryRules.isFinalStatus(code)
    }
}
