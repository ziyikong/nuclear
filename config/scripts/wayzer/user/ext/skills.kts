package wayzer.user.ext

import arc.util.io.Writes
import mindustry.gen.Building
import mindustry.gen.Unit as MindustryUnit
import wayzer.user.ext.Skills.Api.skill
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.time.Duration
import arc.Events
import mindustry.game.EventType

@Savable(false)
val used = mutableMapOf<String, Long>()
var plagueActive = false
customLoad(::used, used::putAll)
listen<EventType.ResetEvent> { 
    used.clear()
    plagueActive = false
}

listen<EventType.UnitDestroyEvent> { e ->
    if (!plagueActive) return@listen
    val unit = e.unit
    if (unit.hasEffect(StatusEffects.corroded)) {
        val duration = unit.getDuration(StatusEffects.corroded)
        Groups.unit.forEach { u -> u.apply(StatusEffects.corroded, 60f * (duration / 60f + 2)) }
        broadcast("[red]瘟疫爆发! 全场单位获得  层腐蚀!".with())
    }
}
command("skill", "技能菜单") {
    aliases = listOf("技能")
    body(Api.skills)
}

@Suppress("unused")
companion object Api {
    val skills = Commands()
    lateinit var script: Skills
    private val used get() = script.used

    @DslMarker
    annotation class SkillScopeMarker

        @Suppress("MemberVisibilityCanBePrivate")
        class SkillScope(val name: String, val player: Player, val ctx: CommandContext) {
            @SkillScopeMarker
            fun returnReply(msg: PlaceHoldString): Nothing = ctx.returnReply(msg)

        /** @param coolDown in ms,  -1一局冷却 */
        fun checkCoolDown(coolDown: Int, set: Boolean = true): Boolean {
            // 每个玩家每个技能独立冷却
            val key = "${player.uuid()}:$name"
            if (key in used) {
                if (coolDown < 0) {
                    ctx.reply("[red]该技能每局限用一次".with())
                    return false
                } else if (used[key]!! >= System.currentTimeMillis()) {
                    ctx.reply("[red]技能冷却，还剩{time:秒}".with("time" to Duration.ofMillis(used[key]!! - System.currentTimeMillis())))
                    return false
                }
            }
            if (set) used[key] = System.currentTimeMillis() + coolDown
            return true
        }

        /** @param coolDown in ms,  -1一局冷却 */
        @SkillScopeMarker
        fun checkOrSetCoolDown(coolDown: Int) {
            if (!checkCoolDown(coolDown)) CommandInfo.Return()
        }

        @SkillScopeMarker
        fun broadcastSkill(skill: String) = broadcast(
            "[yellow][技能][green]{player.name}[white]使用了[green]{skill}[white]技能."
                .with("player" to player, "skill" to skill), quite = true
        )
    }

    @ScriptDsl
    fun Script.skill(name: String, desc: String, vararg aliases: String, body: SkillScope.() -> Unit) {
        skills += CommandInfo(this, name, desc) {
            attr(RequirePermission("wayzer.user.skills."))
            attr(ClientOnly)
            this.aliases = aliases.toList()
            body {
                @Suppress("MemberVisibilityCanBePrivate")
                if (state.rules.pvp)
                    returnReply("[red]PVP模式下禁用所有技能".with())
                if (player!!.dead())
                    returnReply("[red]你已死亡".with())
                SkillScope(name, player!!, context).body()
            }
        }
    }

    fun syncTile(vararg builds: Building) {
        val outStream = ByteArrayOutputStream()
        val write = DataOutputStream(outStream)
        builds.forEach {
            write.writeInt(it.pos())
            write.writeShort(it.block.id.toInt())
            it.writeAll(Writes.get(write))
        }
        Call.blockSnapshot(builds.size.toShort(), outStream.toByteArray())
    }
}
Api.script = this

