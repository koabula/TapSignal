package org.thoughtcrime.securesms.tap.provider.cos.cos.aws

import org.thoughtcrime.securesms.tap.provider.cos.cos.CosAccessToken
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.time.Instant

/**
 * 解析 AWS STS GetSessionToken 响应 XML，提取临时凭证和过期时间。
 */
object StsXmlParser {
    fun parseSessionToken(xml: String): CosAccessToken {
        val factory = XmlPullParserFactory.newInstance()
        val parser = factory.newPullParser()
        parser.setInput(xml.reader())

        var event = parser.eventType
        var accessKeyId: String? = null
        var secretAccessKey: String? = null
        var sessionToken: String? = null
        var expirationMillis: Long = 0

        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "AccessKeyId" -> accessKeyId = parser.nextText()
                    "SecretAccessKey" -> secretAccessKey = parser.nextText()
                    "SessionToken" -> sessionToken = parser.nextText()
                    "Expiration" -> {
                        val iso = parser.nextText()
                        // ISO8601 to millis
                        expirationMillis = Instant.parse(iso).toEpochMilli()
                    }
                }
            }
            event = parser.next()
        }

        if (accessKeyId.isNullOrEmpty() || secretAccessKey.isNullOrEmpty()) {
            throw IllegalStateException("Invalid STS XML: missing credentials")
        }

        return CosAccessToken(
            accessKeyId = accessKeyId!!,
            secretAccessKey = secretAccessKey!!,
            sessionToken = sessionToken,
            expireTime = expirationMillis
        )
    }
} 