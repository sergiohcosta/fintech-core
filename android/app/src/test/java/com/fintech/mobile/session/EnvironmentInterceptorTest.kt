package com.fintech.mobile.session

import com.fintech.mobile.data.EnvironmentUrlProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
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

    private class FakeEnvironmentUrlProvider(var baseUrl: String) : EnvironmentUrlProvider {
        override fun currentBaseUrl(): String = baseUrl
    }
}
