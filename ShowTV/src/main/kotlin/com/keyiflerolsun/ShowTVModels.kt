package com.keyiflerolsun

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class HopeVideoConfig(
    @JsonProperty("media")
    val media: HopeMedia? = null,

    @JsonProperty("subtitles")
    val subtitles: List<HopeSubtitle>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class HopeMedia(
    @JsonProperty("m3u8")
    val m3u8: List<HopeStream>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class HopeStream(
    @JsonProperty("label")
    val label: String? = null,

    @JsonProperty("src")
    val src: String? = null,

    @JsonProperty("type")
    val type: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class HopeSubtitle(
    @JsonProperty("label")
    val label: String? = null,

    @JsonProperty("srclang")
    val srclang: String? = null,

    @JsonProperty("src")
    val src: String? = null,

    @JsonProperty("default")
    val isDefault: Boolean? = null
)
