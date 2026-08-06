# App Android — Seletor de Ambiente (Local/Dev/Hmg/Prod + LAN/Tailscale) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Permitir que o app Android escolha em runtime, na tela de Login, entre 4 ambientes de backend (Local/Dev/Hmg/Prod) e, para os 3 remotos, entre 2 rotas de rede (LAN via `.kafofao` ou Tailscale via `.atlas-haddock.ts.net`) — substituindo o `BASE_URL` hardcoded (`http://10.0.2.2:8080/`) por uma resolução dinâmica.

**Architecture:** Um `OkHttp Interceptor` (`EnvironmentInterceptor`) reescreve scheme/host/porta de cada requisição em runtime, lendo a seleção atual de `EnvironmentPreferences` (SharedPreferences simples, não criptografado). `Retrofit`/`OkHttpClient` continuam Hilt-singletons — nenhuma reconstrução do grafo de DI ao trocar de ambiente. A seleção fica na `LoginScreen`, com um `EnvironmentViewModel` próprio (mesmo padrão do `SessionViewModel` já existente), sem tocar em `LoginViewModel`/`LoginUiState`.

**Tech Stack:** Kotlin, Jetpack Compose + Material3 (`ExposedDropdownMenuBox`, mesmo padrão do seletor de Conta), Hilt, OkHttp `Interceptor`, `SharedPreferences`, JUnit4 + Robolectric + MockWebServer para testes JVM.

Spec: `docs/superpowers/specs/2026-08-04-android-seletor-de-ambiente-design.md`

## Global Constraints

- Projeto existente em `android/` (não cria módulo novo). Todos os arquivos novos seguem o pacote raiz `com.fintech.mobile`.
- Seleção de ambiente só na `LoginScreen`, antes de autenticar — trocar de ambiente estando logado não é suportado nesta versão (não há botão de logout no app).
- O app **não liga/desliga a VPN Tailscale** — isso é responsabilidade do app Tailscale do Android, controlado pelo usuário fora deste app. Rota errada (Tailscale sem VPN ativa, LAN fora de casa) produz erro de rede comum (`NetworkError`), sem tratamento especial.
- Os paths do Retrofit (`/auth/login`, `/api/transactions`, ...) não mudam — só o host/scheme/porta são reescritos pelo `EnvironmentInterceptor`. O Nginx dos ambientes remotos já faz proxy reverso de `/api/`+`/auth/` para o backend com os mesmos paths.
- Hosts reais (confirmados em `homelab-k8s/projects/fintech-core/overlays/{dev,hmg,prod}/{ingress.yaml,ingress-lan.yaml,configmap.yaml}`):
  - Dev — LAN: `http://fintech-core-dev.kafofao/` · Tailscale: `https://fintech-core-dev.atlas-haddock.ts.net/`
  - Hmg — LAN: `http://fintech-core-hmg.kafofao/` · Tailscale: `https://fintech-core-hmg.atlas-haddock.ts.net/`
  - Prod — LAN: `http://fintech-core.kafofao/` · Tailscale: `https://fintech-core.atlas-haddock.ts.net/`
  - Local — sem host fixo: emulador usa `http://10.0.2.2:8080/` por padrão; usuário pode digitar uma URL customizada (ex.: IP do dispositivo físico na rede local).
- Cleartext HTTP liberado globalmente em `network_security_config.xml` (substitui a exceção pontual atual, só para `10.0.2.2`) — justificado porque o app não tem build de release/distribuição nesta v1, e a URL customizável de Local não pode ser whitelisted em compile-time.
- Persistência da seleção de ambiente é `SharedPreferences` simples (não criptografado — não é dado sensível), separado do `SessionManager` (que continua `EncryptedSharedPreferences`, só para o JWT).
- Nenhum teste unitário automatizado para os Composables (`EnvironmentSelector`) — convenção já usada no resto do app (UI Compose validada manualmente).

---

### Task 1: `Environment`/`NetworkRoute` + `EnvironmentUrlResolver` (função pura)

**Files:**
- Create: `android/app/src/main/java/com/fintech/mobile/core/network/Environment.kt`
- Create: `android/app/src/main/java/com/fintech/mobile/core/network/EnvironmentUrlResolver.kt`
- Test: `android/app/src/test/java/com/fintech/mobile/core/network/EnvironmentUrlResolverTest.kt`

