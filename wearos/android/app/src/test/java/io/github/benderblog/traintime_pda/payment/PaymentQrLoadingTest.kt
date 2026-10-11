// Copyright 2026 Traintime PDA authors.
// SPDX-License-Identifier: MPL-2.0

package io.github.benderblog.traintime_pda.payment

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PaymentQrLoadingTest {
    private val cached = PaymentQrResult(byteArrayOf(1), true, 100L)
    private val fresh = PaymentQrResult(byteArrayOf(2), false, 200L)

    @Test
    fun openingCachedCodeDoesNotUseEitherRadioPath() = runTest {
        val result = loadPaymentQr(
            forceRefresh = false,
            readCache = { cached },
            requestFromPhone = { error("Cached opening must not contact phone") },
            requestDirectly = { error("Cached opening must not authenticate on watch") },
        )
        assertThat(result).isEqualTo(cached)
    }

    @Test
    fun phoneRefreshAvoidsWatchAuthenticationEvenWithCachedCode() = runTest {
        val result = loadPaymentQr(
            forceRefresh = true,
            readCache = { error("Refresh must request a fresh code first") },
            requestFromPhone = { fresh },
            requestDirectly = { error("Phone success must avoid watch networking") },
        )
        assertThat(result).isEqualTo(fresh)
    }

    @Test
    fun firstOpenWithoutCacheAlsoUsesPhoneBeforeWatch() = runTest {
        val result = loadPaymentQr(
            forceRefresh = false,
            readCache = { null },
            requestFromPhone = { fresh },
            requestDirectly = { error("Phone success must avoid watch networking") },
        )
        assertThat(result).isEqualTo(fresh)
    }

    @Test
    fun unavailablePhoneFallsBackImmediatelyToWatch() = runTest {
        val result = loadPaymentQr(
            forceRefresh = true,
            readCache = { null },
            requestFromPhone = { error("phone_app_required") },
            requestDirectly = { fresh },
        )
        assertThat(result).isEqualTo(fresh)
        assertThat(testScheduler.currentTime).isEqualTo(0L)
    }

    @Test
    fun phoneWaitIsBoundedAndCancelledBeforeWatchFallback() = runTest {
        var phoneCancelled = false
        val result = loadPaymentQr(
            forceRefresh = true,
            readCache = { null },
            requestFromPhone = {
                try {
                    awaitCancellation()
                } finally {
                    phoneCancelled = true
                }
            },
            requestDirectly = {
                assertThat(phoneCancelled).isTrue()
                fresh
            },
        )
        assertThat(result).isEqualTo(fresh)
        assertThat(testScheduler.currentTime).isEqualTo(10_000L)
    }

    @Test
    fun failedRefreshPreservesOfflineCode() = runTest {
        val result = loadPaymentQr(
            forceRefresh = true,
            readCache = { cached },
            requestFromPhone = { error("Phone disconnected") },
            requestDirectly = { error("Watch offline") },
        )
        assertThat(result).isEqualTo(cached)
    }

    @Test
    fun backgroundCancellationDoesNotStartWatchFallback() = runTest {
        val job = launch {
            loadPaymentQr(
                forceRefresh = true,
                readCache = { error("Cancellation must not be reported as cached success") },
                requestFromPhone = { awaitCancellation() },
                requestDirectly = { error("Leaving foreground must not start watch networking") },
            )
        }
        runCurrent()
        job.cancel()
        job.join()
        assertThat(job.isCancelled).isTrue()
        assertThat(testScheduler.currentTime).isEqualTo(0L)
    }

    @Test
    fun watchCancellationDoesNotReturnOfflineSuccess() = runTest {
        try {
            loadPaymentQr(
                forceRefresh = true,
                readCache = { error("Cancellation must not read cache") },
                requestFromPhone = { error("Phone disconnected") },
                requestDirectly = { throw CancellationException("Activity paused") },
            )
            throw AssertionError("Expected cancellation")
        } catch (_: CancellationException) {
            // The caller must observe cancellation rather than a usable result.
        }
    }
}
