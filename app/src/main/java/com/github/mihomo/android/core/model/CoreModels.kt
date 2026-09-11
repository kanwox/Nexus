package com.github.mihomo.android.core.model

import kotlinx.serialization.Serializable

@Serializable
data class Traffic(
    val up: Long = 0,
    val down: Long = 0
)

@Serializable
data class TunnelState(
    val mode: String = "rule",
    val hasProxy: Boolean = true
)

@Serializable
data class ProxyItem(
    val name: String,
    val type: String = "Shadowsocks",
    val delay: Int = -1,
    val udp: Boolean = true,
    val now: String? = null,
    val all: List<String> = emptyList()
)

@Serializable
data class ProxyGroup(
    val name: String,
    val type: String, // Selector, URLTest, Fallback, etc.
    val now: String,
    val all: List<ProxyItem> = emptyList()
)

enum class TunnelMode(val value: String, val displayName: String) {
    RULE("rule", "规则模式"),
    GLOBAL("global", "全局模式"),
    DIRECT("direct", "直连模式");

    companion object {
        fun fromString(str: String): TunnelMode {
            return entries.firstOrNull { it.value.equals(str, ignoreCase = true) } ?: RULE
        }
    }
}
