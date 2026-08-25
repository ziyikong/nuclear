package wayzer.ext

import arc.util.Log
import mindustry.gen.Groups

name = "扩展: 自定义进出服消息权限管理(联动Agzam插件)"

/**
 * 反射调用Agzam插件: Admins.adminData(PlayerInfo).add/remove("longname") + Admins.save()
 * 玩家持有 longname 权限后即可使用 /custom join|leave 设置自己的进出服消息
 */
private fun toggleCustomPerm(uuidOrName: String): String {
    val target = Groups.player.find { it.uuid() == uuidOrName }
        ?: Groups.player.find { arc.util.Strings.stripColors(it.name).contains(uuidOrName, true) }

    val uuid = target?.uuid() ?: uuidOrName
    val adminsCls = Class.forName("agzam4.admins.Admins")
    val infoCls = Class.forName("mindustry.net.Administration\$PlayerInfo")
    val admCls = Class.forName("agzam4.admins.AdminData")

    val info = Class.forName("mindustry.Vars").getField("netServer").get(null)
        .let { ns -> ns.javaClass.methods.firstOrNull { it.name == "getInfoOptional" }?.invoke(ns, uuid) }
        ?: return "[red]未找到该玩家档案(需进过一次服务器)"

    val adm = adminsCls.methods
        .firstOrNull { it.name == "adminData" && it.parameterCount == 1 && it.parameterTypes[0] == infoCls }
        ?.invoke(null, info)
        ?: return "[red]获取玩家数据失败"

    val has = admCls.methods.firstOrNull { it.name == "has" && it.parameterTypes[0] == String::class.java }
        ?.invoke(adm, "longname") as? Boolean ?: false

    val opName = if (has) "remove" else "add"
    admCls.methods.firstOrNull { it.name == opName && it.parameterTypes[0] == String::class.java }
        ?.invoke(adm, "longname")
    adminsCls.getMethod("save").invoke(null)

    val nameShow = target?.coloredName() ?: uuid.take(8) + "…"
    return if (has) {
        "[yellow]已关闭 {name} 的自定义进出服消息权限".with("name" to nameShow).toString()
    } else {
        "[green]已授权 {name} 使用 /custom 设置进出服消息!(重新进服后生效)".with("name" to nameShow).toString()
    }
}

command("customperm", "管理: 开关玩家自定义进出服消息(/custom)") {
    aliases = listOf("自定义消息权限")
    usage = "<玩家名/uuid>"
    body {
        if (arg.isEmpty()) returnReply(replyUsage())
        reply(toggleCustomPerm(arg[0]).with())
    }
}