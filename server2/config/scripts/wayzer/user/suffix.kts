package wayzer.user

import cf.wayzer.placehold.DynamicVar
import mindustry.gen.Iconc

val logVersion by config.key(false, "记录玩家的版本信息")

val cache = mutableMapOf<String, String>()
val vipSet = mutableSetOf<String>()
listen<EventType.PlayerJoin> {
    val p = it.player
    if (p.hasPermission("suffix.vip")) vipSet += p.uuid()
}
listen<EventType.PlayerLeave> {
    val u = it.player.uuid()
    cache.remove(u)
    vipSet.remove(u)
}

/** 非挂起判断玩家是否为VIP(@vip组/拥有suffix.vip权限)。供投票等无法挂起的场合同步使用 */
fun isVip(player: Player): Boolean = player.uuid() in vipSet
export(this::isVip)

fun Player.getSuffix(): String? {
    cache[uuid()]?.let { return it }
    launch {
        cache[uuid()] = when {
            hasPermission("suffix.admin") -> "${Iconc.admin}"
            hasPermission("suffix.vip") -> "[gold]V[]"
            else -> return@launch
        }
    }
    return null
}

@Savable
val clientType = mutableMapOf<String, Char>()
customLoad(::clientType) { clientType.putAll(it) }
listen<EventType.PlayerLeave> { clientType.remove(it.player.uuid()) }
onEnable {
    netServer.addPacketHandler("ARC") { p, v ->
        if (logVersion)
            logger.info("ARC ${p.name} $v")
        clientType[p.uuid()] = Iconc.blockArc
    }
    netServer.addPacketHandler("MDTX") { p, v ->
        if (logVersion)
            logger.info("MDTX ${p.name} $v")
        clientType[p.uuid()] = 'X'
    }
    netServer.addPacketHandler("fooCheck") { p, v ->
        if (logVersion)
            logger.info("FOO ${p.name} $v")
        clientType[p.uuid()] = '⒡'
    }
}
onDisable {
    netServer.getPacketHandlers("ARC").clear()
    netServer.getPacketHandlers("MDTX").clear()
    netServer.getPacketHandlers("fooCheck").clear()
}


registerVarForType<Player>().apply {

    registerChild("suffix.s2-clientType", "客户端类型后缀", DynamicVar.obj { p -> clientType[p.uuid()] })
    registerChild("suffix.s3-computer", "电脑玩家后缀", DynamicVar.obj { p -> ''.takeIf { !p.con.mobile } })
    registerChild("suffix.s5-group", "权限组后缀", DynamicVar.obj { it.getSuffix() })
}

PermissionApi.registerDefault("suffix.admin", group = "@admin")
PermissionApi.registerDefault("suffix.vip", group = "@vip")