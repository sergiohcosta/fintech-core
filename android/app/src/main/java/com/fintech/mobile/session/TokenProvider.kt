package com.fintech.mobile.session

interface TokenProvider {
    fun currentToken(): String?
    fun saveToken(token: String)
    fun clearToken()
}
