package com.github.kr328.clash.core.model

import kotlinx.serialization.Serializable

typealias Traffic = Long

@Serializable
data class Proxy(
    val name: String,
    val title: String = name,
    val subtitle: String = "",
    val type: String = "Shadowsocks",
    val delay: Int = -1,
    var isGroup: Boolean = false,
)

@Serializable
data class ProxyGroup(
    val type: String,
    val proxies: List<Proxy> = emptyList(),
    val now: String = "",
    val hidden: Boolean = false,
)

enum class ProxySort {
    Default, Title, Delay
}

@Serializable
data class TunnelState(
    val mode: String = "rule",
)
