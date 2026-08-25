@file:Depends("coreLibrary/extApi/KVStore", "KV存储")
@file:Depends("wayzer/maps", "地图管理")

package wayzer.user.ext

import arc.util.Log
import cf.wayzer.placehold.DynamicVar
import coreLibrary.lib.with
import cf.wayzer.scriptAgent.contextScript
import kotlinx.coroutines.Dispatchers
import mindustry.Vars
import mindustry.game.Team
import mindustry.gen.Groups
import mindustry.gen.Player
import org.h2.mvstore.type.StringDataType

name = "用户: 钍币经济系统"

// ===== 配置 =====
val onlineIntervalMin by config.key(5, "在线奖励间隔(分钟)")
val onlineReward by config.key(5, "每个间隔奖励的钍")
val pvpWinReward by config.key(100, "PVP胜利每人奖励的钍")
val pveWinReward by config.key(80, "生存/进攻胜利每人奖励的钍")
val redPacketExpireSec by config.key(60, "红包过期时间(秒),过期退回")

/** 余额表 uuid -> 钍 */
val balanceMap = contextScript<coreLibrary.extApi.KVStore>().open("economy.balance", StringDataType.INSTANCE)

// ===== 核心API(编译为Economy类成员,供依赖脚本调用) =====
fun getMoney(p: Player): Long = balanceMap[p.uuid()]?.toLongOrNull() ?: 0L

fun addMoney(p: Player, n: Long) {
    if (n <= 0) return
    balanceMap[p.uuid()] = (getMoney(p) + n).toString()
}

/** 扣款,余额不足返回false */
fun costMoney(p: Player, n: Long): Boolean {
    if (n <= 0) return true
    val cur = getMoney(p)
    if (cur < n) return false
    balanceMap[p.uuid()] = (cur - n).toString()
    return true
}

fun topMoney(limit: Int = 10): List<Pair<String, Long>> =
    balanceMap.entries.mapNotNull { e -> e.value.toLongOrNull()?.let { e.key to it } }
        .sortedByDescending { it.second }.take(limit)

// ===== 在线时长奖励 =====
onEnable {
    launch(Dispatchers.game) {
        while (true) {
            delay(onlineIntervalMin * 60_000L)
            if (!state.isPlaying || state.isPaused) continue
            val players = Groups.player.toList()
            if (players.isEmpty()) continue
            players.forEach { addMoney(it, onlineReward.toLong()) }
            broadcast(
                "[gold]💰 在线奖励: 每人[+]{reward}钍 (在线满{min}分钟)"
                    .with("reward" to onlineReward, "min" to onlineIntervalMin),
                quite = true
            )
        }
    }
}

// ===== 胜利奖励(PVP胜利 / 生存·进攻胜利) =====
listen<EventType.GameOverEvent> { event ->
    if (!state.isPlaying) return@listen
    val players = Groups.player.toList()
    if (players.isEmpty()) return@listen

    val isPvp = state.rules.pvp
    val winners: List<Player> = if (isPvp) {
        players.filter { it.team() == event.winner }
    } else {
        // 生存/进攻: 胜利方为玩家队(waveTeam获胜代表失败)
        if (event.winner == state.rules.waveTeam || event.winner.isAI) emptyList()
        else players
    }
    if (winners.isEmpty()) return@listen

    val amount = if (isPvp) pvpWinReward.toLong() else pveWinReward.toLong()
    val label = if (isPvp) "PVP胜利" else "胜利"
    winners.forEach { addMoney(it, amount) }
    val names = winners.joinToString("[white],[]") { it.coloredName() }
    broadcast(
        "[gold]💰 {label}:[] {names} 各[+]{amount}钍"
            .with("label" to label, "names" to names, "amount" to amount),
        quite = true
    )
}

// ===== 红包 =====
class RedPacket(
    val fromUuid: String,
    val fromName: String,
    val shares: ArrayDeque<Long>,
    val expireAt: Long
)

val redPackets = mutableListOf<RedPacket>()