**Interfaces:**
- Consumes: nada (lógica pura).
- Produces: `enum class Environment { LOCAL, DEV, HMG, PROD }`, `enum class NetworkRoute { LAN, TAILSCALE }`, `object EnvironmentUrlResolver { fun resolveBaseUrl(environment: Environment, route: NetworkRoute, customLocalUrl: String?): String }` — usado por `EnvironmentPreferences` (Tarefa 2).

- [ ] **Step 1: Escrever o teste**

`android/app/src/test/java/com/fintech/mobile/core/network/EnvironmentUrlResolverTest.kt`:
```kotlin
package com.fintech.mobile.core.network

import org.junit.Test
import kotlin.test.assertEquals

class EnvironmentUrlResolverTest {

    @Test
    fun `dev over LAN resolves to the kafofao host`() {
        val result = EnvironmentUrlResolver.resolveBaseUrl(Environment.DEV, NetworkRoute.LAN, null)
        assertEquals("http://fintech-core-dev.kafofao/", result)
    }

    @Test
    fun `dev over Tailscale resolves to the ts-net host`() {
        val result = EnvironmentUrlResolver.resolveBaseUrl(Environment.DEV, NetworkRoute.TAILSCALE, null)
        assertEquals("https://fintech-core-dev.atlas-haddock.ts.net/", result)
    }

    @Test
    fun `hmg over LAN resolves to the kafofao host`() {
        val result = EnvironmentUrlResolver.resolveBaseUrl(Environment.HMG, NetworkRoute.LAN, null)
        assertEquals("http://fintech-core-hmg.kafofao/", result)
    }

    @Test
    fun `hmg over Tailscale resolves to the ts-net host`() {
        val result = EnvironmentUrlResolver.resolveBaseUrl(Environment.HMG, NetworkRoute.TAILSCALE, null)
        assertEquals("https://fintech-core-hmg.atlas-haddock.ts.net/", result)
    }

    @Test
    fun `prod over LAN resolves to the kafofao host without a suffix`() {
        val result = EnvironmentUrlResolver.resolveBaseUrl(Environment.PROD, NetworkRoute.LAN, null)
        assertEquals("http://fintech-core.kafofao/", result)
    }

    @Test
    fun `prod over Tailscale resolves to the ts-net host without a suffix`() {
        val result = EnvironmentUrlResolver.resolveBaseUrl(Environment.PROD, NetworkRoute.TAILSCALE, null)
        assertEquals("https://fintech-core.atlas-haddock.ts.net/", result)
    }

    @Test
    fun `local without a custom URL defaults to the emulator alias`() {
        val result = EnvironmentUrlResolver.resolveBaseUrl(Environment.LOCAL, NetworkRoute.LAN, null)
        assertEquals("http://10.0.2.2:8080/", result)
    }

    @Test
    fun `local with a blank custom URL defaults to the emulator alias`() {
        val result = EnvironmentUrlResolver.resolveBaseUrl(Environment.LOCAL, NetworkRoute.LAN, "   ")
        assertEquals("http://10.0.2.2:8080/", result)
    }

    @Test
    fun `local with a custom URL missing scheme and trailing slash is normalized`() {
        val result = EnvironmentUrlResolver.resolveBaseUrl(Environment.LOCAL, NetworkRoute.LAN, "192.168.1.50:8080")
        assertEquals("http://192.168.1.50:8080/", result)
    }

    @Test
    fun `local with a custom URL already well formed is unchanged`() {
        val result = EnvironmentUrlResolver.resolveBaseUrl(Environment.LOCAL, NetworkRoute.LAN, "https://192.168.1.50:8443/")
        assertEquals("https://192.168.1.50:8443/", result)
    }

    @Test
    fun `local ignores the route parameter`() {
        val lan = EnvironmentUrlResolver.resolveBaseUrl(Environment.LOCAL, NetworkRoute.LAN, "192.168.1.50:8080")
        val tailscale = EnvironmentUrlResolver.resolveBaseUrl(Environment.LOCAL, NetworkRoute.TAILSCALE, "192.168.1.50:8080")
        assertEquals(lan, tailscale)
    }
}
```

- [ ] **Step 2: Rodar o teste e confirmar que falha**

```bash
cd android && ./gradlew :app:testDebugUnitTest --tests "com.fintech.mobile.core.network.EnvironmentUrlResolverTest"
```

