package at.petrak.hex.common.casting

import at.petrak.hex.HexUtils
import at.petrak.hex.HexUtils.serializeToNBT
import at.petrak.hex.hexmath.HexPattern
import net.minecraft.nbt.*
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3

/**
 * Data allowed into a spell.
 *
 * We use the following types:
 *  * [Entity]
 *  * [Double]
 *  * [Vec3][net.minecraft.world.phys.Vec3] as both position and (when normalized) direction
 *  * [Widget]; [Widget.NULL] is used as our null value
 *  * [List<SpellDatum<*>>][List]
 *  * [HexPattern]! Yes, we have meta-evaluation everyone.
 * The constructor guarantees we won't pass a type that isn't one of those types.
 *
 *
 */
class SpellDatum<T : Any> private constructor(val payload: T) {
    val clazz: Class<T> = payload.javaClass

    inline fun <reified U> tryGet(): U =
        if (payload is U) {
            // learning from psi's mistakes
            if (payload is Double && !payload.isFinite())
                0.0 as U
            else
                payload
        } else {
            throw CastException(CastException.Reason.OP_WRONG_TYPE, U::class.java, this.payload)
        }

    fun serializeToNBT(): CompoundTag {
        val out = CompoundTag()
        when (val pl = this.payload) {
            is Entity -> out.put(
                TAG_ENTITY, NbtUtils.createUUID(pl.uuid)
            )
            is Double -> out.put(
                TAG_DOUBLE, DoubleTag.valueOf(pl)
            )
            is Vec3 -> out.put(
                TAG_VEC3, pl.serializeToNBT()
            )
            is ArrayList<*> -> {
                val subtag = ListTag()
                for (elt in pl)
                    subtag.add((elt as SpellDatum<*>).serializeToNBT())
                out.put(TAG_LIST, subtag)
            }
            is Widget -> {
                out.putString(TAG_WIDGET, pl.name)
            }
            is HexPattern -> {
                out.put(TAG_PATTERN, pl.serializeToNBT())
            }
            else -> throw RuntimeException("cannot serialize $pl because it is of type ${pl.javaClass.canonicalName} which is not serializable")
        }

        return out
    }

    override fun toString(): String =
        buildString {
            append("SpellDatum[")
            append(this@SpellDatum.payload.toString())
            append(']')
        }

    companion object {
        @JvmStatic
        fun make(payload: Any): SpellDatum<*> =
            if (!IsValidType(payload)) {
                // Check to see if it's a java boxed double
                if (payload is java.lang.Double) {
                    val num = payload.toDouble()
                    SpellDatum(if (num.isFinite()) num else 0.0)
                } else {
                    throw CastException(CastException.Reason.INVALID_TYPE, payload)
                }
            } else {
                SpellDatum(payload)
            }

        @JvmStatic
        fun DeserializeFromNBT(nbt: CompoundTag, ctx: CastingContext): SpellDatum<*> {
            val keys = nbt.allKeys
            if (keys.size != 1)
                throw IllegalArgumentException("Expected exactly one kv pair: $nbt")

            return when (val key = keys.iterator().next()) {
                TAG_ENTITY -> {
                    val uuid = nbt.getUUID(key)
                    val entity = ctx.world.getEntity(uuid)
                    // If the entity died or something return Unit
                    SpellDatum(if (entity == null || !entity.isAlive) Widget.NULL else entity)
                }
                TAG_DOUBLE -> SpellDatum(nbt.getDouble(key))
                TAG_VEC3 -> SpellDatum(HexUtils.DeserializeVec3FromNBT(nbt.getLongArray(key)))
                TAG_LIST -> {
                    val arr = nbt.getList(key, Tag.TAG_COMPOUND.toInt())
                    val out = ArrayList<SpellDatum<*>>(arr.size)
                    for (subtag in arr) {
                        // this is safe because otherwise we wouldn't have been able to get the list before
                        out.add(DeserializeFromNBT(subtag as CompoundTag, ctx))
                    }
                    SpellDatum(out)
                }
                TAG_WIDGET -> {
                    SpellDatum(Widget.valueOf(nbt.getString(key)))
                }
                TAG_PATTERN -> {
                    SpellDatum(HexPattern.DeserializeFromNBT(nbt.getCompound(TAG_PATTERN)))
                }
                else -> throw IllegalArgumentException("Unknown key $key: $nbt")
            }
        }

        // Set of legal types to go in a spell
        val ValidTypes: Set<Class<*>> = setOf(
            Entity::class.java,
            Double::class.java,
            Vec3::class.java,
            List::class.java,
            Widget::class.java,
            HexPattern::class.java,
        )

        const val TAG_ENTITY = "entity"
        const val TAG_DOUBLE = "double"
        const val TAG_VEC3 = "vec3"
        const val TAG_LIST = "list"
        const val TAG_WIDGET = "widget"
        const val TAG_PATTERN = "pattern"

        fun <T : Any> IsValidType(checkee: T): Boolean =
            if (checkee is List<*>) {
                // note it should be impossible to pass a spell datum that doesn't contain a valid type,
                // but we best make sure.
                checkee.all { it is SpellDatum<*> && IsValidType(it.payload) }
            } else {
                ValidTypes.any { clazz -> clazz.isAssignableFrom(checkee.javaClass) }
            }
    }
}