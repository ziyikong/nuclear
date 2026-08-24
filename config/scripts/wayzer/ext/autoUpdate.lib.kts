package wayzer.ext

import arc.util.Log
import mindustry.net.BeControl
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit

val syncPaths = arrayOf("config/scripts", ":(exclude)config/scripts/data/config.conf")

private val syncServerDir: File
    get() = File(BeControl::class.java.protectionDomain.codeSource.location.toURI().path).parentFile

private val syncHttp: HttpClient by lazy { HttpClient.newHttpClient() }

private fun git(vararg args: String): String {
    val p = ProcessBuilder("git", "-C", syncServerDir.absolutePath, *args)
        .redirectErrorStream(true).start()
    val out = p.inputStream.bufferedReader().readText()
    if (!p.waitFor(120, TimeUnit.SECONDS)) {
        p.destroyForcibly(); error("git 执行超时")
    }
    if (p.exitValue() != 0) error(out.takeLast(400))
    return out
}

/** fetch远端并判断 config/scripts 是否有新版本 */
fun hasScriptUpdate(): Boolean {
    git("fetch", "origin", "main")
    return git("diff", "--name-only", "HEAD", "FETCH_HEAD", "--", *syncPaths).isNotBlank()
}

/** git方式: 要求服务器目录本身是nuclear的克隆 */
fun syncScriptsFromGit(): String {
    git("fetch", "origin", "main")
    val deleted = git("diff", "--name-only", "--diff-filter=D", "HEAD", "FETCH_HEAD", "--", *syncPaths)
        .split('\n').map { it.trim() }.filter { it.isNotEmpty() }
    if (deleted.isNotEmpty()) {
        git("rm", "-q", "--force", "--", *deleted.toTypedArray())
        Log.info("[ConfigSync] 删除上游已移除的 ${deleted.size} 个文件")
    }
    return git("checkout", "FETCH_HEAD", "--", *syncPaths)
}

/** ZIP方式: 非git目录时通过GitHub API下载源码包,仅解压config/scripts子树 */
fun syncScriptsViaZip(repo: String, token: String): String {
    if (token.isBlank())
        error("服务器目录不是git仓库,且未配置githubToken(私有仓库需要令牌)")
    Log.info("[ConfigSync] 非git目录,使用ZIP方式同步...")
    val tmp = File.createTempFile("nuclear", ".zip")
    try {
        val url = "https://api.github.com/repos/$repo/zipball/main"
        val req = HttpRequest.newBuilder(URI.create(url))
            .header("Authorization", "Bearer $token")
            .header("User-Agent", "ScriptAgent-ConfigSync")
            .GET().build()
        val resp = syncHttp.send(req, HttpResponse.BodyHandlers.ofByteArray())
        if (resp.statusCode() != 200)
            error("GitHub API ${resp.statusCode()}: ${resp.body().decodeToString().take(200)}")
        Files.write(tmp.toPath(), resp.body())

        var count = 0
        java.util.zip.ZipInputStream(tmp.inputStream()).use { zis ->
            val prefix = "config/scripts/"
            while (true) {
                val e = zis.nextEntry ?: break
                // 压缩包内有一层 <repo>-<branch>/ 根目录
                val rel = e.name.substringAfter('/', "")
                if (!e.isDirectory && rel.startsWith(prefix)) {
                    val dest = File(syncServerDir, rel)
                    dest.parentFile.mkdirs()
                    Files.copy(zis, dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    count++
                }
            }
        }
        return "ZIP方式同步完成, 共更新 $count 个文件"
    } finally {
        tmp.delete()
    }
}

/** 统一入口: 是git仓库用git, 否则回退ZIP */
fun syncScripts(repo: String, token: String): String =
    if (File(syncServerDir, ".git").exists()) syncScriptsFromGit()
    else syncScriptsViaZip(repo, token)