Expected: FAIL — `Unresolved reference: Environment` / `NetworkRoute` / `EnvironmentUrlResolver`.

- [ ] **Step 3: Implementar `Environment`/`NetworkRoute` e `EnvironmentUrlResolver`**

`android/app/src/main/java/com/fintech/mobile/core/network/Environment.kt`:
```kotlin
package com.fintech.mobile.core.network

enum class Environment { LOCAL, DEV, HMG, PROD }

enum class NetworkRoute { LAN, TAILSCALE }
```

`android/app/src/main/java/com/fintech/mobile/core/network/EnvironmentUrlResolver.kt`:
```kotlin
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
```

- [ ] **Step 4: Rodar o teste de novo e confirmar que passa**

```bash
cd android && ./gradlew :app:testDebugUnitTest --tests "com.fintech.mobile.core.network.EnvironmentUrlResolverTest"
```

Expected: PASS (11 testes).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/fintech/mobile/core/network/Environment.kt android/app/src/main/java/com/fintech/mobile/core/network/EnvironmentUrlResolver.kt android/app/src/test/java/com/fintech/mobile/core/network/EnvironmentUrlResolverTest.kt
git commit -m "feat(android): resolvedor puro de URL por ambiente e rota de rede"
```

---

### Task 2: `EnvironmentUrlProvider` + `EnvironmentPreferences`

**Files:**
- Create: `android/app/src/main/java/com/fintech/mobile/data/EnvironmentUrlProvider.kt`
- Create: `android/app/src/main/java/com/fintech/mobile/data/EnvironmentPreferences.kt`
- Test: `android/app/src/test/java/com/fintech/mobile/data/EnvironmentPreferencesTest.kt`

**Interfaces:**
- Consumes: `Environment`, `NetworkRoute`, `EnvironmentUrlResolver.resolveBaseUrl(...)` (Tarefa 1).
- Produces: `interface EnvironmentUrlProvider { fun currentBaseUrl(): String }`; `class EnvironmentPreferences` (implementa `EnvironmentUrlProvider`, expõe `environment: StateFlow<Environment>`, `route: StateFlow<NetworkRoute>`, `customLocalUrl: StateFlow<String?>`, e `setEnvironment`/`setRoute`/`setCustomLocalUrl`) — usado por `EnvironmentInterceptor` (Tarefa 3) e `EnvironmentViewModel` (Tarefa 6).

- [ ] **Step 1: Escrever o teste (Robolectric — `SharedPreferences` precisa de `Context` Android)**

`android/app/src/test/java/com/fintech/mobile/data/EnvironmentPreferencesTest.kt`:
```kotlin
package com.fintech.mobile.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.fintech.mobile.core.network.Environment
import com.fintech.mobile.core.network.NetworkRoute
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EnvironmentPreferencesTest {

    private fun newPreferences(): EnvironmentPreferences {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return EnvironmentPreferences(context)
    }

    @Test
    fun `defaults to LOCAL, LAN and no custom URL`() {
        val preferences = newPreferences()

        assertEquals(Environment.LOCAL, preferences.environment.value)
        assertEquals(NetworkRoute.LAN, preferences.route.value)
        assertNull(preferences.customLocalUrl.value)
    }

    @Test
    fun `setEnvironment updates the StateFlow and survives a new instance`() {
        val preferences = newPreferences()

        preferences.setEnvironment(Environment.HMG)

        assertEquals(Environment.HMG, preferences.environment.value)
        assertEquals(Environment.HMG, newPreferences().environment.value)
    }

    @Test
    fun `setRoute updates the StateFlow and survives a new instance`() {
        val preferences = newPreferences()

        preferences.setRoute(NetworkRoute.TAILSCALE)

        assertEquals(NetworkRoute.TAILSCALE, preferences.route.value)
        assertEquals(NetworkRoute.TAILSCALE, newPreferences().route.value)
    }

    @Test
    fun `setCustomLocalUrl updates the StateFlow and survives a new instance`() {
        val preferences = newPreferences()

        preferences.setCustomLocalUrl("192.168.1.50:8080")

        assertEquals("192.168.1.50:8080", preferences.customLocalUrl.value)
        assertEquals("192.168.1.50:8080", newPreferences().customLocalUrl.value)
    }

    @Test
    fun `currentBaseUrl delegates to EnvironmentUrlResolver with the stored selection`() {
        val preferences = newPreferences()
        preferences.setEnvironment(Environment.DEV)
        preferences.setRoute(NetworkRoute.TAILSCALE)

        assertEquals("https://fintech-core-dev.atlas-haddock.ts.net/", preferences.currentBaseUrl())
    }
}
```

- [ ] **Step 2: Rodar o teste e confirmar que falha**

```bash
cd android && ./gradlew :app:testDebugUnitTest --tests "com.fintech.mobile.data.EnvironmentPreferencesTest"
```

Expected: FAIL — `Unresolved reference: EnvironmentPreferences`.

- [ ] **Step 3: Implementar `EnvironmentUrlProvider` e `EnvironmentPreferences`**

`android/app/src/main/java/com/fintech/mobile/data/EnvironmentUrlProvider.kt`:
```kotlin
package com.fintech.mobile.data

