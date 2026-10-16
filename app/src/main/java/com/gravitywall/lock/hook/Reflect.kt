package com.gravitywall.lock.hook

import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * libxposed API 102 不提供 XposedHelpers 一类的辅助工具，这里补齐最常用的反射操作。
 * 所有函数都做"找不到就返回 null/false"处理，避免因为系统改类名而崩溃。
 */
internal object Reflect {

    fun loadClass(loader: ClassLoader?, name: String): Class<*>? =
        runCatching { Class.forName(name, false, loader) }.getOrNull()

    fun method(cls: Class<*>, name: String, vararg params: Class<*>): Method? =
        runCatching { cls.getDeclaredMethod(name, *params) }.getOrNull()

    /** 按名字取第一个同名声明方法（参数签名不确定时的用法）。 */
    fun methodAny(cls: Class<*>, name: String): Method? =
        cls.declaredMethods.firstOrNull { it.name == name }

    /** 按名字取全部同名声明方法（系统里常有重载）。 */
    fun methods(cls: Class<*>, name: String): List<Method> =
        cls.declaredMethods.filter { it.name == name }

    /** 枚举可以安全挂钩的方法：排除合成/桥接方法，并限制参数个数。 */
    fun hookableMethods(cls: Class<*>, cap: Int = 12): List<Method> =
        cls.declaredMethods
            .filter { !it.isSynthetic && !it.isBridge && it.parameterCount <= 4 }
            .sortedBy { it.name }
            .take(cap)

    fun ctor(cls: Class<*>, vararg params: Class<*>): Constructor<*>? =
        runCatching { cls.getDeclaredConstructor(*params) }.getOrNull()

    fun field(cls: Class<*>, name: String): Field? =
        runCatching { cls.getDeclaredField(name) }.getOrNull()

    /** 读字段（含父类链），失败返回 null。 */
    fun readField(obj: Any?, name: String): Any? {
        var c: Class<*>? = obj?.javaClass
        while (c != null && c != Any::class.java) {
            val f = runCatching { c.getDeclaredField(name) }.getOrNull()
            if (f != null) {
                return runCatching {
                    f.isAccessible = true
                    f.get(obj)
                }.getOrNull()
            }
            c = c.superclass
        }
        return null
    }

    /** 写字段（含父类链）。 */
    fun writeField(obj: Any?, name: String, value: Any?): Boolean {
        var c: Class<*>? = obj?.javaClass
        while (c != null && c != Any::class.java) {
            val f = runCatching { c.getDeclaredField(name) }.getOrNull()
            if (f != null) {
                return runCatching {
                    f.isAccessible = true
                    f.set(obj, value)
                }.isSuccess
            }
            c = c.superclass
        }
        return false
    }

    /**
     * 在对象及其父类里找第一个类型匹配的字段并读出。
     * 系统里字段名经常随版本变，但类型（比如 View）往往很稳，这是更抗变化的取法。
     */
    fun readFieldByType(obj: Any?, type: Class<*>): Any? {
        var c: Class<*>? = obj?.javaClass
        while (c != null && c != Any::class.java) {
            for (f in c.declaredFields) {
                if (f.isSynthetic || !type.isAssignableFrom(f.type)) continue
                val v = runCatching {
                    f.isAccessible = true
                    f.get(obj)
                }.getOrNull()
                if (v != null) return v
            }
            c = c.superclass
        }
        return null
    }
}
