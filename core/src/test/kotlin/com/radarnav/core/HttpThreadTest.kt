package com.radarnav.core

import com.radarnav.core.net.fetchString
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody.Companion.asResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

/**
 * Android'de yanıt gövdesini ana iş parçacığında okumak NetworkOnMainThreadException verir
 * (gerçek bir hata: radar sorgusu bu yüzden çalışmıyordu). fetchString, çağıran iş parçacığı
 * ne olursa olsun gövdeyi IO iş parçacığında okumalı.
 */
class HttpThreadTest {

    @OptIn(ExperimentalCoroutinesApi::class, kotlinx.coroutines.DelicateCoroutinesApi::class)
    @Test
    fun `yanit govdesi cagiran (ana) is parcaciginda okunmaz`() = runBlocking {
        val server = MockWebServer()
        val big = "x".repeat(200_000)
        server.enqueue(MockResponse().setBody(big))
        server.start()
        val readThreads = Collections.synchronizedSet(HashSet<String>())
        val client = OkHttpClient.Builder().addNetworkInterceptor { chain ->
            val resp = chain.proceed(chain.request())
            val original = resp.body!!
            val tracking = object : ForwardingSource(original.source()) {
                override fun read(sink: Buffer, byteCount: Long): Long {
                    readThreads.add(Thread.currentThread().name)
                    return super.read(sink, byteCount)
                }
            }
            resp.newBuilder().body(tracking.buffer().asResponseBody(original.contentType(), original.contentLength())).build()
        }.build()

        val fakeMain = newSingleThreadContext("sahte-ana")
        val body = withContext(fakeMain) {
            client.fetchString(Request.Builder().url(server.url("/")).build())
        }
        fakeMain.close()
        server.shutdown()

        assertEquals(big.length, body.length)
        assertTrue("gövde okundu", readThreads.isNotEmpty())
        assertTrue("ana iş parçacığında okundu: $readThreads", readThreads.none { it.startsWith("sahte-ana") })
    }
}
