package wayzer.ext

import coreLibrary.lib.with
import mindustry.content.StatusEffects
import mindustry.gen.Unit
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

name = "扩展: 幸运骰子"

// ===== 配置 =====
val cooldownMs by config.key(30_000L, "掷骰冷却毫秒数")

val lastRoll = ConcurrentHashMap<String, Long>()

/** 扣血: 直接改血量(不吃护盾), 归零则死亡 */
fun hpLoss(unit: Unit, frac: Float) {
    val newHp = unit.health() - unit.maxHealth() * frac
    if (newHp <= 0.01f) unit.kill() else unit.health(newHp)
}

fun giveShield(unit: Unit, frac: Float) {
    unit.shield = unit.maxHealth() * frac
    unit.shieldAlpha = 1f
}

// ===== 掷骰 =====
command("dice", "掷骰子(0~100), 根据点数对自身单位施加效果, 免费") {
    aliases = listOf("骰子", "roll")
    usage = ""
    body {
        val me = player ?: returnReply("[red]请在游戏内使用".with())
        val uuid = me.uuid()
        val now = System.currentTimeMillis()
        val last = lastRoll[uuid]
        if (last != null && now - last < cooldownMs)
            returnReply("[red]骰子冷却中, 还剩 {s} 秒".with("s" to (cooldownMs - (now - last)) / 1000))
        val unit = me.unit()
        if (!unit.isValid || unit.dead)
            returnReply("[red]当前没有单位, 先出生再来掷".with())
        lastRoll[uuid] = now

        val roll = Random.nextInt(0, 101) // 0~100
        // 15个阶段: 低点数自爆/扣血/负面状态, 中段无事发生, 高点数回血/加盾/增益
        val (label, color) = when {
            roll <= 4 -> { unit.kill(); "单位自爆" to "[red]" }
            roll <= 9 -> { hpLoss(unit, 0.8f); "重伤 -80%血" to "[red]" }
            roll <= 15 -> { hpLoss(unit, 0.5f); "重创 -50%血" to "[red]" }
            roll <= 21 -> { hpLoss(unit, 0.3f); "受伤 -30%血" to "[scarlet]" }
            roll <= 27 -> { hpLoss(unit, 0.15f); "擦伤 -15%血" to "[orange]" }
            roll <= 33 -> { unit.apply(StatusEffects.burning, 12f); "灼烧 12秒" to "[orange]" }
            roll <= 39 -> { unit.apply(StatusEffects.slow, 20f); "减速 20秒" to "[lightgrey]" }
            roll <= 45 -> { unit.apply(StatusEffects.disarmed, 10f); "缴械 10秒" to "[yellow]" }
            roll <= 52 -> { "无事发生" to "[lightgrey]" }
            roll <= 59 -> { unit.heal(unit.maxHealth() * 0.25f); "回血 +25%" to "[green]" }
            roll <= 66 -> { unit.heal(unit.maxHealth() * 0.5f); "回血 +50%" to "[green]" }
            roll <= 73 -> { giveShield(unit, 0.5f); "护盾 +50%上限" to "[cyan]" }
            roll <= 79 -> { giveShield(unit, 1f); "护盾 +100%上限" to "[cyan]" }
            roll <= 89 -> { unit.apply(StatusEffects.overdrive, 30f); "超载 30秒 (+攻速)" to "[accent]" }
            else -> {
                unit.apply(StatusEffects.overdrive, 45f)
                giveShield(unit, 1f)
                unit.heal(unit.maxHealth())
                "头奖! 超载45秒+满盾+回满血" to "[gold]"
            }
        }
        broadcast(
            "[gold]🎲 {player} 掷出 [yellow]{roll}[]/100 — {color}{label}[]"
                .with("player" to me, "roll" to roll, "color" to color, "label" to label)
        )
    }
}
