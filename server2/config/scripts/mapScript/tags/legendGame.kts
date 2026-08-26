@file:Depends("coreLibrary/extApi/KVStore", "KV存储")

package mapScript.tags

import arc.util.Log
import coreLibrary.lib.config
import coreLibrary.lib.with
import cf.wayzer.scriptAgent.contextScript
import cf.wayzer.scriptAgent.define.Script
import coreLibrary.lib.util.loop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import mindustry.Vars
import mindustry.content.Fx
import mindustry.content.StatusEffects
import mindustry.content.UnitTypes
import mindustry.game.EventType
import mindustry.game.Team
import mindustry.gen.Call
import mindustry.gen.Groups
import mindustry.gen.Player
import mindustry.gen.Unit
import org.h2.mvstore.type.StringDataType
import wayzer.lib.PlayerData

registerMapTag("@legendGame")

// ===================== 配置 =====================
val enablePvp by config.key(true, "是否开启PVP")
val expRate by config.key(1.0f, "经验倍率")
val dropRate by config.key(1.0f, "掉落倍率")
val maxLevel by config.key(100, "最高等级")
val safeZoneRadius by config.key(100, "安全区半径")
val bossSpawnInterval by config.key(30 * 60 * 1000, "BOSS刷新间隔(毫秒)")
val safeZoneRegen by config.key(5f, "安全区每秒回血")

// ===================== 数据存储 =====================
private val playerData = mutableMapOf<String, MutableMap<String, Any>>()

fun getP(uuid: String): MutableMap<String, Any> {
    return playerData.getOrPut(uuid) {
        mutableMapOf<String, Any>(
            "job" to "Warrior",
            "level" to 1,
            "exp" to 0L,
            "hp" to 100f,
            "maxHp" to 100f,
            "mp" to 50f,
            "maxMp" to 50f,
            "atk" to 10,
            "def" to 5,
            "matk" to 5,
            "mdef" to 3,
            "spd" to 1.0f,
            "gold" to 0L,
            "pk" to 0,
            "guild" to "",
            "inv" to mutableListOf<String>(),
            "equip" to mutableMapOf<String, String>(),
            "kills" to 0,
            "deaths" to 0,
            "bossKills" to 0,
            "spawnX" to (safeZoneRadius * 2f),
            "spawnY" to (safeZoneRadius * 2f),
        )
    }
}

fun getP(p: Player): MutableMap<String, Any> = getP(PlayerData[p].id)

fun expForLevel(level: Int): Long = (level * 100L * level * 1.5).toLong()

fun addExp(p: Player, amount: Long) {
    val data = getP(p)
    data["exp"] = (data["exp"] as Long + (amount * expRate).toLong())
    while ((data["exp"] as Long) >= expForLevel(data["level"] as Int) && (data["level"] as Int) < maxLevel) {
        data["exp"] = (data["exp"] as Long) - expForLevel(data["level"] as Int)
        levelUp(p, data)
    }
}

fun levelUp(p: Player, data: MutableMap<String, Any>) {
    val job = Job.valueOf(data["job"] as String)
    data["level"] = (data["level"] as Int) + 1
    data["maxHp"] = (data["maxHp"] as Float) + 20 + job.hpPerLevel
    data["maxMp"] = (data["maxMp"] as Float) + 10 + job.mpPerLevel
    data["atk"] = (data["atk"] as Int) + job.atkPerLevel
    data["def"] = (data["def"] as Int) + job.defPerLevel
    data["matk"] = (data["matk"] as Int) + job.matkPerLevel
    data["mdef"] = (data["mdef"] as Int) + job.mdefPerLevel
    data["hp"] = data["maxHp"]
    data["mp"] = data["maxMp"]
    applyStats(p, data)
    broadcast("[gold]{name} 升级了! 等级: {level}".with("name" to p.name, "level" to data["level"]))
}

fun applyStats(p: Player, data: MutableMap<String, Any>) {
    val unit = p.unit() ?: return
    unit.maxHealth = data["maxHp"] as Float
    unit.health = data["hp"] as Float
}

