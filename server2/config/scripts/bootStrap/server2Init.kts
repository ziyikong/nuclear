package bootStrap

import mindustry.Vars
import mindustry.net.Administration

name = "二服引导: 端口与基础设置"

onEnable {
    Administration.Config.port.set(5000)
    Administration.Config.showConnectMessages.set(false)
    Log.info("[Server2] 端口已设置为 5000, 传奇模式服务器初始化完成")
}