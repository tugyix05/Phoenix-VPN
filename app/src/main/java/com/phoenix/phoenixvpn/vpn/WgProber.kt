package com.phoenix.phoenixvpn.vpn

import android.os.SystemClock
import android.util.Base64
import android.util.Log
import com.phoenix.phoenixvpn.network.EndpointInfo
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.bouncycastle.crypto.digests.Blake2sDigest
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters

/**
 * Raw-UDP WireGuard handshake prober (v1.72, parallel-walk primitive).
 *
 * Sends a valid Noise_IKpsk2 handshake initiation (148 bytes) on a plain
 * [DatagramSocket] and classifies the reply by SHAPE only: a 92-byte
 * type-2 packet IS a handshake response (the responder answers with its
 * OWN session index, so the receiver field is never matched — same rule
 * as the Scanner engine). A type-3 cookie reply means the endpoint is
 * alive but loaded; it is noted, not counted. Anything else is ignored.
 *
 * Why a prober exists at all: the walk's old attempt primitive is a full
 * GoBackend tunnel start (VpnService, one tunnel at a time, backend
 * mutex + DOWN/UP settle) — parallel tunnel starts would serialize and
 * churn. The parallel part of the walk is therefore raw-UDP probes
 * only, and exactly ONE tunnel is ever started (for the winner).
 *
 * KEY RULE (field-proven, 2026-11-17): the initiation uses the FIXED
 * dummy static private key — 32 zero bytes, exactly like the Scanner
 * engine's DUMMY_STATIC_PRIVATE, which lands on MPT. Fresh random
 * static keys NEVER landed on this phone (wg_probe.py 0/45+ across
 * Nov 3 + Nov 17, while the Scanner/Phoenix landed on the same
 * endpoints within minutes). The ephemeral key is fresh random per
 * initiation, as in the engine. The real account private key is never
 * needed and never used here.
 *
 * Construction is a direct port of ~/workspace/termux-wg-probe/wg_probe.py
 * (verified line-by-line against wireguard.com/protocol, First Message):
 * Noise_IKpsk2_25519_ChaChaPoly_BLAKE2s, MAC1 required, MAC2 zeros OK.
 * Crypto via BouncyCastle lightweight API (no JCA provider, minSdk-safe).
 */
class WgProber {