fun recalcStats(p: Player, data: MutableMap<String, Any>) {
    var atk = data["atk"] as Int
    var def = data["def"] as Int
    var matk = data["matk"] as Int
    var mdef = data["mdef"] as Int
    var hp = data["maxHp"] as Float
    var mp = data["maxMp"] as Float
    var spd = data["spd"] as Float

    val equip = data["equip"] as MutableMap<String, String>
    equip.values.forEach { eqId ->
        equipmentDatabase[eqId]?.let { eq ->
            atk += eq.attack
            def += eq.defense
            matk += eq.magicAttack
            mdef += eq.magicDefense
            hp += eq.hp
            mp += eq.mp
            spd += eq.speed
        }
    }

    data["atk"] = atk
    data["def"] = def
    data["matk"] = matk
    data["mdef"] = mdef
    data["maxHp"] = hp
    data["maxMp"] = mp
    data["spd"] = spd
    data["hp"] = (data["hp"] as Float).coerceAtMost(hp)
    data["mp"] = (data["mp"] as Float).coerceAtMost(mp)
    applyStats(p, data)
}

// ===================== 职业定义 =====================
enum class Job(
    val displayName: String,
    val hpPerLevel: Int,
    val mpPerLevel: Int,
    val atkPerLevel: Int,
    val defPerLevel: Int,
    val matkPerLevel: Int,
    val mdefPerLevel: Int,
) {
    Warrior("战士", 30, 5, 5, 3, 1, 2),
    Mage("法师", 15, 20, 1, 1, 6, 3),
    Taoist("道士", 20, 15, 2, 2, 3, 4),
    Assassin("刺客", 18, 10, 6, 1, 2, 2),
}

enum class EquipType { Weapon, Helmet, Armor, Legs, Boots, Ring, Necklace, Belt, Amulet }

data class Equipment(
    val id: String,
    val name: String,
    val type: EquipType,
    val level: Int,
    val job: Job?,
    val attack: Int = 0,
    val defense: Int = 0,
    val magicAttack: Int = 0,
    val magicDefense: Int = 0,
    val hp: Int = 0,
    val mp: Int = 0,
    val speed: Float = 0f,
)

val equipmentDatabase = mutableMapOf<String, Equipment>().apply {
    put("weapon_wooden_sword", Equipment("weapon_wooden_sword", "木剑", EquipType.Weapon, 1, null, attack = 5))
    put("weapon_bone_sword", Equipment("weapon_bone_sword", "骨剑", EquipType.Weapon, 5, null, attack = 15))
    put("weapon_iron_sword", Equipment("weapon_iron_sword", "铁剑", EquipType.Weapon, 10, Job.Warrior, attack = 30))
    put("weapon_dark_sword", Equipment("weapon_dark_sword", "黑暗之剑", EquipType.Weapon, 20, Job.Warrior, attack = 80))
    put("weapon_dragon_slayer", Equipment("weapon_dragon_slayer", "屠龙刀", EquipType.Weapon, 50, Job.Warrior, attack = 300))
    put("weapon_holy_avenger", Equipment("weapon_holy_avenger", "圣剑", EquipType.Weapon, 80, Job.Warrior, attack = 500))

    put("weapon_staff", Equipment("weapon_staff", "法杖", EquipType.Weapon, 5, Job.Mage, magicAttack = 20))
    put("weapon_crystal_staff", Equipment("weapon_crystal_staff", "水晶法杖", EquipType.Weapon, 20, Job.Mage, magicAttack = 80))
    put("weapon_archmage_staff", Equipment("weapon_archmage_staff", "大法师之杖", EquipType.Weapon, 50, Job.Mage, magicAttack = 300))

    put("weapon_dragon_pattern_sword", Equipment("weapon_dragon_pattern_sword", "龙纹剑", EquipType.Weapon, 30, Job.Taoist, attack = 50, magicAttack = 50))

    put("armor_cloth", Equipment("armor_cloth", "布衣", EquipType.Armor, 1, null, defense = 3, hp = 20))
    put("armor_leather", Equipment("armor_leather", "皮甲", EquipType.Armor, 10, null, defense = 15, hp = 50))
    put("armor_chain", Equipment("armor_chain", "锁子甲", EquipType.Armor, 20, Job.Warrior, defense = 40, hp = 100))
    put("armor_dark", Equipment("armor_dark", "暗黑战甲", EquipType.Armor, 30, Job.Warrior, defense = 100, hp = 200))
    put("armor_dragon_scale", Equipment("armor_dragon_scale", "龙鳞甲", EquipType.Armor, 50, null, defense = 200, hp = 500, magicDefense = 100))
    put("armor_god", Equipment("armor_god", "神圣战甲", EquipType.Armor, 80, null, defense = 400, hp = 1000, magicDefense = 300))

    put("helmet_leather", Equipment("helmet_leather", "皮帽", EquipType.Helmet, 5, null, defense = 5, hp = 30))
    put("helmet_iron", Equipment("helmet_iron", "铁盔", EquipType.Helmet, 15, Job.Warrior, defense = 20, hp = 50))

    put("ring_strength", Equipment("ring_strength", "力量戒指", EquipType.Ring, 10, null, attack = 5))
    put("ring_magic", Equipment("ring_magic", "魔法戒指", EquipType.Ring, 10, Job.Mage, magicAttack = 8))
    put("ring_life", Equipment("ring_life", "生命戒指", EquipType.Ring, 20, null, hp = 100))
    put("ring_paralysis", Equipment("ring_paralysis", "麻痹戒指", EquipType.Ring, 40, null))

    put("necklace_magic", Equipment("necklace_magic", "魔法项链", EquipType.Necklace, 15, Job.Mage, magicAttack = 10, mp = 50))
    put("necklace_luck", Equipment("necklace_luck", "幸运项链", EquipType.Necklace, 25, null))
}

