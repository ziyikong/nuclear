@file:Depends("wayzer/vote", "投票实现")
@file:Depends("wayzer/cmds/restart", "计划重启")

package wayzer.cmds

import arc.files.Fi
import arc.util.Log
import cf.wayzer.scriptAgent.contextScript
import mindustry.Vars
import wayzer.VoteService
import java.util.Locale

val dpDirName by config.key("dps", "数据包候选目录(位于config下)")

private val dpExts = setOf("zip", "jar")

fun VoteService.registerDP() {
    addSubVote(
        "添加数据包(通过后自动安装,本局结束重启生效)", "<文件名>", "dp", "数据包"
    ) {
        val dir = Vars.dataDirectory.child(dpDirName)
        if (arg.isEmpty()) {
            val list = dir.list().filter { it.extension().lowercase(Locale.ROOT) in dpExts }
                .map { it.nameWithoutExtension() }
            returnReply(
                "[yellow]可用数据包(dps目录):\n{list:\n}".with(
                    "list" to list.ifEmpty { listOf("[lightgrey](空)[]") }
                )
            )
        }
        val name = arg[0]
        val src: Fi = sequenceOf(dir.child(name), dir.child("$name.zip"), dir.child("$name.jar"))
            .firstOrNull { it.exists() }
            ?: returnReply("[red]数据包不存在: [accent]$name".with())
        if (Vars.modDirectory.child(src.name()).exists())
            returnReply("[red]同名文件已存在于mods目录".with())
        if (Vars.mods.getMod(src.nameWithoutExtension()) != null)
            returnReply("[red]该数据包已加载".with())

        val starter = player!!
        VoteService.start(
            starter,
            "添加数据包 [accent]$name".with(),
            extDesc = "[white]来源文件: [lightgrey]${src.path()}[][]",
            supportSingle = true
        ) {
            try {
                src.copyTo(Vars.modDirectory.child(src.name()))
            } catch (e: Exception) {
                broadcast("[red]数据包安装失败: ${e.message}".with(), quite = true)
                Log.err("安装数据包 $name 失败", e)
                return@start
            }
            broadcast(
                "[green]数据包[yellow]{name}[green]已安装,服务器将在本局结束后自动重启以应用".with("name" to name),
                quite = true
            )
            contextScript<Restart>().scheduleRestart("应用新数据包: $name")
        }
    }
}

onEnable { VoteService.registerDP() }