package com.ipvio.stream

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * One shared HTTP client for the whole app. Derived clients (other timeouts) share
 * the connection pool and threads instead of each area creating its own – saves memory on weak devices.
 */
object Net {
    val base: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        // Backup servers: if an Xtream server fails, requests go to one of the backup servers entered for it
        .addInterceptor(HostFailover.Interceptor)
        .build()

    fun withTimeouts(connectSec: Long, readSec: Long): OkHttpClient =
        base.newBuilder().connectTimeout(connectSec, TimeUnit.SECONDS).readTimeout(readSec, TimeUnit.SECONDS).build()
}
