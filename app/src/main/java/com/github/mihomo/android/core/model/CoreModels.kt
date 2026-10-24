package com.github.mihomo.android.core.model

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
