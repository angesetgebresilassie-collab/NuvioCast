package com.nuvio.app.features.cast

/**
 * Pure text helpers for rewriting HLS playlists so the phone can (a) pick an audio rendition for
 * receivers that cannot switch audio themselves and (b) route requests that need custom headers
 * through the local server.
 */
internal data class HlsAudioRendition(
    val id: String,
    val groupId: String,
    val name: String,
    val language: String?,
    val isDefault: Boolean,
    val channels: String?,
)

internal object HlsRewriter {
    private val attrRegex = Regex("""([A-Z0-9-]+)=("([^"]*)"|[^,]*)""")
    private val uriAttrRegex = Regex("""URI="([^"]*)"""")

    fun isMaster(text: String): Boolean = text.contains("#EXT-X-STREAM-INF")

    fun parseAttributes(line: String): Map<String, String> {
        val body = line.substringAfter(':', "")
        return attrRegex.findAll(body).associate { match ->
            match.groupValues[1] to (match.groups[3]?.value ?: match.groupValues[2])
        }
    }

    fun audioRenditions(master: String): List<HlsAudioRendition> =
        master.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("#EXT-X-MEDIA:") }
            .mapNotNull { line ->
                val attrs = parseAttributes(line)
                if (attrs["TYPE"] != "AUDIO") return@mapNotNull null
                val group = attrs["GROUP-ID"].orEmpty()
                val name = attrs["NAME"] ?: attrs["LANGUAGE"] ?: "Audio"
                HlsAudioRendition(
                    id = "$group/$name",
                    groupId = group,
                    name = name,
                    language = attrs["LANGUAGE"],
                    isDefault = attrs["DEFAULT"] == "YES",
                    channels = attrs["CHANNELS"],
                )
            }
            .toList()

    /**
     * Collapses the renditions of every audio group down to the one called [selectedId]'s name
     * (so the choice applies across all groups) and marks it DEFAULT. URIs are passed through
     * [mapUri] (absolute URL, isPlaylist).
     */
    fun rewriteMaster(
        text: String,
        baseUrl: String,
        selectedId: String?,
        mapUri: (String, Boolean) -> String,
    ): String {
        val selectedName = selectedId?.substringAfter('/', selectedId)
        val renditions = audioRenditions(text)
        val groupsWithSelection = renditions
            .filter { selectedName != null && it.name == selectedName }
            .map { it.groupId }
            .toSet()

        val out = StringBuilder()
        var nextIsVariantUri = false
        for (raw in text.lineSequence()) {
            val line = raw.trimEnd()
            when {
                nextIsVariantUri && line.isNotBlank() && !line.startsWith("#") -> {
                    nextIsVariantUri = false
                    out.append(mapUri(resolve(baseUrl, line.trim()), true)).append('\n')
                }

                line.startsWith("#EXT-X-STREAM-INF") -> {
                    nextIsVariantUri = true
                    out.append(line).append('\n')
                }

                line.startsWith("#EXT-X-MEDIA:") -> {
                    val attrs = parseAttributes(line)
                    var current = line
                    if (attrs["TYPE"] == "AUDIO" && selectedName != null && attrs["GROUP-ID"] in groupsWithSelection) {
                        val name = attrs["NAME"] ?: attrs["LANGUAGE"] ?: "Audio"
                        if (name != selectedName) continue
                        current = setAttribute(setAttribute(current, "DEFAULT", "YES"), "AUTOSELECT", "YES")
                    }
                    out.append(rewriteUriAttribute(current, baseUrl, true, mapUri)).append('\n')
                }

                line.startsWith("#") && uriAttrRegex.containsMatchIn(line) -> {
                    val isPlaylist = line.startsWith("#EXT-X-I-FRAME-STREAM-INF")
                    out.append(rewriteUriAttribute(line, baseUrl, isPlaylist, mapUri)).append('\n')
                }

                else -> out.append(line).append('\n')
            }
        }
        return out.toString()
    }

    fun rewriteMedia(text: String, baseUrl: String, mapUri: (String, Boolean) -> String): String {
        val out = StringBuilder()
        for (raw in text.lineSequence()) {
            val line = raw.trimEnd()
            when {
                line.isBlank() -> out.append('\n')
                line.startsWith("#") ->
                    out.append(if (uriAttrRegex.containsMatchIn(line)) rewriteUriAttribute(line, baseUrl, false, mapUri) else line)
                        .append('\n')

                else -> out.append(mapUri(resolve(baseUrl, line.trim()), false)).append('\n')
            }
        }
        return out.toString()
    }

    private fun rewriteUriAttribute(
        line: String,
        baseUrl: String,
        isPlaylist: Boolean,
        mapUri: (String, Boolean) -> String,
    ): String = uriAttrRegex.replace(line) { m ->
        "URI=\"" + mapUri(resolve(baseUrl, m.groupValues[1]), isPlaylist) + "\""
    }

    private fun setAttribute(line: String, key: String, value: String): String {
        val regex = Regex("""$key=("[^"]*"|[^,]*)""")
        return if (regex.containsMatchIn(line)) {
            regex.replace(line) { "$key=$value" }
        } else {
            "$line,$key=$value"
        }
    }

    fun resolve(base: String, ref: String): String {
        if (ref.startsWith("http://") || ref.startsWith("https://")) return ref
        return runCatching { java.net.URI(base).resolve(ref).toString() }.getOrElse {
            if (ref.startsWith("/")) {
                val origin = base.substringBefore("://") + "://" + base.substringAfter("://").substringBefore('/')
                origin + ref
            } else {
                base.substringBeforeLast('/') + "/" + ref
            }
        }
    }
}

internal object SubtitleConverter {
    private val srtTimestamp = Regex("""(\d{2}:\d{2}:\d{2}),(\d{3})""")

    fun srtToVtt(srt: String): String {
        val normalized = srt.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        if (normalized.trimStart().startsWith("WEBVTT")) return normalized
        return "WEBVTT\n\n" + srtTimestamp.replace(normalized, "\$1.\$2")
    }
}
