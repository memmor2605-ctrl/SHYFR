package com.shyfr.app.api

import org.junit.Assert.*
import org.junit.Test

class ApiConfigTest {
    @Test fun httpsAccepted_trailingSlashTrimmed() = assertEquals("https://api.example.test", ApiConfig.parse("https://api.example.test/").baseUrl)
    @Test fun subpathAllowed() = assertEquals("https://h.test/shyfr", ApiConfig.parse("https://h.test/shyfr/").baseUrl)
    @Test fun httpRejectedByDefault() { assertThrows(ApiConfig.Invalid::class.java) { ApiConfig.parse("http://api.example.test") } }
    @Test fun httpRejectedForRemoteHostEvenWhenLoopbackAllowed() { assertThrows(ApiConfig.Invalid::class.java) { ApiConfig.parse("http://api.example.test", true) } }
    @Test fun httpAllowedOnlyForLoopbackWhenExplicit() {
        for (h in listOf("localhost", "127.0.0.1", "10.0.2.2")) assertEquals("http://$h:8080", ApiConfig.parse("http://$h:8080", true).baseUrl)
        assertThrows(ApiConfig.Invalid::class.java) { ApiConfig.parse("http://localhost:8080", false) }
    }
    @Test fun badInputsRejected() {
        for (s in listOf("", "  ", "ftp://x.test", "https://", "https://u:p@x.test", "https://x.test?a=1", "https://x.test#f", "not a url", "javascript:alert(1)"))
            assertThrows(s, ApiConfig.Invalid::class.java) { ApiConfig.parse(s) }
    }
    @Test fun urlJoinsPath() = assertEquals("https://a.test/v1/x", ApiConfig.parse("https://a.test").url("/v1/x"))
}
