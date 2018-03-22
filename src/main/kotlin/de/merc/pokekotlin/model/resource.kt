package de.merc.pokekotlin.model

data class ApiResource(
    val url: String
)

data class NamedApiResource(
    val name: String,
    val url: String
)

interface ResourceSummaryList<out T> {
    val count: Int
    val next: String?
    val previous: String?
    val results: List<T>
}

data class ApiResourceList(
        override val count: Int,
        override val next: String? = null,
        override val previous: String? = null,
        override val results: List<ApiResource>
) : ResourceSummaryList<ApiResource>

data class NamedApiResourceList(
        override val count: Int,
        override val next: String? = null,
        override val previous: String? = null,
        override val results: List<NamedApiResource>
) : ResourceSummaryList<NamedApiResource>
