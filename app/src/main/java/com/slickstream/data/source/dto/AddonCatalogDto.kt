package com.slickstream.data.source.dto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * One entry of the community addon catalog at https://stremio-addons.com/catalog.json — a JSON array
 * of installed-addon descriptors. We only need the transport (manifest) URL and a few manifest fields
 * to decide whether an addon is a usable, no-config STREAMING provider before we health-check it.
 */
@Serializable
data class AddonCatalogEntry(
    val transportUrl: String? = null,
    val manifest: AddonManifestDto? = null,
)

@Serializable
data class AddonManifestDto(
    val id: String? = null,
    val name: String? = null,
    /** Either a list of resource NAME strings (["stream","catalog"]) OR objects ({name,types,…}) —
     *  hence JsonElement; we just check whether any entry is the "stream" resource. */
    val resources: List<JsonElement> = emptyList(),
    val types: List<String> = emptyList(),
    val behaviorHints: AddonBehaviorHintsDto? = null,
)

@Serializable
data class AddonBehaviorHintsDto(
    val configurable: Boolean? = null,
    /** When true the addon needs setup (API key / debrid / a /configure step) — we skip those: they
     *  can't be used automatically and the user explicitly doesn't want paid/manual config. */
    val configurationRequired: Boolean? = null,
)
