package com.fintech.mobile.di

import com.fintech.mobile.api.AccountsApi
import com.fintech.mobile.api.AuthApi
import com.fintech.mobile.api.CategoriesApi
import com.fintech.mobile.api.TransactionsApi
import com.fintech.mobile.api.infrastructure.Serializer
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

    // O openapi-generator já gera `Serializer.gson` com TypeAdapters de LocalDate/LocalDateTime/
    // OffsetDateTime (ISO-8601, sem reflexão). Um `Gson()` puro serializa java.time.* refletindo
    // os campos internos (year/month/day) em vez de "2026-07-31" — quebra o contrato com o
    // backend e, em JDK 17+, lança InaccessibleObjectException (módulo java.base fechado).
    @Provides
    @Singleton
    fun provideGson(): Gson = Serializer.gson

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