val itemDatabase = mutableMapOf<String, Map<String, Any>>().apply {
    put("copper", mapOf("name" to "铜矿", "category" to "材料"))
    put("iron", mapOf("name" to "铁矿", "category" to "材料"))
    put("lead", mapOf("name" to "铅矿", "category" to "材料"))
    put("titanium", mapOf("name" to "钛矿", "category" to "材料"))
    put("thorium", mapOf("name" to "钍矿", "category" to "材料"))
    put("health_potion", mapOf("name" to "红药水", "category" to "消耗品", "use" to "heal:100"))
    put("mana_potion", mapOf("name" to "蓝药水", "category" to "消耗品", "use" to "mana:50"))
    put("dragon_heart", mapOf("name" to "龙心", "category" to "珍贵材料"))
    put("demon_crown", mapOf("name" to "魔王冠冕", "category" to "珍贵材料"))
}

// ===================== 怪物定义 =====================
data class MonsterDef(
    val id: String,
    val name: String,
    val level: Int,
    val hp: Float,
    val atk: Int,
    val def: Int,
    val mdef: Int,
    val exp: Long,
    val gold: Long,
    val drops: List<Pair<String, Float>>,
    val isBoss: Boolean = false,
    val spawnMsg: String? = null,
)

val monsters = mutableListOf<MonsterDef>().apply {
    add(MonsterDef("slime", "史莱姆", 1, 50f, 5, 2, 2, 10, 5,
        listOf("copper" to 0.5f, "health_potion" to 0.1f)))
    add(MonsterDef("goblin", "哥布林", 3, 120f, 12, 5, 3, 30, 15,
        listOf("iron" to 0.4f, "health_potion" to 0.15f, "weapon_wooden_sword" to 0.05f)))
    add(MonsterDef("skeleton", "骷髅兵", 5, 200f, 18, 8, 5, 60, 30,
        listOf("lead" to 0.3f, "mana_potion" to 0.1f, "weapon_bone_sword" to 0.08f)))
    add(MonsterDef("zombie", "僵尸", 8, 400f, 25, 12, 8, 120, 60,
        listOf("titanium" to 0.25f, "health_potion" to 0.2f, "armor_leather" to 0.1f)))
    add(MonsterDef("dark_knight", "黑暗骑士", 15, 1000f, 50, 25, 20, 500, 300,
        listOf("thorium" to 0.2f, "weapon_dark_sword" to 0.15f, "armor_dark" to 0.1f)))

    add(MonsterDef("dragon_lord", "龙王", 50, 50000f, 200, 80, 100, 50000, 50000,
        listOf("weapon_dragon_slayer" to 0.3f, "armor_dragon_scale" to 0.2f, "dragon_heart" to 1f),
        isBoss = true, spawnMsg = "[red]龙王苏醒了！"))
    add(MonsterDef("demon_king", "魔王", 80, 150000f, 400, 150, 200, 200000, 100000,
        listOf("weapon_holy_avenger" to 0.2f, "armor_god" to 0.15f, "demon_crown" to 1f),
        isBoss = true, spawnMsg = "[red]魔王降临！"))
}