skill("mono", "技能: 召唤采矿机(自动挖铜/铅),一局限一次,PVP禁用", "矿机") {
    checkOrSetCoolDown(-1)
    UnitTypes.mono.create(player.team()).also {
        it.set(player)
        it.add()
    }
    broadcastSkill("采矿机")
}

skill("poly", "技能: 召唤工程无人机(可建造/维修),一局限一次,PVP禁用", "工程", "工程机") {
    if (state.rules.bannedBlocks.contains(Blocks.airFactory))
        returnReply("[red]该地图工程无人机已禁封,禁止召唤".with())
    checkOrSetCoolDown(-1)
    UnitTypes.poly.create(player.team()).also {
        it.set(player)
        it.add()
    }
    broadcastSkill("工程无人机")
}

skill("mega", "技能: 召唤大型支援机(治疗光束),一局限一次,PVP禁用", "支援", "大型机") {
    if (state.rules.bannedBlocks.contains(Blocks.airFactory))
        returnReply("[red]该地图大型支援机已禁封,禁止召唤".with())
    checkOrSetCoolDown(-1)
    UnitTypes.mega.create(player.team()).also {
        it.set(player)
        it.add()
    }
    broadcastSkill("大型支援机")
}

skill("heal", "技能: 恢复50%生命值,冷却60秒", "治疗", "回血") {
    checkOrSetCoolDown(60000)
    player.unit()?.let { it.heal(it.maxHealth * 0.5f) }
    broadcastSkill("治疗")
}

skill("shield", "技能: 获得等同最大血量的护盾值,冷却120秒", "护盾") {
    checkOrSetCoolDown(120000)
    player.unit()?.let { unit ->
        unit.shield = unit.maxHealth.toFloat()
    }
    broadcastSkill("护盾")
}

skill("overclock", "技能: 获得超频状态(攻速/移速/伤害提升),冷却90秒", "超频", "加速") {
    checkOrSetCoolDown(90000)
    player.unit()?.apply(StatusEffects.overclock, 600f)
    broadcastSkill("超频")
}

skill("ammo", "技能: 补充弹药至上限,冷却45秒", "补给", "弹药") {
    checkOrSetCoolDown(45000)
    player.unit()?.let { unit ->
        // 重新装填所有武器
        unit.mounts.forEach { mount ->
            if (mount.weapon.controllable) {
                mount.reload = 0f
            }
        }
    }
    broadcastSkill("补给")
}

skill("tp", "技能: 传送到核心,冷却180秒", "传送", "回城") {
    checkOrSetCoolDown(180000)
    val core = player.team().cores().firstOrNull()
    if (core != null) {
        player.unit()?.set(core.x, core.y)
        broadcastSkill("传送")
    } else {
        returnReply("[red]未找到核心".with())
    }
}

skill("repair", "技能: 修复周围100格内己方建筑(30%血量),冷却120秒", "修复") {
    checkOrSetCoolDown(120000)
    val unit = player.unit() ?: returnReply("[red]你没有单位".with())
    Groups.build.filter { it.team == player.team() && it.dst(unit) < 100f }.forEach {
        it.heal(it.maxHealth * 0.3f)
    }
    broadcastSkill("修复")
}

skill("retusa", "技能: 召唤海军t1小绿(发射制导鱼雷),一局限一次,PVP禁用,需水域", "t1小绿", "海军") {
    checkOrSetCoolDown(-1)
    UnitTypes.retusa.create(player.team()).also {
        it.set(player)
        it.add()
    }
    broadcastSkill("t1小绿")
}

skill("plague", "技能: 瘟疫-随机感染一单位2层腐蚀,腐蚀单位死传播(层数+2),冷却180秒", "瘟疫") {
    checkOrSetCoolDown(180000)
    val targets = Groups.unit.filter { it.team != player.team() && it.healthf() > 0f }
    if (targets.isEmpty()) returnReply("[red]无有效目标".with())
    val target = targets.random()
    target.apply(StatusEffects.corroded, 60f * 2)
    broadcastSkill("瘟疫->")
    plagueActive = true
}