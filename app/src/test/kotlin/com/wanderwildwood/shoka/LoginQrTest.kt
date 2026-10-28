package com.wanderwildwood.shoka

import com.wanderwildwood.shoka.cloud.Account
import com.wanderwildwood.shoka.cloud.LoginFlow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LoginQrTest {

    @Test
    fun readsNextcloudsAppPasswordCode() {
        assertEquals(
            Account("https://cloud.example", "reader", "AbCdE-fGhIj-KlMnO-pQrSt-UvWxY"),
            LoginFlow.fromQr("nc://login/user:reader&password:AbCdE-fGhIj-KlMnO-pQrSt-UvWxY&server:https://cloud.example"),
        )
    }

    @Test
    fun keepsAServerPathAndAddsHttps() {
        assertEquals(
            Account("https://example.org/nextcloud", "a b", "pw"),
            LoginFlow.fromQr("nc://login/user:a b&password:pw&server:example.org/nextcloud"),
        )
    }

    @Test
    fun turnsAwayAnythingElse() {
        assertNull(LoginFlow.fromQr("https://cloud.example"))
        assertNull(LoginFlow.fromQr("9780441013593"))
        assertNull(LoginFlow.fromQr("nc://login/user:reader&server:https://cloud.example"))
    }
}
