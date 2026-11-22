package com.phoenix.phoenixvpn.vpn

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * v1.77 R7: CGNAT mapping diagnostic (RFC 5780 flavour, read-only).
 *
 * Sends a STUN binding request (no auth, 20-byte header) from TWO fresh
 * sockets to a public STUN server and logs each socket's local port plus
 * the XOR-MAPPED-ADDRESS the server saw. If both sockets map to the same
 * external port, the NAT's mapping is endpoint-independent for this
 * network — the property R2's port reuse leans on (same local port =
 * same external mapping). If they differ, the NAT randomizes per flow
 * and R2's reuse still works (same port rebinds the same mapping) but
 * the measurement tells later tuning what the NAT actually does.
 *
 * Best-effort and diagnostic ONLY: never throws, never blocks the walk
 * (the caller runs it fire-and-forget, 1s per socket), never decides
 * anything on the connect path. A failed lookup or timeout logs nothing
 * — silence is the correct result when the network cannot answer STUN.
 */
object StunMapper {
    private const val TAG = "PhoenixStun"
    private const val STUN_HOST = "stun.cloudflare.com"
    private const val STUN_PORT = 3478
    private const val TIMEOUT_MS = 1_000
    private const val MAGIC_COOKIE = 0x2112A442

    /** One STUN probe: local port + the external address the server saw. */
    private data class StunResult(val localPort: Int, val mapped: String)

    suspend fun probeAndLog() {
        withContext(Dispatchers.IO) {
            try {
                val a = probeOneSocket()
                val b = probeOneSocket()
                if (a == null && b == null) return@withContext
                Log.i(TAG, "STUN ${a?.let { "local=${it.localPort} mapped=${it.mapped}" } ?: "local=? no reply"} " +
                    "| ${b?.let { "local=${it.localPort} mapped=${it.mapped}" } ?: "local=? no reply"} " +
                    "| sameMappedPort=${a != null && b != null && a.mapped.substringAfter(':') == b.mapped.substringAfter(':')}")
            } catch (_: Exception) {
                // Diagnostic only — never disturb the walk.
            }
        }
    }

    private fun probeOneSocket(): StunResult? {
        var socket: DatagramSocket? = null
        try {
            val address = InetAddress.getByName(STUN_HOST)
            socket = DatagramSocket()
            socket.soTimeout = TIMEOUT_MS
            val localPort = socket.localPort
            val txId = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val req = ByteBuffer.allocate(20).order(ByteOrder.BIG_ENDIAN)
                .putShort(0x0001) // Binding Request
                .putShort(0) // length 0, no attributes
                .putInt(MAGIC_COOKIE)
                .put(txId)
                .array()
            socket.send(DatagramPacket(req, req.size, address, STUN_PORT))
            val buf = ByteArray(512)
            val resp = DatagramPacket(buf, buf.size)
            try {
                socket.receive(resp)
            } catch (_: SocketTimeoutException) {
                return null
            }
            val mapped = parseMappedAddress(buf, resp.length, txId) ?: return null
            Log.i(TAG, "STUN local=$localPort mapped=$mapped")
            return StunResult(localPort, mapped)
        } catch (_: Exception) {
            return null
        } finally {
            try {
                socket?.close()
            } catch (_: Exception) {
            }
        }
    }

    /** Parse XOR-MAPPED-ADDRESS (attr 0x0020) from a Binding Success response. */
    private fun parseMappedAddress(buf: ByteArray, len: Int, txId: ByteArray): String? {
        if (len < 20) return null
        val bb = ByteBuffer.wrap(buf, 0, len).order(ByteOrder.BIG_ENDIAN)
        if (bb.short.toInt() and 0xFFFF != 0x0101) return null // Binding Success
        if (bb.int != MAGIC_COOKIE) return null
        val respTx = ByteArray(12)
        bb.get(respTx)
        if (!respTx.contentEquals(txId)) return null
        var off = 20
        while (off + 4 <= len) {
            val type = ((buf[off].toInt() and 0xFF) shl 8) or (buf[off + 1].toInt() and 0xFF)
            val attrLen = ((buf[off + 2].toInt() and 0xFF) shl 8) or (buf[off + 3].toInt() and 0xFF)
            if (type == 0x0020 && attrLen == 8 && off + 12 <= len) {
                if (buf[off + 5].toInt() != 0x01) return null // IPv4 only
                val xPort = ((buf[off + 6].toInt() and 0xFF) shl 8) or (buf[off + 7].toInt() and 0xFF)
                val port = xPort xor (MAGIC_COOKIE ushr 16)
                val xAddr = ByteBuffer.wrap(buf, off + 8, 4).order(ByteOrder.BIG_ENDIAN).int
                val addr = xAddr xor MAGIC_COOKIE
                val ip = "${(addr ushr 24) and 0xFF}.${(addr ushr 16) and 0xFF}.${(addr ushr 8) and 0xFF}.${addr and 0xFF}"
                return "$ip:$port"
            }
            off += 4 + attrLen + (attrLen % 4) // attributes are 4-byte aligned
        }
        return null
    }
}
