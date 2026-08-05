package com.fintech.mobile.session

import com.fintech.mobile.data.EnvironmentUrlProvider
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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
        val original = chain.request()
        // toHttpUrlOrNull em vez de toHttpUrl: EnvironmentUrlResolver já valida a URL
        // customizável antes de persistir (defesa de 1ª camada), mas nunca confiar só
        // numa camada quando o custo do erro é IllegalArgumentException dentro do
        // dispatcher do OkHttp — isso mata o processo (chega ao UncaughtExceptionHandler
        // padrão do Android), não vira exceção capturável no caller do Retrofit suspend.
        val target = environmentUrlProvider.currentBaseUrl().toHttpUrlOrNull()
            ?: return chain.proceed(original)
        val rewritten = original.url.newBuilder()
            .scheme(target.scheme)
            .host(target.host)
            .port(target.port)
            // Preserva o path do host resolvido (subpath da URL customizável de Local,
            // ex: "/fintech") na frente do path original da chamada — os 6 hosts fixos
            // sempre resolvem pra "/" (raiz), então trimEnd('/') + path não muda o
            // comportamento deles.
            .encodedPath(target.encodedPath.trimEnd('/') + original.url.encodedPath)
            .build()
        return chain.proceed(original.newBuilder().url(rewritten).build())
    }
}
