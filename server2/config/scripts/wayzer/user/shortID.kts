package wayzer.user

import arc.Core
import arc.util.serialization.Base64Coder
import cf.wayzer.placehold.DynamicVar
import com.google.common.cache.Cache
import com.google.common.cache.CacheBuilder
import mindustry.net.Administration
import java.security.SecureRandom
import java.time.Duration
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 3位ID生成(混淆加固版):
 * 旧逻辑 md5(md5(uuid)+uuid) 的 Base64 前3位 + 固定替换表 —— 无密钥、算法公开, 可离线复现/推回。
 * 新逻辑 HMAC-SHA256(uuid, 服务端密钥):
 * - 密钥首次运行随机生成, 只存 settings.bin, 不进代码仓库
 * - 3位字符由摘要中两个不相邻字节混合取值(非前缀截断)
 * - 不知道密钥无法从 shortID 推回/离线复现 UUID; 换密钥即全体换ID
 */
val shortIdSecret: ByteArray = run {
    val saved = Core.settings.getString("shortIdHmacKey", null)
    if (saved != null && saved.length >= 40) {
        Base64Coder.decode(saved)
    } else {
        val bs = ByteArray(32).also { SecureRandom().nextBytes(it) }
        Core.settings.put("shortIdHmacKey", String(Base64Coder.encode(bs)))
        Core.settings.forceSave()
        logger.warning("shortID 密钥已重新生成, 全体玩家3位ID已更换")
        bs
    }
}

val shortIdMac = Mac.getInstance("HmacSHA256")!!

// 57字符表, 去掉 I/O/l/0/1 等易混淆字符
val shortIdAlphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789"

fun shortStr(str: String): String {
    val bs = synchronized(shortIdMac) {
        shortIdMac.init(SecretKeySpec(shortIdSecret, "HmacSHA256"))
        shortIdMac.doFinal(str.toByteArray())
    }
    fun ch(i: Int): Char {
        // 混合摘要中不相邻的字节与位置信息, 而非直接截取 Base64 前缀
        val h = (bs[i].toInt() and 0xff) * 251 + (bs[i + 4].toInt() and 0xff) * 17 + i * 7
        return shortIdAlphabet[h % shortIdAlphabet.length]
    }
    return "" + ch(0) + ch(1) + ch(2)
}

@JvmName("shortIDExt")
fun Player.shortID() = shortStr(uuid())
fun shortID(p: Player) = shortStr(p.uuid())
export(::shortStr, ::shortID)

val shortIDs: Cache<String, String> = CacheBuilder.newBuilder()
    .expireAfterWrite(Duration.ofMinutes(60)).build()
listen<EventType.PlayerLeave> {
    val uuid = it.player.uuid()
    val new = it.player.shortID()
    val old = shortIDs.getIfPresent(new)
    shortIDs.put(new, uuid)
    if (old != null && old != uuid)
        logger.warning("3位ID碰撞: $uuid $old")
}

fun getUUIDbyShort(id: String): String? {
    return Groups.player.find { it.uuid() == id || it.shortID() == id }?.uuid()
        ?: shortIDs.getIfPresent(id)
}
export(this::getUUIDbyShort)

registerVarForType<Player>().apply {
    registerChild("shortID", "3位ID, 服务端密钥混淆生成, 可展现给其他玩家", DynamicVar.obj { it.shortID() })
    registerChild("suffix.9shortID", "名字后缀：3位ID", DynamicVar.obj { "|[gray]${it.shortID()}[]" })
}
registerVarForType<Administration.PlayerInfo>().apply {
    registerChild("shortID", "3位ID, 服务端密钥混淆生成", DynamicVar.obj { shortStr(it.id) })
}
