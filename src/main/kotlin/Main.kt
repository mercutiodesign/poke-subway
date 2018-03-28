
import com.github.kittinunf.fuel.core.FuelManager
import com.github.kittinunf.fuel.httpGet
import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import de.merc.pokekotlin.model.*
import org.apache.poi.ss.util.CellRangeAddress
import org.apache.poi.xssf.usermodel.XSSFCellStyle
import org.apache.poi.xssf.usermodel.XSSFRow
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.awt.Desktop
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.system.measureNanoTime


fun setupFuel() {
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


fun main(args: Array<String>) {
    logTiming("fuel startup") {
        setupFuel()
    }


    logTiming("main") {
        val cache = NetworkCache(File("cache.txt"))
        val species = cache.loadAll<PokemonSpecies>("https://pokeapi.co/api/v2/pokemon-species/").values

        // compareWithGenerations(cache, species)
        // printGen5(species)

        val byName = species.asSequence().map { it.getName().toLowerCase() to it }.toMap()

        val teams = mutableListOf<List<PokemonSpecies>>()
        ClassLoader.getSystemResourceAsStream("doubles.txt")
                .bufferedReader()
                .forEachLine { line ->
            val pokemonNames = line.split("\\W+".toRegex()).mapNotNull { byName[it.toLowerCase()] }
            if (pokemonNames.size % 4 != 0) {
                println("warning: uneven number of pokemon ${pokemonNames.size}, skipping this line:\n> $line")
            } else {
                for (i in 0 until pokemonNames.size step 4) {
                    teams.add(pokemonNames.subList(i, i + 4))
                }
            }
        }


        println()
        println("team stats")
        for ((team, count) in teams
                .groupingBy { it }
                .eachCount()
                .entries
                .sortedByDescending { e -> e.value }) {
            println("${team.map { it.getName() }}: $count")
        }
        println()

        println("---------------")
        val generations = cache.loadAll<Generation>("https://pokeapi.co/api/v2/generation/")
        for (team in teams) {
            val gens = team.map { generations[it.generation.url]!!.id}
            val maxGen = gens.max()!!
            if (maxGen <= 5) {
                println("${team.map { it.getName() }}: ${gens.min()} - $maxGen")
            }
        }
        println()

        println("---------  most common pokemon  ---------")
        val all = mutableListOf<PokemonSpecies>()
        teams.forEach { all += it }
        all.groupingBy { it }
                .eachCount()
                .entries
                .sortedByDescending { e -> e.value }
                .take(20)
                .forEach { (species, count) ->
                    println("%-15s | %-70s | %-15s | %2d".format(
                            species.getName("de"),
                            "https://bulbapedia.bulbagarden.net/wiki/${species.getName().urlEncode()}#Game_locations",
                            species.generation.name,
                            count))
                }


    }
}

private fun compareWithGenerations(cache: NetworkCache, species: List<PokemonSpecies>) {
    species.groupingBy { it.generation.url }
            .eachCount()
            .entries
            .sortedBy { it.key }
            .forEach { println("${it.key} => ${it.value}") }

    println("-------------------")

    val generations = cache.loadAll<Generation>("https://pokeapi.co/api/v2/generation/")
    for ((url, generation) in generations.entries) {
        println("$url => ${generation.pokemonSpecies.size}")
    }
}

private fun printGen5(species: List<PokemonSpecies>) {
    for (s in species) {
        if (s.generation.url.endsWith("5/")) {
            println("%4d %-20s %s".format(s.id,
                    s.getName(),
                    s.getName("de")))
        }
    }
}

fun PokemonSpecies.getName(languageName: String = "en") = names.first { it.language.name == languageName }.name
fun Item.getName(languageName: String = "en") = names.first { it.language.name == languageName }.name

inline fun logTiming(label: String, block: () -> Unit) {
    val elapsed = measureNanoTime(block)
    println("> $label in ${"%dms".format(TimeUnit.NANOSECONDS.toMillis(elapsed))} ")
}

object BerryChecker {
    @JvmStatic
    fun main(args: Array<String>) {
        setupFuel()
        val cache = NetworkCache(File("cache.txt"))
        val berries = cache.loadAll<Berry>("https://pokeapi.co/api/v2/berry/")
        println("found ${berries.size} berries")

        val headings = listOf("url", "name en", "name de", "growth time", "max harvest", "soil dryness", "smoothness")
        val colOffset = headings.size
        val flavorNames = berries.values
                .asSequence()
                .flatMap { it.flavors.asSequence() }
                .map { it.flavor.name }
                .toSortedSet()
                .mapIndexed { index, name -> name to index + colOffset }
                .toMap()

        println(flavorNames)

        val output = File("berries.xlsx")

        XSSFWorkbook().use { wb ->
            val sheet = wb.createSheet("data")

            val bold = wb.createCellStyle()
            bold.setFont(wb.createFont().also { it.bold = true })

            var rowNum = 0

            val header = sheet.createRow(rowNum++)
            headings.forEachIndexed { col, value ->
                header.createCell(col, value, bold)
            }
            flavorNames.forEach { name, col ->
                header.createCell(col, name, bold)
            }

            berries.forEach { url, berry ->
                val row = sheet.createRow(rowNum++)
                row.createCellLink(0, url, url.substringAfter("/v2"))

                val item = cache.loadJson<Item>(berry.item.url)
                val name = item.getName()
                row.createCellLink(1,"https://bulbapedia.bulbagarden.net/wiki/" + name.urlEncode(), name)
                row.createCell(2, item.getName("de"))
                row.createCellInt(3, berry.growthTime)
                row.createCellInt(4, berry.maxHarvest)
                row.createCellInt(5, berry.soilDryness)
                row.createCellInt(6, berry.smoothness)

                berry.flavors.forEach {
                    row.createCellInt(flavorNames[it.flavor.name]!!, it.potency)
                }
            }

            sheet.setAutoFilter(CellRangeAddress(0, rowNum - 1, 0, colOffset + flavorNames.size - 1))
            headings.indices.asSequence().drop(2).forEach(sheet::autoSizeColumn)
            sheet.setColumnWidth(0, sheet.getColumnWidth(2))
            sheet.setColumnWidth(1, sheet.getColumnWidth(2))
            output.outputStream().use(wb::write)
        }

        Desktop.getDesktop().open(output)
    }
}

// url encoding for actual urls not forms
fun String.urlEncode() = URLEncoder.encode(this, "UTF-8").replace("+", "%20")

fun XSSFRow.createCell(col: Int, value: String, style: XSSFCellStyle? = null) {
    createCell(col).apply {
        setCellValue(value)
        cellStyle = style
    }
}

fun XSSFRow.createCellLink(col: Int, url: String, value: String) {
    createCell(col).apply {
        setCellValue(value)
        cellFormula = "HYPERLINK(\"$url\", \"$value\")"
    }
}

fun XSSFRow.createCellInt(col: Int, value: Int) {
    createCell(col).setCellValue(value.toDouble())
}


class NetworkCache(val file: File) {
    val cache = mutableMapOf<String, String>()

    init {
        file.forEachLine { line ->
            val (key, value) = line.split('\u0000', limit = 2)
            cache[key] = value
        }
    }

    val gson = GsonBuilder().setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES).create()!!

    inline fun <reified T> loadJson(url: String): T {
        return gson.fromJson(cache.computeIfAbsent(url) {
            val json = url.httpGet().responseString().third.get()
            file.appendText("$url\u0000$json\n")
            json
        }, T::class.java)
    }

    fun loadResourceList(url: String): NamedApiResourceList {
        val list = loadJson<NamedApiResourceList>(url)
        return if (list.results.size >= list.count) list else loadJson(url + "?limit=${list.count}")
    }

    inline fun <reified T> loadAll(url: String): Map<String, T> {
        val list = loadResourceList(url)
        check(list.count == list.results.size) {
            "count (${list.count}) should be equal to the length of the results array (${list.results.size})"
        }

        return list.results.associate {
            it.url to loadJson<T>(it.url)
        }
    }

}
