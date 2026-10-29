package com.ai.assistance.operit.ui.features.extensions.github

import android.content.Context
import android.net.Uri
import com.ai.assistance.operit.BuildConfig
import com.ai.assistance.operit.data.api.GitHubDirectOAuthService
import com.ai.assistance.operit.data.api.GitHubApiService
import com.ai.assistance.operit.data.preferences.GitHubAuthPreferences
import com.ai.assistance.operit.data.preferences.GitHubUser
import com.ai.assistance.operit.util.logging.AppLogger
import kotlin.random.Random
import java.util.UUID

/**
 * 直连 GitHub OAuth 的登录协调器。client 凭据来自构建期注入的 BuildConfig，
 * 不经过上游 broker，token scope 由授权 URL 直接携带。
 */
class GitHubDirectOAuthCoordinator(context: Context) {
    private val githubAuth = GitHubAuthPreferences.getInstance(context.applicationContext)
    private val oauthService = GitHubDirectOAuthService()
    private val apiService = GitHubApiService(context)

    data class Started(
        val authorizationUrl: String,
        val redirectUri: String,
        val expiresAt: Long,
    )

    fun isConfigured(): Boolean =
        BuildConfig.GITHUB_OAUTH_CLIENT_ID.isNotBlank() && BuildConfig.GITHUB_OAUTH_CLIENT_SECRET.isNotBlank()

    suspend fun startEmbeddedLogin(): Result<Started> =
        startLogin("http://127.0.0.1:${Random.nextInt(10000, 65535)}/oauth/github/complete")

    suspend fun startLogin(redirectUri: String): Result<Started> = runCatching {
        val state = UUID.randomUUID().toString()
        githubAuth.saveDirectOAuthState(
            state = state,
            redirectUri = redirectUri,
            expiresAt = System.currentTimeMillis() + TRANSACTION_TIMEOUT_MILLIS,
        )
        Started(
            authorizationUrl =
                oauthService.buildAuthorizationUrl(
                    clientId = BuildConfig.GITHUB_OAUTH_CLIENT_ID,
                    redirectUri = redirectUri,
                    scope = OAUTH_SCOPE,
                    state = state,
                ),
            redirectUri = redirectUri,
            expiresAt = System.currentTimeMillis() + TRANSACTION_TIMEOUT_MILLIS,
        )
    }

    suspend fun completeLogin(completionUri: Uri): Result<GitHubUser> {
        val pending = githubAuth.getDirectOAuthState()
            ?: return Result.failure(IllegalStateException("No GitHub OAuth transaction is active"))
        return try {
            if (System.currentTimeMillis() > pending.expiresAt) {
                githubAuth.clearDirectOAuthState()
                return Result.failure(IllegalStateException("GitHub login transaction expired"))
            }
            val returnedState = completionUri.getQueryParameter("state")
            if (returnedState != pending.state) {
                throw IllegalStateException("GitHub OAuth state mismatch")
            }
            when (completionUri.getQueryParameter("error")) {
                null -> Unit
                "access_denied" -> {
                    githubAuth.clearDirectOAuthState()
                    return Result.failure(IllegalStateException("GitHub authorization was cancelled"))
                }
                else -> {
                    val error = completionUri.getQueryParameter("error").orEmpty()
                    githubAuth.clearDirectOAuthState()
                    return Result.failure(IllegalStateException(error))
                }
            }
            val code = completionUri.getQueryParameter("code")
            if (code.isNullOrBlank()) {
                throw IllegalStateException("GitHub OAuth completion is missing the code")
            }
            val token =
                oauthService.exchangeToken(
                    clientId = BuildConfig.GITHUB_OAUTH_CLIENT_ID,
                    clientSecret = BuildConfig.GITHUB_OAUTH_CLIENT_SECRET,
                    redirectUri = pending.redirectUri,
                    code = code,
                ).getOrElse { error -> throw error }
            val user = apiService.getCurrentUser().getOrElse { error -> throw error }
            githubAuth.saveAuthInfo(
                accessToken = token.accessToken,
                tokenType = token.tokenType,
                expiresIn = token.expiresIn,
                refreshToken = token.refreshToken,
                userInfo = user,
                grantedScope = token.scope,
            )
            githubAuth.clearDirectOAuthState()
            Result.success(user)
        } catch (e: Exception) {
            githubAuth.clearDirectOAuthState()
            AppLogger.e(TAG, "Failed to complete direct GitHub OAuth login", e)
            Result.failure(e)
        }
    }

    suspend fun cancelLogin() {
        githubAuth.clearDirectOAuthState()
    }

    companion object {
        private const val TAG = "GitHubDirectOAuth"
        private const val TRANSACTION_TIMEOUT_MILLIS = 10L * 60 * 1000
        private const val OAUTH_SCOPE = "repo,notifications,user:email,read:user"
    }
}
