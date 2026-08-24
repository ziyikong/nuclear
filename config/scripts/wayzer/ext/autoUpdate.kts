@file:Depends("wayzer/cmds/restart", "计划重启")

package wayzer.ext

import arc.util.Interval
import arc.util.Log
import arc.util.serialization.Jval
import mindustry.core.Version
import mindustry.net.BeControl
import java.io.File
import java.net.URL
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

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


suspend fun download(url: String, file: File): Int = runInterruptible(Dispatchers.IO) {
    val steam = URL(url).openStream()
    val buffer = ByteArray(128 * 1024)//128KB
    val logInterval = Interval()
    var len = 0
    steam.use { input ->
        file.outputStream().use { output ->
            while (isActive) {
                val i = input.read(buffer)
                if (i == -1) break
                output.write(buffer, 0, i)
                len += i
                if (logInterval[60f])
                    logger.info("Downloaded ${len / 1024}KB")
            }
        }
    }
    len
}

onEnable {
    loop {
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
                    val txt =
                        URL("https://api.github.com/repos/$source/releases".let { if (useMirror) "$mirror/$it" else it }).readText()
                    val json = Jval.read(txt).asArray().first()
                    val newBuild = json.getString("tag_name", "")
                    val (version, revision) = ("$newBuild.0").removePrefix("v")
                        .split(".").map { it.toInt() }
                    if (version > Version.build || (version == Version.build && revision > Version.revision)) {
                        val asset = json.get("assets").asArray().find {
                            it.getString("name", "").contains("server", ignoreCase = true)
                        } ?: error("New version $newBuild, but can't find asset")
                        val url = asset.getString("browser_download_url", "")
                        try {
                            update(newBuild, url.let { if (useMirror) "$mirror/$it" else it })
                            cancel()
                        } catch (e: Throwable) {
                            logger.warning("下载更新失败: $e")
                            e.printStackTrace()
                        }
                    }
                } catch (e: Throwable) {
                    logger.warning("获取更新数据失败: $e")
                }
        }
        delay(5 * 60_000)//延时5分钟
    }
}

suspend fun update(version: String, url: String) {
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
            tmp.inputStream().use { it.copyTo(output) }
            output.flush()
        }
        tmp.delete()
        Log.info(
            "&lcVersion downloaded, exiting. Note that if you are not using a auto-restart script, the server will not restart automatically."
        )
    }
}

command("updateConfig", "从git仓库强制同步config/scripts目录") {
    permission = dotId
    body {
        reply("[green]正在后台拉取仓库...".with())
        launch {
            try {
                val out = syncScripts(configRepo, githubToken)
                reply("[green]config/scripts 已同步至origin/main".with())
                reply(
                    "[yellow]脚本变更需重启后完全生效;也可使用sa指令重载。\n[lightgrey]{out}"
                        .with("out" to out.takeLast(200))
                )
            } catch (e: Throwable) {
                reply("[red]同步失败: {msg}".with("msg" to (e.message ?: e.toString())))
                Log.err(e)
            }
        }
    }
}

command("forceUpdate", "强制更新服务器版本") {
    permission = dotId
    usage = "<url>"
    body {
        arg.firstOrNull()?.let { kotlin.runCatching { URL(it) }.getOrNull() } ?: replyUsage()
        reply("[green]正在后台处理中".with())
        launch {
            try {
                update("管理员手动升级", arg.first())
            } catch (e: Throwable) {
                reply("[red]升级失败{e}".with("e" to e))
                e.printStackTrace()
            }
        }
    }
}
