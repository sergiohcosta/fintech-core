plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
    id("org.openapi.generator")
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
    // O ApiClient.kt gerado pelo openapi-generator (kotlin/jvm-retrofit2) importa
    // ScalarsConverterFactory incondicionalmente na infraestrutura, independente do
    // serializationLibrary configurado (gson) — sem essa dependência o código gerado
    // não compila (import não resolvido).
    implementation("com.squareup.retrofit2:converter-scalars:2.11.0")
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

// Bug conhecido do openapi-generator (kotlin/jvm-retrofit2): o valor default de um
// parâmetro de query do tipo enum é emitido sem qualificar o nome do enum
// (ex: "scope: DeleteInstallmentScope? = SINGLE" em vez de "= DeleteInstallmentScope.SINGLE"),
// o que não compila em Kotlin. Bug aberto upstream (OpenAPITools/openapi-generator #12531,
// #21437) sem fix disponível na 7.9.0. Como não podemos editar o código gerado (recriado a
// cada build) nem o contrato (api-spec/openapi.yaml é fonte única, imutável por convenção do
// projeto), corrigimos com um post-processing textual e específico logo após a geração.
val fixGeneratedEnumDefaults = tasks.register("fixGeneratedEnumDefaults") {
    dependsOn("openApiGenerate")
    doLast {
        val transactionsApiFile = file(
            "${layout.buildDirectory.get()}/generated/openapi/src/main/kotlin/com/fintech/mobile/api/TransactionsApi.kt"
        )
        if (transactionsApiFile.exists()) {
            val original = transactionsApiFile.readText()
            val patched = original.replace(
                "scope: DeleteInstallmentScope? = SINGLE",
                "scope: DeleteInstallmentScope? = DeleteInstallmentScope.SINGLE"
            )
            if (patched != original) {
                transactionsApiFile.writeText(patched)
            }
        }
    }
}

tasks.named("preBuild") {
    dependsOn(fixGeneratedEnumDefaults)
}

// Gson serializa java.time.LocalDate por reflexão quando não há TypeAdapter registrado
// (ex: testes que instanciam Gson() puro, como o TransactionRepositoryTest). A partir do
// JDK 17, o module system nega esse acesso reflexivo a java.base por padrão
// (InaccessibleObjectException) — só afeta a JVM host dos testes; no runtime Android (ART)
// não existe essa restrição.
tasks.withType<Test> {
    jvmArgs("--add-opens=java.base/java.time=ALL-UNNAMED")
}