interface EnvironmentUrlProvider {
    fun currentBaseUrl(): String
}
```

`android/app/src/main/java/com/fintech/mobile/data/EnvironmentPreferences.kt`:
```kotlin
package com.fintech.mobile.data

import android.content.Context
import com.fintech.mobile.core.network.Environment
import com.fintech.mobile.core.network.EnvironmentUrlResolver
import com.fintech.mobile.core.network.NetworkRoute
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

// SharedPreferences simples (não criptografado) — ao contrário do JWT no SessionManager,
// ambiente/rota/URL local não são dado sensível. Mesmo padrão de StateFlow-em-memória
// espelhando o disco usado pelo SessionManager, para leitura síncrona pelo
// EnvironmentInterceptor (que roda fora do main thread, sem acesso a coroutines).
@Singleton
class EnvironmentPreferences @Inject constructor(
    @ApplicationContext context: Context
) : EnvironmentUrlProvider {

    private val prefs = context.getSharedPreferences(PREFS_FILE_NAME, Context.MODE_PRIVATE)

    private val _environment = MutableStateFlow(readEnum(KEY_ENVIRONMENT, Environment.LOCAL))
    val environment: StateFlow<Environment> = _environment.asStateFlow()

    private val _route = MutableStateFlow(readEnum(KEY_ROUTE, NetworkRoute.LAN))
    val route: StateFlow<NetworkRoute> = _route.asStateFlow()

    private val _customLocalUrl = MutableStateFlow(prefs.getString(KEY_CUSTOM_LOCAL_URL, null))
    val customLocalUrl: StateFlow<String?> = _customLocalUrl.asStateFlow()

    fun setEnvironment(value: Environment) {
        prefs.edit().putString(KEY_ENVIRONMENT, value.name).apply()
        _environment.value = value
    }

    fun setRoute(value: NetworkRoute) {
        prefs.edit().putString(KEY_ROUTE, value.name).apply()
        _route.value = value
    }

    fun setCustomLocalUrl(value: String?) {
        prefs.edit().putString(KEY_CUSTOM_LOCAL_URL, value).apply()
        _customLocalUrl.value = value
    }

    override fun currentBaseUrl(): String =
        EnvironmentUrlResolver.resolveBaseUrl(_environment.value, _route.value, _customLocalUrl.value)

    private inline fun <reified T : Enum<T>> readEnum(key: String, default: T): T =
        prefs.getString(key, null)?.let { stored ->
            runCatching { enumValueOf<T>(stored) }.getOrNull()
        } ?: default

    private companion object {
        const val PREFS_FILE_NAME = "fintech_environment"
        const val KEY_ENVIRONMENT = "environment"
        const val KEY_ROUTE = "route"
        const val KEY_CUSTOM_LOCAL_URL = "custom_local_url"
    }
}
```

- [ ] **Step 4: Rodar o teste de novo e confirmar que passa**

```bash
cd android && ./gradlew :app:testDebugUnitTest --tests "com.fintech.mobile.data.EnvironmentPreferencesTest"
```

Expected: PASS (5 testes).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/fintech/mobile/data/EnvironmentUrlProvider.kt android/app/src/main/java/com/fintech/mobile/data/EnvironmentPreferences.kt android/app/src/test/java/com/fintech/mobile/data/EnvironmentPreferencesTest.kt
git commit -m "feat(android): persistência da seleção de ambiente (EnvironmentPreferences)"
```

---

### Task 3: `EnvironmentInterceptor`