// 用单独的Map追踪怪物单位，因为Unit没有userData
private val monsterUnits = mutableMapOf<Unit, MonsterDef>()
var bossSpawnTimer = 0L
var currentBoss: MonsterDef? = null

fun spawnMonster(def: MonsterDef, x: Float, y: Float) {
    val unit = if (def.isBoss) UnitTypes.mega.create(Team.sharded) else UnitTypes.mono.create(Team.sharded)
    unit.set(x, y)
    unit.health = def.hp
    unit.maxHealth = def.hp
    unit.add()
    monsterUnits[unit] = def

    if (def.isBoss) {
        def.spawnMsg?.let { broadcast(it.with(), quite = false) }
        currentBoss = def
    }
}

fun handleMonsterDeath(def: MonsterDef, killer: Player) {
    val data = getP(killer)
    val expGain = (def.exp * expRate).toLong()
    val goldGain = (def.gold * dropRate).toLong()

    addExp(killer, expGain)
    data["gold"] = (data["gold"] as Long) + goldGain

    if (def.isBoss) {
        data["bossKills"] = (data["bossKills"] as Int) + 1
        broadcast("[gold]{name} 击杀了 BOSS ${def.name}！经验+$expGain 金币+$goldGain".with("name" to killer.name))
        currentBoss = null
    } else {
        killer.sendMessage("[green]击杀 ${def.name}，经验+$expGain 金币+$goldGain".with())
    }

    def.drops.forEach { (itemId, chance) ->
        if (Math.random() < chance * dropRate) {
            val inv = data["inv"] as MutableList<String>
            inv.add(itemId)
            killer.sendMessage("[yellow]获得: ${itemDatabase[itemId]?.get("name") ?: itemId}".with())
        }
    }

    if (enablePvp && (data["pk"] as Int) > 0) {
        data["pk"] = ((data["pk"] as Int) * 0.9).toInt()
    }
}

fun isInSafeZone(p: Player): Boolean {
    val unit = p.unit() ?: return false
    val cx = safeZoneRadius * 2f
    val cy = safeZoneRadius * 2f
    return unit.dst(cx, cy) < safeZoneRadius
}

fun spawnRandomBoss() {
    val bosses = monsters.filter { it.isBoss }
    if (bosses.isEmpty()) return
    val boss = bosses.random()
    val x = (Math.random() * (Vars.world.width() - safeZoneRadius * 6f) + safeZoneRadius * 3f).toFloat()
    val y = (Math.random() * (Vars.world.height() - safeZoneRadius * 6f) + safeZoneRadius * 3f).toFloat()
    spawnMonster(boss, x, y)
}

// ===================== 事件监听 =====================
onEnable {
    Log.info("[LegendGame] 传奇模式已启用")
}

listen<EventType.PlayerJoin> {
    val p = it.player
    val data = getP(p)
    data["isOnline"] = true
    applyStats(p, data)
    p.sendMessage("""
        [gold]=== 欢迎来到 传奇世界 ===
        [white]职业: ${data["job"]} | 等级: ${data["level"]} | 经验: ${data["exp"]}/${expForLevel(data["level"] as Int)}
        [white]HP: ${data["hp"]}/${data["maxHp"]} | MP: ${data["mp"]}/${data["maxMp"]}
        [white]攻击: ${data["atk"]} | 防御: ${data["def"]} | 魔攻: ${data["matk"]} | 魔防: ${data["mdef"]}
        [white]金币: ${data["gold"]} | PK值: ${data["pk"]}
        [cyan]输入 /legend help 查看指令
    """.trimIndent())
    if (safeZoneRadius > 0) p.sendMessage("[green]安全区保护中".with())
}

listen<EventType.PlayerLeave> {
    val data = getP(it.player)
    data["isOnline"] = false
}

listen<EventType.UnitDestroyEvent> { e ->
    val unit = e.unit
    val def = monsterUnits.remove(unit) ?: return@listen
    // 简化：找到最近的玩家作为击杀者
    val killer = Groups.player.minByOrNull { it.dst(unit) } ?: return@listen
    handleMonsterDeath(def, killer)
}

