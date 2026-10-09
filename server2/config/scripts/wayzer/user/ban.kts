@file:Depends("coreLibrary/DBApi", "数据库储存")

package wayzer.user

import coreLibrary.DBApi.DB.registerTable
import java.text.DateFormat
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*

registerTable(PlayerBan.T)

fun Player.kick(ban: PlayerBan) {
    fun format(instant: Instant) = DateFormat.getDateTimeInstance().format(Date.from(instant))
    kick(
        """
        [red]你已在该服被禁封[]
        [yellow]名字: ${name()}
        [green]原因: ${ban.reason} (封禁ID#${ban.id})
        [green]禁封时间: ${format(ban.createTime)}
        [green]解禁时间: ${format(ban.endTime)}
        [yellow]如有问题,请截图此页咨询管理员
    """.trimIndent(), 0
    )
}

listen<EventType.PlayerConnect> {
    launch(Dispatchers.IO) {
        val ban = PlayerBan.findNotEnd(PlayerData[it.player].id) ?: return@launch
        withContext(Dispatchers.game) {
            it.player.kick(ban)
        }
    }
}

suspend fun ban(player: PlayerData, time: Int, reason: String, operate: Player?) {
    val ban = withContext(Dispatchers.IO) {
        PlayerBan.create(
            player, Duration.ofMinutes(time.toLong()), reason,
            operate?.let { PlayerData[it].id }
        )
    }
    Groups.player.filter { PlayerData[it].id in player.ids }.forEach {
        it.kick(ban)
        broadcast("[red] 管理员把{target.name}飞了,原因: [yellow]{reason}".with("target" to it, "reason" to reason))
    }
}

command("banX", "管理指令: 禁封") {
    usage = "<3位id> <时间|分钟> <原因>"
    permission = "wayzer.admin.ban"
    body {
        if (arg.size < 3) replyUsage()
        val uuid = netServer.admins.getInfoOptional(arg[0])?.id
            ?: depends("wayzer/user/shortID")?.import<(String) -> String?>("getUUIDbyShort")?.invoke(arg[0])
            ?: returnReply("[red]请输入目标3位ID,不清楚可通过/list查询".with())
        val snapshot = Groups.player.find { it.uuid() == uuid }?.let { PlayerData[it] }
            ?: PlayerData.history.getIfPresent(uuid) ?: returnReply("[red]未找到目标".with())
        val time = arg[1].toIntOrNull()?.takeIf { it > 0 } ?: replyUsage()
        val reason = arg.slice(2 until arg.size).joinToString(" ")

        ban(snapshot, time, reason, player)
        reply("[green]已禁封{qq}".with("qq" to (uuid)))
    }
}
command("banmap", "查看当前所有未过期的封禁记录") {
    usage = "[page]"
    aliases = listOf("banlist", "ban查询")
    attr(RequirePermission("wayzer.admin.ban"))
    body {
        val page = arg.firstOrNull()?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val perPage = 10
        val all = withContext(Dispatchers.IO) { PlayerBan.allNotEnd() }
            .sortedByDescending { it.createTime }
        if (all.isEmpty())
            returnReply("[green]当前没有进行中的封禁".with())
        val totalPage = (all.size + perPage - 1) / perPage
        val p = page.coerceIn(1, totalPage)
        val items = all.slice((p - 1) * perPage until (p - 1) * perPage + perPage)
        val opName: (String?) -> String = { op ->
            op?.let {
                val ids = it.removeSurrounding("$").split("$")
                val uuid = ids.firstOrNull() ?: it
                netServer.admins.getInfoOptional(uuid)?.name
                    ?: ids.joinToString(", ")
            } ?: "(系统)"
        }
        val lines = items.joinToString("\n") { ban ->
            val ids = ban.ids.removeSurrounding("$").split("$").joinToString(", ")
            val firstId = ids.firstOrNull() ?: "?"
            val formatTime = { t: Instant ->
                t.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
            }
            "[red]#${ban.id}[green] ${firstId} [yellow]ids:${ids} [lightgrey]原因:${ban.reason} " +
                    "[violet]操作者:${opName(ban.operator)} " +
                    "[grey]封禁:${formatTime(ban.createTime)} [grey]解禁:${formatTime(ban.endTime)}"
        }
        reply(
            "[violet]封禁记录(第${p}/${totalPage}页,共${all.size}条)\n{lines}\n" +
                    "[grey]使用 /unbanX <ID> 取消封禁".with("lines" to lines)
        )
    }
}
command("unbanX", "管理指令: 解禁") {
    usage = "<id>"
    permission = "wayzer.admin.unban"
    body {
        if (arg.isEmpty()) replyUsage()
        val id = arg[0].toIntOrNull() ?: replyUsage()
        val ban = PlayerBan.delete(id) ?: returnReply("[red]找不到封禁记录，检查ID是否正确".with())
        logger.info("unban ${ban.ids} ${ban.endTime} ${ban.reason}")
        reply("[green]解禁成功, 禁封原因: {reason}".with("reason" to ban.reason))
    }
}
PermissionApi.registerDefault("wayzer.admin.ban", "wayzer.admin.unban", group = "@admin")