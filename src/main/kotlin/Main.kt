import com.github.kittinunf.fuel.core.FuelManager
import com.github.kittinunf.fuel.httpGet
import com.google.common.reflect.TypeToken
import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import com.linkedin.paldb.api.PalDB
import de.merc.pokekotlin.model.Generation
import de.merc.pokekotlin.model.NamedApiResourceList
import de.merc.pokekotlin.model.PokemonSpecies
import org.mapdb.DBMaker
import org.mapdb.Serializer
import org.xerial.snappy.SnappyInputStream
import org.xerial.snappy.SnappyOutputStream
import java.io.File
import java.sql.DriverManager
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.logging.Level
import java.util.logging.Logger
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.system.measureNanoTime


fun main(args: Array<String>) {
    logTiming("fuel startup") {
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
    }


    logTiming("main") {
        NetworkCache("cache.db").use { cache ->
            val species = cache.loadAll<PokemonSpecies>("https://pokeapi.co/api/v2/pokemon-species/")
            species.groupingBy { it.generation.url }.eachCount().entries.sortedBy { it.key }.forEach { println("${it.key} => ${it.value}") }

            println("-------------------")

            val generations = cache.loadAll<Generation>("https://pokeapi.co/api/v2/generation/")
            generations.forEach {
                println("https://pokeapi.co/api/v2/generation/${it.id}/ => ${it.pokemonSpecies.size}")
            }
        }
    }
}

inline fun logTiming(label: String, block: () -> Unit) {
    val elapsed = measureNanoTime(block)
    println("> $label in ${"%dms".format(TimeUnit.NANOSECONDS.toMillis(elapsed))} ")
}

object TestProperties {
    @JvmStatic
    fun main(args: Array<String>) {
        val data = readOldDb("cache.db")

        val propFile = File("cache.properties")
        if (!propFile.exists()) {
            logTiming("writing properties") {
                with(Properties()) {
                    putAll(data)
                    store(propFile.bufferedWriter(), "")
                }
            }
        }
        var other = emptyMap<String, String>()
        logTiming("reading properties") {
            with(Properties()) {
                load(propFile.bufferedReader())
                other = toMap() as Map<String, String>
            }
        }

        println("${data.size} => ${other.size} : ${data == other}")
    }
}


object TestPlain {
    @JvmStatic
    fun main(args: Array<String>) {
        val data = readOldDb("cache.db")

        val propFile = File("cache.txt")
        if (!propFile.exists()) {
            logTiming("writing txt") {
                propFile.bufferedWriter().use { wr ->
                    data.forEach { key, value ->
                        wr.append(key).append(0.toChar()).append(value).append('\n')
                    }
                }
            }
        }
        val other = mutableMapOf<String, String>()
        logTiming("reading txt") {
            propFile.useLines {
                it.forEach {
                    val (key, value) = it.split(0.toChar(), limit = 2)
                    other[key] = value
                }
            }
        }

        println("${data.size} => ${other.size} : ${data == other}")
        if (data != other) {
            println(other)
            propFile.delete()
        }
    }
}


object TestPlainGzip {
    @JvmStatic
    fun main(args: Array<String>) {
        val data = readOldDb("cache.db")

        val propFile = File("cache.txt.gz")
        if (!propFile.exists()) {
            logTiming("writing txt.gz") {
                GZIPOutputStream(propFile.outputStream()).bufferedWriter().use { wr ->
                    data.forEach { key, value ->
                        wr.append(key).append(0.toChar()).append(value).append('\n')
                    }
                }
            }
        }
        val other = mutableMapOf<String, String>()
        logTiming("reading txt.gz") {
            GZIPInputStream(propFile.inputStream()).bufferedReader().useLines {
                it.forEach {
                    val (key, value) = it.split(0.toChar(), limit = 2)
                    other[key] = value
                }
            }
        }

        println("${data.size} => ${other.size} : ${data == other}")
        if (data != other) {
            println(other)
            propFile.delete()
        }
    }
}


object TestPalDB {
    @JvmStatic
    fun main(args: Array<String>) {
        val data = readOldDb("cache.db")
        val palFile = File("cache-compressed.paldb")
        Logger.getLogger("com.linkedin.paldb").level = Level.OFF
        val conf = PalDB.newConfiguration().set("compression.enabled", "true")
        if (!palFile.exists()) {
            logTiming("writing paldb") {
                with(PalDB.createWriter(palFile, conf)) {
                    data.forEach { key, value -> put(key, value) }
                    close()
                }
            }
        }
        var other = mutableMapOf<String, String>()
        logTiming("reading paldb") {
            with(PalDB.createReader(palFile, conf)) {
                iterable<String, String>().forEach { e -> other[e.key] = e.value }
                close()
            }
        }

        println("${data.size} => ${other.size} : ${data == other}")
        if (data != other) {
            println(other)
            palFile.delete()
        }
    }
}