**Files:**
- Create: `android/app/src/main/java/com/fintech/mobile/session/EnvironmentInterceptor.kt`
- Test: `android/app/src/test/java/com/fintech/mobile/session/EnvironmentInterceptorTest.kt`

**Interfaces:**
- Consumes: `EnvironmentUrlProvider` (Tarefa 2).
- Produces: `class EnvironmentInterceptor(private val environmentUrlProvider: EnvironmentUrlProvider) : Interceptor` — usado por `NetworkModule` (Tarefa 4).

- [ ] **Step 1: Escrever o teste (mesmo padrão do `AuthInterceptorTest` já existente)**

`android/app/src/test/java/com/fintech/mobile/session/EnvironmentInterceptorTest.kt`:
```kotlin
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
```

- [ ] **Step 2: Rodar o teste e confirmar que falha**

```bash
cd android && ./gradlew :app:testDebugUnitTest --tests "com.fintech.mobile.session.EnvironmentInterceptorTest"
```

Expected: FAIL — `Unresolved reference: EnvironmentInterceptor`.

- [ ] **Step 3: Implementar `EnvironmentInterceptor`**

`android/app/src/main/java/com/fintech/mobile/session/EnvironmentInterceptor.kt`:
```kotlin
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
```

- [ ] **Step 4: Rodar o teste de novo e confirmar que passa**

```bash
cd android && ./gradlew :app:testDebugUnitTest --tests "com.fintech.mobile.session.EnvironmentInterceptorTest"
```

Expected: PASS (3 testes).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/fintech/mobile/session/EnvironmentInterceptor.kt android/app/src/test/java/com/fintech/mobile/session/EnvironmentInterceptorTest.kt
git commit -m "feat(android): EnvironmentInterceptor reescreve host por requisição"
```

---

### Task 4: Conectar no grafo Hilt (`EnvironmentModule` + `NetworkModule`)

**Files:**
- Create: `android/app/src/main/java/com/fintech/mobile/di/EnvironmentModule.kt`
- Modify: `android/app/src/main/java/com/fintech/mobile/di/NetworkModule.kt`

**Interfaces:**
- Consumes: `EnvironmentPreferences`/`EnvironmentUrlProvider` (Tarefa 2), `EnvironmentInterceptor` (Tarefa 3).
- Produces: `EnvironmentUrlProvider` disponível via Hilt (igual ao `TokenProvider` do `SessionModule`); `OkHttpClient` passa a incluir o `EnvironmentInterceptor` antes do `AuthInterceptor` — usado a partir da Tarefa 6 (nenhuma mudança de assinatura pública além disso).

- [ ] **Step 1: Criar o módulo Hilt que expõe `EnvironmentPreferences` como `EnvironmentUrlProvider`**

`android/app/src/main/java/com/fintech/mobile/di/EnvironmentModule.kt`:
```kotlin
package com.fintech.mobile.di

import com.fintech.mobile.data.EnvironmentPreferences
import com.fintech.mobile.data.EnvironmentUrlProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class EnvironmentModule {

    @Binds
    @Singleton
    abstract fun bindEnvironmentUrlProvider(environmentPreferences: EnvironmentPreferences): EnvironmentUrlProvider
}
```

- [ ] **Step 2: Atualizar `NetworkModule` — remover o `BASE_URL` fixo e injetar o `EnvironmentInterceptor`**

Em `android/app/src/main/java/com/fintech/mobile/di/NetworkModule.kt`, substitua o conteúdo inteiro por:
```kotlin
package com.fintech.mobile.di

import com.fintech.mobile.api.AccountsApi
import com.fintech.mobile.api.AuthApi
import com.fintech.mobile.api.CategoriesApi
import com.fintech.mobile.api.TransactionsApi
import com.fintech.mobile.api.infrastructure.Serializer
import com.fintech.mobile.session.AuthInterceptor
import com.fintech.mobile.session.EnvironmentInterceptor
import com.google.gson.Gson
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import javax.inject.Singleton

