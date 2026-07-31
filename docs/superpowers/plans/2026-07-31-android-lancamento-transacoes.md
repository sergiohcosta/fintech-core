# App Android — Lançamento de Transações — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Primeiro cliente Android do fintech-core — login, lançamento de transação (com parcelamento de cartão) com fila offline, e lista de leitura das últimas transações.

**Architecture:** MVVM + Repository (Compose/ViewModel → Repository → Retrofit/Room), Hilt para DI. Cliente HTTP gerado via `openapi-generator` (Kotlin/`jvm-retrofit2`) a partir de `api-spec/openapi.yaml` — mesma fonte de contrato do backend e do frontend web. Outbox local (Room) + `WorkManager` cobre lançamento offline, sem sincronização bidirecional (o app só cria transações).

**Tech Stack:** Kotlin, Jetpack Compose + Material3, Hilt, Retrofit + OkHttp + Gson, Room, WorkManager (`androidx.hilt:hilt-work`), MockK + JUnit4 + Robolectric + `kotlinx-coroutines-test` para testes JVM.

Spec: `docs/superpowers/specs/2026-07-31-android-lancamento-transacoes-design.md`

## Global Constraints

- Projeto novo em `android/` (raiz do repo, ao lado de `backend/` e `frontend/`). Package raiz `com.fintech.mobile`.
- Pré-requisito de toolchain (não existe hoje no projeto): JDK 17, Android SDK (`compileSdk 35`, `minSdk 26`, `targetSdk 35`) — via Android Studio ou `cmdline-tools`.
- Stack: Kotlin + Jetpack Compose (spec decisão a). XML Views está descartado.
- Arquitetura: MVVM + Repository, camadas `ui/ → data/repository/ → data/{remote,local}/`, Hilt para DI (spec decisão b). Sem MVI, sem acesso direto de ViewModel a Retrofit/Room.
- Cliente HTTP: codegen via `openapi-generator` (gerador `kotlin`, biblioteca `jvm-retrofit2`) a partir de `api-spec/openapi.yaml` — **nenhum DTO escrito à mão** (spec decisão c).
- Sincronização offline: outbox local (Room) + `WorkManager`, cobre só `create` — **sem** sync bidirecional nem resolução de conflito (spec decisão d).
- Sessão: JWT em armazenamento seguro (`EncryptedSharedPreferences`), **sem** refresh token — o backend não tem esse fluxo (spec decisão e).
- Ambiente: aponta para `http://10.0.2.2:8080/` (backend local via emulador) nesta v1 — sem build variants dev/prod (spec decisão f).
- Fora de escopo (não implementar nesta versão): editar/excluir transação, telas de conta/categoria/fatura/orçamento/recorrência, refresh token, biometria, notificações push, dark mode, multi-tenant no mesmo device, testes instrumentados de UI (Compose UI test).
- `Level.BASIC` em qualquer `HttpLoggingInterceptor` — nunca logar headers/body (evita vazar JWT ou senha no logcat, mesma regra do CLAUDE.md do backend).
- Nunca commitar segredos; `BASE_URL` aponta só para localhost nesta versão, não há credencial embutida no app.

---

### Task 1: Scaffolding do projeto Android

**Files:**
- Create: `android/settings.gradle.kts`
- Create: `android/build.gradle.kts`
- Create: `android/gradle.properties`
- Create: `android/app/build.gradle.kts`
- Create: `android/app/src/main/AndroidManifest.xml`
- Create: `android/app/src/main/res/values/strings.xml`
- Create: `android/app/src/main/res/values/themes.xml`
- Create: `android/app/src/main/java/com/fintech/mobile/MobileApp.kt`
- Create: `android/app/src/main/java/com/fintech/mobile/MainActivity.kt`
- Modify: `.gitignore` (raiz do repo)

**Interfaces:**
- Consumes: nada (primeira tarefa).
- Produces: módulo Gradle `android/app` compilável, `MobileApp` (`@HiltAndroidApp`, `Application`), `MainActivity` (`@AndroidEntryPoint`, `ComponentActivity`) — base para todas as tarefas seguintes.

- [ ] **Step 1: Criar o `settings.gradle.kts` e o `build.gradle.kts` raiz**

`android/settings.gradle.kts`:
```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "fintech-mobile"
include(":app")
```

`android/build.gradle.kts`:
```kotlin
plugins {
    id("com.android.application") version "8.7.0" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("com.google.dagger.hilt.android") version "2.52" apply false
    id("com.google.devtools.ksp") version "2.0.21-1.0.28" apply false
    id("org.openapi.generator") version "7.4.0" apply false
}
```

`android/gradle.properties`:
```properties
android.useAndroidX=true
android.nonTransitiveRClass=true
kotlin.code.style=official
```

- [ ] **Step 2: Criar `android/app/build.gradle.kts`**

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.fintech.mobile"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.fintech.mobile"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.01.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    implementation("com.google.dagger:hilt-android:2.52")
    ksp("com.google.dagger:hilt-android-compiler:2.52")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.hilt:hilt-work:1.2.0")
    ksp("androidx.hilt:hilt-compiler:1.2.0")

    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    testImplementation("junit:junit:4.13.2")
    testImplementation(kotlin("test"))
    testImplementation("io.mockk:mockk:1.13.13")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.work:work-testing:2.10.0")
}
```

- [ ] **Step 3: Criar o manifesto e recursos mínimos**

`android/app/src/main/AndroidManifest.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />

    <application
        android:name=".MobileApp"
        android:allowBackup="false"
        android:icon="@android:drawable/sym_def_app_icon"
        android:label="@string/app_name"
        android:theme="@style/Theme.FintechMobile">

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:theme="@style/Theme.FintechMobile">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

`android/app/src/main/res/values/strings.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">Fintech Mobile</string>
</resources>
```

`android/app/src/main/res/values/themes.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="Theme.FintechMobile" parent="android:Theme.Material.Light.NoActionBar" />
</resources>
```

- [ ] **Step 4: Criar `MobileApp` e `MainActivity`**

`android/app/src/main/java/com/fintech/mobile/MobileApp.kt`:
```kotlin
package com.fintech.mobile

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class MobileApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
```

`android/app/src/main/java/com/fintech/mobile/MainActivity.kt`:
```kotlin
package com.fintech.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    PlaceholderScreen()
                }
            }
        }
    }
}

@Composable
private fun PlaceholderScreen() {
    Box(modifier = Modifier.fillMaxSize()) {
        Text("Fintech Mobile")
    }
}
```

(`PlaceholderScreen` e o `setContent` serão substituídos pelo `AppNavHost` na Tarefa 13.)

- [ ] **Step 5: Gerar o Gradle Wrapper**

Rode (requer um Gradle instalado no sistema só para este comando único; depois disso, use sempre `./gradlew`):

```bash
cd android
gradle wrapper --gradle-version 8.10.2
```

Expected: cria `android/gradlew`, `android/gradlew.bat`, `android/gradle/wrapper/gradle-wrapper.jar` e `gradle-wrapper.properties`.

- [ ] **Step 6: Ajustar o `.gitignore` da raiz para o módulo Android**

O `.gitignore` da raiz já tem uma regra genérica `*.jar` — sem exceção, isso ignoraria o `gradle-wrapper.jar` (que **precisa** ser versionado para o wrapper funcionar sem Gradle instalado). Adicione ao final de `.gitignore`:

```gitignore
# Android
android/local.properties
android/.gradle/
android/**/build/
android/.idea/
android/*.iml
android/captures/
!android/gradle/wrapper/gradle-wrapper.jar
```

- [ ] **Step 7: Rodar o build e verificar**

```bash
cd android && ./gradlew :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 8: Commit**

```bash
git add android/ .gitignore
git commit -m "feat(android): scaffolding do projeto Android (Compose + Hilt)"
```

---

### Task 2: Codegen do cliente OpenAPI (Kotlin/Retrofit)

**Files:**
- Modify: `android/app/build.gradle.kts`

**Interfaces:**
- Consumes: `api-spec/openapi.yaml` (contrato existente, não modificado).
- Produces (gerado em `android/app/build/generated/openapi/...`, não versionado): interfaces Retrofit `com.fintech.mobile.api.AuthApi`, `com.fintech.mobile.api.AccountsApi`, `com.fintech.mobile.api.CategoriesApi`, `com.fintech.mobile.api.TransactionsApi` (suspend functions); modelos `com.fintech.mobile.api.model.{LoginDTO, LoginResponseDTO, TransactionRequestDTO, TransactionResponseDTO, AccountResponse, CategoryResponseDTO, TransactionType, TransactionStatus, AccountType}`.

- [ ] **Step 1: Adicionar o plugin e a configuração do gerador**

Em `android/app/build.gradle.kts`, adicionar ao bloco `plugins`:
```kotlin
    id("org.openapi.generator")
```

E, após o bloco `dependencies`, adicionar:
```kotlin
openApiGenerate {
    generatorName.set("kotlin")
    library.set("jvm-retrofit2")
    inputSpec.set("${rootDir}/../api-spec/openapi.yaml")
    outputDir.set("${layout.buildDirectory.get()}/generated/openapi")
    packageName.set("com.fintech.mobile.api")
    apiPackage.set("com.fintech.mobile.api")
    modelPackage.set("com.fintech.mobile.api.model")
    configOptions.set(
        mapOf(
            "useCoroutines" to "true",
            "dateLibrary" to "java8",
            "serializationLibrary" to "gson"
        )
    )
}

android {
    sourceSets {
        getByName("main") {
            kotlin.srcDir("${layout.buildDirectory.get()}/generated/openapi/src/main/kotlin")
        }
    }
}

