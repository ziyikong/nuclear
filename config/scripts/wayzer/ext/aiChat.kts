@file:Depends("wayzer/user/economy", "钍币经济")

package wayzer.ext

import arc.util.Log
import cf.wayzer.scriptAgent.contextScript
import wayzer.user.ext.Economy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import arc.util.serialization.Jval
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

name = "扩展: AI问答与抽奖(钍币)"

// ===== AI 配置 =====
val aiEnabled by config.key(true, "是否启用AI问答")
val aiUrl by config.key("https://api.ltzy.top/v1/chat/completions", "OpenAI兼容接口完整地址,例如 https://xxx/v1/chat/completions")
val aiKey by config.key("", "API密钥请配置在config.conf中")
val aiModel by config.key("zhipu/glm-4.7-flash", "模型名")
val aiPrice by config.key(1L, "每次提问消耗的钍")
val aiCooldownSec by config.key(10, "提问冷却(秒)")
val aiMaxLen by config.key(180, "回复截断长度")

// ===== 抽奖配置 =====
val chouPrice by config.key(50L, "单次抽奖消耗的钍")

private val http: HttpClient by lazy { HttpClient.newHttpClient() }
private val aiCooldowns = mutableMapOf<String, Long>()

/** 调用OpenAI兼容接口 */
private fun askAI(question: String): String {
    val safeQ = question.replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\n", "\\n").replace("\r", "").replace("\t", " ")
    val body = """
        {"model":"$aiModel","messages":[
            {"role":"system","content":"你是Mindustry游戏服务器的助手,用简短中文回答,不超过100字"},
            {"role":"user","content":"$safeQ"}
        ]}
    """.trimIndent()
    val builder = HttpRequest.newBuilder(URI.create(aiUrl))
        .header("Content-Type", "application/json")
    if (aiKey.isNotBlank()) builder.header("Authorization", "Bearer $aiKey")
    val resp = http.send(
        builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
        HttpResponse.BodyHandlers.ofString()
    )
    if (resp.statusCode() != 200) error("HTTP ${resp.statusCode()}: ${resp.body().take(200)}")
    return Jval.read(resp.body()).get("choices").asArray().first()
        .get("message").get("content").asString().trim()
}

/** 提问后的欧皇抽奖: 5%双倍返还 / 15%回本 */
private fun gachaRefund(p: Player, price: Long) {
    val roll = (0..99).random()
    when {
        roll < 5 -> {
            contextScript<Economy>().addMoney(p, price * 2)
            broadcast("[gold]🎉 欧皇降临! {player} 提问触发暴击,返还{back}钍!".with("player" to p, "back" to price * 2))
        }
        roll < 20 -> {
            contextScript<Economy>().addMoney(p, price)
            p.sendMessage("[green]🍀 幸运转发: 本次提问免费!".with())
        }
    }
}

command("ai", "向AI提问(消耗钍)") {
    usage = "<问题...>"
    aliases = listOf("问AI")
    body {
        if (!aiEnabled) returnReply("[red]AI问答未启用".with())
        if (aiUrl.isBlank()) returnReply("[red]管理员尚未配置AI接口".with())
        if (arg.isEmpty()) returnReply(replyUsage())

        val me = player ?: returnReply("[red]请在游戏内使用".with())
        val now = System.currentTimeMillis()
        val last = aiCooldowns[me.uuid()] ?: 0
        if (now - last < aiCooldownSec * 1000)
            returnReply("[red]提问太频繁,还剩{sec}秒".with("sec" to (aiCooldownSec - (now - last) / 1000)))

        val eco = contextScript<Economy>()
        if (!eco.costMoney(me, aiPrice))
            returnReply("[red]钍不足: 提问需要{price}钍,你有{have}钍".with("price" to aiPrice, "have" to eco.getMoney(me)))
        aiCooldowns[me.uuid()] = now

        val question = arg.joinToString(" ")
        reply("[accent]🤖 思考中...".with())
        launch(Dispatchers.IO) {
            try {
                var answer = askAI(question)
                if (answer.length > aiMaxLen) answer = answer.take(aiMaxLen) + "..."
                me.sendMessage("[accent]🤖 AI:[] $answer")
                withContext(Dispatchers.game) { gachaRefund(me, aiPrice) }
            } catch (e: Throwable) {
                Log.err(e)
                val reason = (e.message ?: e.toString()).take(150)
                withContext(Dispatchers.game) {
                    contextScript<Economy>().addMoney(me, aiPrice) // 失败退款
                    me.sendMessage(
                        "[red]AI调用失败(已退还{price}钍):[]\n[lightgrey]{reason}"
                            .with("price" to aiPrice, "reason" to reason).toString()
                    )
                }
            }
        }
    }
}

// ===== 抽奖 =====
command("choujiang", "抽奖(消耗钍)") {
    aliases = listOf("抽奖")
    body {
        val me = player ?: returnReply("[red]请在游戏内抽奖".with())
        val eco = contextScript<Economy>()
        if (!eco.costMoney(me, chouPrice))
            returnReply("[red]钍不足: 抽奖需要{price}钍,你有{have}钍".with("price" to chouPrice, "have" to eco.getMoney(me)))

        val roll = (0..999).random()
        val prize: Long = when {
            roll < 600 -> 0
            roll < 850 -> 30
            roll < 950 -> 80
            roll < 990 -> 200
            else -> 1000
        }
        if (prize > 0) {
            eco.addMoney(me, prize)
            reply("[gold]🎉 恭喜抽中 [+]{prize} 钍!".with("prize" to prize))
            if (prize >= 1000) broadcast("[gold]👑 天选之人! {player} 在抽奖中获得 [yellow]{prize} 钍!".with("player" to me, "prize" to prize))
        } else {
            reply("[grey]谢谢参与,再接再厉!".with())
        }
    }
}

onDisable { aiCooldowns.clear() }