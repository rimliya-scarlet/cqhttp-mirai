package tech.mihoyo.mirai.util

import kotlinx.serialization.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import tech.mihoyo.mirai.data.common.*
import kotlin.reflect.KClass

inline fun <reified T : Any> T.toJson(
    serializer: SerializationStrategy<T>? = null
): String = if (serializer == null) {
    CQJson.json.encodeToString(this)
} else CQJson.json.encodeToString(serializer, this)

// 序列化列表时，stringify需要使用的泛型是T，而非List<T>
// 因为使用的stringify的stringify(objs: List<T>)重载
inline fun <reified T : Any> List<T>.toJson(
    serializer: SerializationStrategy<List<T>>? = null
): String = if (serializer == null) CQJson.json.encodeToString(this)
else CQJson.json.encodeToString(serializer, this)

/**
 * Json解析规则，需要注册支持的多态的类
 */
object CQJson {
    @OptIn(InternalSerializationApi::class)
    val json = Json {

        isLenient = true
        ignoreUnknownKeys = true

        @Suppress("UNCHECKED_CAST")
        serializersModule = SerializersModule {
            polymorphic(CQEventDTO::class) {
                this.subclass(CQGroupMessagePacketDTO::class)
                this.subclass(CQPrivateMessagePacketDTO::class)
                this.subclass(CQIgnoreEventDTO::class)
            }
        }
    }
}