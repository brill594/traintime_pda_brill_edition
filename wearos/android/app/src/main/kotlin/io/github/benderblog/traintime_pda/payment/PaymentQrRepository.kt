// Copyright 2026 Traintime PDA authors.
// SPDX-License-Identifier: MPL-2.0

package io.github.benderblog.traintime_pda.payment

import io.github.benderblog.traintime_pda.data.WearCacheStore
import io.github.benderblog.traintime_pda.data.WearPreferences
import io.github.benderblog.traintime_pda.ids.PersistentCookieJar
import io.github.benderblog.traintime_pda.ids.SchoolCardSession
import io.github.benderblog.traintime_pda.ids.WearIDSReAuthClient
import io.github.benderblog.traintime_pda.sync.WearCompanionClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URI
import java.util.concurrent.atomic.AtomicReference

data class PaymentQrResult(
    val bytes: ByteArray,
    val fromCache: Boolean,
    val fetchedAtEpochMs: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PaymentQrResult) return false
        return fromCache == other.fromCache &&
            fetchedAtEpochMs == other.fetchedAtEpochMs &&
            bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + fromCache.hashCode()
        result = 31 * result + fetchedAtEpochMs.hashCode()
        return result
    }
}

/**
 * Payment QR: local cache, then phone Data Layer, then watch IDS auth.
 */
class PaymentQrRepository(
    private val preferences: WearPreferences,
    private val cache: WearCacheStore,
    private val companionClient: WearCompanionClient,
) {
    private val activeSession = AtomicReference<SchoolCardSession?>(null)

    fun cancelActiveRequests() {
        activeSession.getAndSet(null)?.cancelRequests()
    }

    suspend fun load(
        forceRefresh: Boolean = false,
        reAuthHandler: (suspend (WearIDSReAuthClient) -> URI)? = null,
    ): PaymentQrResult = withContext(Dispatchers.IO) {
        loadPaymentQr(
            forceRefresh = forceRefresh,
            readCache = {
                cache.readPaymentQr()?.let { (bytes, fetchedAt) ->
                    PaymentQrResult(bytes, fromCache = true, fetchedAtEpochMs = fetchedAt)
                }
            },
            requestFromPhone = { requestFromPhone() },
            requestDirectly = { requestDirectly(reAuthHandler) },
        )
    }

    private suspend fun requestFromPhone(): PaymentQrResult {
        val response = companionClient.requestPaymentQrFromPhone()
        if (!response.ok || response.pngBytes == null || response.fetchedAtEpochMs == null) {
            throw IllegalStateException(response.error ?: "phone_payment_request_failed")
        }
        cache.writePaymentQr(response.pngBytes, response.fetchedAtEpochMs)
        return PaymentQrResult(
            bytes = response.pngBytes,
            fromCache = false,
            fetchedAtEpochMs = response.fetchedAtEpochMs,
        )
    }

    private suspend fun requestDirectly(
        reAuthHandler: (suspend (WearIDSReAuthClient) -> URI)?,
    ): PaymentQrResult = withContext(Dispatchers.IO) {
        val account = preferences.idsAccount
        val password = preferences.idsPassword
        if (account.isEmpty() || password.isEmpty()) {
            throw IllegalStateException("missing_ids_credentials")
        }
        val session = SchoolCardSession(
            cookieJar = PersistentCookieJar(cache.cookieDir),
            username = account,
            password = password,
            browserFingerprint = preferences.getOrCreateIdsBrowserFingerprint(),
            storedOpenId = preferences.schoolCardOpenId,
            storedOpenIdFetchedAt = preferences.schoolCardOpenIdFetchedAt,
            isNetworkAllowed = companionClient::isForeground,
            onOpenIdChanged = { value, fetchedAt ->
                if (value == null || fetchedAt == null) {
                    preferences.clearSchoolCardOpenId()
                } else {
                    preferences.storeSchoolCardOpenId(value, fetchedAt)
                }
            },
        )
        activeSession.set(session)
        try {
            currentCoroutineContext().ensureActive()
            session.authenticateWithStoredCredentials(reAuthHandler = reAuthHandler)
            currentCoroutineContext().ensureActive()
            val bytes = session.getQRCode()
            currentCoroutineContext().ensureActive()
            val fetchedAt = System.currentTimeMillis()
            cache.writePaymentQr(bytes, fetchedAt)
            PaymentQrResult(
                bytes = bytes,
                fromCache = false,
                fetchedAtEpochMs = fetchedAt,
            )
        } finally {
            activeSession.compareAndSet(session, null)
        }
    }
}

internal suspend fun loadPaymentQr(
    forceRefresh: Boolean,
    readCache: () -> PaymentQrResult?,
    requestFromPhone: suspend () -> PaymentQrResult,
    requestDirectly: suspend () -> PaymentQrResult,
): PaymentQrResult {
    currentCoroutineContext().ensureActive()
    if (!forceRefresh) readCache()?.let { return it }
    try {
        // Bound phone discovery and response together; never hold up offline use indefinitely.
        withTimeoutOrNull(10_000L) { requestFromPhone() }?.let { return it }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        currentCoroutineContext().ensureActive()
    }
    currentCoroutineContext().ensureActive()
    return try {
        requestDirectly()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (direct: Exception) {
        currentCoroutineContext().ensureActive()
        readCache() ?: throw direct
    }
}