command("hongbao", "发红包") {
    aliases = listOf("红包")
    usage = "<份数> <总额>"
    body {
        val sender = player ?: returnReply("[red]请在游戏内发红包".with())
        val count = arg.getOrNull(0)?.toIntOrNull() ?: returnReply(replyUsage())
        val total = arg.getOrNull(1)?.toLongOrNull() ?: returnReply(replyUsage())
        if (count !in 1..30) returnReply("[red]份数需在1~30之间".with())
        if (total < count) returnReply("[red]总额至少为份数×1钍".with())
        if (!costMoney(sender, total)) returnReply("[red]钍不足,你有{have}钍".with("have" to getMoney(sender)))

        // 随机拆分: 每份至少1
        var remain = total
        val shares = ArrayDeque<Long>()
        repeat(count - 1) {
            val max = remain - (count - shares.size - 1)
            val v = (1..max.coerceAtLeast(1)).random()
            shares.add(v); remain -= v
        }
        shares.add(remain)

        redPackets.add(RedPacket(sender.uuid(), sender.coloredName(), shares, System.currentTimeMillis() + redPacketExpireSec * 1000))
        broadcast(
            "[gold]🧧 {player} 发出红包![] 共[count]份/[total]钍,输入 [accent]/qhb[] 抢红包!"
                .with("player" to sender, "count" to count, "total" to total)
        )
    }
}

command("qhb", "抢红包") {
    aliases = listOf("抢红包")
    body {
        val me = player ?: returnReply("[red]请在游戏内抢红包".with())
        val now = System.currentTimeMillis()
        redPackets.removeAll { it.expireAt < now && run {
            // 过期退款
            val back = it.shares.sum()
            if (back > 0) {
                val finder = Groups.player.find { p -> p.uuid() == it.fromUuid }
                    ?: Groups.player.find { p -> it.fromName.contains(p.name) }
                finder?.let { f -> addMoney(f, back) }
            }
            true
        } }
        val packet = redPackets.firstOrNull { it.shares.isNotEmpty() }
            ?: returnReply("[red]没有可抢的红包".with())
        val money = packet.shares.removeFirst()
        addMoney(me, money)
        reply("[gold]🧧 抢到 [+]{money} 钍![剩余{left}份]".with("money" to money, "left" to packet.shares.size))
        if (packet.shares.isEmpty()) {
            redPackets.remove(packet)
            broadcast("[grey]🧧 {player} 抢完了{from}的红包".with("player" to me, "from" to packet.fromName), quite = true)
        }
    }
}

// ===== 查询指令 =====
command("money", "查看钍币(排行榜)") {
    aliases = listOf("钍", "余额")
    usage = "[top]"
    body {
        if (arg.firstOrNull()?.lowercase() == "top") {
            val list = topMoney(10)
            reply(
                "[gold]===[white] 钍币排行 [gold]===\n{list:\n}".with(
                    "list" to list.mapIndexed { i, (uuid, v) ->
                        val name = Groups.player.find { it.uuid() == uuid }?.coloredName() ?: "${uuid.take(6)}…"
                        "[red]#${i + 1}[] $name: [yellow]$v"
                    }.ifEmpty { listOf("[lightgrey](暂无数据)") }
                )
            )
        } else {
            val target = player ?: returnReply("[red]控制台请使用 money top".with())
            reply("[yellow]你的钍币: [gold]{money}".with("money" to getMoney(target)))
        }
    }
}

// 变量注册: 其他模板可用{player.money}
registerVarForType<Player>().apply {
    registerChild("money", "玩家钍币余额", DynamicVar.obj { getMoney(it) })
}

// 定时清理过期红包
onEnable {
    launch(Dispatchers.game) {
        while (true) {
            delay(30_000)
            val now = System.currentTimeMillis()
            redPackets.removeAll { pkt ->
                if (pkt.expireAt >= now) return@removeAll false
                val back = pkt.shares.sum()
                if (back > 0) {
                    val finder = Groups.player.find { p -> p.uuid() == pkt.fromUuid }
                        ?: Groups.player.find { p -> pkt.fromName.contains(p.name) }
                    finder?.let { addMoney(it, back) }
                    broadcast("[grey]🧧 {from} 的红包过期退回[+]{back}钍".with("from" to pkt.fromName, "back" to back), quite = true)
                }
                true
            }
        }
    }
}