tasks.named("preBuild") {
    dependsOn("openApiGenerate")
}
```

- [ ] **Step 2: Rodar a geração e confirmar as assinaturas geradas**

```bash
cd android && ./gradlew :app:openApiGenerate
```

Expected: `BUILD SUCCESSFUL`, arquivos criados em `android/app/build/generated/openapi/src/main/kotlin/com/fintech/mobile/api/`.

Abra `AuthApi.kt`, `AccountsApi.kt`, `CategoriesApi.kt` e `TransactionsApi.kt` gerados e confirme que:
- `AuthApi.login(loginDTO: LoginDTO): LoginResponseDTO` é `suspend fun`.
- `TransactionsApi.createTransaction(transactionRequestDTO: TransactionRequestDTO): List<TransactionResponseDTO>` e `TransactionsApi.listTransactions(...)` (parâmetros opcionais nullable com default `null`) são `suspend fun`.
- `AccountsApi.listAccounts(): List<AccountResponse>` é `suspend fun`.
- `CategoriesApi.listCategories(includeArchived: Boolean? = null): List<CategoryResponseDTO>` é `suspend fun`.

Se algum nome de parâmetro (`@Body`) divergir do assumido nas tarefas seguintes (ex: `loginDTO` vs `body`), ajuste as chamadas nas tarefas correspondentes para bater com o nome real gerado — o comportamento não muda, só o nome do parâmetro nomeado.

- [ ] **Step 3: Compilar o módulo inteiro com o código gerado**

```bash
./gradlew :app:compileDebugKotlin
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add android/app/build.gradle.kts
git commit -m "feat(android): gera cliente Kotlin/Retrofit a partir do openapi.yaml"
```

---

### Task 3: Módulo de rede + sessão (Retrofit/OkHttp, `SessionManager`, `AuthInterceptor`)

**Files:**
- Create: `android/app/src/main/java/com/fintech/mobile/session/TokenProvider.kt`
- Create: `android/app/src/main/java/com/fintech/mobile/session/SessionManager.kt`
- Create: `android/app/src/main/java/com/fintech/mobile/session/AuthInterceptor.kt`
- Create: `android/app/src/main/java/com/fintech/mobile/di/SessionModule.kt`
- Create: `android/app/src/main/java/com/fintech/mobile/di/NetworkModule.kt`
- Test: `android/app/src/test/java/com/fintech/mobile/session/AuthInterceptorTest.kt`

**Interfaces:**
- Consumes: `com.fintech.mobile.api.{AuthApi, AccountsApi, CategoriesApi, TransactionsApi}` (Tarefa 2).
- Produces: `TokenProvider` (interface: `currentToken(): String?`, `saveToken(token: String)`, `clearToken()`), `SessionManager` (implementa `TokenProvider`, expõe `tokenFlow: StateFlow<String?>`), Hilt providers para `Retrofit`/`OkHttpClient`/as 4 Apis — usados a partir da Tarefa 7.

- [ ] **Step 1: Escrever o teste do `AuthInterceptor`**

`android/app/src/test/java/com/fintech/mobile/session/AuthInterceptorTest.kt`:
```kotlin
package com.fintech.mobile.session

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AuthInterceptorTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var tokenProvider: FakeTokenProvider

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        tokenProvider = FakeTokenProvider(token = "abc123")
        client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(tokenProvider))
            .build()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `adds bearer header when token is present`() {
        server.enqueue(MockResponse().setResponseCode(200))

        client.newCall(Request.Builder().url(server.url("/api/transactions")).build()).execute()

        assertEquals("Bearer abc123", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `omits header when there is no token`() {
        tokenProvider.token = null
        server.enqueue(MockResponse().setResponseCode(200))

        client.newCall(Request.Builder().url(server.url("/api/transactions")).build()).execute()

        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `clears token on 401 response`() {
        server.enqueue(MockResponse().setResponseCode(401))

        client.newCall(Request.Builder().url(server.url("/api/transactions")).build()).execute()

        assertNull(tokenProvider.currentToken())
    }

    private class FakeTokenProvider(var token: String?) : TokenProvider {
        override fun currentToken(): String? = token
        override fun saveToken(token: String) { this.token = token }
        override fun clearToken() { token = null }
    }
}
```

- [ ] **Step 2: Rodar o teste e confirmar que falha (as classes ainda não existem)**

```bash
./gradlew :app:testDebugUnitTest --tests "com.fintech.mobile.session.AuthInterceptorTest"
```

Expected: FAIL — `Unresolved reference: TokenProvider` / `AuthInterceptor`.

- [ ] **Step 3: Criar `TokenProvider` e `AuthInterceptor`**

`android/app/src/main/java/com/fintech/mobile/session/TokenProvider.kt`:
```kotlin
package com.fintech.mobile.session

interface TokenProvider {
    fun currentToken(): String?
    fun saveToken(token: String)
    fun clearToken()
}
```

`android/app/src/main/java/com/fintech/mobile/session/AuthInterceptor.kt`:
```kotlin
package com.fintech.mobile.session

import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject

class AuthInterceptor @Inject constructor(
    private val tokenProvider: TokenProvider
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val token = tokenProvider.currentToken()
        val request = if (token != null) {
            chain.request().newBuilder()
                .addHeader("Authorization", "Bearer $token")
                .build()
        } else {
            chain.request()
        }

        val response = chain.proceed(request)
        if (response.code == 401) {
            tokenProvider.clearToken()
        }
        return response
    }
}
```

- [ ] **Step 4: Rodar o teste de novo e confirmar que passa**

```bash
./gradlew :app:testDebugUnitTest --tests "com.fintech.mobile.session.AuthInterceptorTest"
```

Expected: PASS (3 testes).

- [ ] **Step 5: Criar `SessionManager`**

`android/app/src/main/java/com/fintech/mobile/session/SessionManager.kt`:
```kotlin
package com.fintech.mobile.session

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SessionManager @Inject constructor(
    @ApplicationContext context: Context
) : TokenProvider {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        PREFS_FILE_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private val _tokenFlow = MutableStateFlow(prefs.getString(KEY_TOKEN, null))
    val tokenFlow: StateFlow<String?> = _tokenFlow.asStateFlow()

    override fun currentToken(): String? = _tokenFlow.value

    override fun saveToken(token: String) {
        prefs.edit().putString(KEY_TOKEN, token).apply()
        _tokenFlow.value = token
    }

    override fun clearToken() {
        prefs.edit().remove(KEY_TOKEN).apply()
        _tokenFlow.value = null
    }

    private companion object {
        const val PREFS_FILE_NAME = "fintech_session"
        const val KEY_TOKEN = "jwt_token"
    }
}
```

(Sem teste unitário dedicado — `EncryptedSharedPreferences` depende do Android Keystore, indisponível em teste JVM puro; validado manualmente na Tarefa 14.)

- [ ] **Step 6: Criar o módulo Hilt que expõe `SessionManager` como `TokenProvider`**

`android/app/src/main/java/com/fintech/mobile/di/SessionModule.kt`:
```kotlin
package com.fintech.mobile.di

import com.fintech.mobile.session.SessionManager
import com.fintech.mobile.session.TokenProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class SessionModule {

    @Binds
    @Singleton
    abstract fun bindTokenProvider(sessionManager: SessionManager): TokenProvider
}
```

- [ ] **Step 7: Criar `NetworkModule`**

`android/app/src/main/java/com/fintech/mobile/di/NetworkModule.kt`:
```kotlin
package com.fintech.mobile.di

import com.fintech.mobile.api.AccountsApi
import com.fintech.mobile.api.AuthApi
import com.fintech.mobile.api.CategoriesApi
import com.fintech.mobile.api.TransactionsApi
import com.fintech.mobile.session.AuthInterceptor
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

private const val BASE_URL = "http://10.0.2.2:8080/"

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideGson(): Gson = Gson()

    @Provides
    @Singleton
    fun provideOkHttpClient(authInterceptor: AuthInterceptor): OkHttpClient {
        // Level.BASIC nunca loga headers/body — evita vazar o JWT ou a senha no logcat.
        val logging = HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }
        return OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .addInterceptor(logging)
            .build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(client: OkHttpClient, gson: Gson): Retrofit =
        Retrofit.Builder()
            .baseUrl(BASE_URL)
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

- [ ] **Step 8: Rodar todos os testes do módulo e compilar**

```bash
./gradlew :app:testDebugUnitTest :app:compileDebugKotlin
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 9: Commit**

```bash
git add android/app/src/main/java/com/fintech/mobile/session android/app/src/main/java/com/fintech/mobile/di android/app/src/test
git commit -m "feat(android): módulo de rede (Retrofit/OkHttp) e sessão JWT segura"
```

---

### Task 4: `ApiResult` + `apiCall` (mapeamento de erro, puro)

**Files:**
- Create: `android/app/src/main/java/com/fintech/mobile/core/network/ApiResult.kt`
- Create: `android/app/src/main/java/com/fintech/mobile/core/network/BackendErrorResponse.kt`
- Create: `android/app/src/main/java/com/fintech/mobile/core/network/ApiCall.kt`
- Test: `android/app/src/test/java/com/fintech/mobile/core/network/ApiCallTest.kt`

**Interfaces:**
- Consumes: `retrofit2.Response<T>` — **divergência confirmada na Tarefa 2**: todo método gerado (`AuthApi`, `AccountsApi`, `CategoriesApi`, `TransactionsApi`) retorna `suspend fun ...: Response<T>`, nunca `T` direto (comportamento padrão da biblioteca `jvm-retrofit2` do openapi-generator com `useCoroutines=true` — não há `HttpException` lançada para status HTTP não-2xx; o chamador recebe `Response` com `isSuccessful=false`). `apiCall` abaixo já reflete isso.
- Produces: `sealed class ApiResult<out T>` (`Success<T>`, `ValidationError`, `HttpError`, `NetworkError`) e `suspend fun <T> apiCall(gson: Gson, block: suspend () -> Response<T>): ApiResult<T>` — usados por todos os Repositories a partir da Tarefa 7. Assinatura dos call sites não muda de forma perceptível: `apiCall(gson) { transactionsApi.createTransaction(dto) }` compila igual, só o tipo inferido do lambda passa de `T` para `Response<T>`.

- [ ] **Step 1: Escrever o teste**

`android/app/src/test/java/com/fintech/mobile/core/network/ApiCallTest.kt`:
```kotlin
package com.fintech.mobile.core.network

import com.google.gson.Gson
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import retrofit2.Response
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ApiCallTest {

    private val gson = Gson()

    @Test
    fun `maps a successful response to Success`() = runTest {
        val result = apiCall(gson) { Response.success("ok") }
        assertEquals(ApiResult.Success("ok"), result)
    }

    @Test
    fun `maps 400 with body to ValidationError with field details`() = runTest {
        val body = """{"message":"Dados inválidos","details":{"amount":"deve ser maior que zero"}}"""
            .toResponseBody("application/json".toMediaType())

        val result = apiCall<Unit>(gson) { Response.error(400, body) }

        assertIs<ApiResult.ValidationError>(result)
        assertEquals("Dados inválidos", result.message)
        assertEquals(mapOf("amount" to "deve ser maior que zero"), result.fieldErrors)
    }

    @Test
    fun `maps a non-400 error response to HttpError with the status code`() = runTest {
        val body = """{"message":"Fatura não encontrada"}"""
            .toResponseBody("application/json".toMediaType())

        val result = apiCall<Unit>(gson) { Response.error(404, body) }

        assertIs<ApiResult.HttpError>(result)
        assertEquals(404, result.code)
        assertEquals("Fatura não encontrada", result.message)
    }

    @Test
    fun `maps IOException to NetworkError`() = runTest {
        val exception = IOException("sem conexão")

        val result = apiCall<Unit>(gson) { throw exception }

        assertIs<ApiResult.NetworkError>(result)
        assertEquals(exception, result.cause)
    }
}
```

- [ ] **Step 2: Rodar o teste e confirmar que falha**

```bash
./gradlew :app:testDebugUnitTest --tests "com.fintech.mobile.core.network.ApiCallTest"
```

Expected: FAIL — `Unresolved reference: apiCall` / `ApiResult`.

- [ ] **Step 3: Implementar `ApiResult`, `BackendErrorResponse` e `apiCall`**

`android/app/src/main/java/com/fintech/mobile/core/network/ApiResult.kt`:
```kotlin
package com.fintech.mobile.core.network

sealed class ApiResult<out T> {
    data class Success<T>(val value: T) : ApiResult<T>()
    data class ValidationError(val message: String, val fieldErrors: Map<String, String>) : ApiResult<Nothing>()
    data class HttpError(val code: Int, val message: String) : ApiResult<Nothing>()
    data class NetworkError(val cause: Throwable) : ApiResult<Nothing>()
}
```

`android/app/src/main/java/com/fintech/mobile/core/network/BackendErrorResponse.kt`:
```kotlin
package com.fintech.mobile.core.network

// Espelha o formato de GlobalExceptionHandler.buildErrorResponse (backend):
// {timestamp, status, error, message, details?}. Só os campos usados pelo app são mapeados.
data class BackendErrorResponse(
    val message: String? = null,
    val details: Map<String, String>? = null
)
```

`android/app/src/main/java/com/fintech/mobile/core/network/ApiCall.kt`:
```kotlin
package com.fintech.mobile.core.network

import com.google.gson.Gson
import retrofit2.Response
import java.io.IOException

// Todo método gerado pelo openapi-generator (kotlin/jvm-retrofit2) retorna Response<T> —
// não lança HttpException em status não-2xx. Só IOException (sem conexão/timeout) é
// exceção de verdade aqui; erro HTTP é um valor (response.isSuccessful == false).
suspend fun <T> apiCall(gson: Gson, block: suspend () -> Response<T>): ApiResult<T> {
    return try {
        val response = block()
        if (response.isSuccessful) {
            val body = response.body()
            if (body != null) {
                ApiResult.Success(body)
            } else {
                ApiResult.HttpError(code = response.code(), message = "Resposta vazia")
            }
        } else {
            val parsed = parseErrorBody(gson, response)
            if (response.code() == 400) {
                ApiResult.ValidationError(
                    message = parsed?.message ?: "Dados inválidos",
                    fieldErrors = parsed?.details ?: emptyMap()
                )
            } else {
                ApiResult.HttpError(
                    code = response.code(),
                    message = parsed?.message ?: response.message().ifBlank { "Erro ${response.code()}" }
                )
            }
        }
    } catch (e: IOException) {
        ApiResult.NetworkError(e)
    }
}

private fun parseErrorBody(gson: Gson, response: Response<*>): BackendErrorResponse? {
    val raw = response.errorBody()?.string() ?: return null
    return runCatching { gson.fromJson(raw, BackendErrorResponse::class.java) }.getOrNull()
}
```

- [ ] **Step 4: Rodar o teste de novo e confirmar que passa**

```bash
./gradlew :app:testDebugUnitTest --tests "com.fintech.mobile.core.network.ApiCallTest"
```

Expected: PASS (4 testes).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/fintech/mobile/core/network android/app/src/test/java/com/fintech/mobile/core/network
git commit -m "feat(android): mapeamento puro de erro de API (ApiResult/apiCall)"
```

---

### Task 5: `AmountParser` (parsing de valor pt-BR, puro)

**Files:**
- Create: `android/app/src/main/java/com/fintech/mobile/core/format/AmountParser.kt`
- Test: `android/app/src/test/java/com/fintech/mobile/core/format/AmountParserTest.kt`

**Interfaces:**
- Consumes: nada (lógica pura).
- Produces: `object AmountParser { fun parse(raw: String): Double? }` — usado por `NewTransactionFormValidator` (Tarefa 10).

- [ ] **Step 1: Escrever o teste**

`android/app/src/test/java/com/fintech/mobile/core/format/AmountParserTest.kt`:
```kotlin
package com.fintech.mobile.core.format

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AmountParserTest {

    @Test
    fun `parses plain decimal with dot`() {
        assertEquals(1234.56, AmountParser.parse("1234.56"))
    }

    @Test
    fun `parses pt-BR decimal with comma`() {
        assertEquals(1234.56, AmountParser.parse("1234,56"))
    }

    @Test
    fun `parses pt-BR with thousand separator dot and decimal comma`() {
        assertEquals(1234.56, AmountParser.parse("1.234,56"))
    }

    @Test
    fun `parses US format with thousand separator comma and decimal dot`() {
        assertEquals(1234.56, AmountParser.parse("1,234.56"))
    }

    @Test
    fun `returns null for blank input`() {
        assertNull(AmountParser.parse("  "))
    }

    @Test
    fun `returns null for non numeric input`() {
        assertNull(AmountParser.parse("abc"))
    }
}
```

- [ ] **Step 2: Rodar o teste e confirmar que falha**

```bash
./gradlew :app:testDebugUnitTest --tests "com.fintech.mobile.core.format.AmountParserTest"
```

Expected: FAIL — `Unresolved reference: AmountParser`.

- [ ] **Step 3: Implementar `AmountParser`**

`android/app/src/main/java/com/fintech/mobile/core/format/AmountParser.kt`:
```kotlin
package com.fintech.mobile.core.format

object AmountParser {

    // Mesma inferência "por valor" do CsvExtractor do backend (ver summary.md):
    // dois separadores → o último é o decimal; só vírgula → vírgula é decimal.
    fun parse(raw: String): Double? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null

        val hasComma = trimmed.contains(',')
        val hasDot = trimmed.contains('.')

        val normalized = when {
            hasComma && hasDot -> {
                if (trimmed.lastIndexOf(',') > trimmed.lastIndexOf('.')) {
                    trimmed.replace(".", "").replace(",", ".")
                } else {
                    trimmed.replace(",", "")
                }
            }
            hasComma -> trimmed.replace(",", ".")
            else -> trimmed
        }

        return normalized.toDoubleOrNull()
    }
}
```

- [ ] **Step 4: Rodar o teste de novo e confirmar que passa**

```bash
./gradlew :app:testDebugUnitTest --tests "com.fintech.mobile.core.format.AmountParserTest"
```

Expected: PASS (6 testes).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/fintech/mobile/core/format android/app/src/test/java/com/fintech/mobile/core/format
git commit -m "feat(android): parser puro de valor pt-BR/ponto-decimal"
```

---

### Task 6: Outbox local (Room: entidade + Dao)

**Files:**
- Create: `android/app/src/main/java/com/fintech/mobile/data/local/PendingTransactionEntity.kt`
- Create: `android/app/src/main/java/com/fintech/mobile/data/local/PendingTransactionDao.kt`
- Create: `android/app/src/main/java/com/fintech/mobile/data/local/AppDatabase.kt`
- Create: `android/app/src/main/java/com/fintech/mobile/di/DatabaseModule.kt`
- Test: `android/app/src/test/java/com/fintech/mobile/data/local/PendingTransactionDaoTest.kt`

**Interfaces:**
- Consumes: nada.
- Produces: `PendingTransactionEntity(localId, payloadJson, createdAt, status, errorMessage)` com `companion object { STATUS_PENDING, STATUS_FAILED }`; `PendingTransactionDao` (`insert`, `observeAll`, `getByStatus`, `updateStatus`, `delete`) — usado por `TransactionRepository` (Tarefa 9) e `SyncWorker` (Tarefa 11).

- [ ] **Step 1: Escrever o teste do Dao (Robolectric — Room precisa do SQLite do Android)**

`android/app/src/test/java/com/fintech/mobile/data/local/PendingTransactionDaoTest.kt`:
```kotlin
package com.fintech.mobile.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PendingTransactionDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: PendingTransactionDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.pendingTransactionDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `insert then observeAll returns the pending item`() = runTest {
        dao.insert(PendingTransactionEntity(payloadJson = "{}", createdAt = 1L))

        val all = dao.observeAll().first()

        assertEquals(1, all.size)
        assertEquals(PendingTransactionEntity.STATUS_PENDING, all[0].status)
    }

    @Test
    fun `getByStatus only returns items with the matching status`() = runTest {
        val failedId = dao.insert(
            PendingTransactionEntity(payloadJson = "{}", createdAt = 1L, status = PendingTransactionEntity.STATUS_FAILED)
        )
        dao.insert(PendingTransactionEntity(payloadJson = "{}", createdAt = 2L))

        val pending = dao.getByStatus(PendingTransactionEntity.STATUS_PENDING)

        assertEquals(1, pending.size)
        assertTrue(pending.none { it.localId == failedId })
    }

    @Test
    fun `updateStatus marks the item as failed with a message`() = runTest {
        val id = dao.insert(PendingTransactionEntity(payloadJson = "{}", createdAt = 1L))

        dao.updateStatus(id, PendingTransactionEntity.STATUS_FAILED, "valor inválido")

        val all = dao.observeAll().first()
        assertEquals(PendingTransactionEntity.STATUS_FAILED, all[0].status)
        assertEquals("valor inválido", all[0].errorMessage)
    }

    @Test
    fun `delete removes the item`() = runTest {
        val id = dao.insert(PendingTransactionEntity(payloadJson = "{}", createdAt = 1L))

        dao.delete(id)

        assertTrue(dao.observeAll().first().isEmpty())
    }
}
```

- [ ] **Step 2: Rodar o teste e confirmar que falha**

```bash
./gradlew :app:testDebugUnitTest --tests "com.fintech.mobile.data.local.PendingTransactionDaoTest"
```

Expected: FAIL — classes `PendingTransactionEntity`/`AppDatabase`/`PendingTransactionDao` não existem.

- [ ] **Step 3: Implementar a entidade, o Dao e o `AppDatabase`**

`android/app/src/main/java/com/fintech/mobile/data/local/PendingTransactionEntity.kt`:
```kotlin
package com.fintech.mobile.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "pending_transactions")
data class PendingTransactionEntity(
    @PrimaryKey(autoGenerate = true) val localId: Long = 0,
    val payloadJson: String,
    val createdAt: Long,
    val status: String = STATUS_PENDING,
    val errorMessage: String? = null
) {
    companion object {
        const val STATUS_PENDING = "PENDING"
        const val STATUS_FAILED = "FAILED"
    }
}
```

`android/app/src/main/java/com/fintech/mobile/data/local/PendingTransactionDao.kt`:
```kotlin
package com.fintech.mobile.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PendingTransactionDao {

    @Insert
    suspend fun insert(entity: PendingTransactionEntity): Long

    @Query("SELECT * FROM pending_transactions ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<PendingTransactionEntity>>

    @Query("SELECT * FROM pending_transactions WHERE status = :status ORDER BY createdAt ASC")
    suspend fun getByStatus(status: String = PendingTransactionEntity.STATUS_PENDING): List<PendingTransactionEntity>

    @Query("UPDATE pending_transactions SET status = :status, errorMessage = :errorMessage WHERE localId = :localId")
    suspend fun updateStatus(localId: Long, status: String, errorMessage: String?)

    @Query("DELETE FROM pending_transactions WHERE localId = :localId")
    suspend fun delete(localId: Long)
}
```

`android/app/src/main/java/com/fintech/mobile/data/local/AppDatabase.kt`:
```kotlin
package com.fintech.mobile.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [PendingTransactionEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun pendingTransactionDao(): PendingTransactionDao
}
```

- [ ] **Step 4: Rodar o teste de novo e confirmar que passa**

```bash
./gradlew :app:testDebugUnitTest --tests "com.fintech.mobile.data.local.PendingTransactionDaoTest"
```

Expected: PASS (4 testes).

- [ ] **Step 5: Criar o módulo Hilt do banco**

`android/app/src/main/java/com/fintech/mobile/di/DatabaseModule.kt`:
```kotlin
package com.fintech.mobile.di

import android.content.Context
import androidx.room.Room
import com.fintech.mobile.data.local.AppDatabase
import com.fintech.mobile.data.local.PendingTransactionDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "fintech-mobile.db").build()

    @Provides
    @Singleton
    fun providePendingTransactionDao(database: AppDatabase): PendingTransactionDao =
        database.pendingTransactionDao()
}
```

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/java/com/fintech/mobile/data/local android/app/src/main/java/com/fintech/mobile/di/DatabaseModule.kt android/app/src/test/java/com/fintech/mobile/data/local
git commit -m "feat(android): outbox local (Room) para lançamentos pendentes"
```