    /**
     * One probe round: fire a fresh-socket initiation at EVERY candidate
     * in parallel (fresh socket = fresh flow/draw per endpoint) and
     * collect responders in ARRIVAL order. Waits up to [timeoutMs] for
     * the first response, then [graceMs] more for additional responders
     * (the walk tries them in this order if the winner's tunnel start
     * fails). Returns empty when nothing responded. Never throws for
     * per-endpoint failures — a socket that fails to bind just skips
     * that endpoint for the round. Cancellation closes every live
     * socket at once, so a blocked receive aborts promptly.
     */
    suspend fun probeRound(
        candidates: List<EndpointInfo>,
        timeoutMs: Long,
        graceMs: Long
    ): List<EndpointInfo> {
        if (candidates.isEmpty()) return emptyList()
        val tracker = SocketTracker()
        val found = Channel<EndpointInfo>(Channel.UNLIMITED)
        return try {
            coroutineScope {
                val jobs = candidates.map { e ->
                    async(Dispatchers.IO) {
                        try {
                            probeOne(e, timeoutMs, tracker)?.let { found.trySend(it) }
                        } catch (_: Exception) {
                            // Per-endpoint failure: skip for this round.
                        }
                    }
                }
                val responders = mutableListOf<EndpointInfo>()
                val roundEnd = SystemClock.elapsedRealtime() + timeoutMs
                var graceEnd = -1L
                try {
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        if (jobs.all { it.isCompleted }) {
                            // Everything finished: drain stragglers, stop.
                            while (true) {
                                val r = found.tryReceive().getOrNull() ?: break
                                responders += r
                                if (graceEnd < 0) {
                                    graceEnd = SystemClock.elapsedRealtime() + graceMs
                                }
                            }
                            break
                        }
                        val limit = if (graceEnd >= 0) graceEnd else roundEnd
                        val wait = limit - SystemClock.elapsedRealtime()
                        if (wait <= 0) break
                        val next = withTimeoutOrNull(wait) { found.receive() } ?: break
                        responders += next
                        if (graceEnd < 0) {
                            graceEnd = SystemClock.elapsedRealtime() + graceMs
                        }
                        if (responders.size == candidates.size) break
                    }
                } finally {
                    jobs.forEach { it.cancel() }
                    tracker.closeAll()
                }
                Log.i(
                    TAG,
                    "probe round: ${responders.size}/${candidates.size} responders " +
                        responders.joinToString(",") { it.id }
                )
                responders
            }
        } finally {
            found.close()
        }
    }

    /**
     * One probe: fresh socket, one initiation, wait for a type-2 reply.
     * Returns the endpoint on a landed handshake response, null on
     * timeout / bind failure / any error. The socket is always closed.
     */
    private suspend fun probeOne(
        endpoint: EndpointInfo,
        timeoutMs: Long,
        tracker: SocketTracker
    ): EndpointInfo? {
        currentCoroutineContext().ensureActive()
        val address = try {
            InetAddress.getByName(endpoint.ip)
        } catch (_: Exception) {
            return null
        }
        val packet = try {
            buildInitiation()
        } catch (_: Exception) {
            return null
        }
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket()
            tracker.register(socket)
            socket.soTimeout = timeoutMs.toInt()
            socket.connect(address, endpoint.port)
            socket.send(DatagramPacket(packet, packet.size))
            val deadline = SystemClock.elapsedRealtime() + timeoutMs
            val recvBuf = ByteArray(2048)
            while (true) {
                currentCoroutineContext().ensureActive()
                val remaining = deadline - SystemClock.elapsedRealtime()
                if (remaining <= 0) return null
                socket.soTimeout = remaining.coerceAtMost(timeoutMs).toInt()
                val recvPacket = DatagramPacket(recvBuf, recvBuf.size)
                try {
                    socket.receive(recvPacket)
                } catch (_: SocketTimeoutException) {
                    return null
                }
                val n = recvPacket.length
                if (n == 92 && recvBuf[0] == 2.toByte()) return endpoint
                if (n >= 1 && recvBuf[0] == 3.toByte()) {
                    // Cookie reply: endpoint alive but loaded (wants a
                    // mac2 retry, not implemented) — keep waiting.
                    continue
                }
                // Anything else: ignore, keep waiting for a real reply.
            }
        } catch (_: SocketException) {
            return null
        } catch (_: Exception) {
            return null
        } finally {
            socket?.let {
                tracker.unregister(it)
                try {
                    it.close()
                } catch (_: Exception) {
                }
            }
        }
    }

    /** Live sockets of the current round, so cancellation can close them all at once. */
    private class SocketTracker {
        private val sockets = ConcurrentHashMap.newKeySet<DatagramSocket>()
        fun register(s: DatagramSocket) {
            sockets.add(s)
        }

        fun unregister(s: DatagramSocket) {
            sockets.remove(s)
        }

        fun closeAll() {
            sockets.toList().forEach {
                try {
                    it.close()
                } catch (_: Exception) {
                }
            }
            sockets.clear()
        }
    }

    // ============================================================
    //  Initiation construction (Noise_IKpsk2, port of wg_probe.py)
    // ============================================================

    /** Build one 148-byte handshake initiation. */
    private fun buildInitiation(): ByteArray {
        val peerPublic = Base64.decode(PEER_PUBLIC_B64, Base64.NO_WRAP)
        val peerKey = X25519PublicKeyParameters(peerPublic)

        val ePriv = X25519PrivateKeyParameters(SecureRandom())
        val ePub = ePriv.generatePublicKey().encoded
        // Fixed dummy static key (see class KDoc): the engine's proven key.
        val sPriv = X25519PrivateKeyParameters(DUMMY_STATIC_PRIVATE)
        val sPub = sPriv.generatePublicKey().encoded

        // NOTE: the PSK is mixed ONLY in the response message, never here.
        var chainingKey = blake2s(PROTOCOL_NAME)
        var handshakeHash = blake2s(blake2s(chainingKey + IDENTIFIER) + peerPublic)

        // -> e: hash mix AND chaining-key step (KDF1 over the ephemeral pubkey)
        chainingKey = kdf1(chainingKey, ePub)
        handshakeHash = blake2s(handshakeHash + ePub)
        // es
        val dh1 = ByteArray(32)
        ePriv.generateSecret(peerKey, dh1, 0)
        val (ck1, tempKey1) = hkdf(chainingKey, dh1)
        chainingKey = ck1
        // -> s (encrypted static public)
        val encStatic = chachaEncrypt(tempKey1, handshakeHash, sPub)
        handshakeHash = blake2s(handshakeHash + encStatic)
        // ss — the temp_key from THIS step encrypts the timestamp (no psk step)
        val dh2 = ByteArray(32)
        sPriv.generateSecret(peerKey, dh2, 0)
        val (ck2, tempKey2) = hkdf(chainingKey, dh2)
        chainingKey = ck2
        // Encrypted timestamp (TAI64N; exact value does not matter for a
        // probe against an unknown peer with no stored replay state).
        val encTimestamp = chachaEncrypt(tempKey2, handshakeHash, tai64n())

        val senderIndex = SecureRandom().nextInt().let { if (it == 0) 1 else it }
        val body = ByteArray(116)
        body[0] = 1
        ByteBuffer.wrap(body, 4, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(senderIndex)
        System.arraycopy(ePub, 0, body, 8, 32)
        System.arraycopy(encStatic, 0, body, 40, 48)
        System.arraycopy(encTimestamp, 0, body, 88, 28)
        // mac1 (keyed BLAKE2s-16 over the message; the responder drops the
        // packet without a valid mac1) + mac2 (zeros: no cookie seen).
        val mac1Key = blake2s(MAC1_PREFIX + peerPublic)
        val mac1 = blake2sKeyed(mac1Key, body, 16)
        val packet = ByteArray(148)
        System.arraycopy(body, 0, packet, 0, 116)
        System.arraycopy(mac1, 0, packet, 116, 16)
        // bytes 132..147 (mac2) stay zero.
        return packet
    }

    /** TAI64N timestamp, 12 bytes big-endian (8s seconds + 4s nanoseconds). */
    private fun tai64n(): ByteArray {
        val millis = System.currentTimeMillis()
        val sec = millis / 1000L
        val nsec = ((millis % 1000L) * 1_000_000L).toInt()
        val tai = (1L shl 62) + sec + 37L
        return ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN)
            .putLong(tai).putInt(nsec).array()
    }

    /** BLAKE2s, unkeyed, 32-byte output. */
    private fun blake2s(data: ByteArray): ByteArray {
        val digest = Blake2sDigest()
        digest.update(data, 0, data.size)
        val out = ByteArray(32)
        digest.doFinal(out, 0)
        return out
    }

    /** BLAKE2s, keyed, [outLen]-byte output (MAC1 uses 16). */
    private fun blake2sKeyed(key: ByteArray, data: ByteArray, outLen: Int): ByteArray {
        val digest = Blake2sDigest(key, outLen, ByteArray(8), ByteArray(8))
        digest.update(data, 0, data.size)
        val out = ByteArray(outLen)
        digest.doFinal(out, 0)
        return out
    }

    /** HMAC-BLAKE2s (Noise HKDF primitive). */
    private fun hmacBlake2s(key: ByteArray, msg: ByteArray): ByteArray {
        val mac = HMac(Blake2sDigest())
        mac.init(KeyParameter(key))
        mac.update(msg, 0, msg.size)
        val out = ByteArray(32)
        mac.doFinal(out, 0)
        return out
    }

    /** Noise HKDF (2 outputs): (new chaining key, temp key). */
    private fun hkdf(chainingKey: ByteArray, ikm: ByteArray): Pair<ByteArray, ByteArray> {
        val temp = hmacBlake2s(chainingKey, ikm)
        val out1 = hmacBlake2s(temp, byteArrayOf(0x01))
        val out2 = hmacBlake2s(temp, out1 + byteArrayOf(0x02))
        return out1 to out2
    }

    /** WireGuard KDF with one output (ephemeral chaining step). */
    private fun kdf1(chainingKey: ByteArray, ikm: ByteArray): ByteArray {
        val temp = hmacBlake2s(chainingKey, ikm)
        return hmacBlake2s(temp, byteArrayOf(0x01))
    }

    /** ChaCha20-Poly1305 encrypt (12 zero nonce, [aad] as associated data). */
    private fun chachaEncrypt(key: ByteArray, aad: ByteArray, plain: ByteArray): ByteArray {
        val cipher = ChaCha20Poly1305()
        cipher.init(true, AEADParameters(KeyParameter(key), 128, ZERO_NONCE, aad))
        val out = ByteArray(plain.size + 16)
        var len = cipher.processBytes(plain, 0, plain.size, out, 0)
        len += cipher.doFinal(out, len)
        return out.copyOf(len)
    }

    companion object {
        private const val TAG = "PhoenixWgProber"

        /** Cloudflare WARP peer public key (public, same for all WARP endpoints). */
        private const val PEER_PUBLIC_B64 = "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo="

        private val PROTOCOL_NAME =
            "Noise_IKpsk2_25519_ChaChaPoly_BLAKE2s".toByteArray(Charsets.UTF_8)
        private val IDENTIFIER =
            "WireGuard v1 zx2c4 Jason@zx2c4.com".toByteArray(Charsets.UTF_8)
        private val MAC1_PREFIX = "mac1----".toByteArray(Charsets.UTF_8)
        private val ZERO_NONCE = ByteArray(12)

        /**
         * Fixed dummy static private key — 32 zero bytes, exactly the
         * Scanner engine's DUMMY_STATIC_PRIVATE. See class KDoc.
         */
        private val DUMMY_STATIC_PRIVATE = ByteArray(32)
    }
}
