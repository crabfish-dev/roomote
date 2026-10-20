package com.elvi.tvremote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TvAddressTest {
    @Test fun normalizesLocalAddresses() {
        assertEquals("192.168.1.20", TvAddress.normalize(" 192.168.1.20 "))
        assertEquals("living-room.local", TvAddress.normalize("Living-Room.local"))
        assertEquals("fe80::1234%wlan0", TvAddress.normalize("[fe80::1234%wlan0]"))
    }

    @Test fun rejectsUrlsPortsAndMalformedAddresses() {
        listOf("", "https://tv.local", "tv.local:6466", "192.168.1.300", "1.2.3", "-tv.local",
            "tv..local", "tv/path", "tv local", "me@tv.local", "tv;echo bad", "bad::no",
            "1:2:3:4:5:6:7:8:9", "::a::b", "fe80::1%bad%zone").forEach { input ->
            assertThrows(input, IllegalArgumentException::class.java) { TvAddress.normalize(input) }
        }
    }
}
