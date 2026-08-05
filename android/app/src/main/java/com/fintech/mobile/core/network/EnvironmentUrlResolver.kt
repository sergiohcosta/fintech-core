package com.fintech.mobile.core.network

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

// Hosts confirmados em homelab-k8s/projects/fintech-core/overlays/{dev,hmg,prod}/
// {ingress.yaml,ingress-lan.yaml,configmap.yaml}. Os dois hosts (LAN e Tailscale) de cada
// ambiente apontam para o mesmo Nginx do frontend, que faz proxy reverso de /api/ e /auth/
// para o backend com os mesmos paths — trocar de rota nunca muda o path da chamada.
object EnvironmentUrlResolver {

    fun resolveBaseUrl(environment: Environment, route: NetworkRoute, customLocalUrl: String?): String =
        when (environment) {
            Environment.LOCAL -> resolveLocalUrl(customLocalUrl)
            Environment.DEV -> hostFor("fintech-core-dev", route)
            Environment.HMG -> hostFor("fintech-core-hmg", route)
            Environment.PROD -> hostFor("fintech-core", route)
        }

    private fun hostFor(subdomain: String, route: NetworkRoute): String = when (route) {
        NetworkRoute.LAN -> "http://$subdomain.kafofao/"
        NetworkRoute.TAILSCALE -> "https://$subdomain.atlas-haddock.ts.net/"
    }

    private fun resolveLocalUrl(customLocalUrl: String?): String {
        val trimmed = customLocalUrl?.trim()
        if (trimmed.isNullOrBlank()) return DEFAULT_LOCAL_URL
        val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "http://$trimmed"
        }
        val normalized = if (withScheme.endsWith("/")) withScheme else "$withScheme/"
        // Validação defensiva: o usuário digita essa URL num campo de texto livre (device
        // físico, sem autocomplete de host). Prefixar scheme/sufixar barra não garante URL
        // válida ("http://", "abc def", porta fora de 0-65535 continuam passando). Sem este
        // guard, EnvironmentInterceptor usaria toHttpUrl() (não-nulo) sobre essa string e o
        // IllegalArgumentException do OkHttp escaparia pra fora do try/catch do apiCall,
        // matando o processo (crash real, não só erro de rede na UI).
        return if (normalized.toHttpUrlOrNull() != null) normalized else DEFAULT_LOCAL_URL
    }

    private const val DEFAULT_LOCAL_URL = "http://10.0.2.2:8080/"
}
