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

// exportSchema = true (AppDatabase.kt) precisa deste diretório de saída para o KSP do Room
// gravar o schema JSON versionado. Commitado (não é build output) — é o histórico de
// migração, exigido no primeiro bump de versão do banco.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
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
        if (!transactionsApiFile.exists()) {
            throw org.gradle.api.GradleException(
                "fixGeneratedEnumDefaults: TransactionsApi.kt não existe mais no output do " +
                    "openapi-generator (${transactionsApiFile.path}). O patch do default de enum " +
                    "sem qualificação (bug OpenAPITools/openapi-generator #12531/#21437) não foi " +
                    "aplicado — se o gerador não emite mais esse bug, remova esta task; se só " +
                    "mudou o caminho do arquivo, atualize-o aqui."
            )
        }
        val original = transactionsApiFile.readText()
        val fixedMarker = "scope: DeleteInstallmentScope? = DeleteInstallmentScope.SINGLE"
        if (original.contains(fixedMarker)) {
            // Já patchado (ex: openApiGenerate ficou UP-TO-DATE e reusou o output de um build
            // anterior desta mesma sessão) — nada a fazer, não é uma falha do patch.
            return@doLast
        }
        val patched = original.replace(
            "scope: DeleteInstallmentScope? = SINGLE",
            fixedMarker
        )
        if (patched == original) {
            throw org.gradle.api.GradleException(
                "fixGeneratedEnumDefaults: padrão-alvo \"scope: DeleteInstallmentScope? = SINGLE\" " +
                    "não foi encontrado em TransactionsApi.kt. Provável bump do openapi-generator " +
                    "(bug corrigido upstream) ou mudança no api-spec/openapi.yaml — sem falhar aqui " +
                    "o build passaria verde e o Kotlin gerado voltaria a não compilar (ou, se o " +
                    "código mudou de outro jeito, o bug de enum sem qualificação voltaria em " +
                    "silêncio). Confira o arquivo gerado e ajuste o patch ou remova a task."
            )
        }
        transactionsApiFile.writeText(patched)
    }
}

// Mismatch de contrato descoberto em QA manual (Tarefa 14): TransactionResponseDTO.createdAt
// é LocalDateTime no backend (sem offset, ex: "2026-07-28T22:32:56.732702"), mas o gerador
// Kotlin mapeia campos date-time para OffsetDateTime, que exige offset e lança
// DateTimeParseException ao desserializar — crash real no primeiro GET /api/transactions após
// o login. Nenhum teste unitário pegou isso porque todos usam Response mockado (DTO já
// construído em memória), nunca desserialização de JSON de verdade. Como não editamos o
// contrato (api-spec/openapi.yaml, compartilhado com o frontend Angular) nem o código gerado
// (recriado a cada build), o adapter é reescrito para tentar OffsetDateTime.parse primeiro e,
// se faltar o offset, cair para LocalDateTime + assumir UTC (createdAt não é exibido nem usado
// por nenhuma tela desta v1 — só precisa parsear sem derrubar o app).
val fixGeneratedOffsetDateTimeAdapter = tasks.register("fixGeneratedOffsetDateTimeAdapter") {
    dependsOn("openApiGenerate")
    doLast {
        val adapterFile = file(
            "${layout.buildDirectory.get()}/generated/openapi/src/main/kotlin/com/fintech/mobile/api/infrastructure/OffsetDateTimeAdapter.kt"
        )
        if (!adapterFile.exists()) {
            throw org.gradle.api.GradleException(
                "fixGeneratedOffsetDateTimeAdapter: OffsetDateTimeAdapter.kt não existe mais no " +
                    "output do openapi-generator (${adapterFile.path}). O patch que evita " +
                    "DateTimeParseException ao desserializar createdAt (LocalDateTime sem offset " +
                    "no backend vs. OffsetDateTime esperado pelo gerador) não foi aplicado — o " +
                    "crash do primeiro GET /api/transactions após login pode voltar em silêncio. " +
                    "Atualize o caminho do arquivo ou remova a task se o gerador não precisar mais dele."
            )
        }
        val original = adapterFile.readText()
        val target = "                return OffsetDateTime.parse(out.nextString(), formatter)"
        val replacement = """                val raw = out.nextString()
                return try {
                    OffsetDateTime.parse(raw, formatter)
                } catch (e: java.time.format.DateTimeParseException) {
                    java.time.LocalDateTime.parse(raw).atOffset(java.time.ZoneOffset.UTC)
                }"""
        if (original.contains(replacement)) {
            // Já patchado nesta sessão (openApiGenerate UP-TO-DATE reusando output anterior).
            return@doLast
        }
        val patched = original.replace(target, replacement)
        if (patched == original) {
            throw org.gradle.api.GradleException(
                "fixGeneratedOffsetDateTimeAdapter: padrão-alvo do parse de OffsetDateTime não foi " +
                    "encontrado em OffsetDateTimeAdapter.kt. Provável bump do openapi-generator " +
                    "mudou o código emitido — sem falhar aqui o patch simplesmente para de agir e o " +
                    "DateTimeParseException (crash real, achado em QA manual) volta sem sinal. " +
                    "Confira o arquivo gerado e ajuste o alvo do replace ou remova a task."
            )
        }
        adapterFile.writeText(patched)
    }
}

