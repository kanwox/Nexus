package com.github.mihomo.android.data

import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * Shared OkHttpClient root so all HTTP / proxy / loopback / WebSocket clients share one
 * [okhttp3.Dispatcher] thread pool and [okhttp3.ConnectionPool] instead of spawning
 * independent executors per repository.
 */
internal object NetworkClients {
    val baseClient: OkHttpClient by lazy {
        OkHttpClient.Builder().build()
    }

    fun directClient(
        connectTimeoutSec: Long,
        readTimeoutSec: Long,
        callTimeoutSec: Long,
        allowPrivateSupplier: () -> Boolean,
    ): OkHttpClient = baseClient.newBuilder()
        .connectTimeout(connectTimeoutSec, TimeUnit.SECONDS)
        .readTimeout(readTimeoutSec, TimeUnit.SECONDS)
        .callTimeout(callTimeoutSec, TimeUnit.SECONDS)
        .withSsrfProtection(allowPrivateSupplier = allowPrivateSupplier, validateDns = true)
        .build()

    fun localProxyClient(
        port: Int,
        connectTimeoutSec: Long,
        readTimeoutSec: Long,
        callTimeoutSec: Long,
        allowPrivateSupplier: () -> Boolean,
    ): OkHttpClient = baseClient.newBuilder()
        .connectTimeout(connectTimeoutSec, TimeUnit.SECONDS)
        .readTimeout(readTimeoutSec, TimeUnit.SECONDS)
        .callTimeout(callTimeoutSec, TimeUnit.SECONDS)
        .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port)))
        .withSsrfProtection(allowPrivateSupplier = allowPrivateSupplier, validateDns = false)
        .build()

    fun loopbackClient(
        connectTimeoutMs: Long,
        readTimeoutMs: Long,
        callTimeoutMs: Long,
    ): OkHttpClient = baseClient.newBuilder()
        .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
        .callTimeout(callTimeoutMs, TimeUnit.MILLISECONDS)
        .build()

    fun webSocketClient(): OkHttpClient = baseClient.newBuilder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
}
