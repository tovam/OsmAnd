package net.osmand.plus.utils

import net.osmand.core.jni.FColorARGB

object NativeUtilities {
    fun createFColorARGB(color: Int) = FColorARGB(color.toLong() and 0xFFFFFFFFL)
}