---

### Task 7: Login (`AuthRepository` + `LoginViewModel` + `LoginScreen`)

**Files:**
- Create: `android/app/src/main/java/com/fintech/mobile/data/repository/AuthRepository.kt`
- Create: `android/app/src/main/java/com/fintech/mobile/ui/login/LoginUiState.kt`
- Create: `android/app/src/main/java/com/fintech/mobile/ui/login/LoginViewModel.kt`
- Create: `android/app/src/main/java/com/fintech/mobile/ui/login/LoginScreen.kt`
- Test: `android/app/src/test/java/com/fintech/mobile/data/repository/AuthRepositoryTest.kt`
- Test: `android/app/src/test/java/com/fintech/mobile/ui/login/LoginViewModelTest.kt`

**Interfaces:**
- Consumes: `com.fintech.mobile.api.AuthApi` (Tarefa 2), `TokenProvider` (Tarefa 3), `ApiResult`/`apiCall` (Tarefa 4).
- Produces: `AuthRepository.login(email: String, password: String): ApiResult<Unit>`; `LoginViewModel.login(email, password)` expõe `uiState: StateFlow<LoginUiState>`; `LoginScreen(onLoginSuccess: () -> Unit)` — usado pela navegação na Tarefa 13.

- [ ] **Step 1: Escrever o teste do `AuthRepository`**

`android/app/src/test/java/com/fintech/mobile/data/repository/AuthRepositoryTest.kt`:
```kotlin
package com.fintech.mobile.data.repository

import com.fintech.mobile.api.AuthApi
import com.fintech.mobile.api.model.LoginDTO
import com.fintech.mobile.api.model.LoginResponseDTO
import com.fintech.mobile.core.network.ApiResult
import com.fintech.mobile.session.TokenProvider
import com.google.gson.Gson
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Test
import retrofit2.Response
import kotlin.test.assertIs

class AuthRepositoryTest {

    private val gson = Gson()

    @Test
    fun `saves the token and returns Success when login succeeds`() = runTest {
        val authApi = mockk<AuthApi>()
        coEvery { authApi.login(LoginDTO(email = "carlos@costa.com", password = "costa123")) } returns
            Response.success(LoginResponseDTO(token = "jwt-token-123"))
        val tokenProvider = mockk<TokenProvider>(relaxUnitFun = true)

        val repository = AuthRepository(authApi, tokenProvider, gson)
        val result = repository.login("carlos@costa.com", "costa123")

        assertIs<ApiResult.Success<Unit>>(result)
        verify { tokenProvider.saveToken("jwt-token-123") }
    }

    @Test
    fun `does not save a token when the backend returns a blank token`() = runTest {
        val authApi = mockk<AuthApi>()
        coEvery { authApi.login(any()) } returns Response.success(LoginResponseDTO(token = null))
        val tokenProvider = mockk<TokenProvider>(relaxUnitFun = true)

        val repository = AuthRepository(authApi, tokenProvider, gson)
        val result = repository.login("carlos@costa.com", "costa123")

        assertIs<ApiResult.HttpError>(result)
        verify(exactly = 0) { tokenProvider.saveToken(any()) }
    }
}
```

