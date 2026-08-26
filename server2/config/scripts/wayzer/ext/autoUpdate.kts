@file:Depends("wayzer/cmds/restart", "计划重启")

package wayzer.ext

import arc.util.Interval
import arc.util.Log
import arc.util.serialization.Jval
import mindustry.core.Version
import mindustry.net.BeControl
import java.io.File
import java.net.URL
import java.net.URI
import java.time.LocalDateTime
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.StandardCopyOption

name = "自动更新"

val enableUpdate by config.key(false, "是否开启自动更新")
val source by config.key("Anuken/Mindustry", "服务端来源，Github仓库")
val onlyInNight by config.key(false, "仅在凌晨自动更新", "本地时间1:00到7:00")
val useMirror by config.key(false, "使用镜像加速下载")
val mirror by config.key("https://gh.tinylake.tech", "GH镜像源")
val configSync by config.key(true, "更新时从git仓库同步config/scripts目录")
val autoSync by config.key(true, "自动同步: 定时检查GitHub仓库并拉取最新config/scripts")
val autoSyncRestart by config.key(false, "自动同步到新版本后是否计划重启(关闭则需手动重启或sa reload生效)")
val configRepo by config.key("ziyikong/nuclear", "配置仓库(GitHub 用户/名称)")
val githubToken by config.key("", "GitHub令牌: 服务器目录非git克隆时必填,用于拉取私有仓库")


fun download(url: String, file: File): Int {
    val connection = URL(url).openConnection()
    connection.connectTimeout = 30000
    connection.readTimeout = 30000
    val stream = connection.getInputStream()
    val buffer = ByteArray(128 * 1024)
    val logInterval = Interval()
    var len = 0
    stream.use { input ->
        file.outputStream().use { output ->
            while (true) {
                val i = input.read(buffer)
                if (i == -1) break
                output.write(buffer, 0, i)
                len += i
                if (logInterval[60f])
                    logger.info("Downloaded ${len / 1024}KB")
            }
        }
    }
    return len
}

onEnable {
    java.lang.Thread {
        while (true) {
            try {
                // ===== 自动同步仓库中的config/scripts =====
                if (autoSync) try {
                    if (hasScriptUpdate()) {
                        syncScripts(configRepo, githubToken)
                        broadcast("[green]🔄 检测到仓库更新,config/scripts 已自动同步至最新版".with(), quite = true)
                        if (autoSyncRestart)
                            contextScript<wayzer.cmds.Restart>().scheduleRestart("脚本/配置自动更新")
                    }
                } catch (e: Throwable) {
                    logger.warning("[ConfigSync] 自动同步失败: $e")
                }
                // ===== 自动检查服务端新版本 =====
                if (enableUpdate) {
                    if (!onlyInNight || LocalDateTime.now().hour in 1..6)
                        try {
                            val apiUrl = "https://api.github.com/repos/$source/releases"
                            val finalUrl = if (useMirror) "$mirror/$apiUrl" else apiUrl
                            val txt = URL(finalUrl).readText()
                            val json = Jval.read(txt).asArray().first()
                            val newBuild = json.getString("tag_name", "")
                            val versionParts = newBuild.removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }
                            val version = versionParts.getOrNull(0) ?: 0
                            val revision = versionParts.getOrNull(1) ?: 0
                            if (version > Version.build || (version == Version.build && revision > Version.revision)) {
                                val asset = json.get("assets").asArray().find {
                                    it.getString("name", "").contains("server", ignoreCase = true)
                                } ?: error("New version $newBuild, but can't find asset")
                                val url = asset.getString("browser_download_url", "")
                                val finalDownloadUrl = if (useMirror && url.startsWith("https://github.com")) "$mirror/$url" else url
                                try {
                                    update(newBuild, finalDownloadUrl)
                                    break
                                } catch (e: Throwable) {
                                    logger.warning("下载更新失败: $e")
                                    e.printStackTrace()
                                }
                            }
                        } catch (e: Throwable) {
                            logger.warning("获取更新数据失败: $e")
                        }
                }
            } catch (e: Throwable) {
                logger.warning("自动更新循环异常: $e")
            }
            java.lang.Thread.sleep(5 * 60 * 1000)
        }
    }.start()
}

