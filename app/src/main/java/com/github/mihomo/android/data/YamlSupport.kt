package com.github.mihomo.android.data

import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.nodes.NodeId
import org.yaml.snakeyaml.nodes.Tag
import org.yaml.snakeyaml.representer.Representer
import org.yaml.snakeyaml.resolver.Resolver

/**
 * One place that defines how the app parses and re-serializes user/subscription YAML.
 *
 * Two SnakeYAML defaults used to be wrong for this app:
 *  - the loader rejected documents above 3 MB (`codePointLimit`), which silently pushed
 *    large subscriptions onto the lossy text fallback and dropped every per-profile
 *    override (prepend rules / hosts / plaintext-DNS reject);
 *  - the YAML 1.1 resolver turned bare `on/off/yes/no` scalars into booleans, so a proxy
 *    named `on` or a password `yes` came back out of the dump as `true`.
 *
 * [create] therefore always uses [SafeConstructor] (no global tags), a 100 MB code point
 * limit and a YAML 1.2-style boolean resolver. Callers must not share a [Yaml] instance
 * between threads: SnakeYAML's parser carries per-load state, so create one per operation.
 */
internal object YamlSupport {
    private const val CODE_POINT_LIMIT = 100 * 1024 * 1024

    /** Block style + two-space indent; wide lines so long rules/URLs are never folded. */
    fun dumperOptions(): DumperOptions = DumperOptions().apply {
        defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
        isPrettyFlow = true
        indent = 2
        // SnakeYAML wraps plain scalars at 80 columns by default. YAML folds the wrapped
        // line back into a single space on reload, which corrupts long DOMAIN-REGEX rules,
        // URLs and passwords. Keep every value on one physical line.
        width = 100_000
    }

    fun create(): Yaml {
        val loaderOptions = LoaderOptions().apply {
            codePointLimit = CODE_POINT_LIMIT
            // Configs that use YAML anchors for shared proxy templates can legitimately
            // exceed SnakeYAML's default alias cap of 50.
            maxAliasesForCollections = 1_000
        }
        val dumperOptions = dumperOptions()
        return Yaml(
            SafeConstructor(loaderOptions),
            Representer(dumperOptions),
            dumperOptions,
            loaderOptions,
            Yaml12Resolver()
        )
    }

    /** Keeps YAML 1.1's `yes/no/on/off` as plain strings; only `true`/`false` are booleans. */
    private class Yaml12Resolver : Resolver() {
        override fun resolve(kind: NodeId, value: String?, implicit: Boolean): Tag {
            val tag = super.resolve(kind, value, implicit)
            if (implicit && tag == Tag.BOOL && value != null) {
                val normalized = value.lowercase()
                if (normalized != "true" && normalized != "false") return Tag.STR
            }
            return tag
        }
    }
}
