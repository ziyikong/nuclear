package wayzer.reGrief

import arc.Events
import arc.func.Cons
import arc.struct.ObjectMap
import arc.struct.Seq
import mindustry.Vars
import mindustry.content.Blocks
import mindustry.gen.Player
import mindustry.net.Administration
import mindustry.net.Administration.ActionType
import mindustry.world.Tile

name = "反破坏: 钍反应堆核心保护(中文)"

val protectRadius by config.key(10, "核心保护区半径(格),<=0关闭拦截")
val announceCN by config.key(true, "启用中文建造播报(替换Agzam插件的俄语播报)")

/** 是否位于任意队伍核心的保护区 */
fun nearCore(tile: Tile): Boolean {
    val radius = protectRadius
    if (radius <= 0) return false
    for (data in state.teams.present) {
        for (core in data.cores) {
            val dx = tile.worldx() - core.x
            val dy = tile.worldy() - core.y
            val range = radius * Vars.tilesize + core.block.size * Vars.tilesize / 2f
            if (dx * dx + dy * dy <= range * range) return true
        }
    }
    return false
}

/** 地图九宫格方位(替代原俄语播报里的方位词) */
fun locationName(tile: Tile): String {
    val col = tile.x * 3 / world.width() - 1
    val row = tile.y * 3 / world.height() - 1
    val rowName = when (row) { -1 -> "下方"; 1 -> "上方"; else -> "" }
    val colName = when (col) { -1 -> "左侧"; 1 -> "右侧"; else -> "" }
    return when {
        col == 0 && row == 0 -> "地图中央"
        rowName.isEmpty() -> "地图$colName"
        colName.isEmpty() -> "地图$rowName"
        else -> "地图$rowName$colName"
    }
}

// ===== 移除Agzam插件的俄语播报(BlockBuildBeginEvent全部旧监听),换上中文版 =====
val chineseAnnounce = Cons<EventType.BlockBuildBeginEvent> { e ->
    if (!announceCN) return@Cons
    val unit = e.unit ?: return@Cons
    if (e.breaking) return@Cons
    val plan = unit.buildPlan() ?: return@Cons
    if (plan.block != Blocks.thoriumReactor) return@Cons
    val player: Player = unit.getPlayer() ?: return@Cons
    broadcast(
        "[yellow]⚠ [green]{player.name}[] 正在[accent]{loc}[]建造[red]钍反应堆[]!"
            .with("player" to player, "loc" to locationName(e.tile))
    )
}

onEnable {
    try {
        val f = Events::class.java.getDeclaredField("events")
        f.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val map = f.get(null) as ObjectMap<Any, Seq<Cons<*>>>
        // 清空旧的BlockBuildBeginEvent监听(即插件注册的俄语播报),本服无其他使用者
        (map.get(EventType.BlockBuildBeginEvent::class.java) as? Seq<Cons<*>>)?.clear()
    } catch (e: Exception) {
        logger.warning("移除俄语播报失败(不影响其他功能): ${e.message}")
    }
    Events.on(EventType.BlockBuildBeginEvent::class.java, chineseAnnounce)
}

onDisable {
    Events.remove(EventType.BlockBuildBeginEvent::class.java, chineseAnnounce)
}

// ===== 核心保护区拦截 =====
registerActionFilter(Administration.ActionFilter { action ->
    if (action.type !== ActionType.placeBlock) return@ActionFilter true
    if (protectRadius <= 0) return@ActionFilter true
    val block = action.block ?: return@ActionFilter true
    if (block !== Blocks.thoriumReactor) return@ActionFilter true
    val tile = action.tile ?: return@ActionFilter true
    if (!nearCore(tile)) return@ActionFilter true
    action.player?.sendMessage(
        "[red]⛔ 核心保护区:[] 核心 [red]$protectRadius[] 格范围内禁止放置 [red]钍反应堆[]!".with().toString()
    )
    false
})