object TestSqlite {
    @JvmStatic
    fun main(args: Array<String>) {
        val data = readOldDb("cache.db")
        val sqliteFile = File("cache-sqlite.db")
        if (!sqliteFile.exists()) {
            logTiming("writing sqlite") {
                DriverManager.getConnection("jdbc:sqlite:$sqliteFile")?.use { con ->
                    con.createStatement().use { stmt ->
                        stmt.executeUpdate("create table cache (key text not null primary key, value text not null)")
                    }
                    con.prepareStatement("insert into cache values(?, ?)").use { stmt ->
                        data.forEach { key, value ->
                            stmt.setString(1, key)
                            stmt.setString(2, value)
                            stmt.executeUpdate()
                        }
                    }
                }
            }
        }
        var other = mutableMapOf<String, String>()
        logTiming("reading sqlite") {
            DriverManager.getConnection("jdbc:sqlite:$sqliteFile")?.use { con ->
                con.createStatement().use { stmt ->
                    stmt.executeQuery("select key, value from cache").use { rs ->
                        while (rs.next()) {
                            other[rs.getString(1)] = rs.getString(2)
                        }
                    }
                }
            }
        }

        println("${data.size} => ${other.size} : ${data == other}")
        if (data != other) {
            println(other)
            sqliteFile.delete()
        }
    }
}


object TestGson {
    @JvmStatic
    fun main(args: Array<String>) {
        val data = readOldDb("cache.db")
        val gsonFile = File("cache.json")
        if (!gsonFile.exists()) {
            logTiming("writing gson") {
                val gson = GsonBuilder().create()
                gsonFile.bufferedWriter().use { wr ->
                    gson.toJson(data, wr)
                }
            }
        }

        var other = emptyMap<String, String>()
        logTiming("reading gson") {
            val gson = GsonBuilder().create()

            try {
                gsonFile.bufferedReader().use { reader ->
                    other = gson.fromJson(reader, object : TypeToken<HashMap<String, String>>() {}.type)
                }
            } catch (e: Exception) {
                println(e)
            }

        }

        println("${data.size} => ${other.size} : ${data == other}")
        if (data != other) {
            println(other)
            gsonFile.delete()
        }
    }
}


object TestGsonCompressed {
    @JvmStatic
    fun main(args: Array<String>) {
        val data = readOldDb("cache.db")
        val gsonFile = File("cache.json.gz")
        if (!gsonFile.exists()) {
            logTiming("writing gson") {
                val gson = GsonBuilder().create()
                GZIPOutputStream(gsonFile.outputStream()).bufferedWriter().use { wr ->
                    gson.toJson(data, wr)
                }
            }
        }

        var other = emptyMap<String, String>()
        logTiming("reading gson") {
            val gson = GsonBuilder().create()

            try {
                GZIPInputStream(gsonFile.inputStream()).bufferedReader().use { reader ->
                    other = gson.fromJson(reader, object : TypeToken<HashMap<String, String>>() {}.type)
                }
            } catch (e: Exception) {
                println(e)
            }

        }

        println("${data.size} => ${other.size} : ${data == other}")
        if (data != other) {
            println(other)
            gsonFile.delete()
        }
    }
}

object TestGsonSnappy {
    @JvmStatic
    fun main(args: Array<String>) {
        val data = readOldDb("cache.db")
        val gsonFile = File("cache.json.sn")
        if (!gsonFile.exists()) {
            logTiming("writing gson") {
                val gson = GsonBuilder().create()
                SnappyOutputStream(gsonFile.outputStream()).bufferedWriter().use { wr ->
                    gson.toJson(data, wr)
                }
            }
        }

        var other = emptyMap<String, String>()
        logTiming("reading gson") {
            val gson = GsonBuilder().create()

            try {
                SnappyInputStream(gsonFile.inputStream()).bufferedReader().use { reader ->
                    other = gson.fromJson(reader, object : TypeToken<HashMap<String, String>>() {}.type)
                }
            } catch (e: Exception) {
                println(e)
            }

        }

        println("${data.size} => ${other.size} : ${data == other}")
        if (data != other) {
            println(other)
            gsonFile.delete()
        }
    }
}

fun readOldDb(file: String): Map<String, String> {
    var map = emptyMap<String, String>()
    logTiming("reading old db") {
        DBMaker.fileDB(file)
                .concurrencyDisable()
                .fileMmapEnableIfSupported()
                .make()
                .use { db ->
                    map = db.hashMap("cache", Serializer.STRING, Serializer.STRING)
                            .createOrOpen()
                            .toMap()
                }
    }
    return map
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

    inline fun <reified T> loadAll(url: String): List<T> {
        val list = loadResourceList(url)
        check(list.count == list.results.size) {
            "count (${list.count}) should be equal to the length of the results array (${list.results.size})"
        }

        return list.results.map {
            loadJson<T>(it.url)
        }
    }

    override fun close() {
        db.close()
    }

}