- [ ] **Step 2: Rodar o teste e confirmar que falha**

```bash
./gradlew :app:testDebugUnitTest --tests "com.fintech.mobile.data.repository.AuthRepositoryTest"
```

Expected: FAIL — `Unresolved reference: AuthRepository`.

- [ ] **Step 3: Implementar `AuthRepository`**

`android/app/src/main/java/com/fintech/mobile/data/repository/AuthRepository.kt`:
```kotlin
package com.fintech.mobile.data.repository

import com.fintech.mobile.api.AuthApi
import com.fintech.mobile.api.model.LoginDTO
import com.fintech.mobile.core.network.ApiResult
import com.fintech.mobile.core.network.apiCall
import com.fintech.mobile.session.TokenProvider
import com.google.gson.Gson
import javax.inject.Inject

class AuthRepository @Inject constructor(
    private val authApi: AuthApi,
    private val tokenProvider: TokenProvider,
    private val gson: Gson
) {
    suspend fun login(email: String, password: String): ApiResult<Unit> {
        return when (val result = apiCall(gson) { authApi.login(LoginDTO(email = email, password = password)) }) {
            is ApiResult.Success -> {
                val token = result.value.token
                if (token.isNullOrBlank()) {
                    ApiResult.HttpError(code = 200, message = "Resposta de login sem token")
                } else {
                    tokenProvider.saveToken(token)
                    ApiResult.Success(Unit)
                }
            }
            is ApiResult.ValidationError -> result
            is ApiResult.HttpError -> result
            is ApiResult.NetworkError -> result
        }
    }
}
```

- [ ] **Step 4: Rodar o teste de novo e confirmar que passa**

```bash
./gradlew :app:testDebugUnitTest --tests "com.fintech.mobile.data.repository.AuthRepositoryTest"
```

Expected: PASS (2 testes).

- [ ] **Step 5: Escrever o teste do `LoginViewModel`**

`android/app/src/test/java/com/fintech/mobile/ui/login/LoginViewModelTest.kt`:
```kotlin
package com.fintech.mobile.ui.login

import com.fintech.mobile.api.AuthApi
import com.fintech.mobile.api.model.LoginDTO
import com.fintech.mobile.api.model.LoginResponseDTO
import com.fintech.mobile.data.repository.AuthRepository
import com.fintech.mobile.session.TokenProvider
import com.google.gson.Gson
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import kotlin.test.assertEquals
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() { Dispatchers.setMain(dispatcher) }

    @After
    fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun `blank email shows a validation error without calling the API`() {
        val authApi = mockk<AuthApi>(relaxed = true)
        val viewModel = viewModelWith(authApi)

        viewModel.login("", "costa123")

        assertIs<LoginUiState.Error>(viewModel.uiState.value)
    }

    @Test
    fun `successful login moves to Success state`() = runTest {
        val authApi = mockk<AuthApi>()
        coEvery { authApi.login(any()) } returns Response.success(LoginResponseDTO(token = "jwt-abc"))
        val viewModel = viewModelWith(authApi)

        viewModel.login("carlos@costa.com", "costa123")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(LoginUiState.Success, viewModel.uiState.value)
    }

    @Test
    fun `401 from the backend shows an invalid credentials message`() = runTest {
        val authApi = mockk<AuthApi>()
        val body = "{}".toResponseBody("application/json".toMediaType())
        coEvery { authApi.login(any()) } returns Response.error(401, body)
        val viewModel = viewModelWith(authApi)

        viewModel.login("carlos@costa.com", "senha-errada")
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertIs<LoginUiState.Error>(state)
        assertEquals("E-mail ou senha inválidos", state.message)
    }

    private fun viewModelWith(authApi: AuthApi): LoginViewModel {
        val tokenProvider = mockk<TokenProvider>(relaxUnitFun = true)
        val repository = AuthRepository(authApi, tokenProvider, Gson())
        return LoginViewModel(repository)
    }
}
```

- [ ] **Step 6: Rodar o teste e confirmar que falha**

```bash
./gradlew :app:testDebugUnitTest --tests "com.fintech.mobile.ui.login.LoginViewModelTest"
```

Expected: FAIL — `Unresolved reference: LoginViewModel` / `LoginUiState`.

- [ ] **Step 7: Implementar `LoginUiState` e `LoginViewModel`**

`android/app/src/main/java/com/fintech/mobile/ui/login/LoginUiState.kt`:
```kotlin
package com.fintech.mobile.ui.login

sealed class LoginUiState {
    data object Idle : LoginUiState()
    data object Loading : LoginUiState()
    data class Error(val message: String) : LoginUiState()
    data object Success : LoginUiState()
}
```

`android/app/src/main/java/com/fintech/mobile/ui/login/LoginViewModel.kt`:
```kotlin
package com.fintech.mobile.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fintech.mobile.core.network.ApiResult
import com.fintech.mobile.data.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<LoginUiState>(LoginUiState.Idle)
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    fun login(email: String, password: String) {
        if (email.isBlank() || password.isBlank()) {
            _uiState.value = LoginUiState.Error("Informe e-mail e senha")
            return
        }
        viewModelScope.launch {
            _uiState.value = LoginUiState.Loading
            _uiState.value = when (val result = authRepository.login(email.trim(), password)) {
                is ApiResult.Success -> LoginUiState.Success
                is ApiResult.ValidationError -> LoginUiState.Error(result.message)
                is ApiResult.NetworkError -> LoginUiState.Error("Sem conexão. Tente novamente.")
                is ApiResult.HttpError -> LoginUiState.Error(mapHttpError(result.code, result.message))
            }
        }
    }

    private fun mapHttpError(code: Int, fallbackMessage: String): String = when (code) {
        401 -> "E-mail ou senha inválidos"
        429 -> "Muitas tentativas. Aguarde um minuto e tente novamente."
        else -> fallbackMessage
    }
}
```

- [ ] **Step 8: Rodar o teste de novo e confirmar que passa**

```bash
./gradlew :app:testDebugUnitTest --tests "com.fintech.mobile.ui.login.LoginViewModelTest"
```

Expected: PASS (3 testes).

- [ ] **Step 9: Implementar `LoginScreen` (sem teste — UI Compose, validada manualmente na Tarefa 14)**

`android/app/src/main/java/com/fintech/mobile/ui/login/LoginScreen.kt`:
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

- [ ] **Step 10: Compilar o módulo**

```bash
./gradlew :app:compileDebugKotlin
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 11: Commit**

```bash
git add android/app/src/main/java/com/fintech/mobile/data/repository/AuthRepository.kt android/app/src/main/java/com/fintech/mobile/ui/login android/app/src/test/java/com/fintech/mobile/data/repository/AuthRepositoryTest.kt android/app/src/test/java/com/fintech/mobile/ui/login
git commit -m "feat(android): tela de login com JWT persistido"
```

---

### Task 8: Leitura de conta/categoria (`AccountRepository` + `CategoryRepository`)

**Files:**
- Create: `android/app/src/main/java/com/fintech/mobile/data/repository/AccountRepository.kt`
- Create: `android/app/src/main/java/com/fintech/mobile/data/repository/CategoryRepository.kt`
- Test: `android/app/src/test/java/com/fintech/mobile/data/repository/AccountRepositoryTest.kt`
- Test: `android/app/src/test/java/com/fintech/mobile/data/repository/CategoryRepositoryTest.kt`

**Interfaces:**
- Consumes: `com.fintech.mobile.api.{AccountsApi, CategoriesApi}` (Tarefa 2), `ApiResult`/`apiCall` (Tarefa 4).
- Produces: `AccountRepository.listAccounts(): ApiResult<List<AccountResponse>>`, `CategoryRepository.listCategories(): ApiResult<List<CategoryResponseDTO>>` — usados por `NewTransactionViewModel` (Tarefa 10).

- [ ] **Step 1: Escrever os testes**

`android/app/src/test/java/com/fintech/mobile/data/repository/AccountRepositoryTest.kt`:
```kotlin
package com.fintech.mobile.data.repository

import com.fintech.mobile.api.AccountsApi
import com.fintech.mobile.api.model.AccountResponse
import com.fintech.mobile.api.model.AccountType
import com.fintech.mobile.core.network.ApiResult
import com.google.gson.Gson
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import retrofit2.Response
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AccountRepositoryTest {

    @Test
    fun `returns the accounts from the API`() = runTest {
        val account = AccountResponse(
            id = UUID.randomUUID(),
            name = "Cartão Nubank",
            type = AccountType.CREDIT_CARD,
            countInLiquidBalance = false,
            countInNetWorth = true,
            active = true,
            balance = 150.0
        )
        val api = mockk<AccountsApi>()
        coEvery { api.listAccounts() } returns Response.success(listOf(account))

        val result = AccountRepository(api, Gson()).listAccounts()

        assertIs<ApiResult.Success<List<AccountResponse>>>(result)
        assertEquals(listOf(account), result.value)
    }
}
```

`android/app/src/test/java/com/fintech/mobile/data/repository/CategoryRepositoryTest.kt`:
```kotlin
package com.fintech.mobile.data.repository

import com.fintech.mobile.api.CategoriesApi
import com.fintech.mobile.api.model.CategoryResponseDTO
import com.fintech.mobile.core.network.ApiResult
import com.google.gson.Gson
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import retrofit2.Response
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CategoryRepositoryTest {

    @Test
    fun `returns the categories from the API`() = runTest {
        val category = CategoryResponseDTO(
            id = UUID.randomUUID(),
            name = "Mercado",
            icon = "cart",
            color = "#00FF00",
            archived = false,
            children = emptyList()
        )
        val api = mockk<CategoriesApi>()
        coEvery { api.listCategories(any()) } returns Response.success(listOf(category))

        val result = CategoryRepository(api, Gson()).listCategories()

        assertIs<ApiResult.Success<List<CategoryResponseDTO>>>(result)
        assertEquals(listOf(category), result.value)
    }
}
```

- [ ] **Step 2: Rodar os testes e confirmar que falham**

```bash
./gradlew :app:testDebugUnitTest --tests "com.fintech.mobile.data.repository.AccountRepositoryTest" --tests "com.fintech.mobile.data.repository.CategoryRepositoryTest"
```

Expected: FAIL — `Unresolved reference: AccountRepository` / `CategoryRepository`.

- [ ] **Step 3: Implementar os repositórios**

`android/app/src/main/java/com/fintech/mobile/data/repository/AccountRepository.kt`:
```kotlin
package com.fintech.mobile.data.repository

import com.fintech.mobile.api.AccountsApi
import com.fintech.mobile.api.model.AccountResponse
import com.fintech.mobile.core.network.ApiResult
import com.fintech.mobile.core.network.apiCall
import com.google.gson.Gson
import javax.inject.Inject

class AccountRepository @Inject constructor(
    private val accountsApi: AccountsApi,
    private val gson: Gson
) {
    suspend fun listAccounts(): ApiResult<List<AccountResponse>> =
        apiCall(gson) { accountsApi.listAccounts() }
}
```

`android/app/src/main/java/com/fintech/mobile/data/repository/CategoryRepository.kt`:
```kotlin
package com.fintech.mobile.data.repository

import com.fintech.mobile.api.CategoriesApi
import com.fintech.mobile.api.model.CategoryResponseDTO
import com.fintech.mobile.core.network.ApiResult
import com.fintech.mobile.core.network.apiCall
import com.google.gson.Gson
import javax.inject.Inject

class CategoryRepository @Inject constructor(
    private val categoriesApi: CategoriesApi,
    private val gson: Gson
) {
    suspend fun listCategories(): ApiResult<List<CategoryResponseDTO>> =
        apiCall(gson) { categoriesApi.listCategories(includeArchived = false) }
}
```

- [ ] **Step 4: Rodar os testes de novo e confirmar que passam**

```bash
./gradlew :app:testDebugUnitTest --tests "com.fintech.mobile.data.repository.AccountRepositoryTest" --tests "com.fintech.mobile.data.repository.CategoryRepositoryTest"
```

Expected: PASS (2 testes).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/fintech/mobile/data/repository/AccountRepository.kt android/app/src/main/java/com/fintech/mobile/data/repository/CategoryRepository.kt android/app/src/test/java/com/fintech/mobile/data/repository/AccountRepositoryTest.kt android/app/src/test/java/com/fintech/mobile/data/repository/CategoryRepositoryTest.kt
git commit -m "feat(android): repositórios de leitura de conta e categoria"
```

