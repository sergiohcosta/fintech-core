package com.fintech.mobile.session

import com.fintech.mobile.data.EnvironmentUrlProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.IOException
import kotlin.test.assertEquals

class EnvironmentInterceptorTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var urlProvider: FakeEnvironmentUrlProvider

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        urlProvider = FakeEnvironmentUrlProvider(baseUrl = server.url("/").toString())
        client = OkHttpClient.Builder()
            .addInterceptor(EnvironmentInterceptor(urlProvider))
            .build()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `rewrites the request to the host from the current environment`() {
        server.enqueue(MockResponse().setResponseCode(200))

        client.newCall(
            Request.Builder().url("http://placeholder.invalid/auth/login").build()
        ).execute()

        val recorded = server.takeRequest()
        assertEquals("/auth/login", recorded.path)
    }

    @Test
    fun `preserves query parameters while rewriting the host`() {
        server.enqueue(MockResponse().setResponseCode(200))

        client.newCall(
            Request.Builder().url("http://placeholder.invalid/api/transactions?includeProjected=false").build()
        ).execute()

        val recorded = server.takeRequest()
        assertEquals("/api/transactions?includeProjected=false", recorded.path)
    }

    @Test
    fun `reflects a runtime change of environment on the next request`() {
        server.enqueue(MockResponse().setResponseCode(200))
        server.enqueue(MockResponse().setResponseCode(200))

        client.newCall(Request.Builder().url("http://placeholder.invalid/api/accounts").build()).execute()
        server.takeRequest()

        val secondServer = MockWebServer().apply { start() }
        try {
            urlProvider.baseUrl = secondServer.url("/").toString()
            secondServer.enqueue(MockResponse().setResponseCode(200))

            client.newCall(Request.Builder().url("http://placeholder.invalid/api/categories").build()).execute()

            assertEquals("/api/categories", secondServer.takeRequest().path)
        } finally {
            secondServer.shutdown()
        }
    }

    @Test
    fun `an invalid base url does not crash and lets the request proceed`() {
        urlProvider.baseUrl = "not a valid url"

        // O crash real que este teste guarda é IllegalArgumentException (toHttpUrl não-nulo
        // do OkHttp) escapando do dispatcher e matando o processo. Com toHttpUrlOrNull, o
        // interceptor deixa a requisição original passar sem reescrever — ela ainda pode
        // falhar por I/O normal (o host original "placeholder.invalid" não resolve DNS),
        // que é o mesmo IOException que o apiCall já trata como erro de rede. O teste só
        // precisa provar que nenhuma outra exceção (em especial IllegalArgumentException)
        // escapa.
        try {
            client.newCall(
                Request.Builder().url("http://placeholder.invalid/auth/login").build()
            ).execute()
        } catch (expected: IOException) {
            // esperado: falha de rede comum, não o crash de processo que este teste evita
        }
    }

    @Test
    fun `preserves the subpath of a custom local url ahead of the request path`() {
        val basePath = server.url("/fintech/").toString()
        urlProvider.baseUrl = basePath
        server.enqueue(MockResponse().setResponseCode(200))

        client.newCall(
            Request.Builder().url("http://placeholder.invalid/auth/login").build()
        ).execute()

        val recorded = server.takeRequest()
        assertEquals("/fintech/auth/login", recorded.path)
    }

    private class FakeEnvironmentUrlProvider(var baseUrl: String) : EnvironmentUrlProvider {
        override fun currentBaseUrl(): String = baseUrl
    }
}
