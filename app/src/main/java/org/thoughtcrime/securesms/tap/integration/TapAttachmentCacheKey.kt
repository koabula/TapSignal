package org.thoughtcrime.securesms.tap.integration

object TapAttachmentCacheKey {

    fun build(rawPath: String?, attachmentId: String, extension: String = ".bin"): String {
        val normalized = normalize(rawPath, attachmentId)
        val hash = normalized.hashCode()
        return "${hash}_$attachmentId$extension"
    }

    fun normalize(rawPath: String?, attachmentId: String): String {
        if (rawPath.isNullOrBlank()) {
            return attachmentId
        }
        val withoutPrefix = if (rawPath.startsWith("TAP:")) rawPath.substring(4) else rawPath
        val withoutQuery = withoutPrefix.substringBefore('?')
        return if (withoutQuery.isNotBlank()) withoutQuery else attachmentId
    }
}
