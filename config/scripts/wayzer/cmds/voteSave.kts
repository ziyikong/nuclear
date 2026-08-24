@file:Depends("wayzer/vote", "投票实现")

package wayzer.cmds

import arc.util.Log
import arc.util.Time
import mindustry.io.SaveIO
import wayzer.VoteService

val voteSaveSlots = listOf(90, 91, 92, 93, 94) // 投票专用存档槽(与手动1-8、自动100+隔离)

/** 轮换选择最久未用的槽位 */
fun nextVoteSlot(): Int =
    voteSaveSlots.map { SaveIO.fileFor(it) }.minByOrNull { it.lastModified() }
        ?.nameWithoutExtension()?.toIntOrNull() ?: voteSaveSlots.first()

fun VoteService.registerSave() {
    addSubVote(
        "主动保存当前进度(存入专用槽位90~94)", "[槽位]", "save", "保存", "存档"
    ) {
        if (!state.isPlaying)
            returnReply("[red]当前没有进行中的游戏".with())

        val requested = arg.firstOrNull()?.toIntOrNull()
        val slot = when {
            requested == null -> nextVoteSlot()
            requested in voteSaveSlots -> requested
            else -> returnReply("[red]槽位需在 {slots} 之间(保护手动与自动存档)".with("slots" to voteSaveSlots.toString()))
        }

        val wave = state.wave
        val mapName = state.map.plainName()
        val starter = player!!

        VoteService.start(
            starter,
            "保存当前进度到存档槽 [accent]{slot}".with("slot" to slot),
            extDesc = "[white]地图: [lightgrey]$mapName[] | [white]波数: [lightgrey]$wave",
            supportSingle = true
        ) {
            if (!state.isPlaying) {
                broadcast("[red]游戏已结束,保存取消".with(), quite = true)
                return@start
            }
            Core.app.post {
                try {
                    SaveIO.save(SaveIO.fileFor(slot))
                    broadcast(
                        "[green]✅ 投票通过! 进度已保存至存档槽 [accent]{slot}[][yellow](地图:{map} 波数:{wave})"
                            .with("slot" to slot, "map" to mapName, "wave" to wave)
                    )
                } catch (e: Exception) {
                    broadcast("[red]保存失败: {msg}".with("msg" to (e.message ?: "未知错误")))
                    Log.err(e)
                }
            }
        }
    }
}

onEnable { VoteService.registerSave() }