// Mismatch descoberto em QA manual (Tarefa 14): o padrão `oneOf: [$ref] + nullable: true`
// usado no spec para campos nullable (ex: CreditCardDetailsResponse.brand → CardBrand) gera
// corretamente um wrapper quando o $ref aponta para um schema de objeto (ex: AccountResponse.
// creditCardDetails → AccountResponseCreditCardDetails, com campos reais), mas quando o $ref
// aponta para um ENUM (CardBrand), o gerador Kotlin produz uma classe vazia e inútil
// (CreditCardDetailsResponseBrand, sem TypeAdapter/valores) em vez de usar o enum direto —
// resultado: JsonSyntaxException ao desserializar "brand" (string) como objeto, crash real ao
// abrir a lista de contas com cartão de crédito. O lado *Request* do mesmo padrão gera
// corretamente "CardBrand?" (só o lado *Response* tem o bug), então a correção troca o tipo do
// campo para o enum real nos 2 arquivos afetados. Não alteramos api-spec/openapi.yaml (o padrão
// oneOf+nullable é usado deliberadamente em todo o contrato e funciona nos outros consumidores).
val fixGeneratedCreditCardBrand = tasks.register("fixGeneratedCreditCardBrand") {
    dependsOn("openApiGenerate")
    doLast {
        val modelDir = "${layout.buildDirectory.get()}/generated/openapi/src/main/kotlin/com/fintech/mobile/api/model"
        listOf("AccountResponseCreditCardDetails.kt", "CreditCardDetailsResponse.kt").forEach { name ->
            val f = file("$modelDir/$name")
            if (!f.exists()) {
                throw org.gradle.api.GradleException(
                    "fixGeneratedCreditCardBrand: $name não existe mais no output do " +
                        "openapi-generator (${f.path}). O patch que troca o wrapper vazio " +
                        "(oneOf+nullable sobre um \$ref de ENUM) pelo CardBrand real não foi " +
                        "aplicado — a JsonSyntaxException ao desserializar contas com cartão de " +
                        "crédito (crash real, achado em QA manual) pode voltar em silêncio. " +
                        "Atualize o caminho do arquivo ou remova a task se o gerador corrigiu o bug."
                )
            }
            val original = f.readText()
            if (original.contains("val brand: CardBrand? = null")) {
                // Já patchado nesta sessão (openApiGenerate UP-TO-DATE reusando output anterior).
                return@forEach
            }
            val patched = original
                .replace(
                    "val brand: CreditCardDetailsResponseBrand? = null",
                    "val brand: CardBrand? = null"
                )
                .replace(
                    "import com.fintech.mobile.api.model.CreditCardDetailsResponseBrand",
                    "import com.fintech.mobile.api.model.CardBrand"
                )
            if (patched == original) {
                throw org.gradle.api.GradleException(
                    "fixGeneratedCreditCardBrand: nenhum dos padrões-alvo (declaração de " +
                        "CreditCardDetailsResponseBrand ou seu import) foi encontrado em $name. " +
                        "Provável bump do openapi-generator mudou o código emitido para o padrão " +
                        "oneOf+nullable sobre enum — sem falhar aqui o patch para de agir e a " +
                        "JsonSyntaxException volta sem sinal. Confira o arquivo gerado e ajuste o " +
                        "patch ou remova a task se o bug upstream foi corrigido."
                )
            }
            f.writeText(patched)
        }
    }
}

tasks.named("preBuild") {
    dependsOn(fixGeneratedEnumDefaults)
    dependsOn(fixGeneratedOffsetDateTimeAdapter)
    dependsOn(fixGeneratedCreditCardBrand)
}
