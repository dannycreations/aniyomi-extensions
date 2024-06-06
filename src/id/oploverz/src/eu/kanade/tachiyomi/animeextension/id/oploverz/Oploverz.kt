package eu.kanade.tachiyomi.animeextension.id.oploverz

import android.app.Application
import android.content.SharedPreferences
import android.util.Base64
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.BuildConfig
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import eu.kanade.tachiyomi.util.parallelMapNotNullBlocking
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Locale

class Oploverz : ConfigurableAnimeSource, AnimeHttpSource() {
    private val mainBaseUrl: String = "https://oploverz.host"

    override val name: String = "Oploverz"
    override val lang: String = "id"
    override val supportsLatest: Boolean = true
    override val baseUrl: String by lazy { getPrefBaseUrl() }

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request =
        GET("$baseUrl/anime/?page=$page&order=popular")

    override fun popularAnimeParse(response: Response): AnimesPage =
        getAnimeParse(response, "div.listupd > article")

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/anime/?page=$page&order=latest")

    override fun latestUpdatesParse(response: Response): AnimesPage =
        getAnimeParse(response, "div.listupd > article")

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = OploverzFilters.getSearchParameters(filters)
        return GET("$baseUrl/anime/page/$page/?s=$query${params.filter}")
    }

    override fun searchAnimeParse(response: Response): AnimesPage =
        getAnimeParse(response, "div.listupd > article")

    // ============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList = OploverzFilters.FILTER_LIST

    // =========================== Anime Details ============================

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoup()
        val detail = doc.selectFirst("div.info-content > div.spe")!!

        return SAnime.create().apply {
            author = detail.getInfo("Studio")
            status = parseStatus(detail.getInfo("Status"))
            title = doc.selectFirst("h1.entry-title")!!.text()
            thumbnail_url = doc.selectFirst("div.thumb > img")!!.attr("src")
            description = doc.select("div.entry-content > p").joinToString("\n\n") { it.text() }
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = response.asJsoup()

        return doc.select("div.eplister > ul > li").map {
            val episodeUrl = it.selectFirst("a")!!.attr("href")
            val episodeNumber = it.selectFirst("div.epl-num")!!.text().toFloatOrNull() ?: 1F
            val episodeName = it.selectFirst("div.epl-title")!!.text()
            val episodeDate = it.selectFirst("div.epl-date")!!.text().toDate()
            SEpisode.create().apply {
                setUrlWithoutDomain(episodeUrl)
                episode_number = episodeNumber
                name = episodeName
                date_upload = episodeDate
            }
        }
    }

    // ============================ Video Links =============================

    override fun videoListParse(response: Response): List<Video> {
        val doc = response.asJsoup()
        val parseUrl = response.request.url.toUrl()
        val url = "${parseUrl.protocol}://${parseUrl.host}"
        if (!getPrefBaseUrl().contains(url)) putPrefBaseUrl(url)

        return doc.select("select.mirror > option[value!=\"\"]")
            .parallelMapNotNullBlocking {
                runCatching { getEmbedLinks(it) }.getOrNull()
            }
            .parallelCatchingFlatMapBlocking {
                getVideosFromEmbed(it.first)
            }
    }

    // ============================= Utilities ==============================

    private fun getAnimeParse(response: Response, query: String): AnimesPage {
        val doc = response.asJsoup()
        val animes = doc.select(query).map {
            SAnime.create().apply {
                setUrlWithoutDomain(it.selectFirst("div.bsx > a")!!.attr("href"))
                title = it.selectFirst("div.tt > h2")!!.text()
                thumbnail_url = it.selectFirst("div.limit > img")!!.attr("src")
            }
        }

        val hasNextPage = try {
            val pagination = doc.selectFirst("div.hpage")!!
            pagination.selectFirst("a.r") != null
        } catch (_: Exception) {
            try {
                val pagination = doc.selectFirst("div.pagination")!!
                val totalPage = pagination.select("a.page-numbers").let {
                    it.elementAt(it.lastIndex - 1).text()
                }
                val currentPage = pagination.selectFirst("span.page-numbers.current")!!.text()
                currentPage.toInt() < totalPage.toInt()
            } catch (_: Exception) {
                false
            }
        }

        return AnimesPage(animes, hasNextPage)
    }

    private fun getEmbedLinks(element: Element): Pair<String, String> {
        val getFrame = Base64.decode(element.`val`(), Base64.DEFAULT).toString(Charsets.UTF_8)
        val parseFrame = Jsoup.parse(getFrame)
        val embedLink = parseFrame.selectFirst("iframe")!!.attr("src")
        return Pair(embedLink, "")
    }

    private fun getVideosFromEmbed(link: String): List<Video> {
        return when {
            "blogger" in link -> {
                client.newCall(GET(link)).execute().body.string().let {
                    val json = JSONObject(it.substringAfter("= ").substringBefore("<"))
                    val streams = json.getJSONArray("streams")
                    val videoList = mutableListOf<Video>()
                    for (i in 0 until streams.length()) {
                        val stream = streams.getJSONObject(i)
                        val videoUrl = stream.getString("play_url")
                        val videoQuality = when (stream.getString("format_id")) {
                            "18" -> "Google 360p"
                            "22" -> "Google 720p"
                            else -> "Unknown Resolution"
                        }
                        videoList.add(Video(videoUrl, videoQuality, videoUrl, headers))
                    }
                    videoList
                }
            }

            else -> emptyList()
        }
    }

    override fun List<Video>.sort(): List<Video> =
        sortedWith(compareByDescending { it.quality.contains(getPrefQuality()) })

    private fun String?.toDate(): Long =
        runCatching { DATE_FORMATTER.parse(this?.trim() ?: "")?.time }.getOrNull() ?: 0L

    private fun Element.getInfo(info: String, cut: Boolean = true): String =
        selectFirst("span:has(b:contains($info))")!!.text()
            .let {
                when {
                    cut -> it.substringAfter(" ")
                    else -> it
                }.trim()
            }

    private fun parseStatus(status: String?): Int =
        when (status?.trim()?.lowercase()) {
            "completed" -> SAnime.COMPLETED
            "ongoing" -> SAnime.ONGOING
            else -> SAnime.UNKNOWN
        }

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = PREF_BASEURL_KEY
            title = PREF_BASEURL_TITLE
            dialogTitle = PREF_BASEURL_TITLE
            summary = getPrefBaseUrl()

            setDefaultValue(getPrefBaseUrl())
            setOnPreferenceChangeListener { _, newValue ->
                val changed = newValue as String
                summary = changed
                Toast.makeText(screen.context, RESTART_ANIYOMI, Toast.LENGTH_LONG).show()
                putPrefBaseUrl(changed)
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_ENTRIES
            summary = "%s"

            setDefaultValue(getPrefQuality())
            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                putPrefQuality(entry)
            }
        }.also(screen::addPreference)
    }

    private fun getPrefBaseUrl(): String =
        preferences.getString(PREF_BASEURL_KEY, mainBaseUrl)!!

    private fun putPrefBaseUrl(newValue: String): Boolean =
        preferences.edit().putString(PREF_BASEURL_KEY, newValue).commit()

    private fun getPrefQuality(): String =
        preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!

    private fun putPrefQuality(newValue: String): Boolean =
        preferences.edit().putString(PREF_QUALITY_KEY, newValue).commit()

    companion object {
        private const val PREF_BASEURL_KEY = "baseurlkey_v${BuildConfig.VERSION_NAME}"
        private const val PREF_BASEURL_TITLE = "Override BaseUrl"

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred Quality"
        private const val PREF_QUALITY_DEFAULT = "720p"
        private val PREF_QUALITY_ENTRIES = arrayOf("720p", "360p")

        private const val RESTART_ANIYOMI = "Restart Aniyomi to apply new setting."

        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("MMMM d, yyyy", Locale("en", "US"))
        }
    }
}