// Nunca usada de fato — o EnvironmentInterceptor reescreve scheme/host/porta de toda
// requisição antes dela sair (ver EnvironmentInterceptor, Tarefa 3 do plano do seletor de
// ambiente). Só precisa ser sintaticamente uma URL válida para o Retrofit aceitar no build.
private const val PLACEHOLDER_BASE_URL = "http://placeholder.invalid/"

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    // O openapi-generator já gera `Serializer.gson` com TypeAdapters de LocalDate/LocalDateTime/
    // OffsetDateTime (ISO-8601, sem reflexão). Um `Gson()` puro serializa java.time.* refletindo
    // os campos internos (year/month/day) em vez de "2026-07-31" — quebra o contrato com o
    // backend e, em JDK 17+, lança InaccessibleObjectException (módulo java.base fechado).
    @Provides
    @Singleton
    fun provideGson(): Gson = Serializer.gson

    @Provides
    @Singleton
    fun provideOkHttpClient(
        environmentInterceptor: EnvironmentInterceptor,
        authInterceptor: AuthInterceptor
    ): OkHttpClient {
        // Level.BASIC nunca loga headers/body — evita vazar o JWT ou a senha no logcat.
        val logging = HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }
        return OkHttpClient.Builder()
            .addInterceptor(environmentInterceptor)
            .addInterceptor(authInterceptor)
            .addInterceptor(logging)
            .build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(client: OkHttpClient, gson: Gson): Retrofit =
        Retrofit.Builder()
            .baseUrl(PLACEHOLDER_BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()

    @Provides
    @Singleton
    fun provideAuthApi(retrofit: Retrofit): AuthApi = retrofit.create(AuthApi::class.java)

    @Provides
    @Singleton
    fun provideAccountsApi(retrofit: Retrofit): AccountsApi = retrofit.create(AccountsApi::class.java)

    @Provides
    @Singleton
    fun provideCategoriesApi(retrofit: Retrofit): CategoriesApi = retrofit.create(CategoriesApi::class.java)

    @Provides
    @Singleton
    fun provideTransactionsApi(retrofit: Retrofit): TransactionsApi = retrofit.create(TransactionsApi::class.java)
}
```

- [ ] **Step 3: Rodar toda a suíte e compilar**

```bash
cd android && ./gradlew :app:testDebugUnitTest :app:compileDebugKotlin
```

Expected: `BUILD SUCCESSFUL`, todos os testes anteriores (incluindo `AuthInterceptorTest`, `AuthRepositoryTest`, etc — nenhum deles depende do valor de `BASE_URL`, todos mockam `AuthApi`/etc diretamente) continuam passando.

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/java/com/fintech/mobile/di/EnvironmentModule.kt android/app/src/main/java/com/fintech/mobile/di/NetworkModule.kt
git commit -m "feat(android): conecta EnvironmentInterceptor no grafo Hilt, remove BASE_URL fixo"
```

---

### Task 5: Cleartext HTTP liberado globalmente

**Files:**
- Modify: `android/app/src/main/res/xml/network_security_config.xml`

**Interfaces:**
- Consumes: nada.
- Produces: nada (config de plataforma) — usado implicitamente por toda chamada HTTP do app a partir de agora.

- [ ] **Step 1: Substituir a exceção pontual pela liberação global**

Conteúdo completo de `android/app/src/main/res/xml/network_security_config.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- Cleartext (HTTP) liberado globalmente. Antes da Tarefa 5 do seletor de ambiente, só o
     host do emulador (10.0.2.2) tinha exceção; agora os hosts LAN (*.kafofao, ver
     EnvironmentUrlResolver) também são HTTP puro, e a URL customizável do ambiente Local em
     dispositivo físico não pode ser whitelisted em compile-time (o usuário digita o IP na
     hora). O app não tem build de release/distribuição nesta v1 — é ferramenta de uso
     pessoal/dev, então liberar globalmente é aceitável aqui (ver design doc
     docs/superpowers/specs/2026-08-04-android-seletor-de-ambiente-design.md, decisão d). -->
<network-security-config>
    <base-config cleartextTrafficPermitted="true" />
</network-security-config>
```

- [ ] **Step 2: Compilar e empacotar**

```bash
cd android && ./gradlew :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/res/xml/network_security_config.xml
git commit -m "feat(android): libera cleartext HTTP globalmente (hosts LAN e URL customizável)"
```

---

### Task 6: `EnvironmentViewModel` + `EnvironmentSelector` na tela de Login

**Files:**
- Create: `android/app/src/main/java/com/fintech/mobile/ui/login/EnvironmentViewModel.kt`
- Create: `android/app/src/main/java/com/fintech/mobile/ui/login/EnvironmentSelector.kt`
- Modify: `android/app/src/main/java/com/fintech/mobile/ui/login/LoginScreen.kt`

