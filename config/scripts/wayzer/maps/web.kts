@file:Depends("wayzer/maps")

package wayzer

import arc.files.Fi
import arc.util.Log
import arc.util.serialization.Jval
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mindustry.Vars
import mindustry.game.Gamemode
import mindustry.io.MapIO
import mindustry.maps.Map as MdtMap
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files

name = "扩展: Mindustry资源站地图源(www.mindustry.top)"

val webEnable by config.key(true, "是否启用www.mindustry.top地图站作为地图来源")
val webHost by config.key("https://api.mindustry.top", "地图站API地址")
val webCacheDirName by config.key("web", "地图缓存目录名(位于config/maps下)")

private val http: HttpClient by lazy { HttpClient.newHttpClient() }

private fun String.httpGet(): String {
    val req = HttpRequest.newBuilder(URI.create(this))
        .header("User-Agent", "ScriptAgent-WebMaps")
        .GET().build()
    val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
    if (resp.statusCode() != 200) error("HTTP ${resp.statusCode()}: $this")
    return resp.body()
}

private fun String.httpGetBytes(): ByteArray {
    val req = HttpRequest.newBuilder(URI.create(this))
        .header("User-Agent", "ScriptAgent-WebMaps")
        .GET().build()
    val resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray())
    if (resp.statusCode() != 200) error("HTTP ${resp.statusCode()}: $this")
    return resp.body()
}

private fun parseMode(m: String?): Gamemode = when (m?.lowercase()) {
    "attack" -> Gamemode.attack
    "pvp" -> Gamemode.pvp
    "survive", "survival" -> Gamemode.survival
    "sandbox" -> Gamemode.sandbox
    "editor" -> Gamemode.editor
    else -> Gamemode.survival
}

/** 从详情JSON提取作者: 优先站点上传者,其次文件内嵌tag */
private fun Jval.extractAuthor(): String =
    get("user")?.getString("name", null)?.takeIf { it.isNotBlank() }
        ?: get("tags")?.get("author")?.asString()?.takeIf { it.isNotBlank() }
        ?: "mindustry.top"

private fun Jval.extractDescription(default: String): String =
    get("tags")?.get("description")?.asString()?.takeIf { it.isNotBlank() } ?: default

val webProvider = object : MapProvider() {
    private val dir: Fi get() = Vars.customMapDirectory.child(webCacheDirName)

    /** 详情JSON缓存,避免重复拉取 */
    private val detailCache = mutableMapOf<Int, Jval>()

    private suspend fun fetchDetail(id: Int): Jval = detailCache[id] ?: run {
        val d = withContext(Dispatchers.IO) { "$webHost/maps/$id.json".httpGet() }
        Jval.read(d).also { if (detailCache.size > 200) detailCache.clear(); detailCache[id] = it }
    }

    override suspend fun searchMaps(search: String?): Collection<MapInfo> {
        if (!webEnable) return emptyList()
        val list = try {
            withContext(Dispatchers.IO) { "$webHost/maps/list".httpGet() }
        } catch (e: Exception) {
            Log.warn("[WebMaps] 拉取地图列表失败: ${e.message}")
            return emptyList()
        }
        return Jval.read(list).asArray().map { j ->
            val id = j.getInt("id", -1)
            MapInfo(
                this, id, parseMode(j.getString("mode", null)), null,
                mutableMapOf(
                    "name" to j.getString("name", "unknown"),
                    "description" to j.getString("desc", ""),
                    "latest" to j.getString("latest", id.toString())
                )
            )
        }.filter { info ->
            search.isNullOrEmpty()
                || info.name.contains(search, ignoreCase = true)
                || info.description.contains(search, ignoreCase = true)
        }
    }

    /** 管理指令按ID直接换图时走这里,可拿到完整作者信息 */
    override suspend fun findById(id: Int, reply: ((PlaceHoldString) -> Unit)?): MapInfo? {
        if (!webEnable || id < 1000) return null // 本地地图ID较小,避免抢占
        return try {
            val j = fetchDetail(id)
            MapInfo(
                this, id, parseMode(j.getString("mode", null)), null,
                mutableMapOf(
                    "name" to j.getString("name", "unknown"),
                    "author" to j.extractAuthor(),
                    "description" to j.extractDescription("")
                )
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 核心: 下载.msav后,用站点元数据覆盖内嵌tags
     * (地图站作者存在网站数据库,文件内常为空 —— 这就是之前作者/简介显示不出来的原因)
     */
    override suspend fun lazyGetMap(info: MapInfo): MdtMap {
        info.map?.let { return it }
        val latest = info.meta["latest"] ?: info.id.toString()
        dir.mkdirs()
        val file: Fi = dir.child("${info.id}_$latest.msav")
        if (!file.exists()) {
            val bytes = withContext(Dispatchers.IO) { "$webHost/maps/$latest.msav".httpGetBytes() }
            Files.write(file.file().toPath(), bytes)
        }
        val detail = try { fetchDetail(info.id) } catch (e: Exception) { null }

        val author = detail?.extractAuthor() ?: info.meta["author"] ?: "mindustry.top"
        val desc = detail?.extractDescription(info.meta["description"] ?: "") ?: ""

        val map = MapIO.createMap(file, true)
        map.tags.put("author", author)
        map.tags.put("description", desc)
        // 回写meta,让/gameover等界面立即显示正确作者
        (info.meta as? MutableMap<String, String>)?.apply {
            put("author", author)
            put("description", desc)
        }
        Log.info("[WebMaps] 已加载地图站地图 #{id} {name} By {author}".replace("{id}", info.id.toString())
            .replace("{name}", info.name).replace("{author}", author))
        return map
    }
}

MapRegistry.register(this, webProvider)

onDisable { detailCache.clear() }