listen<EventType.WorldLoadEvent> {
    launch(Dispatchers.game) {
        loop {
            delay(1000)
            if (System.currentTimeMillis() - bossSpawnTimer > bossSpawnInterval && currentBoss == null) {
                spawnRandomBoss()
                bossSpawnTimer = System.currentTimeMillis()
            }
            Groups.player.forEach { p ->
                val data = getP(p)
                if (isInSafeZone(p)) {
                    data["hp"] = ((data["hp"] as Float) + safeZoneRegen).coerceAtMost(data["maxHp"] as Float)
                    data["mp"] = ((data["mp"] as Float) + safeZoneRegen * 0.5f).coerceAtMost(data["maxMp"] as Float)
                    applyStats(p, data)
                }
                if ((data["pk"] as Int) > 0) {
                    data["pk"] = ((data["pk"] as Int) * 0.999).toInt()
                }
            }
        }
    }
}

// 自动刷怪
onEnable {
    if (safeZoneRadius > 0) {
        launch(Dispatchers.game) {
            loop {
                delay(10000)
                Groups.player.forEach { p ->
                    val data = getP(p)
                    if (!isInSafeZone(p) && Math.random() < 0.1) {
                        val ml = monsters.filter { !it.isBoss && it.level <= (data["level"] as Int) + 5 }
                        if (ml.isNotEmpty()) {
                            val m = ml.random()
                            val rx = (p.x + (Math.random() * 400 - 200)).toFloat()
                            val ry = (p.y + (Math.random() * 400 - 200)).toFloat()
                            if (rx > 0 && rx < Vars.world.width() && ry > 0 && ry < Vars.world.height()) {
                                spawnMonster(m, rx, ry)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ===================== 指令 =====================
command("legend", "传奇游戏") {
    aliases = listOf("lg", "传奇")
    usage = "<help|info|job|equip|unequip|bag|shop|buy|rank|pk|guild>"
    body {
        val sub = arg.firstOrNull()
        val data = getP(player!!)

        when (sub) {
            null, "info", "信息" -> player!!.sendMessage("""
                [gold]=== 角色信息 ===
                [white]${data["job"]} Lv.${data["level"]} | 经验: ${data["exp"]}/${expForLevel(data["level"] as Int)}
                [white]HP: ${data["hp"]}/${data["maxHp"]} MP: ${data["mp"]}/${data["maxMp"]}
                [white]攻击: ${data["atk"]} 防御: ${data["def"]} 魔攻: ${data["matk"]} 魔防: ${data["mdef"]}
                [white]金币: ${data["gold"]} PK: ${data["pk"]} 击杀: ${data["kills"]} 死亡: ${data["deaths"]} BOSS: ${data["bossKills"]}
            """.trimIndent())

            "help", "帮助" -> player!!.sendMessage("""
                [gold]=== 指令 ===
                [white]/legend - 信息 | /legend job <职业> - 转职(10级)
                [white]/legend equip <id> - 装备 | /legend unequip <部位> - 卸下
                [white]/legend bag - 背包 | /legend shop - 商店 | /legend buy <id> [数量]
                [white]/legend rank - 排行 | /legend pk - PK值 | /legend guild <create|join|leave|info> [名字]
            """.trimIndent())

            "job", "职业" -> {
                val jobName = arg.getOrNull(1) ?: return@body player!!.sendMessage("[red]职业: Warrior/Mage/Taoist/Assassin".with())
                val job = Job.values().find { it.displayName == jobName || it.name == jobName } ?: return@body player!!.sendMessage("[red]无效".with())
                if ((data["level"] as Int) < 10) return@body player!!.sendMessage("[red]10级可转职".with())
                data["job"] = job.name
                recalcStats(player!!, data)
                player!!.sendMessage("[green]转职: ${job.displayName}".with())
            }

            "equip", "装备" -> {
                val itemId = arg.getOrNull(1) ?: return@body player!!.sendMessage("[red]装备ID".with())
                val eq = equipmentDatabase[itemId] ?: return@body player!!.sendMessage("[red]不存在".with())
                val job = Job.valueOf(data["job"] as String)
                if (eq.job != null && eq.job != job) return@body player!!.sendMessage("[red]职业不符".with())
                if ((data["level"] as Int) < eq.level) return@body player!!.sendMessage("[red]等级不足".with())
                val inv = data["inv"] as MutableList<String>
                if (itemId !in inv) return@body player!!.sendMessage("[red]背包无此物品".with())
                val equip = data["equip"] as MutableMap<String, String>
                equip[eq.type.name]?.let { old -> inv.add(old) }
                equip[eq.type.name] = eq.id
                inv.remove(itemId)
                recalcStats(player!!, data)
                player!!.sendMessage("[green]装备: ${eq.name}".with())
            }

            "unequip", "卸下" -> {
                val slot = arg.getOrNull(1) ?: return@body player!!.sendMessage("[red]部位".with())
                val equip = data["equip"] as MutableMap<String, String>
                val removed = equip.remove(slot.lowercase().replaceFirstChar { it.uppercase() })
                removed?.let {
                    val inv = data["inv"] as MutableList<String>
                    inv.add(it)
                    recalcStats(player!!, data)
                    player!!.sendMessage("[yellow]卸下: ${equipmentDatabase[it]?.name ?: it}".with())
                } ?: player!!.sendMessage("[red]无装备".with())
            }

            "bag", "背包" -> {
                val inv = data["inv"] as MutableList<String>
                if (inv.isEmpty()) return@body player!!.sendMessage("[gray]空".with())
                val msg = inv.mapIndexed { i, id -> "[gold]${i+1}. ${itemDatabase[id]?.get("name") ?: id}".with() }.joinToString("\n")
                player!!.sendMessage("[gold]=== 背包 ===\n$msg".with())
            }

            "shop", "商店" -> player!!.sendMessage("""
                [gold]=== 商店 ===
                [white]health_potion(红药水) 50金 | mana_potion(蓝药水) 80金
                [white]weapon_wooden_sword(木剑) 100金 | armor_cloth(布衣) 100金
                [white]用 /legend buy <id> [数量] 购买
            """.trimIndent())

            "buy", "购买" -> {
                val itemId = arg.getOrNull(1) ?: return@body player!!.sendMessage("[red]物品ID".with())
                val count = arg.getOrNull(2)?.toIntOrNull() ?: 1
                val item = itemDatabase[itemId] ?: return@body player!!.sendMessage("[red]无此商品".with())
                val price = when (itemId) {
                    "health_potion" -> 50; "mana_potion" -> 80
                    "weapon_wooden_sword" -> 100; "armor_cloth" -> 100
                    else -> 1000
                } * count
                if ((data["gold"] as Long) < price) return@body player!!.sendMessage("[red]金币不足".with())
                data["gold"] = (data["gold"] as Long) - price
                val inv = data["inv"] as MutableList<String>
                repeat(count) { inv.add(itemId) }
                player!!.sendMessage("[green]购买: ${item["name"]} x$count".with())
            }

            "rank", "排行" -> {
                val top = playerData.values
                    .sortedByDescending { it["level"] as Int }
                    .take(10)
                    .mapIndexed { i, d -> "[gold]${i+1}. ${d["job"]} Lv.${d["level"]} (${d["kills"]}杀)".with() }
                    .joinToString("\n")
                player!!.sendMessage("[gold]=== 排行榜 ===\n$top".with())
            }

            "pk" -> {
                val pk = data["pk"] as Int
                player!!.sendMessage("[white]PK值: $pk".with())
            }

            "guild", "公会" -> {
                val action = arg.getOrNull(1) ?: return@body player!!.sendMessage("[red]create|join|leave|info".with())
                when (action) {
                    "create" -> {
                        val name = arg.getOrNull(2) ?: return@body player!!.sendMessage("[red]名字".with())
                        if ((data["gold"] as Long) < 10000) return@body player!!.sendMessage("[red]需1万金币".with())
                        data["gold"] = (data["gold"] as Long) - 10000
                        data["guild"] = name
                        player!!.sendMessage("[green]创建: $name".with())
                    }
                    "join" -> { val n = arg.getOrNull(2) ?: return@body player!!.sendMessage("[red]名字".with()); data["guild"] = n; player!!.sendMessage("[green]加入: $n".with()) }
                    "leave" -> { data["guild"] = ""; player!!.sendMessage("[yellow]退出".with()) }
                    "info" -> { data["guild"]?.let { player!!.sendMessage("[green]公会: $it".with()) } ?: player!!.sendMessage("[gray]无".with()) }
                    else -> player!!.sendMessage("[red]未知".with())
                }
            }

            else -> player!!.sendMessage("[red]未知指令".with())
        }
    }
}

onDisable {
    Log.info("[LegendGame] 卸载")
}