fun update(version: String, url: String) {
    Log.info("发现新版本可用 $version 正在从 $url 下载")
    val dest = File(BeControl::class.java.protectionDomain.codeSource.location.toURI().path)
    val tmp = dest.resolveSibling("server-$version.jar.tmp")
    val size = try {
        download(url, tmp)
    } catch (e: Throwable) {
        tmp.delete()
        throw e
    }
    Log.info("新版本 $version 下载完成: ${size / 1024}KB")
    contextScript<wayzer.cmds.Restart>().scheduleRestart("新版本更新 $version") {
        if (configSync) try {
            Log.info(syncScripts(configRepo, githubToken))
            Log.info("config/scripts 已从仓库同步")
        } catch (e: Throwable) {
            Log.err("config/scripts 同步失败(继续更新jar): $e")
        }
        dest.outputStream().use { output ->
            tmp.inputStream().use { input -> input.copyTo(output) }
            output.flush()
        }
        tmp.delete()
        Log.info("服务端已更新至 $version，重启中...")
    }
}

private fun hasScriptUpdate(): Boolean {
    try {
        val apiUrl = "https://api.github.com/repos/$configRepo/commits?path=config/scripts&per_page=1"
        val finalUrl = if (useMirror) "$mirror/$apiUrl" else apiUrl
        val txt = URL(finalUrl).readText()
        val json = Jval.read(txt).asArray().first()
        val sha = json.get("sha").asString()
        val lastShaFile = File("config/scripts/.last_sync_sha")
        val lastSha = if (lastShaFile.exists()) lastShaFile.readText().trim() else ""
        return sha != lastSha
    } catch (e: Throwable) {
        logger.warning("检查脚本更新失败: $e")
        return false
    }
}

fun syncScripts(repo: String, token: String): String {
    try {
        val apiUrl = "https://api.github.com/repos/$repo/contents/config/scripts"
        val finalUrl = if (useMirror) "$mirror/$apiUrl" else apiUrl
        val requestBuilder = HttpRequest.newBuilder()
            .uri(URI(finalUrl))
            .timeout(java.time.Duration.ofSeconds(30))
        if (token.isNotBlank()) {
            requestBuilder.header("Authorization", "Bearer $token")
        }
        val request = requestBuilder.GET().build()
        val client = HttpClient.newHttpClient()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) {
            return "GitHub API错误: ${response.statusCode()} ${response.body()}"
        }
        val json = Jval.read(response.body()).asArray()
        val scriptDir = File("config/scripts")
        if (!scriptDir.exists()) scriptDir.mkdirs()
        var count = 0
        json.forEach { item ->
            val name = item.getString("name", "")
            if (name.endsWith(".kts") || name.endsWith(".kt") || name.endsWith(".txt") || name.endsWith(".json")) {
                val downloadUrl = item.getString("download_url", "")
                if (downloadUrl.isNotBlank()) {
                    val file = File(scriptDir, name)
                    try {
                        val bytes = URL(downloadUrl).readBytes()
                        file.outputStream().use { it.write(bytes) }
                        count++
                    } catch (e: Throwable) {
                        logger.warning("下载 $name 失败: $e")
                    }
                }
            }
        }
        val commitsUrl = "https://api.github.com/repos/$repo/commits?path=config/scripts&per_page=1"
        val commitsFinalUrl = if (useMirror) "$mirror/$commitsUrl" else commitsUrl
        val commitsTxt = URL(commitsFinalUrl).readText()
        val commitsJson = Jval.read(commitsTxt).asArray().first()
        val sha = commitsJson.get("sha").asString()
        File("config/scripts/.last_sync_sha").writeText(sha)
        return "同步完成，更新了 $count 个文件"
    } catch (e: Throwable) {
        return "同步失败: $e"
    }
}