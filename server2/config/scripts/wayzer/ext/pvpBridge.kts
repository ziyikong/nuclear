@file:Depends("wayzer/user/economy", "钍币经济")

package wayzer.ext

import arc.Events
import arc.func.Cons
import arc.util.Log
import cf.wayzer.scriptAgent.contextScript
import mindustry.game.EventType
import mindustry.gen.Groups
import wayzer.user.ext.Economy

name = "扩展: PlayerVsPlayer押注联动"

val betBonus by config.key(50L, "链上下注成功额外奖励的钍")
val matchNoSkills by config.key(true, "官方对局进行中是否禁用技能")

/** 官方对局进行中标志(skills等脚本通过 contextScript<PvpBridge>().matchActive 查询) */
var matchActive = false

/**
 * 插件类不在脚本编译classpath,使用反射挂接事件;
 * 插件缺失时本脚本静默降级,不影响其他功能
 */
fun listenReflect(className: String, cons: Cons<Any>) {
    try {
        val cls = Class.forName(className)
        @Suppress("UNCHECKED_CAST")
        Events.on(cls as Class<Any>, cons)
        Log.info("[PvPBridge] 已挂接 $className")
    } catch (e: Throwable) {
        Log.warn("[PvPBridge] 挂接 $className 失败(插件未加载?): ${e.message}")
    }
}

private fun Any.field(name: String): Any? =
    this::class.java.getField(name).get(this)

onEnable {
    // 链上下注成功 → 奖励钍币
    listenReflect("PlayerVsPlayer.blockchain.BlockchainClient\$SuccessfullPlayerBet") { e ->
        val uuid = e.field("uuid") as? String ?: return@listenReflect
        val amount = e.field("betAmount") as? Double ?: return@listenReflect
        val p = Groups.player.find { it.uuid() == uuid } ?: return@listenReflect
        if (betBonus > 0) {
            contextScript<Economy>().addMoney(p, betBonus)
            p.sendMessage("[gold]🎲 链上押注成功![] 获得 [+]${betBonus} 钍奖励".with().toString())
        }
        broadcast(
            "[grey]🎲 {player} 在官方对局押注了 [yellow]{amt}[grey] ETH"
                .with("player" to p, "amt" to "%.4f".format(amount)),
            quite = true
        )
    }
    // 官方对局开始 → 禁用技能
    listenReflect("PlayerVsPlayer.PlayerVsPlayer\$GameReadyToStart") { _ ->
        matchActive = true
        if (matchNoSkills)
            broadcast("[accent]⚔ 官方PVP对局开始![] 期间技能不可用".with())
    }
    listenReflect("PlayerVsPlayer.PlayerVsPlayer\$GameFailed") { _ ->
        matchActive = false
    }
}

// 对局结束兜底恢复
listen<EventType.GameOverEvent> {
    if (matchActive) {
        matchActive = false
        if (matchNoSkills) broadcast("[green]官方对局结束,技能已恢复".with(), quite = true)
    }
}

onDisable { matchActive = false }