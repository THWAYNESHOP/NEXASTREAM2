package com.nexastream.app.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class XmlTvParserTest {
    @Test
    fun parsesXmlTvTimezoneOffsets() {
        assertEquals(
            Instant.parse("2026-08-16T17:30:00Z").toEpochMilli(),
            XmlTvParser.parseXmlTvTime("20260816203000 +0300"),
        )
        assertEquals(
            Instant.parse("2026-08-16T22:30:00Z").toEpochMilli(),
            XmlTvParser.parseXmlTvTime("20260816173000 -0500"),
        )
    }

    @Test
    fun rejectsIncompleteDates() {
        assertNull(XmlTvParser.parseXmlTvTime("20260816"))
        assertNull(XmlTvParser.parseXmlTvTime(null))
    }
}