**Interfaces:**
- Consumes: `EnvironmentPreferences` (Tarefa 2), `Environment`/`NetworkRoute` (Tarefa 1).
- Produces: `EnvironmentSelector` (Composable, sem parâmetros — resolve seu próprio `EnvironmentViewModel` via `hiltViewModel()`) — usado só dentro de `LoginScreen`; nenhum consumidor futuro previsto no plano.

- [ ] **Step 1: Criar `EnvironmentViewModel`**

Mesmo padrão de delegação fina do `SessionViewModel` já existente (`ui/navigation/SessionViewModel.kt`) — sem teste dedicado, por convenção do projeto para ViewModels que só repassam `StateFlow`/setters de um repository já testado.

`android/app/src/main/java/com/fintech/mobile/ui/login/EnvironmentViewModel.kt`:
```kotlin
package com.fintech.mobile.ui.login

import androidx.lifecycle.ViewModel
import com.fintech.mobile.core.network.Environment
import com.fintech.mobile.core.network.NetworkRoute
import com.fintech.mobile.data.EnvironmentPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class EnvironmentViewModel @Inject constructor(
    private val environmentPreferences: EnvironmentPreferences
) : ViewModel() {

    val environment: StateFlow<Environment> = environmentPreferences.environment
    val route: StateFlow<NetworkRoute> = environmentPreferences.route
    val customLocalUrl: StateFlow<String?> = environmentPreferences.customLocalUrl

    fun onEnvironmentChange(value: Environment) = environmentPreferences.setEnvironment(value)
    fun onRouteChange(value: NetworkRoute) = environmentPreferences.setRoute(value)
    fun onCustomLocalUrlChange(value: String) = environmentPreferences.setCustomLocalUrl(value)
}
```

- [ ] **Step 2: Criar o Composable `EnvironmentSelector`**

Mesmo padrão de `ExposedDropdownMenuBox` já usado no seletor de Conta (`ui/newtransaction/NewTransactionScreen.kt`) — incluindo o `Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable)`, sem o qual o dropdown não abre (bug real já corrigido uma vez nesse componente, Tarefa 14 do plano anterior).

`android/app/src/main/java/com/fintech/mobile/ui/login/EnvironmentSelector.kt`:
```kotlin
package com.fintech.mobile.ui.login

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.fintech.mobile.core.network.Environment
import com.fintech.mobile.core.network.NetworkRoute

private fun environmentLabel(environment: Environment): String = when (environment) {
    Environment.LOCAL -> "Local"
    Environment.DEV -> "Dev"
    Environment.HMG -> "Hmg"
    Environment.PROD -> "Prod"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnvironmentSelector(viewModel: EnvironmentViewModel = hiltViewModel()) {
    val environment by viewModel.environment.collectAsState()
    val route by viewModel.route.collectAsState()
    val customLocalUrl by viewModel.customLocalUrl.collectAsState()

    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        var environmentMenuExpanded by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(
            expanded = environmentMenuExpanded,
            onExpandedChange = { environmentMenuExpanded = it }
        ) {
            OutlinedTextField(
                value = environmentLabel(environment),
                onValueChange = {},
                readOnly = true,
                label = { Text("Ambiente") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = environmentMenuExpanded) },
                modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable)
            )
            ExposedDropdownMenu(
                expanded = environmentMenuExpanded,
                onDismissRequest = { environmentMenuExpanded = false }
            ) {
                Environment.entries.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(environmentLabel(option)) },
                        onClick = {
                            viewModel.onEnvironmentChange(option)
                            environmentMenuExpanded = false
                        }
                    )
                }
            }
        }

        if (environment == Environment.LOCAL) {
            OutlinedTextField(
                value = customLocalUrl ?: "",
                onValueChange = viewModel::onCustomLocalUrlChange,
                label = { Text("URL local (opcional — padrão: emulador)") },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )
        } else {
            Row(modifier = Modifier.padding(top = 8.dp)) {
                FilterChip(
                    selected = route == NetworkRoute.LAN,
                    onClick = { viewModel.onRouteChange(NetworkRoute.LAN) },
                    label = { Text("LAN") }
                )
                FilterChip(
                    selected = route == NetworkRoute.TAILSCALE,
                    onClick = { viewModel.onRouteChange(NetworkRoute.TAILSCALE) },
                    label = { Text("Tailscale") },
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }
    }
}
```

