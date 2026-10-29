package com.ai.assistance.operit.data.api

import com.ai.assistance.operit.util.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
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
        val expiresIn: Long?,
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
        okhttp3.HttpUrl.Builder().scheme("https").host("github.com")
            .addPathSegment("login").addPathSegment("oauth").addPathSegment("authorize").build()
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
                    val responseBody = response.body?.string().orEmpty()
                    // JSON 解析失败时保留原文，便于从错误信息直接看出 GitHub 实际返回了什么。
                    val payload =
                        runCatching { json.decodeFromString<TokenExchangePayload>(responseBody) }
                            .getOrElse { return@withContext Result.failure(
                                IllegalStateException(
                                    "GitHub 返回了无法解析的响应（HTTP ${response.code}）：$responseBody",
                                    it,
                                ),
                            ) }
                    payload.error?.let { error ->
                        return@withContext Result.failure(
                            IllegalStateException(
                                error + (payload.errorDescription?.let { ": $it" } ?: ""),
                            ),
                        )
                    }
                    val token = payload.accessToken
                    if (token.isNullOrBlank()) {
                        AppLogger.e(
                            TAG,
                            "GitHub token exchange returned no access_token; body=$responseBody",
                        )
                        return@withContext Result.failure(
                            IllegalStateException(
                                "GitHub 未返回 access_token（HTTP ${response.code}），返回体：$responseBody。" +
                                    "常见原因：client_secret 不正确，或构建时未注入 OAuth 凭据。",
                            ),
                        )
                    }
                    Result.success(
                        TokenExchangeResult(
                            accessToken = token,
                            tokenType = payload.tokenType ?: "bearer",
                            scope = payload.scope.orEmpty(),
                            refreshToken = payload.refreshToken,
                            expiresIn = payload.expiresIn,
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
        // GitHub 返回下划线命名，必须显式映射，否则全部解析为 null。
        @SerialName("access_token") val accessToken: String? = null,
        @SerialName("token_type") val tokenType: String? = null,
        val scope: String? = null,
        @SerialName("refresh_token") val refreshToken: String? = null,
        @SerialName("expires_in") val expiresIn: Long? = null,
        @SerialName("refresh_token_expires_in") val refreshTokenExpiresIn: Long? = null,
        val error: String? = null,
        @SerialName("error_description") val errorDescription: String? = null,
    )

    companion object {
        private const val TAG = "GitHubDirectOAuth"
    }
}
