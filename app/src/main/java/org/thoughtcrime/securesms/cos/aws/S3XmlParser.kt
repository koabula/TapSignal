package org.thoughtcrime.securesms.cos

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.time.Instant

object S3XmlParser {
    fun parseListObjects(xml: String): List<CosFileInfo> {
        val factory = XmlPullParserFactory.newInstance()
        val parser = factory.newPullParser()
        parser.setInput(xml.reader())
        var event = parser.eventType
        val list = mutableListOf<CosFileInfo>()
        var key: String? = null
        var size: Long = 0
        var lastModified: Long = 0
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "Key" -> key = parser.nextText()
                    "Size" -> size = parser.nextText().toLong()
                    "LastModified" -> {
                        val iso = parser.nextText()
                        lastModified = try { Instant.parse(iso).toEpochMilli() } catch (e: Exception) { 0 }
                    }
                }
            } else if (event == XmlPullParser.END_TAG && parser.name == "Contents") {
                if (key != null) {
                    list.add(CosFileInfo(key!!, size, lastModified))
                }
                key = null
                size = 0
                lastModified = 0
            }
            event = parser.next()
        }
        return list
    }
} 