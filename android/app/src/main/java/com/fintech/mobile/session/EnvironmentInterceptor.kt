package com.fintech.mobile.session

import com.fintech.mobile.data.EnvironmentUrlProvider
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject

// Reescreve scheme/host/porta de cada requisição com base na seleção atual de ambiente —
// o Retrofit é construído uma única vez (Hilt singleton) com uma baseUrl placeholder
// (ver NetworkModule); é este interceptor que decide o destino real por requisição, sem
// precisar recriar o grafo de DI ao trocar de ambiente na tela de Login.
class EnvironmentInterceptor @Inject constructor(
    private val environmentUrlProvider: EnvironmentUrlProvider
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val target = environmentUrlProvider.currentBaseUrl().toHttpUrl()
        val original = chain.request()
        val rewritten = original.url.newBuilder()
            .scheme(target.scheme)
            .host(target.host)
            .port(target.port)
            .build()
        return chain.proceed(original.newBuilder().url(rewritten).build())
    }
}
