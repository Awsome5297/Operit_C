package com.ai.assistance.operit.data.resources.models

import android.content.Context
import com.ai.assistance.operit.data.preferences.GitHubAuthPreferences
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException

/**
 * 私有仓库的 Release 资产不能用 releases/download 直链获取：该地址需要浏览器会话，
 * 携带 Bearer 头仍返回 404。正确做法是先查 asset id，再用 API 端点换取签名地址。
 * 签名地址不接受 Authorization 头，因此重定向后必须去掉认证头。
 */
internal object GitHubAssetAuth {
    private const val MAX_REDIRECTS = 3
    private const val API_BASE = "https://api.github.com"
    private val assetUrlPattern =
        Regex(
            "^https?://github\\.com/([^/]+)/([^/]+)/releases/download/([^/]+)/([^?]+)(?:\\?.*)?$",
            RegexOption.IGNORE_CASE,
        )

    private data class AssetRef(val owner: String, val repo: String, val tag: String, val name: String)

    suspend fun execute(context: Context, client: OkHttpClient, url: String): Response {
        val auth = GitHubAuthPreferences.getInstance(context).getAuthorizationHeader()
        val asset = parseAssetUrl(url)
        if (auth == null || asset == null) {
            return followRedirects(client, Request.Builder().url(url).build(), authenticated = false)
        }
        val assetId =
            resolveAssetId(client, auth, asset)
                ?: throw IOException("无法通过 API 定位发布资源：${asset.name}")
        val apiRequest =
            Request.Builder()
                .url("$API_BASE/repos/${asset.owner}/${asset.repo}/releases/assets/$assetId")
                .header("Authorization", auth)
                .header("Accept", "application/octet-stream")
                .build()
        return followRedirects(client, apiRequest, authenticated = true)
    }

    private fun parseAssetUrl(url: String): AssetRef? {
        val match = assetUrlPattern.matchEntire(url) ?: return null
        val (owner, repo, tag, name) = match.destructured
        return AssetRef(owner, repo, tag, name)
    }

    private fun resolveAssetId(client: OkHttpClient, auth: String, asset: AssetRef): Long? {
        val request =
            Request.Builder()
                .url("$API_BASE/repos/${asset.owner}/${asset.repo}/releases/tags/${asset.tag}")
                .header("Authorization", auth)
                .header("Accept", "application/vnd.github+json")
                .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            val assets = JSONObject(body).optJSONArray("assets") ?: return null
            for (index in 0 until assets.length()) {
                val item = assets.getJSONObject(index)
                if (item.optString("name") == asset.name) {
                    return item.optLong("id").takeIf { it > 0 }
                }
            }
        }
        return null
    }

    /** 签名地址只认可自身查询参数，携带认证头会返回 401。 */
    private fun followRedirects(
        client: OkHttpClient,
        initial: Request,
        authenticated: Boolean,
    ): Response {
        var request = initial
        var response = client.newCall(request).execute()
        var redirects = 0
        while (response.isRedirect && redirects < MAX_REDIRECTS) {
            val location = response.header("Location")
            response.close()
            if (location == null) break
            request =
                Request.Builder()
                    .url(location)
                    .apply { if (authenticated) removeHeader("Authorization") }
                    .build()
            response = client.newCall(request).execute()
            redirects++
        }
        return response
    }
}
