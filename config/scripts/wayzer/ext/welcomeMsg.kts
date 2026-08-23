package wayzer.ext

import cf.wayzer.placehold.PlaceHoldApi.with
import mindustry.net.Administration

val customWelcome by config.key("customWelcome", false, "是否开启自定义进服信息(中文)") {
    if (dataDirectory != null)
        Administration.Config.showConnectMessages.set(!it)
}
val type by config.key(MsgType.InfoMessage, "发送方式")
val template by config.key(
    """
    Welcome to this Server
    [scarlet]欢迎[white]{player.name}[cyan]来到
    [negstat]nu[crimson]cl[maroon]ea[purple]r  p[brick]ow[scarlet]er[sky]服务器[]
""".trimIndent(), "欢迎信息模板"
)

listen<EventType.PlayerJoin> {
    it.player.sendMessage(template.with(), type)
    if (customWelcome)
        broadcast("[cyan][+]{player.name} [goldenrod]进入了核电站".with("player" to it.player))
}

listen<EventType.PlayerLeave> {
    if (customWelcome && it.player.lastText != "[Silent_Leave]")
        broadcast("[coral][-]{player.name} [brick]离开了核电站".with("player" to it.player))
}