---

### Task 9: `TransactionRepository` (criar + listar, integração com outbox)

**Files:**
- Create: `android/app/src/main/java/com/fintech/mobile/data/repository/CreateTransactionResult.kt`
- Create: `android/app/src/main/java/com/fintech/mobile/data/repository/TransactionRepository.kt`
- Test: `android/app/src/test/java/com/fintech/mobile/data/repository/TransactionRepositoryTest.kt`

**Interfaces:**
- Consumes: `com.fintech.mobile.api.TransactionsApi` (Tarefa 2), `PendingTransactionDao`/`PendingTransactionEntity` (Tarefa 6), `ApiResult`/`apiCall` (Tarefa 4).
- Produces: `sealed class CreateTransactionResult { Saved, Queued, data class Failed(message, fieldErrors) }`; `TransactionRepository.create(dto): CreateTransactionResult`, `.listRemote(): ApiResult<List<TransactionResponseDTO>>`, `.observePending(): Flow<List<PendingTransactionEntity>>`, `.discardPending(localId: Long)` — usados por `NewTransactionViewModel` (Tarefa 10), `SyncWorker` (Tarefa 11) e `TransactionListViewModel` (Tarefa 12).

- [ ] **Step 1: Escrever o teste**

`android/app/src/test/java/com/fintech/mobile/data/repository/TransactionRepositoryTest.kt`:
```kotlin
package com.fintech.mobile.data.repository

import com.fintech.mobile.api.TransactionsApi
import com.fintech.mobile.api.model.TransactionRequestDTO
import com.fintech.mobile.api.model.TransactionResponseDTO
import com.fintech.mobile.api.model.TransactionType
import com.fintech.mobile.data.local.PendingTransactionDao
import com.google.gson.Gson
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import retrofit2.Response
import java.io.IOException
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TransactionRepositoryTest {

    private val gson = Gson()
    private val sampleDto = TransactionRequestDTO(
        description = "Mercado",
        amount = 150.0,
        date = LocalDate.of(2026, 7, 31),
        type = TransactionType.EXPENSE,
        accountId = UUID.randomUUID()
    )

    @Test
    fun `queues the payload when the create call fails with a network error`() = runTest {
        val api = mockk<TransactionsApi>()
        coEvery { api.createTransaction(sampleDto) } throws IOException("sem conexão")
        val dao = mockk<PendingTransactionDao>()
        coEvery { dao.insert(any()) } returns 1L

        val result = TransactionRepository(api, dao, gson).create(sampleDto)

        assertEquals(CreateTransactionResult.Queued, result)
        coVerify { dao.insert(match { it.payloadJson == gson.toJson(sampleDto) }) }
    }

    @Test
    fun `does not queue when the backend rejects with a validation error`() = runTest {
        val api = mockk<TransactionsApi>()
        val body = """{"message":"Erro de Validação","details":{"amount":"deve ser maior que zero"}}"""
            .toResponseBody("application/json".toMediaType())
        coEvery { api.createTransaction(sampleDto) } returns Response.error(400, body)
        val dao = mockk<PendingTransactionDao>()

        val result = TransactionRepository(api, dao, gson).create(sampleDto)

        assertIs<CreateTransactionResult.Failed>(result)
        coVerify(exactly = 0) { dao.insert(any()) }
    }

    @Test
    fun `returns Saved when the API accepts the transaction`() = runTest {
        val api = mockk<TransactionsApi>()
        coEvery { api.createTransaction(sampleDto) } returns Response.success(emptyList<TransactionResponseDTO>())
        val dao = mockk<PendingTransactionDao>()

        val result = TransactionRepository(api, dao, gson).create(sampleDto)

        assertEquals(CreateTransactionResult.Saved, result)
    }
}
```

- [ ] **Step 2: Rodar o teste e confirmar que falha**

```bash
./gradlew :app:testDebugUnitTest --tests "com.fintech.mobile.data.repository.TransactionRepositoryTest"
```

Expected: FAIL — `Unresolved reference: TransactionRepository` / `CreateTransactionResult`.

- [ ] **Step 3: Implementar `CreateTransactionResult` e `TransactionRepository`**

`android/app/src/main/java/com/fintech/mobile/data/repository/CreateTransactionResult.kt`:
```kotlin
package com.fintech.mobile.data.repository

sealed class CreateTransactionResult {
    data object Saved : CreateTransactionResult()
    data object Queued : CreateTransactionResult()
    data class Failed(val message: String, val fieldErrors: Map<String, String> = emptyMap()) : CreateTransactionResult()
}
```

`android/app/src/main/java/com/fintech/mobile/data/repository/TransactionRepository.kt`:
```kotlin
package com.fintech.mobile.data.repository

import com.fintech.mobile.api.TransactionsApi
import com.fintech.mobile.api.model.TransactionRequestDTO
import com.fintech.mobile.api.model.TransactionResponseDTO
import com.fintech.mobile.core.network.ApiResult
import com.fintech.mobile.core.network.apiCall
import com.fintech.mobile.data.local.PendingTransactionDao
import com.fintech.mobile.data.local.PendingTransactionEntity
import com.google.gson.Gson
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class TransactionRepository @Inject constructor(
    private val transactionsApi: TransactionsApi,
    private val pendingDao: PendingTransactionDao,
    private val gson: Gson
) {
    suspend fun create(dto: TransactionRequestDTO): CreateTransactionResult {
        return when (val result = apiCall(gson) { transactionsApi.createTransaction(dto) }) {
            is ApiResult.Success -> CreateTransactionResult.Saved
            is ApiResult.NetworkError -> {
                pendingDao.insert(
                    PendingTransactionEntity(
                        payloadJson = gson.toJson(dto),
                        createdAt = System.currentTimeMillis()
                    )
                )
                CreateTransactionResult.Queued
            }
            is ApiResult.ValidationError -> CreateTransactionResult.Failed(result.message, result.fieldErrors)
            is ApiResult.HttpError -> CreateTransactionResult.Failed(result.message)
        }
    }

    suspend fun listRemote(): ApiResult<List<TransactionResponseDTO>> =
        apiCall(gson) { transactionsApi.listTransactions() }

    fun observePending(): Flow<List<PendingTransactionEntity>> = pendingDao.observeAll()

    suspend fun discardPending(localId: Long) = pendingDao.delete(localId)
}
```

- [ ] **Step 4: Rodar o teste de novo e confirmar que passa**

```bash
./gradlew :app:testDebugUnitTest --tests "com.fintech.mobile.data.repository.TransactionRepositoryTest"
```

Expected: PASS (3 testes).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/fintech/mobile/data/repository/CreateTransactionResult.kt android/app/src/main/java/com/fintech/mobile/data/repository/TransactionRepository.kt android/app/src/test/java/com/fintech/mobile/data/repository/TransactionRepositoryTest.kt
git commit -m "feat(android): TransactionRepository com fallback para o outbox"
```

---

### Task 10: Novo lançamento (validador puro + `NewTransactionViewModel` + `NewTransactionScreen`)

**Files:**
- Create: `android/app/src/main/java/com/fintech/mobile/ui/newtransaction/NewTransactionFormValidator.kt`
- Create: `android/app/src/main/java/com/fintech/mobile/ui/newtransaction/NewTransactionUiState.kt`
- Create: `android/app/src/main/java/com/fintech/mobile/ui/newtransaction/NewTransactionViewModel.kt`
- Create: `android/app/src/main/java/com/fintech/mobile/ui/newtransaction/NewTransactionScreen.kt`
- Test: `android/app/src/test/java/com/fintech/mobile/ui/newtransaction/NewTransactionFormValidatorTest.kt`
- Test: `android/app/src/test/java/com/fintech/mobile/ui/newtransaction/NewTransactionViewModelTest.kt`

**Interfaces:**
- Consumes: `AmountParser` (Tarefa 5), `AccountRepository`/`CategoryRepository` (Tarefa 8), `TransactionRepository`/`CreateTransactionResult` (Tarefa 9), `com.fintech.mobile.api.model.{AccountResponse, AccountType, CategoryResponseDTO, TransactionRequestDTO, TransactionType}` (Tarefa 2).
- Produces: `NewTransactionFormValidator.validate(...): FormValidationResult`; `NewTransactionViewModel` expõe `uiState: StateFlow<NewTransactionUiState>` e `submit()`; `NewTransactionScreen(onSaved: () -> Unit)` — usado pela navegação na Tarefa 13.

- [ ] **Step 1: Escrever o teste do validador**

`android/app/src/test/java/com/fintech/mobile/ui/newtransaction/NewTransactionFormValidatorTest.kt`:
```kotlin
package com.fintech.mobile.ui.newtransaction

import org.junit.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NewTransactionFormValidatorTest {

    private val accountId = UUID.randomUUID()

    @Test
    fun `valid form without installments parses amount and account`() {
        val result = NewTransactionFormValidator.validate(
            description = "Mercado",
            amountText = "150,00",
            accountId = accountId,
            totalInstallmentsText = "",
            requiresInstallments = false
        )

        assertIs<FormValidationResult.Valid>(result)
        assertEquals("Mercado", result.form.description)
        assertEquals(150.0, result.form.amount)
        assertEquals(accountId, result.form.accountId)
        assertEquals(null, result.form.totalInstallments)
    }

    @Test
    fun `blank description is a field error`() {
        val result = NewTransactionFormValidator.validate(
            description = "  ",
            amountText = "150,00",
            accountId = accountId,
            totalInstallmentsText = "",
            requiresInstallments = false
        )

        assertIs<FormValidationResult.Invalid>(result)
        assertTrue(result.fieldErrors.containsKey("description"))
    }

    @Test
    fun `unparseable amount is a field error`() {
        val result = NewTransactionFormValidator.validate(
            description = "Mercado",
            amountText = "abc",
            accountId = accountId,
            totalInstallmentsText = "",
            requiresInstallments = false
        )

        assertIs<FormValidationResult.Invalid>(result)
        assertTrue(result.fieldErrors.containsKey("amount"))
    }

    @Test
    fun `missing account is a field error`() {
        val result = NewTransactionFormValidator.validate(
            description = "Mercado",
            amountText = "150,00",
            accountId = null,
            totalInstallmentsText = "",
            requiresInstallments = false
        )

        assertIs<FormValidationResult.Invalid>(result)
        assertTrue(result.fieldErrors.containsKey("accountId"))
    }

    @Test
    fun `valid installments count is parsed when required`() {
        val result = NewTransactionFormValidator.validate(
            description = "Notebook",
            amountText = "3000",
            accountId = accountId,
            totalInstallmentsText = "10",
            requiresInstallments = true
        )

        assertIs<FormValidationResult.Valid>(result)
        assertEquals(10, result.form.totalInstallments)
    }

    @Test
    fun `zero installments is a field error when required`() {
        val result = NewTransactionFormValidator.validate(
            description = "Notebook",
            amountText = "3000",
            accountId = accountId,
            totalInstallmentsText = "0",
            requiresInstallments = true
        )

        assertIs<FormValidationResult.Invalid>(result)
        assertTrue(result.fieldErrors.containsKey("totalInstallments"))
    }
}
```

- [ ] **Step 2: Rodar o teste e confirmar que falha**

```bash
./gradlew :app:testDebugUnitTest --tests "com.fintech.mobile.ui.newtransaction.NewTransactionFormValidatorTest"
```

Expected: FAIL — `Unresolved reference: NewTransactionFormValidator`.

- [ ] **Step 3: Implementar o validador**

`android/app/src/main/java/com/fintech/mobile/ui/newtransaction/NewTransactionFormValidator.kt`:
```kotlin
package com.fintech.mobile.ui.newtransaction

import com.fintech.mobile.core.format.AmountParser
import java.util.UUID

data class ParsedTransactionForm(
    val description: String,
    val amount: Double,
    val accountId: UUID,
    val totalInstallments: Int?
)

sealed class FormValidationResult {
    data class Valid(val form: ParsedTransactionForm) : FormValidationResult()
    data class Invalid(val fieldErrors: Map<String, String>) : FormValidationResult()
}

object NewTransactionFormValidator {

