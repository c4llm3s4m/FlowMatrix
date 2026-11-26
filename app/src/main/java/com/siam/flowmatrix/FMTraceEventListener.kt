package com.siam.flowmatrix

import android.os.SystemClock
import android.util.Log
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.Protocol
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.ConcurrentHashMap

class FMTraceEventListener(private val tag: String) : EventListener() {
    private val callStartTimeMap = ConcurrentHashMap<Int, Long>()

    override fun callStart(call: Call) {
        val startMs = SystemClock.elapsedRealtime()
        callStartTimeMap[call.hashCode()] = startMs
        Log.d("FM_TRACE", "[$tag] CALL_START id=${call.hashCode()} url=${call.request().url.encodedPath}")
    }

    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
        val startMs = callStartTimeMap[call.hashCode()] ?: SystemClock.elapsedRealtime()
        val elapsed = SystemClock.elapsedRealtime() - startMs
        Log.d("FM_TRACE", "[$tag] DNS_END id=${call.hashCode()} ms=${elapsed} ips=$inetAddressList")
    }

    override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) {
        val startMs = callStartTimeMap[call.hashCode()] ?: SystemClock.elapsedRealtime()
        val elapsed = SystemClock.elapsedRealtime() - startMs
        Log.d("FM_TRACE", "[$tag] TCP_END id=${call.hashCode()} ms=${elapsed} proto=$protocol")
    }

    override fun secureConnectEnd(call: Call, handshake: Handshake?) {
        val startMs = callStartTimeMap[call.hashCode()] ?: SystemClock.elapsedRealtime()
        val elapsed = SystemClock.elapsedRealtime() - startMs
        Log.d("FM_TRACE", "[$tag] TLS_END id=${call.hashCode()} ms=${elapsed} tls=${handshake?.tlsVersion}")
    }

    override fun connectFailed(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?, ioe: IOException) {
        val startMs = callStartTimeMap[call.hashCode()] ?: SystemClock.elapsedRealtime()
        val elapsed = SystemClock.elapsedRealtime() - startMs
        Log.d("FM_TRACE", "[$tag] CONNECT_FAIL id=${call.hashCode()} ms=${elapsed} err=${ioe::class.java.simpleName}:${ioe.message}")
    }

    override fun connectionAcquired(call: Call, connection: Connection) {
        Log.d("FM_TRACE", "[$tag] CONN_ACQUIRED id=${call.hashCode()} conn=${connection.hashCode()} proto=${connection.protocol()}")
    }

    override fun responseHeadersEnd(call: Call, response: Response) {
        val startMs = callStartTimeMap[call.hashCode()] ?: SystemClock.elapsedRealtime()
        val elapsed = SystemClock.elapsedRealtime() - startMs
        Log.d("FM_TRACE", "[$tag] RESP_HDR id=${call.hashCode()} ms=${elapsed} code=${response.code}")
    }

    override fun callFailed(call: Call, ioe: IOException) {
        val startMs = callStartTimeMap.remove(call.hashCode()) ?: SystemClock.elapsedRealtime()
        val elapsed = SystemClock.elapsedRealtime() - startMs
        Log.d("FM_TRACE", "[$tag] CALL_FAIL id=${call.hashCode()} ms=${elapsed} err=${ioe::class.java.simpleName}:${ioe.message}")
    }
}