package com.wanderwildwood.shoka

import com.wanderwildwood.shoka.ui.FilesPicker
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenTest {

    @Test fun onlyAudioReadersReleaseCertificateIsTrusted() {
        val release = Listen.CERT_SHA256
        val other = "00".repeat(32)
        assertTrue(Listen.trusted(listOf(release), debuggable = false))
        // An app that took Audio Reader's name with another key, or none we could read.
        assertFalse(Listen.trusted(listOf(other), debuggable = false))
        assertFalse(Listen.trusted(emptyList(), debuggable = false))
        // Signed with Audio Reader's key and another as well.
        assertFalse(Listen.trusted(listOf(release, other), debuggable = false))
        // Files' certificate is not Audio Reader's.
        assertFalse(Listen.trusted(listOf(FilesPicker.CERT_SHA256), debuggable = false))
    }

    @Test fun debugKeysOnlyInADebugBuild() {
        val debug = FilesPicker.DEBUG_CERTS_SHA256.first()
        assertFalse(Listen.trusted(listOf(debug), debuggable = false))
        assertTrue(Listen.trusted(listOf(debug), debuggable = true))
        assertTrue(Listen.trusted(listOf(Listen.CERT_SHA256), debuggable = true))
    }
}