    fun validate(
        description: String,
        amountText: String,
        accountId: UUID?,
        totalInstallmentsText: String,
        requiresInstallments: Boolean
    ): FormValidationResult {
        val errors = mutableMapOf<String, String>()

        if (description.isBlank()) errors["description"] = "Informe uma descrição"

        val amount = AmountParser.parse(amountText)
        if (amount == null || amount < 0.01) errors["amount"] = "Informe um valor válido"

        if (accountId == null) errors["accountId"] = "Selecione uma conta"

        var totalInstallments: Int? = null
        if (requiresInstallments && totalInstallmentsText.isNotBlank()) {
            totalInstallments = totalInstallmentsText.toIntOrNull()
            if (totalInstallments == null || totalInstallments < 1) {
                errors["totalInstallments"] = "Número de parcelas inválido"
            }
        }

        if (errors.isNotEmpty()) return FormValidationResult.Invalid(errors)

        return FormValidationResult.Valid(
            ParsedTransactionForm(
                description = description.trim(),
                amount = amount!!,
                accountId = accountId!!,
                totalInstallments = totalInstallments
            )
        )
    }
}
```

- [ ] **Step 4: Rodar o teste de novo e confirmar que passa**

```bash
./gradlew :app:testDebugUnitTest --tests "com.fintech.mobile.ui.newtransaction.NewTransactionFormValidatorTest"
```

Expected: PASS (6 testes).

- [ ] **Step 5: Escrever o teste do `NewTransactionViewModel`**

`android/app/src/test/java/com/fintech/mobile/ui/newtransaction/NewTransactionViewModelTest.kt`:
```kotlin
package com.fintech.mobile.ui.newtransaction

import com.fintech.mobile.api.model.AccountResponse
import com.fintech.mobile.api.model.AccountType
import com.fintech.mobile.core.network.ApiResult
import com.fintech.mobile.data.repository.AccountRepository
import com.fintech.mobile.data.repository.CategoryRepository
import com.fintech.mobile.data.repository.CreateTransactionResult
import com.fintech.mobile.data.repository.TransactionRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class NewTransactionViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val accountId = UUID.randomUUID()

    @Before
    fun setUp() { Dispatchers.setMain(dispatcher) }

    @After
    fun tearDown() { Dispatchers.resetMain() }

    private fun buildViewModel(
        accounts: List<AccountResponse> = emptyList(),
        transactionRepository: TransactionRepository = mockk()
    ): NewTransactionViewModel {
        val accountRepository = mockk<AccountRepository>()
        coEvery { accountRepository.listAccounts() } returns ApiResult.Success(accounts)
        val categoryRepository = mockk<CategoryRepository>()
        coEvery { categoryRepository.listCategories() } returns ApiResult.Success(emptyList())

        val viewModel = NewTransactionViewModel(accountRepository, categoryRepository, transactionRepository)
        dispatcher.scheduler.advanceUntilIdle()
        return viewModel
    }

    @Test
    fun `shows the installments field only when the selected account is a credit card`() {
        val creditCard = AccountResponse(
            id = accountId, name = "Cartão", type = AccountType.CREDIT_CARD,
            countInLiquidBalance = false, countInNetWorth = true, active = true, balance = 0.0
        )
        val viewModel = buildViewModel(accounts = listOf(creditCard))

        viewModel.onAccountChange(accountId)

        assertTrue(viewModel.uiState.value.showInstallments)
    }

    @Test
    fun `submitting an invalid form sets field errors without calling the repository`() {
        val transactionRepository = mockk<TransactionRepository>()
        val viewModel = buildViewModel(transactionRepository = transactionRepository)

        viewModel.submit()

        assertTrue(viewModel.uiState.value.fieldErrors.isNotEmpty())
    }

    @Test
    fun `successful submit sets the Saved banner`() = runTest {
        val checking = AccountResponse(
            id = accountId, name = "Conta corrente", type = AccountType.CHECKING,
            countInLiquidBalance = true, countInNetWorth = true, active = true, balance = 0.0
        )
        val transactionRepository = mockk<TransactionRepository>()
        coEvery { transactionRepository.create(any()) } returns CreateTransactionResult.Saved
        val viewModel = buildViewModel(accounts = listOf(checking), transactionRepository = transactionRepository)

        viewModel.onDescriptionChange("Mercado")
        viewModel.onAmountChange("150,00")
        viewModel.onAccountChange(accountId)
        viewModel.submit()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(SubmitBanner.SAVED, viewModel.uiState.value.banner)
    }
}
```

- [ ] **Step 6: Rodar o teste e confirmar que falha**

```bash
./gradlew :app:testDebugUnitTest --tests "com.fintech.mobile.ui.newtransaction.NewTransactionViewModelTest"
```

Expected: FAIL — `Unresolved reference: NewTransactionViewModel` / `NewTransactionUiState` / `SubmitBanner`.

- [ ] **Step 7: Implementar `NewTransactionUiState` e `NewTransactionViewModel`**

`android/app/src/main/java/com/fintech/mobile/ui/newtransaction/NewTransactionUiState.kt`:
```kotlin
package com.fintech.mobile.ui.newtransaction

import com.fintech.mobile.api.model.AccountResponse
import com.fintech.mobile.api.model.AccountType
import com.fintech.mobile.api.model.CategoryResponseDTO
import com.fintech.mobile.api.model.TransactionType
import java.time.LocalDate
import java.util.UUID

enum class SubmitBanner { SAVED, QUEUED }

