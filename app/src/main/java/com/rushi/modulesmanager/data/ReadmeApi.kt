package com.rushi.modulesmanager.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer

/**
 * Reads the README from raw.githubusercontent.com so it does not consume the GitHub API quota
 * used by live update checks. The API's rendered HTML is only a fallback.
 */
class ReadmeApi(private val client: OkHttpClient) {
    data class Document(val markdown: String?, val html: String)

    private val parser = Parser.builder().build()
    private val renderer = HtmlRenderer.builder()
        .extensions(listOf(TablesExtension.create()))
        .build()

    fun renderMarkdown(markdown: String): String = renderer.render(parser.parse(markdown))

    suspend fun fetch(repoFullName: String): Document? = withContext(Dispatchers.IO) {
        for (name in README_NAMES) {
            val raw = fetchText("https://raw.githubusercontent.com/$repoFullName/HEAD/$name")
            if (!raw.isNullOrBlank()) {
                return@withContext Document(raw, renderMarkdown(raw))
            }
        }
        fetchApiHtml(repoFullName)?.let { Document(null, it) }
    }

    private fun fetchText(url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", RepoApi.USER_AGENT)
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            response.body?.string()?.takeIf { it.isNotBlank() }
        }
    }

    private fun fetchApiHtml(repoFullName: String): String? {
        val request = Request.Builder()
            .url("https://api.github.com/repos/$repoFullName/readme")
            .header("User-Agent", RepoApi.USER_AGENT)
            .header("Accept", "application/vnd.github.html+json")
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            response.body?.string()?.takeIf { it.isNotBlank() }
        }
    }

    private companion object {
        val README_NAMES = listOf("README.md", "README.markdown", "readme.md")
    }
}
