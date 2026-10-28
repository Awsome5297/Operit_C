package com.ai.assistance.operit.data.api

import com.ai.assistance.operit.util.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 直连 GitHub OAuth：不再依赖上游 broker，token 由用户自己的 OAuth App 颁发，
 * scope 由授权请求直接携带，保证具备私有仓库读取权限。
 */
class GitHubDirectOAuthService {

    @Serializable
    data class TokenExchangeResult(
        val accessToken: String,
        val tokenType: String,
        val scope: String,
        val refreshToken: String?,
    )

    private val client =
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    fun buildAuthorizationUrl(
        clientId: String,
        redirectUri: String,
        scope: String,
        state: String,
    ): String =
        okhttp3.HttpUrl.Companion.toHttpUrl("https://github.com/login/oauth/authorize")
            .newBuilder()
            .addQueryParameter("client_id", clientId)
            .addQueryParameter("redirect_uri", redirectUri)
            .addQueryParameter("scope", scope)
            .addQueryParameter("state", state)
            .build()
            .toString()

    suspend fun exchangeToken(
        clientId: String,
        clientSecret: String,
        redirectUri: String,
        code: String,
    ): Result<TokenExchangeResult> =
        withContext(Dispatchers.IO) {
            try {
                val body =
                    FormBody.Builder()
                        .add("client_id", clientId)
                        .add("client_secret", clientSecret)
                        .add("redirect_uri", redirectUri)
                        .add("code", code)
                        .build()
                val request =
                    Request.Builder()
                        .url("https://github.com/login/oauth/access_token")
                        .header("Accept", "application/json")
                        .post(body)
                        .build()
                client.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string()
                    if (!response.isSuccessful || responseBody == null) {
                        return@withContext Result.failure(
                            IllegalStateException("GitHub token exchange failed: HTTP ${response.code}"),
                        )
                    }
                    val payload = json.decodeFromString<TokenExchangePayload>(responseBody)
                    payload.error?.let { error ->
                        return@withContext Result.failure(
                            IllegalStateException(
                                error + (payload.errorDescription?.let { ": $it" } ?: ""),
                            ),
                        )
                    }
                    val token = payload.accessToken
                    if (token.isNullOrBlank()) {
                        return@withContext Result.failure(
                            IllegalStateException("GitHub token exchange response is missing access_token"),
                        )
                    }
                    Result.success(
                        TokenExchangeResult(
                            accessToken = token,
                            tokenType = payload.tokenType ?: "bearer",
                            scope = payload.scope.orEmpty(),
                            refreshToken = payload.refreshToken,
                        ),
                    )
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to exchange GitHub OAuth token", e)
                Result.failure(e)
            }
        }

    @Serializable
    private data class TokenExchangePayload(
        val accessToken: String? = null,
        val tokenType: String? = null,
        val scope: String? = null,
        val refreshToken: String? = null,
        val error: String? = null,
        val errorDescription: String? = null,
    )

    companion object {
        private const val TAG = "GitHubDirectOAuth"
    }
}