data class NewTransactionUiState(
    val description: String = "",
    val amountText: String = "",
    val date: LocalDate = LocalDate.now(),
    val type: TransactionType = TransactionType.EXPENSE,
    val accounts: List<AccountResponse> = emptyList(),
    val selectedAccountId: UUID? = null,
    val categories: List<CategoryResponseDTO> = emptyList(),
    val selectedCategoryId: UUID? = null,
    val totalInstallmentsText: String = "",
    val isSubmitting: Boolean = false,
    val fieldErrors: Map<String, String> = emptyMap(),
    val submitError: String? = null,
    val banner: SubmitBanner? = null
) {
    val selectedAccountType: AccountType?
        get() = accounts.find { it.id == selectedAccountId }?.type

    val showInstallments: Boolean
        get() = selectedAccountType == AccountType.CREDIT_CARD
}
```

`android/app/src/main/java/com/fintech/mobile/ui/newtransaction/NewTransactionViewModel.kt`:
```kotlin
package com.fintech.mobile.ui.newtransaction

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fintech.mobile.api.model.TransactionRequestDTO
import com.fintech.mobile.core.network.ApiResult
import com.fintech.mobile.data.repository.AccountRepository
import com.fintech.mobile.data.repository.CategoryRepository
import com.fintech.mobile.data.repository.CreateTransactionResult
import com.fintech.mobile.data.repository.TransactionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class NewTransactionViewModel @Inject constructor(
    private val accountRepository: AccountRepository,
    private val categoryRepository: CategoryRepository,
    private val transactionRepository: TransactionRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(NewTransactionUiState())
    val uiState: StateFlow<NewTransactionUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            when (val accounts = accountRepository.listAccounts()) {
                is ApiResult.Success -> _uiState.value = _uiState.value.copy(accounts = accounts.value)
                else -> Unit
            }
        }
        viewModelScope.launch {
            when (val categories = categoryRepository.listCategories()) {
                is ApiResult.Success -> _uiState.value = _uiState.value.copy(categories = categories.value)
                else -> Unit
            }
        }
    }

    fun onDescriptionChange(value: String) { _uiState.value = _uiState.value.copy(description = value) }
    fun onAmountChange(value: String) { _uiState.value = _uiState.value.copy(amountText = value) }
    fun onDateChange(value: LocalDate) { _uiState.value = _uiState.value.copy(date = value) }
    fun onAccountChange(value: UUID) { _uiState.value = _uiState.value.copy(selectedAccountId = value) }
    fun onCategoryChange(value: UUID?) { _uiState.value = _uiState.value.copy(selectedCategoryId = value) }
    fun onTotalInstallmentsChange(value: String) { _uiState.value = _uiState.value.copy(totalInstallmentsText = value) }

    fun submit() {
        val state = _uiState.value
        when (
            val validation = NewTransactionFormValidator.validate(
                description = state.description,
                amountText = state.amountText,
                accountId = state.selectedAccountId,
                totalInstallmentsText = state.totalInstallmentsText,
                requiresInstallments = state.showInstallments
            )
        ) {
            is FormValidationResult.Invalid -> {
                _uiState.value = state.copy(fieldErrors = validation.fieldErrors)
            }
            is FormValidationResult.Valid -> {
                _uiState.value = state.copy(isSubmitting = true, fieldErrors = emptyMap(), submitError = null)
                viewModelScope.launch {
                    val dto = TransactionRequestDTO(
                        description = validation.form.description,
                        amount = validation.form.amount,
                        date = state.date,
                        type = state.type,
                        accountId = validation.form.accountId,
                        categoryId = state.selectedCategoryId,
                        totalInstallments = validation.form.totalInstallments
                    )
                    when (val result = transactionRepository.create(dto)) {
                        is CreateTransactionResult.Saved ->
                            _uiState.value = _uiState.value.copy(isSubmitting = false, banner = SubmitBanner.SAVED)
                        is CreateTransactionResult.Queued ->
                            _uiState.value = _uiState.value.copy(isSubmitting = false, banner = SubmitBanner.QUEUED)
                        is CreateTransactionResult.Failed ->
                            _uiState.value = _uiState.value.copy(
                                isSubmitting = false,
                                submitError = result.message,
                                fieldErrors = result.fieldErrors
                            )
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 8: Rodar o teste de novo e confirmar que passa**

```bash
./gradlew :app:testDebugUnitTest --tests "com.fintech.mobile.ui.newtransaction.NewTransactionViewModelTest"
```

Expected: PASS (3 testes).

- [ ] **Step 9: Implementar `NewTransactionScreen` (sem teste — UI Compose)**

`android/app/src/main/java/com/fintech/mobile/ui/newtransaction/NewTransactionScreen.kt`:
```kotlin
package com.fintech.mobile.ui.newtransaction

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewTransactionScreen(
    onSaved: () -> Unit,
    viewModel: NewTransactionViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(uiState.banner) {
        if (uiState.banner != null) onSaved()
    }

    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        OutlinedTextField(
            value = uiState.description,
            onValueChange = viewModel::onDescriptionChange,
            label = { Text("Descrição") },
            isError = uiState.fieldErrors.containsKey("description"),
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = uiState.amountText,
            onValueChange = viewModel::onAmountChange,
            label = { Text("Valor") },
            isError = uiState.fieldErrors.containsKey("amount"),
            modifier = Modifier.fillMaxWidth()
        )

        var accountMenuExpanded by remember { mutableStateOf(false) }
        val selectedAccountName = uiState.accounts.find { it.id == uiState.selectedAccountId }?.name ?: ""
        ExposedDropdownMenuBox(expanded = accountMenuExpanded, onExpandedChange = { accountMenuExpanded = it }) {
            OutlinedTextField(
                value = selectedAccountName,
                onValueChange = {},
                readOnly = true,
                label = { Text("Conta") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = accountMenuExpanded) },
                isError = uiState.fieldErrors.containsKey("accountId"),
                modifier = Modifier.fillMaxWidth()
            )
            ExposedDropdownMenuDefaults.run {
                androidx.compose.material3.ExposedDropdownMenu(
                    expanded = accountMenuExpanded,
                    onDismissRequest = { accountMenuExpanded = false }
                ) {
                    uiState.accounts.forEach { account ->
                        DropdownMenuItem(
                            text = { Text(account.name) },
                            onClick = {
                                viewModel.onAccountChange(account.id)
                                accountMenuExpanded = false
                            }
                        )
                    }
                }
            }
        }

        if (uiState.showInstallments) {
            OutlinedTextField(
                value = uiState.totalInstallmentsText,
                onValueChange = viewModel::onTotalInstallmentsChange,
                label = { Text("Número de parcelas") },
                isError = uiState.fieldErrors.containsKey("totalInstallments"),
                modifier = Modifier.fillMaxWidth()
            )
        }

        uiState.submitError?.let { Text(it) }

        Button(onClick = viewModel::submit) {
            Text(if (uiState.isSubmitting) "Salvando..." else "Salvar")
        }
    }
}
```

- [ ] **Step 10: Compilar o módulo**

```bash
./gradlew :app:compileDebugKotlin
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 11: Commit**

```bash
git add android/app/src/main/java/com/fintech/mobile/ui/newtransaction android/app/src/test/java/com/fintech/mobile/ui/newtransaction
git commit -m "feat(android): formulário de novo lançamento com parcelamento condicional"
```

---

### Task 11: `SyncWorker` (WorkManager, drena a fila)

**Files:**
- Create: `android/app/src/main/java/com/fintech/mobile/sync/SyncWorker.kt`
- Modify: `android/app/src/main/java/com/fintech/mobile/MobileApp.kt`
- Test: `android/app/src/test/java/com/fintech/mobile/sync/SyncWorkerTest.kt`

**Interfaces:**
- Consumes: `com.fintech.mobile.api.TransactionsApi` (Tarefa 2), `PendingTransactionDao`/`PendingTransactionEntity` (Tarefa 6), `ApiResult`/`apiCall` (Tarefa 4).
- Produces: `SyncWorker` (`CoroutineWorker`), agendado em `MobileApp.onCreate()` — periódico (15 min) + disparo único ao iniciar o app, ambos com `NetworkType.CONNECTED`.

- [ ] **Step 1: Escrever o teste**

`android/app/src/test/java/com/fintech/mobile/sync/SyncWorkerTest.kt`:
```kotlin
package com.fintech.mobile.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.fintech.mobile.api.TransactionsApi
import com.fintech.mobile.api.model.TransactionRequestDTO
import com.fintech.mobile.api.model.TransactionResponseDTO
import com.fintech.mobile.api.model.TransactionType
import com.fintech.mobile.data.local.PendingTransactionDao
import com.fintech.mobile.data.local.PendingTransactionEntity
import com.google.gson.Gson
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Response
import java.io.IOException
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncWorkerTest {

    private val gson = Gson()
    private val sampleDto = TransactionRequestDTO(
        description = "Mercado",
        amount = 150.0,
        date = LocalDate.of(2026, 7, 31),
        type = TransactionType.EXPENSE,
        accountId = UUID.randomUUID()
    )

    private fun buildWorker(api: TransactionsApi, dao: PendingTransactionDao): SyncWorker {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return TestListenableWorkerBuilder<SyncWorker>(context)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters
                ) = SyncWorker(appContext, workerParameters, api, dao, gson)
            })
            .build()
    }

    @Test
    fun `removes a pending item after a successful sync`() = runTest {
        val pending = PendingTransactionEntity(localId = 1, payloadJson = gson.toJson(sampleDto), createdAt = 1L)
        val api = mockk<TransactionsApi>()
        coEvery { api.createTransaction(sampleDto) } returns Response.success(emptyList<TransactionResponseDTO>())
        val dao = mockk<PendingTransactionDao>()
        coEvery { dao.getByStatus(PendingTransactionEntity.STATUS_PENDING) } returns listOf(pending)
        coEvery { dao.delete(1L) } returns Unit

        val result = buildWorker(api, dao).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        coVerify { dao.delete(1L) }
    }

    @Test
    fun `marks the item as failed on validation error and keeps the worker successful`() = runTest {
        val pending = PendingTransactionEntity(localId = 1, payloadJson = gson.toJson(sampleDto), createdAt = 1L)
        val api = mockk<TransactionsApi>()
        val body = """{"message":"valor inválido"}""".toResponseBody("application/json".toMediaType())
        coEvery { api.createTransaction(sampleDto) } returns Response.error(400, body)
        val dao = mockk<PendingTransactionDao>()
        coEvery { dao.getByStatus(PendingTransactionEntity.STATUS_PENDING) } returns listOf(pending)
        coEvery { dao.updateStatus(1L, PendingTransactionEntity.STATUS_FAILED, "valor inválido") } returns Unit

        val result = buildWorker(api, dao).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        coVerify { dao.updateStatus(1L, PendingTransactionEntity.STATUS_FAILED, "valor inválido") }
    }

    @Test
    fun `requests a retry when a network error happens`() = runTest {
        val pending = PendingTransactionEntity(localId = 1, payloadJson = gson.toJson(sampleDto), createdAt = 1L)
        val api = mockk<TransactionsApi>()
        coEvery { api.createTransaction(sampleDto) } throws IOException("sem conexão")
        val dao = mockk<PendingTransactionDao>()
        coEvery { dao.getByStatus(PendingTransactionEntity.STATUS_PENDING) } returns listOf(pending)

        val result = buildWorker(api, dao).doWork()

        assertEquals(ListenableWorker.Result.retry(), result)
    }
}
```

- [ ] **Step 2: Rodar o teste e confirmar que falha**

```bash
./gradlew :app:testDebugUnitTest --tests "com.fintech.mobile.sync.SyncWorkerTest"
```

Expected: FAIL — `Unresolved reference: SyncWorker`.

- [ ] **Step 3: Implementar `SyncWorker`**

`android/app/src/main/java/com/fintech/mobile/sync/SyncWorker.kt`:
```kotlin
package com.fintech.mobile.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.fintech.mobile.api.TransactionsApi
import com.fintech.mobile.api.model.TransactionRequestDTO
import com.fintech.mobile.core.network.ApiResult
import com.fintech.mobile.core.network.apiCall
import com.fintech.mobile.data.local.PendingTransactionDao
import com.fintech.mobile.data.local.PendingTransactionEntity
import com.google.gson.Gson
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val transactionsApi: TransactionsApi,
    private val pendingDao: PendingTransactionDao,
    private val gson: Gson
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val pending = pendingDao.getByStatus(PendingTransactionEntity.STATUS_PENDING)
        for (item in pending) {
            val dto = gson.fromJson(item.payloadJson, TransactionRequestDTO::class.java)
            when (val result = apiCall(gson) { transactionsApi.createTransaction(dto) }) {
                is ApiResult.Success -> pendingDao.delete(item.localId)
                is ApiResult.ValidationError ->
                    pendingDao.updateStatus(item.localId, PendingTransactionEntity.STATUS_FAILED, result.message)
                is ApiResult.HttpError ->
                    pendingDao.updateStatus(item.localId, PendingTransactionEntity.STATUS_FAILED, result.message)
                is ApiResult.NetworkError -> return Result.retry()
            }
        }
        return Result.success()
    }
}
```

- [ ] **Step 4: Rodar o teste de novo e confirmar que passa**

```bash
./gradlew :app:testDebugUnitTest --tests "com.fintech.mobile.sync.SyncWorkerTest"
```

Expected: PASS (3 testes).

- [ ] **Step 5: Agendar o `SyncWorker` em `MobileApp`**

Atualizar `android/app/src/main/java/com/fintech/mobile/MobileApp.kt`:
```kotlin
package com.fintech.mobile

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.fintech.mobile.sync.SyncWorker
import dagger.hilt.android.HiltAndroidApp
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltAndroidApp
class MobileApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        scheduleSync()
    }

    private fun scheduleSync() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val workManager = WorkManager.getInstance(this)

        workManager.enqueueUniquePeriodicWork(
            PERIODIC_SYNC_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
        )

        workManager.enqueueUniqueWork(
            STARTUP_SYNC_NAME,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints)
                .build()
        )
    }

    private companion object {
        const val PERIODIC_SYNC_NAME = "sync-pending-transactions-periodic"
        const val STARTUP_SYNC_NAME = "sync-pending-transactions-startup"
    }
}
```

- [ ] **Step 6: Compilar o módulo**

```bash
./gradlew :app:compileDebugKotlin
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit**

```bash
git add android/app/src/main/java/com/fintech/mobile/sync android/app/src/main/java/com/fintech/mobile/MobileApp.kt android/app/src/test/java/com/fintech/mobile/sync
git commit -m "feat(android): SyncWorker drena o outbox (periódico + ao iniciar o app)"
```

---

### Task 12: Lista de transações (`TransactionListViewModel` + `TransactionListScreen`)

**Files:**
- Create: `android/app/src/main/java/com/fintech/mobile/ui/transactionlist/TransactionListUiState.kt`
- Create: `android/app/src/main/java/com/fintech/mobile/ui/transactionlist/TransactionListViewModel.kt`
- Create: `android/app/src/main/java/com/fintech/mobile/ui/transactionlist/TransactionListScreen.kt`
- Test: `android/app/src/test/java/com/fintech/mobile/ui/transactionlist/TransactionListViewModelTest.kt`

**Interfaces:**
- Consumes: `TransactionRepository` (Tarefa 9), `PendingTransactionEntity` (Tarefa 6), `com.fintech.mobile.api.model.TransactionResponseDTO` (Tarefa 2).
- Produces: `TransactionListViewModel` expõe `uiState: StateFlow<TransactionListUiState>`, `refresh()`, `discardPending(localId: Long)`; `TransactionListScreen(onAddTransaction: () -> Unit)` — usado pela navegação na Tarefa 13.

- [ ] **Step 1: Escrever o teste**

`android/app/src/test/java/com/fintech/mobile/ui/transactionlist/TransactionListViewModelTest.kt`:
```kotlin
package com.fintech.mobile.ui.transactionlist

import com.fintech.mobile.api.model.TransactionResponseDTO
import com.fintech.mobile.api.model.TransactionStatus
import com.fintech.mobile.api.model.TransactionType
import com.fintech.mobile.core.network.ApiResult
import com.fintech.mobile.data.local.PendingTransactionEntity
import com.fintech.mobile.data.repository.TransactionRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class TransactionListViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() { Dispatchers.setMain(dispatcher) }

    @After
    fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun `loads remote transactions and pending items on init`() {
        val remote = TransactionResponseDTO(
            id = UUID.randomUUID(), description = "Mercado", amount = 150.0,
            date = LocalDate.of(2026, 7, 31), type = TransactionType.EXPENSE, status = TransactionStatus.PENDING
        )
        val pending = PendingTransactionEntity(localId = 1, payloadJson = "{}", createdAt = 1L)

        val repository = mockk<TransactionRepository>()
        every { repository.observePending() } returns MutableStateFlow(listOf(pending))
        coEvery { repository.listRemote() } returns ApiResult.Success(listOf(remote))

        val viewModel = TransactionListViewModel(repository)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf(remote), viewModel.uiState.value.transactions)
        assertEquals(listOf(pending), viewModel.uiState.value.pending)
    }

    @Test
    fun `shows an error message when the refresh fails with a network error`() {
        val repository = mockk<TransactionRepository>()
        every { repository.observePending() } returns MutableStateFlow(emptyList())
        coEvery { repository.listRemote() } returns ApiResult.NetworkError(RuntimeException("sem conexão"))

        val viewModel = TransactionListViewModel(repository)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("Sem conexão. Tente novamente.", viewModel.uiState.value.error)
    }
}
```

- [ ] **Step 2: Rodar o teste e confirmar que falha**

```bash
./gradlew :app:testDebugUnitTest --tests "com.fintech.mobile.ui.transactionlist.TransactionListViewModelTest"
```

Expected: FAIL — `Unresolved reference: TransactionListViewModel` / `TransactionListUiState`.

- [ ] **Step 3: Implementar `TransactionListUiState` e `TransactionListViewModel`**

`android/app/src/main/java/com/fintech/mobile/ui/transactionlist/TransactionListUiState.kt`:
```kotlin
package com.fintech.mobile.ui.transactionlist

import com.fintech.mobile.api.model.TransactionResponseDTO
import com.fintech.mobile.data.local.PendingTransactionEntity

data class TransactionListUiState(
    val isLoading: Boolean = true,
    val transactions: List<TransactionResponseDTO> = emptyList(),
    val pending: List<PendingTransactionEntity> = emptyList(),
    val error: String? = null
)
```

`android/app/src/main/java/com/fintech/mobile/ui/transactionlist/TransactionListViewModel.kt`:
```kotlin
package com.fintech.mobile.ui.transactionlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fintech.mobile.core.network.ApiResult
import com.fintech.mobile.data.repository.TransactionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TransactionListViewModel @Inject constructor(
    private val transactionRepository: TransactionRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(TransactionListUiState())
    val uiState: StateFlow<TransactionListUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            transactionRepository.observePending().collect { pending ->
                _uiState.value = _uiState.value.copy(pending = pending)
            }
        }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            _uiState.value = when (val result = transactionRepository.listRemote()) {
                is ApiResult.Success -> _uiState.value.copy(isLoading = false, transactions = result.value)
                is ApiResult.NetworkError -> _uiState.value.copy(isLoading = false, error = "Sem conexão. Tente novamente.")
                else -> _uiState.value.copy(isLoading = false, error = "Não foi possível carregar as transações.")
            }
        }
    }

    fun discardPending(localId: Long) {
        viewModelScope.launch { transactionRepository.discardPending(localId) }
    }
}
```

