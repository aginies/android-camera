package com.androidcam.prusa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QrTokenParserTest {
    private val token = "ng4MnFsThXAMvqwHwJEX"

    @Test
    fun bareToken() {
        assertEquals(token, QrTokenParser.extractToken(token))
        assertEquals(token, QrTokenParser.extractToken("  $token  \n"))
    }

    @Test
    fun urlQueryParam() {
        assertEquals(
            token,
            QrTokenParser.extractToken("https://connect.prusa3d.com/camera?token=$token"),
        )
        assertEquals(
            token,
            QrTokenParser.extractToken("https://example.com/x?a=1&token=$token&b=2"),
        )
    }

    @Test
    fun urlPathSegment() {
        assertEquals(
            token,
            QrTokenParser.extractToken("https://connect.prusa3d.com/camera/$token"),
        )
    }

    @Test
    fun jsonTokenField() {
        assertEquals(
            token,
            QrTokenParser.extractToken("""{"camera":"x","token":"$token","qr":true}"""),
        )
    }

    @Test
    fun embeddedFallback() {
        assertEquals(token, QrTokenParser.extractToken("prusa:$token:extra"))
    }

    @Test
    fun noToken() {
        assertNull(QrTokenParser.extractToken(""))
        assertNull(QrTokenParser.extractToken("   "))
        assertNull(QrTokenParser.extractToken("https://example.com/no-token-here"))
        // 19 chars — too short.
        assertNull(QrTokenParser.extractToken("ng4MnFsThXAMvqwHwJE"))
    }
}
