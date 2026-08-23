package wayzer.reGrief

import mindustry.Vars
import mindustry.world.Tiles
import mindustry.gen.Fire

val limit by config.key(500, "火焰格数限制")

val fireCount: Int
    get() {
        val firesField = Tiles::class.java.getDeclaredField("fires")
        firesField.isAccessible = true
        val firesArray = firesField.get(Vars.world.tiles) as Array<*>
        return firesArray.count { it != null && (it as? Fire)?.isAdded() == true && (it as Fire).fin() < 1f }
    }

var done = false
listen<EventType.ResetEvent> { done = false }

listen(EventType.Trigger.update) {
    if (state.rules.fire && fireCount > limit) {
        done = true
        state.rules.fire = false
        broadcast("[yellow]火焰过多造成服务器卡顿,自动关闭火焰".with())
    }
}

listen<EventType.PlayerJoin> {
    if (done) {
        it.player.sendMessage("[yellow]火焰过多造成服务器卡顿,自动关闭火焰".with())
    }
}