package com.androidcam.stream

import android.util.Base64
import timber.log.Timber
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Minimal RTSP server (H.264 payload, RFC 6184) that runs in parallel with
 * the HTTP MJPEG stream. Shares the same camera feed via [H264StreamEncoder]
 * (fed by [com.androidcam.camera.FrameCapturer]).
 *
 * Protocol: HTTP/1.0-style text over TCP (RTSP 1.0), UDP for RTP payloads
 * with TCP-interleaved fallback (RFC 2326 §10.12).
 * Supports OPTIONS, DESCRIBE, SETUP, PLAY, TEARDOWN. Auth via Basic
 * credentials (password = the shared [token]).
 *
 * Connected clients receive RTP either via UDP from the server RTP port or
 * interleaved over the RTSP TCP control connection.
 *
 * RTP packetization (RFC 6184): single NAL units when they fit in the MTU,
 * FU-A fragmentation for larger NALs, and a STAP-A of SPS/PPS before every
 * IDR frame. SPS/PPS are also carried in the SDP (sprop-parameter-sets).
 * A session starts sending at the next IDR frame so the client always has a
 * decodable starting point.
 */
class RtspServer(
    private val token: String,
    private val encoderProvider: () -> H264StreamEncoder?,
    private val ipProvider: () -> String?,
) {
    companion object {
        /** Maximum concurrent RTSP sessions. */
        private const val MAX_SESSIONS = 4

        /** Control port to try first; RTP port is controlPort + 1. */
        private const val RTSP_PORT_START = 8554

        /** How many ports to try before giving up. */
        private const val PORT_RETRY_COUNT = 6

        /** Idle poll interval when no new frame is available. */
        private const val RTSP_IDLE_DELAY_MS = 50L

        /** RTP clock rate for H.264. */
        private const val RTP_CLOCK_RATE = 90_000

        /** RTP payload type for H.264 (dynamic range 96–127). */
        private const val RTP_PAYLOAD_TYPE = 96

        /** Maximum RTP payload size — leaves room for IP (20) + UDP (8) headers under the 1500-byte MTU. */
        private const val MAX_RTP_PAYLOAD = 1400
    }

    private var running = false

    private var _controlPort = 0

    /** RTSP control port (0 if not running). */
    val controlPort: Int get() = _controlPort

    private var _rtpPort = 0

    /** RTP port (0 if not running). */
    val rtpPort: Int get() = _rtpPort

    /** Server IP address used for SDP connection address. */
    @Volatile
    private var _serverIp: String? = null

    /** The server's own IP, used in SDP and mDNS. Re-resolved per request so a
     *  DHCP/network change does not leave the SDP pointing at a stale address. */
    val serverIp: String get() = ipProvider() ?: _serverIp ?: "0.0.0.0"

    /** Fixed random SSRC for all sessions. */
    private val ssrc = 1 + (Math.random() * 0x7FFFFFFF).toInt()

    /** Accept thread (daemon). */
    private var acceptThread: Thread? = null

    /** The control ServerSocket created in start() — used by acceptLoop(). */
    private var controlServerSocket: ServerSocket? = null

    /** Shared DatagramSocket for UDP RTP transmission. */
    @Volatile
    private var serverRtpSocket: DatagramSocket? = null

    /** Session counter for session IDs. */
    private val sessionCounter = AtomicInteger(1)

    /** Active session threads — used by stop() to interrupt. */
    private val sessionThreads = CopyOnWriteArrayList<Thread>()

    /**
     * Start the RTSP server. Returns false if all candidate ports are in use.
     * The server binds to 0.0.0.0 (all interfaces) for maximum reachability,
     * but advertises [advertisedIp] in the SDP so clients know where to send RTP.
     */
    fun start(advertisedIp: Inet4Address): Boolean {
        if (running) {
            Timber.w("RtspServer: already running")
            return true
        }
        running = true

        var bound = false
        for (i in 0 until PORT_RETRY_COUNT) {
            val ctrl = RTSP_PORT_START + i * 2
            val rtp = ctrl + 1
            var ss: ServerSocket? = null
            var ds: DatagramSocket? = null
            try {
                // Bind control port to 0.0.0.0 so reachable on all interfaces
                ss = ServerSocket(ctrl, 4, InetAddress.getByName("0.0.0.0"))
                ds = DatagramSocket(rtp, InetAddress.getByName("0.0.0.0"))
                _controlPort = ctrl
                _rtpPort = rtp
                _serverIp = advertisedIp.hostAddress
                controlServerSocket = ss
                serverRtpSocket = ds
                acceptThread =
                    Thread(Runnable { this@RtspServer.acceptLoop(ss) }, "rtsp-accept").apply {
                        isDaemon = true
                        start()
                    }
                Timber.i("RTSP server started: rtsp://$advertisedIp:$_controlPort (RTP UDP $_rtpPort)")
                bound = true
                break
            } catch (e: Exception) {
                try {
                    ss?.close()
                } catch (_: Exception) {
                }
                try {
                    ds?.close()
                } catch (_: Exception) {
                }
                Timber.w(e, "RTSP port $ctrl/$rtp in use, trying next")
            }
        }
        if (!bound) {
            running = false
            Timber.w("RtspServer: all candidate ports busy, RTSP not started")
            return false
        }
        return true
    }

    /** Stop the server and all sessions. */
    fun stop() {
        if (!running) return
        running = false

        // Close the control ServerSocket so acceptLoop unblocks
        controlServerSocket?.let {
            try {
                it.close()
            } catch (_: Exception) {
            }
            controlServerSocket = null
        }

        // Close shared RTP DatagramSocket
        serverRtpSocket?.let {
            try {
                it.close()
            } catch (_: Exception) {
            }
            serverRtpSocket = null
        }

        // Interrupt all threads
        acceptThread?.interrupt()
        for (t in sessionThreads) t.interrupt()
        sessionThreads.clear()

        Timber.d("RTSP server stopped")
    }

    // -- Accept loop --

    private fun acceptLoop(ss: ServerSocket) {
        try {
            while (running) {
                try {
                    val client = ss.accept()
                    if (!running) break
                    if (sessionThreads.size >= MAX_SESSIONS) {
                        // Too many clients — send a polite 503 before closing.
                        try {
                            val writer = BufferedWriter(OutputStreamWriter(client.outputStream))
                            writer.write("RTSP/1.0 503 Service Unavailable\r\n")
                            writer.write("CSeq: 1\r\n")
                            writer.write("\r\n")
                            writer.flush()
                        } catch (_: Exception) {
                        }
                        client.close()
                        continue
                    }
                    val session = Session(client, this)
                    val t = Thread(session, "rtsp-session-${sessionCounter.getAndIncrement()}")
                    t.setDaemon(true)
                    sessionThreads.add(t)
                    t.start()
                } catch (e: Exception) {
                    if (running) Timber.w(e, "RTSP accept error")
                }
            }
        } catch (e: Exception) {
            if (running) Timber.w(e, "RTSP accept loop terminated")
        } finally {
            try {
                ss.close()
            } catch (_: Exception) {
            }
            controlServerSocket = null
        }
    }

    // -- Session (inner so it can access outer private members) --

    private inner class Session(
        private val client: Socket,
        private val server: RtspServer,
    ) : Runnable {
        private var state = State.INIT
        private var sessionToken: String? = null
        private var clientAddress: InetSocketAddress = InetSocketAddress(0)
        private var clientPort = 0
        private var useTcpInterleaved = false
        private var interleavedChannels = "0-1"
        private var senderThread: Thread? = null
        private var seq = 0
        private var lastUnitId = -1L

        private val running = AtomicBoolean(true)

        override fun run() {
            try {
                val reader = BufferedReader(InputStreamReader(client.inputStream))
                while (running.get() && server.running) {
                    val requestLine = reader.readLine()
                    if (requestLine == null) break
                    if (requestLine.isEmpty()) continue
                    val (method, url, cseq, headers) = parseRequest(reader, requestLine)
                    if (method.isEmpty()) continue
                    dispatch(method, url, cseq, headers)
                }
            } catch (e: Exception) {
                Timber.d("RTSP session closed: ${client.remoteSocketAddress}")
            } finally {
                cleanup()
            }
        }

        private fun parseRequest(
            reader: BufferedReader,
            requestLine: String,
        ): Tuple4<String, String, Int, Map<String, String>> {
            val parts = requestLine.split(" ")
            if (parts.size < 3) {
                return Tuple4("", requestLine, 0, emptyMap())
            }
            val method = parts[0]
            val url = parts[1]
            // Parse CSeq from headers
            val headers = mutableMapOf<String, String>()
            var cseq = 0
            while (true) {
                val header = reader.readLine()
                if (header == null || header.isEmpty()) break
                val colon = header.indexOf(':')
                if (colon > 0) {
                    val key = header.substring(0, colon).trim()
                    val value = header.substring(colon + 1).trim()
                    headers[key] = value
                    if (key.equals("CSeq", ignoreCase = true)) {
                        cseq = value.toIntOrNull() ?: 0
                    }
                }
            }
            return Tuple4(method, url, cseq, headers)
        }

        private fun dispatch(
            method: String,
            url: String,
            cseq: Int,
            headers: Map<String, String>,
        ) {
            when (method) {
                "OPTIONS" -> respondOptions(cseq)
                "DESCRIBE" -> handleDescribe(cseq, url, headers)
                "SETUP" -> handleSetup(cseq, headers)
                "PLAY" -> handlePlay(cseq, headers)
                "TEARDOWN" -> handleTeardown(cseq, headers)
                else -> respond(405, cseq)
            }
        }

        private fun respond(
            code: Int,
            cseq: Int,
        ) {
            try {
                val writer = BufferedWriter(OutputStreamWriter(client.outputStream))
                writer.write("RTSP/1.0 $code\r\n")
                if (cseq > 0) writer.write("CSeq: $cseq\r\n")
                writer.write("\r\n")
                writer.flush()
            } catch (_: Exception) {
            }
        }

        private fun respondOptions(cseq: Int) {
            respond(200, cseq)
        }

        private fun respondAuth(cseq: Int) {
            try {
                val writer = BufferedWriter(OutputStreamWriter(client.outputStream))
                writer.write("RTSP/1.0 401 Unauthorized\r\n")
                writer.write("CSeq: $cseq\r\n")
                writer.write("WWW-Authenticate: Basic realm=\"androidcam\"\r\n")
                writer.write("\r\n")
                writer.flush()
            } catch (_: Exception) {
            }
        }

        private fun parseAuth(
            headers: Map<String, String>,
            cseq: Int,
        ): Boolean {
            val authHeader = headers["Authorization"]
            if (authHeader == null || !authHeader.startsWith("Basic ")) {
                respondAuth(cseq)
                return false
            }
            val decoded = Base64.decode(authHeader.substring(6), Base64.DEFAULT)
            val credentials = decoded.decodeToString()
            val colon = credentials.indexOf(':')
            if (colon < 0) {
                respondAuth(cseq)
                return false
            }
            val password = credentials.substring(colon + 1)
            if (password != token) {
                respondAuth(cseq)
                return false
            }
            sessionToken = "session-${sessionCounter.getAndIncrement()}"
            return true
        }

        private fun handleDescribe(
            cseq: Int,
            url: String,
            headers: Map<String, String>,
        ) {
            if (state != State.INIT) {
                respond(405, cseq)
                return
            }
            if (!parseAuth(headers, cseq)) return
            state = State.DESCRIBED
            val sdp = generateSdp(server.rtpPort, server.serverIp, server.encoderProvider())
            val body = sdp.toByteArray(Charsets.UTF_8)
            try {
                val writer = BufferedWriter(OutputStreamWriter(client.outputStream))
                writer.write("RTSP/1.0 200 OK\r\n")
                writer.write("CSeq: $cseq\r\n")
                writer.write("Content-Type: application/sdp\r\n")
                writer.write("Content-Length: ${body.size}\r\n")
                writer.write("\r\n")
                writer.flush()
                client.outputStream.write(body)
                client.outputStream.flush()
            } catch (e: Exception) {
                Timber.w(e, "RTSP DESCRIBE error")
            }
        }

        private fun handleSetup(
            cseq: Int,
            headers: Map<String, String>,
        ) {
            if (state != State.DESCRIBED) {
                respond(405, cseq)
                return
            }
            if (!parseAuth(headers, cseq)) return
            state = State.SETUP_DONE
            try {
                // Parse client port and transport from Transport header
                val transport = headers["Transport"]
                var clientPortStart = 0
                // TCP interleaved is signalled by the "interleaved=" parameter
                // (RFC 2326 §10.12) — NOT by the absence of "/UDP": clients may
                // send a bare "RTP/AVP;unicast" for plain UDP.
                var isTcp = false
                if (transport != null) {
                    isTcp = transport.contains("interleaved=")
                    val interleavedMatch = Regex("interleaved=(\\d+)-(\\d+)").find(transport)
                    if (interleavedMatch != null) {
                        interleavedChannels =
                            "${interleavedMatch.groupValues[1]}-${interleavedMatch.groupValues[2]}"
                    }
                    val clientPortMatch = Regex("client_port=(\\d+)-?(\\d+)?").find(transport)
                    if (clientPortMatch != null) {
                        clientPortStart = clientPortMatch.groupValues[1].toInt()
                    }
                }
                useTcpInterleaved = isTcp

                if (isTcp) {
                    // TCP interleaved: no datagram socket needed
                    Timber.d("RTSP SETUP: TCP interleaved mode (channels $interleavedChannels)")
                    val writer = BufferedWriter(OutputStreamWriter(client.outputStream))
                    writer.write("RTSP/1.0 200 OK\r\n")
                    writer.write("CSeq: $cseq\r\n")
                    writer.write("Session: $sessionToken\r\n")
                    writer.write("Transport: RTP/AVP/TCP;unicast;interleaved=$interleavedChannels\r\n")
                    writer.write("\r\n")
                    writer.flush()
                } else {
                    // UDP mode
                    clientPort = if (clientPortStart > 0) clientPortStart else 50000 + (Math.random() * 1000).toInt()
                    val clientIp = (client.remoteSocketAddress as InetSocketAddress).address
                    clientAddress = InetSocketAddress(clientIp, clientPort)
                    val writer = BufferedWriter(OutputStreamWriter(client.outputStream))
                    writer.write("RTSP/1.0 200 OK\r\n")
                    writer.write("CSeq: $cseq\r\n")
                    writer.write("Session: $sessionToken\r\n")
                    val clientPorts = if (clientPortStart > 0) ";client_port=$clientPortStart-${clientPortStart + 1}" else ""
                    writer.write("Transport: RTP/AVP/UDP;unicast;server_port=$rtpPort$clientPorts\r\n")
                    writer.write("\r\n")
                    writer.flush()
                }
            } catch (e: Exception) {
                Timber.w(e, "RTSP SETUP error")
                respond(500, cseq)
            }
        }

        private fun handlePlay(
            cseq: Int,
            headers: Map<String, String>,
        ) {
            if (state != State.SETUP_DONE) {
                respond(405, cseq)
                return
            }
            if (!parseAuth(headers, cseq)) return
            state = State.PLAYING
            // Ask the encoder for an IDR so this client can start decoding
            // within one frame instead of waiting for the next periodic keyframe.
            server.encoderProvider()?.requestKeyFrame()
            startSender()
            try {
                val writer = BufferedWriter(OutputStreamWriter(client.outputStream))
                writer.write("RTSP/1.0 200 OK\r\n")
                writer.write("CSeq: $cseq\r\n")
                if (sessionToken != null) writer.write("Session: $sessionToken\r\n")
                writer.write("\r\n")
                writer.flush()
            } catch (e: Exception) {
                Timber.w(e, "RTSP PLAY response error")
            }
        }

        private fun handleTeardown(
            cseq: Int,
            headers: Map<String, String>,
        ) {
            try {
                val writer = BufferedWriter(OutputStreamWriter(client.outputStream))
                writer.write("RTSP/1.0 200 OK\r\n")
                writer.write("CSeq: $cseq\r\n")
                if (sessionToken != null) writer.write("Session: $sessionToken\r\n")
                writer.write("\r\n")
                writer.flush()
            } catch (_: Exception) {
            }
            cleanup()
        }

        private fun startSender() {
            val t =
                Thread(Runnable { this@Session.senderLoop() }, "rtsp-sender-$sessionToken").apply {
                    isDaemon = true
                    start()
                }
            senderThread = t
            sessionThreads.add(t)
        }

        private fun senderLoop() {
            Timber.d("RTSP sender loop started for session $sessionToken")
            var playing = false
            while (running.get() && server.running) {
                val unit = server.encoderProvider()?.latestUnit
                if (unit != null && unit.id != lastUnitId) {
                    if (!playing) {
                        if (!unit.isKeyFrame) {
                            // Wait for an IDR so the client has a decodable start.
                            Thread.sleep(RTSP_IDLE_DELAY_MS)
                            continue
                        }
                        playing = true
                        Timber.d("RTSP session $sessionToken: sending from keyframe ${unit.id}")
                    }
                    lastUnitId = unit.id
                    sendAccessUnit(unit, server.encoderProvider())
                } else {
                    Thread.sleep(RTSP_IDLE_DELAY_MS)
                }
            }
            Timber.d("RTSP sender loop stopped for session $sessionToken")
        }

        /**
         * Packetize one access unit per RFC 6184 and send it.
         *
         * NALs that fit in the MTU go out as single-NAL packets; larger NALs
         * are split into FU-A fragments. Before an IDR frame a STAP-A packet
         * carrying SPS/PPS is prepended so the client is self-sufficient even
         * without the SDP's sprop-parameter-sets.
         */
        private fun sendAccessUnit(
            unit: H264StreamEncoder.AccessUnit,
            encoder: H264StreamEncoder?,
        ) {
            val ts = unit.pts90k
            val packets = mutableListOf<ByteArray>()
            if (unit.isKeyFrame) {
                val sps = encoder?.sps
                val pps = encoder?.pps
                if (sps != null && pps != null) {
                    packets.add(buildRtpPacket(buildStapA(sps, pps), ts))
                }
            }
            for (nal in unit.nals) {
                if (nal.size <= MAX_RTP_PAYLOAD) {
                    packets.add(buildRtpPacket(nal, ts))
                } else {
                    packets.addAll(buildFuA(nal, ts))
                }
            }
            if (packets.isEmpty()) return
            // Assign sequence numbers (16-bit wrap) and set the marker bit on
            // the last packet of the access unit.
            var s = seq
            try {
                for (i in packets.indices) {
                    val pkt = packets[i]
                    pkt[2] = ((s shr 8) and 0xFF).toByte()
                    pkt[3] = (s and 0xFF).toByte()
                    if (i == packets.lastIndex) {
                        pkt[1] = (pkt[1].toInt() or 0x80).toByte()
                    }
                    s = (s + 1) and 0xFFFF
                    if (useTcpInterleaved) {
                        writeInterleaved(pkt)
                    } else {
                        val sock = server.serverRtpSocket
                        if (sock == null || sock.isClosed) {
                            // Socket was closed (e.g., teardown)
                            Timber.d("RTSP sender loop: socket closed or null, stopping")
                            running.set(false)
                            return
                        }
                        sock.send(DatagramPacket(pkt, pkt.size, clientAddress))
                    }
                }
                seq = s
            } catch (e: Exception) {
                Timber.w(e, "RTSP sender error: ${e.message}")
                running.set(false)
            }
        }

        /** Write one RTP packet to the control connection in interleaved format. */
        private fun writeInterleaved(pkt: ByteArray) {
            val out = client.outputStream
            out.write(0x24) // '$' start marker
            out.write(0) // channel 0 (RTP)
            out.write((pkt.size shr 8) and 0xFF) // length, big-endian
            out.write(pkt.size and 0xFF)
            out.write(pkt)
            out.flush()
        }

        /** RTP header + payload; sequence number and marker are patched by the caller. */
        private fun buildRtpPacket(
            payload: ByteArray,
            ts: Long,
        ): ByteArray {
            val header =
                encodeRtpHeader(seq = 0, ts = ts, ssrc = server.ssrc, pt = RTP_PAYLOAD_TYPE, marker = false)
            val out = ByteArray(header.size + payload.size)
            System.arraycopy(header, 0, out, 0, header.size)
            System.arraycopy(payload, 0, out, header.size, payload.size)
            return out
        }

        /** Fragment a NAL unit into FU-A packets (RFC 6184 §5.2). */
        private fun buildFuA(
            nal: ByteArray,
            ts: Long,
        ): List<ByteArray> {
            val nalType = nal[0].toInt() and 0x1F
            val nri = nal[0].toInt() and 0x60
            // RFC 6184 §5.4: the FU *indicator* carries F|NRI|Type(28) — the NRI
            // of the original NAL. Receivers (ffmpeg, GStreamer) rebuild the NAL
            // header from the indicator's NRI, so it must be set here.
            val fuIndicator = (nri or 0x1C).toByte()
            val maxChunk = MAX_RTP_PAYLOAD - 2 // FU indicator + FU header
            val packets = mutableListOf<ByteArray>()
            var offset = 1 // skip the NAL header byte
            var first = true
            while (first || offset < nal.size) {
                val end = minOf(offset + maxChunk, nal.size)
                val fuHeader =
                    (
                        nalType or
                            (if (first) 0x80 else 0) or // S
                            (if (end == nal.size) 0x40 else 0)
                    ).toByte() // E
                val payload = ByteArray(2 + (end - offset))
                payload[0] = fuIndicator
                payload[1] = fuHeader
                System.arraycopy(nal, offset, payload, 2, end - offset)
                packets.add(buildRtpPacket(payload, ts))
                offset = end
                first = false
            }
            return packets
        }

        /** Aggregate small NAL units (SPS/PPS) into one STAP-A packet (RFC 6184 §4.4.1). */
        private fun buildStapA(vararg nals: ByteArray): ByteArray {
            val total = 1 + nals.sumOf { 2 + it.size }
            val out = ByteArray(total)
            var pos = 0
            out[pos++] = 0x18.toByte() // STAP-A: type 24
            for (nal in nals) {
                out[pos++] = ((nal.size shr 8) and 0xFF).toByte()
                out[pos++] = (nal.size and 0xFF).toByte()
                System.arraycopy(nal, 0, out, pos, nal.size)
                pos += nal.size
            }
            return out
        }

        private fun generateSdp(
            rtpPort: Int,
            serverIp: String,
            encoder: H264StreamEncoder?,
        ): String {
            val sb = StringBuilder()
            sb.append("v=0\r\n")
            sb.append("o=- ${System.currentTimeMillis()} 0 IN IP4 $serverIp\r\n")
            sb.append("s=AndroidCam RTSP Stream\r\n")
            sb.append("c=IN IP4 $serverIp\r\n")
            sb.append("t=0 0\r\n")
            sb.append("m=video $rtpPort RTP/AVP $RTP_PAYLOAD_TYPE\r\n")
            sb.append("a=rtpmap:$RTP_PAYLOAD_TYPE H264/90000\r\n")
            val sps = encoder?.sps
            val pps = encoder?.pps
            if (sps != null && pps != null && sps.size >= 4) {
                val profileLevelId =
                    String.format(
                        "%02X%02X%02X",
                        sps[1].toInt() and 0xFF,
                        sps[2].toInt() and 0xFF,
                        sps[3].toInt() and 0xFF,
                    )
                sb.append(
                    "a=fmtp:$RTP_PAYLOAD_TYPE profile-level-id=$profileLevelId;" +
                        "sprop-parameter-sets=" +
                        Base64.encodeToString(sps, Base64.NO_WRAP) + "," +
                        Base64.encodeToString(pps, Base64.NO_WRAP) + "\r\n",
                )
            }
            sb.append("a=control:trackID=1\r\n")
            sb.append("a=sendonly\r\n")
            return sb.toString()
        }

        private fun cleanup() {
            running.set(false)
            // Remove both the sender thread and this session thread; leaking
            // either one would fill sessionThreads and trip MAX_SESSIONS (503).
            senderThread?.let {
                it.interrupt()
                server.sessionThreads.remove(it)
            }
            server.sessionThreads.remove(Thread.currentThread())
            try {
                client.close()
            } catch (_: Exception) {
            }
        }

        private fun encodeRtpHeader(
            seq: Int,
            ts: Long,
            ssrc: Int,
            pt: Int,
            marker: Boolean,
        ): ByteArray {
            val buf = ByteArray(12)
            buf[0] = 0x80.toByte() // V=2, no padding, no extension, no CSRC
            buf[1] = ((if (marker) 0x80 else 0) or pt).toByte() // marker bit + payload type
            buf[2] = (seq shr 8).toByte()
            buf[3] = seq.toByte()
            buf[4] = (ts.toInt() shr 24).toByte()
            buf[5] = (ts.toInt() shr 16).toByte()
            buf[6] = (ts.toInt() shr 8).toByte()
            buf[7] = ts.toInt().toByte()
            buf[8] = (ssrc shr 24).toByte()
            buf[9] = (ssrc shr 16).toByte()
            buf[10] = (ssrc shr 8).toByte()
            buf[11] = ssrc.toByte()
            return buf
        }
    }

    // -- RTP helpers --

    /** RTSP session state machine. */
    private enum class State { INIT, DESCRIBED, SETUP_DONE, PLAYING }

    // -- Kotlin doesn't have Tuple4, so we use a data class --

    private data class Tuple4<A, B, C, D>(
        val a: A,
        val b: B,
        val c: C,
        val d: D,
    )
}
