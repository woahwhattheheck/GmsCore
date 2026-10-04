/*
 * SPDX-FileCopyrightText: 2022 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.droidguard

import android.content.Context
import android.os.Bundle
import android.os.Parcelable
import java.lang.reflect.Method

class HandleProxy(val handle: Any, val vmKey: String, val extra: ByteArray = ByteArray(0)) {
    constructor(clazz: Class<*>, context: Context, vmKey: String, data: Parcelable) : this(
        kotlin.runCatching {
            clazz.getDeclaredConstructor(Context::class.java, Parcelable::class.java).newInstance(context, data)
        }.getOrElse {
            throw BytesException(ByteArray(0), it)
        },
        vmKey
    )

    constructor(clazz: Class<*>, context: Context, flow: String?, byteCode: ByteArray, callback: Any, vmKey: String, extra: ByteArray, bundle: Bundle?) : this(
        kotlin.runCatching {
            clazz.getDeclaredConstructor(Context::class.java, String::class.java, ByteArray::class.java, Object::class.java, Bundle::class.java).newInstance(context, flow, byteCode, callback, bundle)
        }.getOrElse {
            throw BytesException(extra, it)
        }, vmKey, extra)

    fun run(data: Map<Any, Any>): ByteArray {
        try {
            val method = findVmMethod(handle.javaClass, "run", 1) ?: throw NoSuchMethodException("run")
            return method.invoke(handle, data) as ByteArray
        } catch (e: Exception) {
            throw BytesException(extra, e)
        }
    }

    fun init(): Boolean {
        try {
            val method = findVmMethod(handle.javaClass, "init", 0) ?: throw NoSuchMethodException("init")
            return method.invoke(handle) as Boolean
        } catch (e: Exception) {
            throw BytesException(extra, e)
        }
    }

    fun close() {
        try {
            val method = findVmMethod(handle.javaClass, "close", 0) ?: throw NoSuchMethodException("close")
            method.invoke(handle)
        } catch (e: Exception) {
            throw BytesException(extra, e)
        }
    }

    private companion object {
        fun findVmMethod(clazz: Class<*>, name: String, parameterCount: Int): Method? {
            val method = (clazz.methods + clazz.declaredMethods).firstOrNull {
                it.name == name && it.parameterTypes.size == parameterCount
            } ?: return null
            method.isAccessible = true
            return method
        }
    }
}
