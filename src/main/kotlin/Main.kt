
import com.github.kittinunf.fuel.core.FuelManager
import com.github.kittinunf.fuel.httpGet
import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import de.merc.pokekotlin.model.NamedApiResourceList
import de.merc.pokekotlin.model.PokemonSpecies
import org.mapdb.DBMaker
import org.mapdb.Serializer

fun main(args: Array<String>) {
    with(FuelManager.instance) {
        baseHeaders = mapOf(
                "User-Agent" to "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_12_6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/64.0.3282.186 Safari/537.36"
        )
        addRequestInterceptor {
            { request -> println("loading ${request.url}"); it(request) }
        }
        addResponseInterceptor {
            { request, response ->
                println("done loading ${request.url}, status: ${response.statusCode}, length: ${response.contentLength}")
                it(request, response)
            }
        }
    }


    NetworkCache("cache.db").use { cache ->
        val list = cache.loadResourceList("https://pokeapi.co/api/v2/pokemon-species/")

        check(list.count == list.results.size) {
            // since we don't support pagination currently
            "count (${list.count}) should be equal to the length of the results array (${list.results.size})"
        }

        /*
        for (resource in list.results) {
            with(resource) {
                println("$name => $url")
            }
        }
        */

        val species = list.results.map {
            cache.loadJson<PokemonSpecies>(it.url)
        }

        species.groupingBy { it.generation.url }.eachCount().entries.sortedBy { it.key }.forEach(::println)
    }
}


class NetworkCache(file: String) : AutoCloseable {
    private val db = DBMaker.fileDB(file)
            .concurrencyDisable()
            .fileMmapEnableIfSupported()
            .make()

    val cache = db.hashMap("cache", Serializer.STRING, Serializer.STRING)
            .createOrOpen()

    val gson = GsonBuilder().setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES).create()

    inline fun <reified T> loadJson(url: String): T {
        return gson.fromJson(cache.computeIfAbsent(url) {
            url.httpGet().responseString().third.get()
        }, T::class.java)
    }

    fun loadResourceList(url: String): NamedApiResourceList {
        val list = loadJson<NamedApiResourceList>(url)
        return if (list.results.size >= list.count) list else loadJson(url + "?limit=${list.count}")
    }

    override fun close() {
        db.close()
    }

}
