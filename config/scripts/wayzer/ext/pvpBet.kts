@file:Depends("wayzer/user/economy", "钍币经济")

package wayzer.ext

import cf.wayzer.scriptAgent.contextScript
import coreLibrary.lib.with
import mindustry.game.EventType
import mindustry.game.Team
import mindustry.gen.Groups
import mindustry.gen.Player
import wayzer.user.ext.Economy

name = "扩展: PvP钍币押注"

// ===== 配置 =====
val minBet by config.key(10L, "单次押注最低钍数")
val maxBet by config.key(10000L, "单次押注最高钍数")

// ===== 押注数据(每局重置) =====
class Bet(val uuid: String, val team: Team, val amount: Long)
val bets = mutableListOf<Bet>()

fun betsOn(team: Team) = bets.filter { it.team == team }
fun potOf(team: Team) = betsOn(team).sumOf { it.amount }
fun totalPot() = bets.sumOf { it.amount }

/** 离线也能结算: 直接写经济余额表(与economy.kts的addMoney同一存储) */
fun addMoneyUuid(uuid: String, n: Long) {
    if (n <= 0) return
    val map = contextScript<Economy>().balanceMap
    map[uuid] = ((map[uuid]?.toLongOrNull() ?: 0L) + n).toString()
}

fun refundAll() {
    bets.forEach { addMoneyUuid(it.uuid, it.amount) }
    bets.clear()
}

fun teamColor(t: Team): String {
    val c = t.color
    return "[#%02x%02x%02x]".format((c.r * 255).toInt(), (c.g * 255).toInt(), (c.b * 255).toInt())
}

fun parseTeam(s: String): Team? {
    val id = s.toIntOrNull() ?: return null
    return if (id in 0..254) Team.get(id) else null
}

// ===== 押注 =====
command("bet", "PvP押注胜负") {
    aliases = listOf("押注")
    usage = "<队伍编号> <数量>"
    body {
        val me = player ?: returnReply("[red]请在游戏内押注".with())
        if (!state.isPlaying || !state.rules.pvp) returnReply("[red]当前不是PvP对局,无法押注".with())
        val team = parseTeam(arg.getOrElse(0) { "" }) ?: returnReply(replyUsage())
        val activeTeams = state.teams.getActive().map { it.team }
        if (team !in activeTeams)
            returnReply("[red]该队不在本局对局中 (可选编号: {teams})".with("teams" to activeTeams.joinToString("/") { it.id.toString() }))
        val amount = arg.getOrNull(1)?.toLongOrNull() ?: returnReply(replyUsage())
        if (amount < minBet || amount > maxBet)
            returnReply("[red]单次押注需在{min}~{max}钍之间".with("min" to minBet, "max" to maxBet))
        val econ = contextScript<Economy>()
        if (!econ.costMoney(me, amount)) returnReply("[red]钍不足,你有{have}钍".with("have" to econ.getMoney(me)))

        bets.add(Bet(me.uuid(), team, amount))
        val sidePot = potOf(team)
        val pot = totalPot()
        val odds = "%.2f".format(pot.toDouble() / sidePot)
        broadcast(
            "[gold]🎲 {player} 押注 {color}[队伍{teamId}][] [yellow]+{amt}钍[] — 该队池 {sidePot} | 总池 {pot} | 现赔率 x{odds}"
                .with(
                    "player" to me, "color" to teamColor(team), "teamId" to team.id,
                    "amt" to amount, "sidePot" to sidePot, "pot" to pot, "odds" to odds
                )
        )
    }
}

// ===== 查看押注 =====
command("bets", "查看本局押注") {
    usage = ""
    body {
        if (bets.isEmpty())
            returnReply("[lightgrey]本局还没有押注 (PvP对局中输入 /bet <队伍编号> <数量>)".with())
        val lines = bets.groupBy { it.team }.entries.map { (t, bs) ->
            val pot = bs.sumOf { it.amount }
            val odds = "%.2f".format(totalPot().toDouble() / pot)
            "{color}[队伍{teamId}][] {n}人 {pot}钍 (赔率 x{odds})".with(
                "color" to teamColor(t), "teamId" to t.id, "n" to bs.size, "pot" to pot, "odds" to odds
            ).toString()
        }
        reply("[gold]===[white] 本局押注 [gold]===\n{list:\n}".with("list" to lines))
    }
}

// ===== 结算(GameOverEvent) =====
listen<EventType.GameOverEvent> { event ->
    if (bets.isEmpty()) return@listen
    if (!state.rules.pvp) {
        refundAll()
        broadcast("[grey]🎲 非PvP对局结束, 押注已全额退还".with(), quite = true)
        return@listen
    }
    val winner = event.winner
    val winSide = potOf(winner)
    val pot = totalPot()
    if (winSide <= 0) {
        refundAll()
        broadcast("[grey]🎲 获胜队伍无人押注, 全部押注已退还".with(), quite = true)
        return@listen
    }
    // 赢家分池: 按各自押注占获胜方比例瓜分总奖池
    val winners = betsOn(winner)
    winners.forEach { b ->
        val payout = (pot.toDouble() * b.amount / winSide).toLong()
        addMoneyUuid(b.uuid, payout)
        Groups.player.find { it.uuid() == b.uuid }?.sendMessage(
            "[gold]🎲 押中了![] 押 [yellow]{amt}钍[] 得 [green]+{got}钍".with("amt" to b.amount, "got" to payout).toString()
        )
    }
    broadcast(
        "[gold]🎲 押注结算: {color}[队伍{teamId}][] 获胜! {n}位赢家瓜分 [yellow]{pot}钍"
            .with("color" to teamColor(winner), "teamId" to winner.id, "n" to winners.size, "pot" to pot)
    )
    bets.clear()
}

// ===== 安全网: 换图未结算 → 退款 =====
listen<EventType.WorldLoadEvent> {
    if (bets.isEmpty()) return@listen
    refundAll()
    broadcast("[grey]🎲 地图重载, 未结算押注已退还".with(), quite = true)
}
