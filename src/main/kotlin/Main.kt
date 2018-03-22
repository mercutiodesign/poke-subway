
import com.beust.klaxon.Klaxon
import org.mapdb.DBMaker
import org.mapdb.Serializer

fun main(args: Array<String>) {
    DBMaker.fileDB("cache.db")
            .concurrencyDisable()
            .fileMmapEnableIfSupported()
            .make()
            .use {
                val map = it.hashMap("cache", Serializer.STRING, Serializer.STRING).createOrOpen()
                map.computeIfAbsent("test") {
                    ""
                }
            }

    val list = Klaxon().parse<NamedAPIResourceList>(ClassLoader.getSystemResourceAsStream("pokemon-species.json"))!!
    check(list.count == list.results.size) {
        // since we don't support pagination currently
        "count (${list.count}) should be equal to the length of the results array (${list.results.size})"
    }

    for (resource in list.results) {
        with (resource) {
            println("$name => $url")
        }
    }
}

class NetworkCache {

}

data class NamedAPIResource(val name: String, val url: String)
data class NamedAPIResourceList(val count: Int, val results: List<NamedAPIResource>, val previous: String? = null, val next: String? = null)

