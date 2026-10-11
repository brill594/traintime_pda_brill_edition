// Copyright 2026 Traintime PDA authors.
// SPDX-License-Identifier: MPL-2.0

package io.github.benderblog.traintime_pda.ids

import com.google.common.truth.Truth.assertThat
import okhttp3.CookieJar
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class ForegroundNetworkingTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun backgroundSessionCannotReachTransport() {
        val session = TestSession(PersistentCookieJar(tempFolder.newFolder())) { false }
        assertBlocked { session.request() }
        assertThat(session.transportCalls).isEqualTo(0)
    }

    @Test
    fun leavingForegroundBlocksSubsequentRequestsIncludingNoCookieProbe() {
        var foreground = true
        val session = TestSession(PersistentCookieJar(tempFolder.newFolder())) { foreground }
        session.request()
        assertThat(session.transportCalls).isEqualTo(1)

        foreground = false
        assertBlocked { session.request() }
        assertBlocked { session.requestWithoutCookies() }
        assertThat(session.transportCalls).isEqualTo(1)
    }

    @Test
    fun cancelledAuthenticationCannotResumeWhenActivityReturns() {
        var foreground = true
        val session = TestSession(PersistentCookieJar(tempFolder.newFolder())) { foreground }
        session.request()
        foreground = false
        session.cancelRequests()
        foreground = true

        assertBlocked { session.request() }
        assertBlocked { session.requestWithoutCookies() }
        assertThat(session.transportCalls).isEqualTo(1)
    }

    private fun assertBlocked(action: () -> Unit) {
        try {
            action()
            throw AssertionError("Background work must not reach HTTP transport")
        } catch (error: IOException) {
            assertThat(error.message).contains("requires foreground")
        }
    }

    private class TestSession(
        cookieJar: PersistentCookieJar,
        isNetworkAllowed: () -> Boolean,
    ) : IdsSession(cookieJar, "account", "password", "fingerprint", isNetworkAllowed) {
        var transportCalls = 0
            private set

        private val transport = client.newBuilder().addInterceptor { chain ->
            transportCalls++
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("test".toResponseBody())
                .build()
        }.build()

        fun request() {
            transport.newCall(Request.Builder().url("https://example.test/").build())
                .execute().use { assertThat(it.code).isEqualTo(200) }
        }

        fun requestWithoutCookies() {
            transport.newBuilder().cookieJar(CookieJar.NO_COOKIES).build()
                .newCall(Request.Builder().url("https://example.test/").build())
                .execute().use { assertThat(it.code).isEqualTo(200) }
        }
    }
}