- [ ] **Step 4: Rodar o teste de novo e confirmar que passa**

```bash
./gradlew :app:testDebugUnitTest --tests "com.fintech.mobile.ui.transactionlist.TransactionListViewModelTest"
```

Expected: PASS (2 testes).

- [ ] **Step 5: Implementar `TransactionListScreen` (sem teste — UI Compose)**

`android/app/src/main/java/com/fintech/mobile/ui/transactionlist/TransactionListScreen.kt`:
```kotlin
package com.fintech.mobile.ui.transactionlist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionListScreen(
    onAddTransaction: () -> Unit,
    viewModel: TransactionListViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Transações") },
                actions = {
                    IconButton(onClick = viewModel::refresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Atualizar")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddTransaction) {
                Icon(Icons.Filled.Add, contentDescription = "Novo lançamento")
            }
        }
    ) { padding ->
        if (uiState.isLoading && uiState.transactions.isEmpty()) {
            CircularProgressIndicator(modifier = Modifier.padding(padding))
            return@Scaffold
        }

        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            items(uiState.pending) { pending ->
                ListItem(
                    headlineContent = { Text("Pendente de envio") },
                    supportingContent = { pending.errorMessage?.let { Text(it) } }
                )
            }
            items(uiState.transactions) { transaction ->
                ListItem(
                    headlineContent = { Text(transaction.description) },
                    supportingContent = { Text("${transaction.date} · ${transaction.type}") },
                    trailingContent = { Text(transaction.amount.toString()) }
                )
            }
            uiState.error?.let { error ->
                item {
                    Row(horizontalArrangement = Arrangement.Center) { Text(error) }
                }
            }
        }
    }
}
```

- [ ] **Step 6: Compilar o módulo**

```bash
./gradlew :app:compileDebugKotlin
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit**

```bash
git add android/app/src/main/java/com/fintech/mobile/ui/transactionlist android/app/src/test/java/com/fintech/mobile/ui/transactionlist
git commit -m "feat(android): lista de transações com indicador de itens pendentes"
```

---

### Task 13: Navegação (`AppNavHost` + `MainActivity`)

**Files:**
- Create: `android/app/src/main/java/com/fintech/mobile/ui/navigation/SessionViewModel.kt`
- Create: `android/app/src/main/java/com/fintech/mobile/ui/navigation/AppNavHost.kt`
- Modify: `android/app/src/main/java/com/fintech/mobile/MainActivity.kt`

**Interfaces:**
- Consumes: `SessionManager` (Tarefa 3), `LoginScreen` (Tarefa 7), `NewTransactionScreen` (Tarefa 10), `TransactionListScreen` (Tarefa 12).
- Produces: `AppNavHost()` — ponto único de composição usado por `MainActivity`; nenhuma tarefa futura depende disso.

- [ ] **Step 1: Criar `SessionViewModel`**

`android/app/src/main/java/com/fintech/mobile/ui/navigation/SessionViewModel.kt`:
```kotlin
package com.fintech.mobile.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fintech.mobile.session.SessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class SessionViewModel @Inject constructor(
    sessionManager: SessionManager
) : ViewModel() {
    val isLoggedIn: StateFlow<Boolean> = sessionManager.tokenFlow
        .map { it != null }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = sessionManager.currentToken() != null
        )
}
```

- [ ] **Step 2: Criar `AppNavHost`**

`android/app/src/main/java/com/fintech/mobile/ui/navigation/AppNavHost.kt`:
```kotlin
package com.fintech.mobile.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.fintech.mobile.ui.login.LoginScreen
import com.fintech.mobile.ui.newtransaction.NewTransactionScreen
import com.fintech.mobile.ui.transactionlist.TransactionListScreen

object Routes {
    const val LOGIN = "login"
    const val TRANSACTION_LIST = "transaction_list"
    const val NEW_TRANSACTION = "new_transaction"
}

@Composable
fun AppNavHost(navController: NavHostController = rememberNavController()) {
    val sessionViewModel: SessionViewModel = hiltViewModel()
    val isLoggedIn by sessionViewModel.isLoggedIn.collectAsState()

    // 401 em qualquer chamada limpa a sessão (AuthInterceptor); esse efeito reage
    // e devolve o usuário para o Login mesmo que o NavHost já tenha sido composto.
    LaunchedEffect(isLoggedIn) {
        if (!isLoggedIn && navController.currentDestination?.route != Routes.LOGIN) {
            navController.navigate(Routes.LOGIN) {
                popUpTo(0) { inclusive = true }
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = if (isLoggedIn) Routes.TRANSACTION_LIST else Routes.LOGIN
    ) {
        composable(Routes.LOGIN) {
            LoginScreen(onLoginSuccess = {
                navController.navigate(Routes.TRANSACTION_LIST) {
                    popUpTo(Routes.LOGIN) { inclusive = true }
                }
            })
        }
        composable(Routes.TRANSACTION_LIST) {
            TransactionListScreen(onAddTransaction = { navController.navigate(Routes.NEW_TRANSACTION) })
        }
        composable(Routes.NEW_TRANSACTION) {
            NewTransactionScreen(onSaved = { navController.popBackStack() })
        }
    }
}
```

- [ ] **Step 3: Atualizar `MainActivity` para usar o `AppNavHost`**

`android/app/src/main/java/com/fintech/mobile/MainActivity.kt`:
```kotlin
package com.fintech.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.fintech.mobile.ui.navigation.AppNavHost
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppNavHost()
                }
            }
        }
    }
}
```

- [ ] **Step 4: Rodar toda a suíte e compilar**

```bash
cd android && ./gradlew :app:testDebugUnitTest :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`, todos os testes anteriores continuam passando.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/fintech/mobile/ui/navigation android/app/src/main/java/com/fintech/mobile/MainActivity.kt
git commit -m "feat(android): navegação reativa à sessão (Login ↔ Lista ↔ Novo lançamento)"
```

---

### Task 14: Checklist manual de QA (emulador contra backend local)

**Files:**
- Nenhum arquivo de código — verificação manual do app completo.

**Interfaces:**
- Consumes: app completo (Tarefas 1–13) + backend local rodando.
- Produces: confirmação de que o fluxo ponta a ponta funciona antes de considerar a v1 pronta.

- [ ] **Step 1: Subir o backend local com o dataset seed**

```bash
docker compose up -d
cd backend && ./mvnw spring-boot:run
```

Expected: backend saudável em `http://localhost:8080/actuator/health`. Credencial de teste: `carlos@costa.com` / `costa123` (ver `dataset.md`).

- [ ] **Step 2: Instalar e abrir o app no emulador**

```bash
cd android && ./gradlew :app:installDebug
```

Abra o app manualmente no emulador (`10.0.2.2` resolve para `localhost` da máquina host automaticamente).

- [ ] **Step 3: Testar login**

- Login com credencial errada → mensagem "E-mail ou senha inválidos", sem navegar.
- Login com `carlos@costa.com` / `costa123` → navega para a lista de transações.

- [ ] **Step 4: Testar lançamento simples (conta não-cartão)**

Na lista, toque no FAB, preencha descrição/valor/conta (uma `CHECKING` ou `CASH` do seed) e salve.
Expected: volta para a lista, transação nova aparece após o próximo refresh.

- [ ] **Step 5: Testar lançamento parcelado (conta cartão)**

Repita com uma conta `CREDIT_CARD` do seed — confirme que o campo "Número de parcelas" aparece só quando essa conta é selecionada, preencha com `3` parcelas e salve.
Expected: sucesso; confira no backend (`GET /api/transactions`) que 3 transações foram criadas com o mesmo `installmentGroupId`.

- [ ] **Step 6: Testar a fila offline**

Ative o modo avião no emulador. Lance uma nova transação.
Expected: mensagem indicando que foi salva localmente (banner `QUEUED`); o item aparece na lista com o indicador de "pendente de envio".

Desative o modo avião e aguarde (ou force `adb shell cmd jobscheduler run -f com.fintech.mobile <job-id-do-SyncWorker>` se quiser não esperar os 15 min).
Expected: o item pendente some da lista e a transação aparece confirmada.

- [ ] **Step 7: Testar expiração de sessão**

Pare o backend (`Ctrl+C` no `spring-boot:run`) e reinicie — isso invalida assinaturas antigas se o `JWT_SECRET` mudar, ou simplesmente pare o backend e tente qualquer ação.
Expected: sem o backend, chamadas caem em `NetworkError`, não em 401 — para testar o 401 de fato, expire manualmente o token (aguardar o tempo de expiração configurado) e tente atualizar a lista.
Expected: app limpa a sessão e volta para a tela de Login automaticamente.

- [ ] **Step 8: Registrar o resultado**

Se algum passo falhar, essa tarefa **não** está concluída — volte à tarefa de código correspondente antes de prosseguir. Sem commit nesta tarefa (nenhum arquivo alterado).

---

### Task 15: Atualizar `structure.md`

**Files:**
- Modify: `structure.md`

**Interfaces:**
- Consumes: nada.
- Produces: documentação da estrutura do repo refletindo o novo diretório `android/`.

- [ ] **Step 1: Adicionar a entrada do módulo Android**

Em `structure.md`, após o bloco `frontend/`, adicionar:
```
├── android/                     # App Android (Kotlin + Jetpack Compose)
│   ├── app/
│   │   ├── build.gradle.kts     # Codegen OpenAPI (openapi-generator, kotlin/jvm-retrofit2) + deps
│   │   └── src/
│   │       ├── main/java/com/fintech/mobile/
│   │       │   ├── core/        # ApiResult/apiCall, AmountParser — lógica pura, sem import Android
│   │       │   ├── data/        # local/ (Room outbox), repository/
│   │       │   ├── di/          # módulos Hilt (rede, sessão, banco)
│   │       │   ├── session/     # SessionManager, TokenProvider, AuthInterceptor
│   │       │   ├── sync/        # SyncWorker (WorkManager)
│   │       │   └── ui/          # Compose screens + ViewModels (login, transactionlist, newtransaction, navigation)
│   │       └── test/             # unit tests JVM (JUnit4, MockK, Robolectric)
│   └── settings.gradle.kts
```

- [ ] **Step 2: Commit**

```bash
git add structure.md
git commit -m "docs: documenta o módulo android/ em structure.md"
```

---

## Self-Review

**Cobertura da spec:** §1 (login/criar/outbox/lista) → Tarefas 7, 9–10, 6/11, 12. §2 decisões a–f → Global Constraints + Tarefas 1–3. §3 arquitetura → Tarefas 1–3, 6–13 seguem as camadas descritas. §4 telas/fluxo → Tarefas 7, 10, 12, 13. §5 outbox/sync → Tarefas 6, 9, 11. §6 erros → `ApiResult`/`apiCall` (Tarefa 4) + `AuthInterceptor` (Tarefa 3) + mapeamento em `LoginViewModel`/`NewTransactionViewModel`. §7 fora de escopo → nenhuma tarefa implementa esses itens. §8 testes → toda tarefa de lógica/repositório/worker tem teste JVM; UI Compose sem teste instrumentado, conforme decidido.

**Placeholders:** nenhum "TBD"/"implementar depois" — toda etapa tem código completo ou um checklist manual concreto (Tarefa 14).

**Consistência de tipos:** `ApiResult<T>` (Tarefa 4) usado identicamente em `AuthRepository`, `AccountRepository`, `CategoryRepository`, `TransactionRepository`, `SyncWorker`. `CreateTransactionResult` (Tarefa 9) consumido igual em `NewTransactionViewModel` (Tarefa 10). `PendingTransactionEntity`/`PendingTransactionDao` (Tarefa 6) com a mesma assinatura em `TransactionRepository` (Tarefa 9) e `SyncWorker` (Tarefa 11). `TokenProvider` (Tarefa 3) implementado por `SessionManager` e consumido por `AuthInterceptor` e `AuthRepository` sem divergência de assinatura.
