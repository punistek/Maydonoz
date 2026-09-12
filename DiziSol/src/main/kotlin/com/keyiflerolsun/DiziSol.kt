package com.keyiflerolsun

import android.util.Log
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.nicehttp.RequestBodyTypes
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

class DiziSol : MainAPI() {
    override var mainUrl = "https://dizisol.com"
    override var name = "DiziSol"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.TvSeries)

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/153.0.0.0 Safari/537.36"

    override val mainPage = mainPageOf(
        "tv" to "Diziler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val apiPage = page.coerceAtLeast(1)
        val url = "$mainUrl/api/library/browse?type=tv&page=$apiPage"
        val response = app.get(url, referer = "$mainUrl/diziler?sayfa=$apiPage")
            .parsedSafe<BrowseResponse>()

        val items = response?.results.orEmpty().mapNotNull { it.toSearchResponse() }
        Log.d("DIZISOL", "MAIN page=$apiPage items=${items.size}")

        // DiziSol sayfası 191+ sayfalık katalog kullanıyor. API sonuç döndürdüğü sürece devam.
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    private fun BrowseItem.toSearchResponse(): SearchResponse? {
        val tmdbId = id ?: return null
        val displayTitle = title?.takeIf { it.isNotBlank() }
            ?: name?.takeIf { it.isNotBlank() }
            ?: return null

        return newTvSeriesSearchResponse(
            displayTitle,
            MediaData(type = "tv", tmdbId = tmdbId).toJson(),
            TvType.TvSeries
        ) {
            this.posterUrl = posterPath?.toImageUrl("w500")
            this.year = firstAirDate?.take(4)?.toIntOrNull()
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // Arama API'si mevcut Resolver Lab kayıtlarında kanıtlanmadığı için endpoint uydurmuyoruz.
        // Katalogdaki ilk sayfalarda yerel eşleşme yaparak güvenli bir fallback sağlıyoruz.
        val needle = query.trim()
        if (needle.isBlank()) return emptyList()

        val out = LinkedHashMap<Int, SearchResponse>()
        for (page in 1..8) {
            val res = app.get("$mainUrl/api/library/browse?type=tv&page=$page")
                .parsedSafe<BrowseResponse>() ?: continue

            res.results.orEmpty().forEach { item ->
                val title = item.title ?: item.name ?: return@forEach
                if (title.contains(needle, ignoreCase = true)) {
                    val id = item.id ?: return@forEach
                    item.toSearchResponse()?.let { out[id] = it }
                }
            }
            if (out.size >= 30) break
        }
        return out.values.toList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val media = runCatching { parseJson<MediaData>(url) }.getOrNull() ?: return null
        if (media.type != "tv") return null

        val detailUrl = "$mainUrl/api/tmdb/tv/${media.tmdbId}" +
            "?language=tr-TR&append_to_response=videos%2Ccredits%2Csimilar%2Crecommendations%2Cseasons" +
            "&include_video_language=en%2Ctr%2Cnull"

        val detail = app.get(detailUrl, referer = "$mainUrl/diziler")
            .parsedSafe<TvDetail>() ?: return null

        val episodes = mutableListOf<Episode>()
        detail.seasons.orEmpty()
            .filter { (it.seasonNumber ?: 0) > 0 }
            .sortedBy { it.seasonNumber }
            .forEach { season ->
                val seasonNo = season.seasonNumber ?: return@forEach
                val seasonData = runCatching {
                    app.get(
                        "$mainUrl/api/tmdb/tv/${media.tmdbId}/season/$seasonNo",
                        referer = "$mainUrl/dizi/${media.tmdbId}"
                    ).parsedSafe<SeasonDetail>()
                }.getOrNull() ?: return@forEach

                seasonData.episodes.orEmpty().forEach { ep ->
                    val epNo = ep.episodeNumber ?: return@forEach
                    episodes += newEpisode(EpisodeData(media.tmdbId, seasonNo, epNo).toJson()) {
                        this.name = ep.name
                        this.season = seasonNo
                        this.episode = epNo
                        this.posterUrl = ep.stillPath?.toImageUrl("w780")
                        this.description = ep.overview
                    }
                }
            }

        val title = detail.name ?: detail.originalName ?: return null
        val year = detail.firstAirDate?.take(4)?.toIntOrNull()
        val tags = detail.genres.orEmpty().mapNotNull { it.name }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = detail.posterPath?.toImageUrl("w500")
            this.backgroundPosterUrl = detail.backdropPath?.toImageUrl("original")
            this.year = year
            this.plot = detail.overview
            this.tags = tags
            this.score = detail.voteAverage?.let { Score.from10(it.toString()) }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val ep = runCatching { parseJson<EpisodeData>(data) }.getOrNull() ?: return false

        // Kanıtlanan zincir:
        // /api/movies/by-tmdb/{tmdb}?season={s}&episode={e}
        // -> internal id
        // -> POST /api/admin/resolve-stream/{internalId} {"partKey":""}
        val episodeInfoUrl = "$mainUrl/api/movies/by-tmdb/${ep.tmdbId}" +
            "?season=${ep.season}&episode=${ep.episode}"

        val episodeInfo = app.get(
            episodeInfoUrl,
            referer = "$mainUrl/izle/dizi/x?sezon=${ep.season}&bolum=${ep.episode}"
        ).parsedSafe<LibraryEpisode>() ?: return false

        val internalId = episodeInfo.id ?: return false
        val body = """{"partKey":""}"""
            .toRequestBody(RequestBodyTypes.JSON.toMediaTypeOrNull())

        val resolved = runCatching {
            app.post(
                "$mainUrl/api/admin/resolve-stream/$internalId",
                requestBody = body,
                headers = mapOf(
                    "Content-Type" to "application/json",
                    "Origin" to mainUrl,
                    "User-Agent" to userAgent,
                ),
                referer = mainUrl
            ).parsedSafe<ResolveResponse>()
        }.getOrNull()

        val streamUrl = resolved?.m3u8Url
            ?: episodeInfo.m3u8Url
            ?: episodeInfo.sources.orEmpty().firstNotNullOfOrNull { it.m3u8Url }
            ?: return false

        val subtitleTr = resolved?.subtitleTr ?: episodeInfo.subtitleTr
        val subtitleEn = resolved?.subtitleEn ?: episodeInfo.subtitleEn

        subtitleTr?.takeIf { it.isNotBlank() }?.let {
            subtitleCallback(newSubtitleFile("Türkçe", it))
        }
        subtitleEn?.takeIf { it.isNotBlank() }?.let {
            subtitleCallback(newSubtitleFile("English", it))
        }

        val playbackHeaders = mapOf(
            "User-Agent" to userAgent,
            "Referer" to "$mainUrl/"
        )

        callback(
            newExtractorLink(
                source = "DiziSol",
                name = "DiziSol",
                url = streamUrl,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = "$mainUrl/"
                this.headers = playbackHeaders
                this.quality = Qualities.Unknown.value
            }
        )

        // API'nin verdiği aktif alternatif HLS kaynaklarını da ekle.
        episodeInfo.sources.orEmpty()
            .filter { it.isActive != false && !it.m3u8Url.isNullOrBlank() && it.m3u8Url != streamUrl }
            .forEachIndexed { index, source ->
                source.subtitleTr?.takeIf { it.isNotBlank() }?.let {
                    subtitleCallback(newSubtitleFile("Türkçe - ${source.provider ?: "Kaynak ${index + 2}"}", it))
                }
                source.subtitleEn?.takeIf { it.isNotBlank() }?.let {
                    subtitleCallback(newSubtitleFile("English - ${source.provider ?: "Source ${index + 2}"}", it))
                }
                callback(
                    newExtractorLink(
                        source = "DiziSol",
                        name = "DiziSol ${source.provider ?: "Kaynak ${index + 2}"}",
                        url = source.m3u8Url!!,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "$mainUrl/"
                        this.headers = playbackHeaders
                        this.quality = Qualities.Unknown.value
                    }
                )
            }

        return true
    }

    private fun String.toImageUrl(size: String): String {
        if (startsWith("http://") || startsWith("https://")) return this
        return "https://image.tmdb.org/t/p/$size$this"
    }

    data class MediaData(
        val type: String,
        val tmdbId: Int,
    )

    data class EpisodeData(
        val tmdbId: Int,
        val season: Int,
        val episode: Int,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class BrowseResponse(
        @JsonProperty("results") val results: List<BrowseItem>? = emptyList(),
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class BrowseItem(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("media_type") val mediaType: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("poster_path") val posterPath: String? = null,
        @JsonProperty("backdrop_path") val backdropPath: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("vote_average") val voteAverage: Double? = null,
        @JsonProperty("first_air_date") val firstAirDate: String? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class TvDetail(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("original_name") val originalName: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("poster_path") val posterPath: String? = null,
        @JsonProperty("backdrop_path") val backdropPath: String? = null,
        @JsonProperty("first_air_date") val firstAirDate: String? = null,
        @JsonProperty("vote_average") val voteAverage: Double? = null,
        @JsonProperty("genres") val genres: List<Genre>? = emptyList(),
        @JsonProperty("seasons") val seasons: List<SeasonSummary>? = emptyList(),
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Genre(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("name") val name: String? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SeasonSummary(
        @JsonProperty("season_number") val seasonNumber: Int? = null,
        @JsonProperty("episode_count") val episodeCount: Int? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SeasonDetail(
        @JsonProperty("episodes") val episodes: List<TmdbEpisode>? = emptyList(),
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class TmdbEpisode(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("still_path") val stillPath: String? = null,
        @JsonProperty("episode_number") val episodeNumber: Int? = null,
        @JsonProperty("season_number") val seasonNumber: Int? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class LibraryEpisode(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("tmdbId") val tmdbId: Int? = null,
        @JsonProperty("mediaType") val mediaType: String? = null,
        @JsonProperty("season") val season: Int? = null,
        @JsonProperty("episode") val episode: Int? = null,
        @JsonProperty("m3u8Url") val m3u8Url: String? = null,
        @JsonProperty("subtitleTr") val subtitleTr: String? = null,
        @JsonProperty("subtitleEn") val subtitleEn: String? = null,
        @JsonProperty("sources") val sources: List<SourceItem>? = emptyList(),
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SourceItem(
        @JsonProperty("provider") val provider: String? = null,
        @JsonProperty("m3u8Url") val m3u8Url: String? = null,
        @JsonProperty("subtitleTr") val subtitleTr: String? = null,
        @JsonProperty("subtitleEn") val subtitleEn: String? = null,
        @JsonProperty("isActive") val isActive: Boolean? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ResolveResponse(
        @JsonProperty("success") val success: Boolean? = null,
        @JsonProperty("m3u8Url") val m3u8Url: String? = null,
        @JsonProperty("subtitleTr") val subtitleTr: String? = null,
        @JsonProperty("subtitleEn") val subtitleEn: String? = null,
        @JsonProperty("fromLive") val fromLive: Boolean? = null,
        @JsonProperty("fromCache") val fromCache: Boolean? = null,
    )
}
