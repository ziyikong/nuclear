@file:Depends("coreLibrary/extApi/KVStore", "KV存储")
@file:Depends("wayzer/user/economy", "钍币经济")

package wayzer.rpg

import cf.wayzer.placehold.DynamicVar
import coreLibrary.lib.with
import cf.wayzer.scriptAgent.contextScript
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import mindustry.Vars
import mindustry.game.EventType
import mindustry.gen.Groups
import mindustry.content.StatusEffects
import mindustry.gen.Player
import org.h2.mvstore.type.StringDataType

name = "传奇RPG: 等级·经验·Boss波"

// ===== 配置 =====
val expBase by config.key(100, "1级升2级所需经验(之后按1.5次幂增长)")
val waveExpEvery by config.key(5, "每N波发放一次经验")
val waveExp by config.key(20, "每次波次经验基础值")
val bossWaveEvery by config.key(25, "Boss波间隔")
val bossThorium by config.key(200, "Boss波全服钍奖励")
val deathPenaltyPct by config.key(10, "死亡损失经验百分比")

// ===== 存储 =====
private val expMap = contextScript<coreLibrary.extApi.KVStore>().open("rpg.exp", StringDataType.INSTANCE)

fun getExp(p: Player): Long = expMap[p.uuid()]?.toLongOrNull() ?: 0L

fun addExp(p: Player, n: Long) {
    if (n <= 0L) return
    val oldLevel = levelOf(getExp(p))
    val newExp = getExp(p) + n
    expMap[p.uuid()] = newExp.toString()
    val newLevel = levelOf(newExp)
    if (newLevel > oldLevel && p.unit() != null) {
        broadcast("[gold]⭐ 恭喜 {player} 升级到 Lv.{lv}! 获得永久强化!".with("player" to p, "lv" to newLevel))
        applyBuffs(p.unit(), newLevel)
    }
}

fun levelOf(exp: Long): Int {
    var lv = 0; var need = expBase.toLong()
    var rest = exp
    while (rest >= need) { rest -= need; lv++; need = (need * 1.5).toLong() }
    return lv + 1
}

fun applyBuffs(u: mindustry.gen.Unit?, lv: Int) {
    u ?: return
    u.apply(StatusEffects.overclock, Float.MAX_VALUE)
    u.apply(StatusEffects.shielded, Float.MAX_VALUE)
    if (lv >= 5) u.apply(StatusEffects.boss, Float.MAX_VALUE)
}

// ===== 波次奖励 =====
listen<EventType.WaveEvent> {
    if (!state.isPlaying || state.isPaused) return@listen
    val wave = state.wave
    val players = Groups.player.toList()
    if (players.isEmpty()) return@listen

    if (wave % waveExpEvery == 0) {
        val exp = waveExp + (wave / 10)
        players.forEach { addExp(it, exp.toLong()) }
        broadcast("[cyan]📈 第{wave}波存活! 全体+[exp]经验".with("wave" to wave, "exp" to exp), quite = true)
    }

    if (wave % bossWaveEvery == 0) {
        players.forEach { addExp(it, (waveExp * 3).toLong()) }
        contextScript<Economy>().let { eco -> players.forEach { eco.addMoney(it, bossThorium) } }
        broadcast(
            "[scarlet]💀 Boss波攻克![] 全体+[gold]{thorium}钍[] 与 [cyan]{exp}经验!"
                .with("thorium" to bossThorium, "exp" to waveExp * 3)
        )
    }
}

// ===== 死亡惩罚 =====
listen<EventType.UnitDestroyEvent> { e ->
    val p = e.unit.player ?: return@listen
    if (e.unit.team() == state.rules.waveTeam) return@listen
    val cur = getExp(p)
    val loss = cur * deathPenaltyPct / 100
    if (loss > 0) {
        expMap[p.uuid()] = (cur - loss).toString()
        p.sendMessage("[red]💀 你阵亡了, 损失 {loss} 经验".with("loss" to loss).toString())
    }
}

// ===== 进服重施加成 =====
listen<EventType.PlayerJoin> {
    launch(Dispatchers.game) {
        delay(3000)
        val lv = levelOf(getExp(it.player))
        if (lv > 1) applyBuffs(it.player.unit(), lv)
    }
}

// ===== 查询指令 =====
command("stats", "查看等级与经验") {
    aliases = listOf("我的属性")
    body {
        val me = player ?: returnReply("[red]请在游戏内使用".with())
        val exp = getExp(me)
        val lv = levelOf(exp)
        var need = expBase.toLong(); var used = 0L; var l = 1
        while (l < lv + 1) { used += need; need = (need * 1.5).toLong(); l++ }
        reply(
            "[cyan]===[white] 我的属性 [cyan]===\n" +
            "[gold]等级: Lv.{lv}\n[cyan]经验: {cur}/{need}\n[gold]下一级还需: {left}"
                .with("lv" to lv, "cur" to exp, "need" to (used + need), "left" to (used + need - exp))
        )
    }
}

registerVarForType<Player>().apply {
    registerChild("level", "玩家传奇等级", DynamicVar.obj { levelOf(getExp(it)) })
}