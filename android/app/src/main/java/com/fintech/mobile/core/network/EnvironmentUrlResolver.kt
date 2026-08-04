package com.fintech.mobile.core.network

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
        return if (withScheme.endsWith("/")) withScheme else "$withScheme/"
    }

    private const val DEFAULT_LOCAL_URL = "http://10.0.2.2:8080/"
}
