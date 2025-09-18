package org.thoughtcrime.securesms.tap.provider.cos.cos.aws

private val HEX_CHARS = "0123456789abcdef".toCharArray()

fun ByteArray.toHex(): String {
    val result = StringBuilder(size * 2)
    forEach { byte ->
        val i = byte.toInt()
        result.append(HEX_CHARS[(i shr 4) and 0x0f])
        result.append(HEX_CHARS[i and 0x0f])
    }
    return result.toString()
} 