- [ ] **Step 3: Incorporar o `EnvironmentSelector` na `LoginScreen`**

Substitua o conteúdo inteiro de `android/app/src/main/java/com/fintech/mobile/ui/login/LoginScreen.kt` por (única mudança em relação ao arquivo atual: adiciona a chamada a `EnvironmentSelector()` como primeiro filho da `Column`):
```kotlin
package com.fintech.mobile.ui.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun LoginScreen(
    onLoginSuccess: () -> Unit,
    viewModel: LoginViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    LaunchedEffect(uiState) {
        if (uiState is LoginUiState.Success) onLoginSuccess()
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        EnvironmentSelector()

        OutlinedTextField(value = email, onValueChange = { email = it }, label = { Text("E-mail") })
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Senha") },
            visualTransformation = PasswordVisualTransformation()
        )
        Button(onClick = { viewModel.login(email, password) }) {
            Text("Entrar")
        }
        when (val state = uiState) {
            is LoginUiState.Loading -> CircularProgressIndicator()
            is LoginUiState.Error -> Text(state.message)
            else -> Unit
        }
    }
}
```

(`EnvironmentSelector` está no mesmo pacote `com.fintech.mobile.ui.login`, não precisa de import adicional.)

- [ ] **Step 4: Rodar toda a suíte e compilar**

```bash
cd android && ./gradlew :app:testDebugUnitTest :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`. `LoginViewModelTest` continua passando sem alteração (nenhuma mudança em `LoginViewModel`/`LoginUiState`).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/fintech/mobile/ui/login/EnvironmentViewModel.kt android/app/src/main/java/com/fintech/mobile/ui/login/EnvironmentSelector.kt android/app/src/main/java/com/fintech/mobile/ui/login/LoginScreen.kt
git commit -m "feat(android): seletor de ambiente (Local/Dev/Hmg/Prod + LAN/Tailscale) na tela de Login"
```

---

### Task 7: Checklist manual de QA (emulador + rede local, se disponível)

**Files:**
- Nenhum arquivo de código — verificação manual.

**Interfaces:**
- Consumes: app completo (Tarefas 1–6).
- Produces: confirmação de que a troca de ambiente funciona de ponta a ponta antes de considerar a feature pronta.

- [ ] **Step 1: Instalar e abrir o app no emulador**

```bash
cd android && ./gradlew :app:installDebug
```

- [ ] **Step 2: Testar o default (Local, sem customização)**

Abra o app — `EnvironmentSelector` deve mostrar "Ambiente: Local" e o campo de URL vazio. Faça login com `carlos@costa.com` / `costa123` contra o backend local (`docker compose up -d` + `./mvnw spring-boot:run`, como no checklist da Tarefa 14 do plano anterior).
Expected: login funciona exatamente como antes desta feature (`http://10.0.2.2:8080/` continua sendo o default).

- [ ] **Step 3: Testar a troca de ambiente sem submeter**

Troque o dropdown para "Dev", depois "Hmg", depois "Prod" — confirme que o toggle LAN/Tailscale aparece (Local não mostra o toggle, mostra o campo de texto). Volte para "Local".
Expected: nenhum crash, UI reage a cada troca.

- [ ] **Step 4: Testar contra um ambiente remoto real, se você tiver acesso à rede do homelab**

Se você estiver na rede local de casa: selecione "Dev" + "LAN", tente logar com uma credencial válida daquele ambiente.
Se você tiver o Tailscale ativo no dispositivo/emulador: selecione "Dev" + "Tailscale", tente logar.
Expected: login bem-sucedido navega para a lista de transações, como qualquer outro ambiente. Se a rede/rota escolhida não for alcançável, a tela mostra "Sem conexão. Tente novamente." (nenhum crash).

- [ ] **Step 5: Testar a URL customizável de Local**

Suba o backend local e descubra o IP da máquina na rede local (`ip addr` ou similar). No emulador, troque "Ambiente" para "Local" e digite `<seu-ip>:8080` no campo de URL. Faça login.
Expected: login funciona via essa URL customizada (prova que a normalização de scheme/porta/barra funciona).

- [ ] **Step 6: Registrar o resultado**

Se algum passo falhar, esta tarefa **não** está concluída — volte à tarefa de código correspondente antes de prosseguir. Sem commit nesta tarefa (nenhum arquivo alterado).

---
