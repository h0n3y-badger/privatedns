package com.privatedns

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.random.Random

/**
 * End-to-end DNS-over-TLS check (RFC 7858): TLS handshake to <host>:853 with
 * SNI + certificate validation, then a real A-record query for a test domain.
 */
object DotTester {
    private const val PORT = 853
    private const val TIMEOUT_MS = 5000

    data class Result(
        val ok: Boolean,
        val detail: String,
    )

    fun test(host: String, queryName: String = "example.com"): Result {
        val start = System.nanoTime()
        return try {
            val socket = SSLSocketFactory.getDefault().createSocket() as SSLSocket
            socket.use { s ->
                s.soTimeout = TIMEOUT_MS
                s.connect(InetSocketAddress(host, PORT), TIMEOUT_MS)
                s.startHandshake()
                val tlsMs = (System.nanoTime() - start) / 1_000_000
                val proto = s.session.protocol
                val peer = s.session.peerPrincipal?.name ?: "?"

                val id = Random.nextInt(0x10000)
                val query = buildQuery(id, queryName)
                val out = DataOutputStream(s.outputStream)
                out.writeShort(query.size)
                out.write(query)
                out.flush()

                val inp = DataInputStream(s.inputStream)
                val len = inp.readUnsignedShort()
                val resp = ByteArray(len)
                inp.readFully(resp)
                val totalMs = (System.nanoTime() - start) / 1_000_000

                val respId = ((resp[0].toInt() and 0xFF) shl 8) or (resp[1].toInt() and 0xFF)
                if (respId != id) return Result(false, "DNS response ID mismatch")
                val rcode = resp[3].toInt() and 0x0F
                if (rcode != 0) return Result(false, "DNS query failed, rcode=$rcode")
                val answers = ((resp[6].toInt() and 0xFF) shl 8) or (resp[7].toInt() and 0xFF)
                val ips = parseARecords(resp)

                Result(
                    true,
                    "$proto handshake ${tlsMs}ms, cert $peer\n" +
                        "$queryName -> ${if (ips.isEmpty()) "$answers answer(s)" else ips.joinToString()} " +
                        "(${totalMs}ms total)",
                )
            }
        } catch (e: Exception) {
            Result(false, "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun buildQuery(id: Int, name: String): ByteArray {
        val buf = ArrayList<Byte>(64)
        fun short(v: Int) { buf.add((v ushr 8).toByte()); buf.add(v.toByte()) }
        short(id)
        short(0x0100) // RD
        short(1); short(0); short(0); short(0)
        for (label in name.trimEnd('.').split('.')) {
            buf.add(label.length.toByte())
            label.toByteArray(Charsets.US_ASCII).forEach { buf.add(it) }
        }
        buf.add(0)
        short(1) // QTYPE A
        short(1) // QCLASS IN
        return buf.toByteArray()
    }

    private fun parseARecords(resp: ByteArray): List<String> {
        val ips = mutableListOf<String>()
        try {
            val qdCount = ((resp[4].toInt() and 0xFF) shl 8) or (resp[5].toInt() and 0xFF)
            val anCount = ((resp[6].toInt() and 0xFF) shl 8) or (resp[7].toInt() and 0xFF)
            var pos = 12
            repeat(qdCount) {
                pos = skipName(resp, pos) + 4
            }
            repeat(anCount) {
                pos = skipName(resp, pos)
                val type = ((resp[pos].toInt() and 0xFF) shl 8) or (resp[pos + 1].toInt() and 0xFF)
                val rdLen = ((resp[pos + 8].toInt() and 0xFF) shl 8) or (resp[pos + 9].toInt() and 0xFF)
                pos += 10
                if (type == 1 && rdLen == 4) {
                    ips.add(
                        (0..3).joinToString(".") { (resp[pos + it].toInt() and 0xFF).toString() },
                    )
                }
                pos += rdLen
            }
        } catch (_: Exception) {
            // best-effort; caller falls back to answer count
        }
        return ips
    }

    private fun skipName(resp: ByteArray, start: Int): Int {
        var pos = start
        while (true) {
            val len = resp[pos].toInt() and 0xFF
            when {
                len == 0 -> return pos + 1
                len >= 0xC0 -> return pos + 2 // compression pointer
                else -> pos += len + 1
            }
        }
    }
}
