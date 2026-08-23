package wayzer.cmds

import arc.files.Fi
import arc.util.Log
import mindustry.game.EventType
import mindustry.Vars
import mindustry.server.ServerControl

val cmdFile = Fi("server_commands.txt")
val lastProcessed = java.util.concurrent.atomic.AtomicLong(0)
var lastCheck = 0L

// Startup log to confirm script loads
Log.info("[AutoExec] Script loaded, waiting for autoexec on...")

listen(EventType.Trigger.update) {
    val now = System.currentTimeMillis()
    if (now - lastCheck < 1000) return@listen
    lastCheck = now
    
    if (cmdFile.exists()) {
        val len = cmdFile.length()
        if (len > lastProcessed.get()) {
            val content = cmdFile.readString()
            val lines = content.lines().toList()
            var processed = 0L
            lines.forEach { line ->
                if (processed < lastProcessed.get()) {
                    processed += line.length + 1
                    return@forEach
                }
                val trimmed = line.trim()
                if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                    try {
                        // 使用服务器控制处理器执行命令
                        val control = Vars.control as? ServerControl
                        val response = control?.handler?.handleMessage(trimmed)
                        Log.info("[AutoExec] Executed: ${trimmed} -> ${response?.type}")
                    } catch (e: Exception) {
                        Log.err("[AutoExec] Failed: ${trimmed}", e)
                    }
                }
                processed += line.length + 1
            }
            lastProcessed.set(len)
        }
    }
}

listen<EventType.ResetEvent> {
    lastProcessed.set(0)
}

command("autoexec", "自动执行命令文件开关") {
    usage = "[on/off]"
    permission = "scriptAgent.admin"
    body {
        if (arg.isEmpty()) {
            reply("用法: autoexec on|off".with())
            return@body
        }
        when (arg[0].lowercase()) {
            "on" -> {
                lastCheck = 0
                reply("[green]自动执行已启用".with())
            }
            "off" -> {
                reply("[yellow]自动执行已禁用".with())
            }
            else -> replyUsage()
        }
    }
}