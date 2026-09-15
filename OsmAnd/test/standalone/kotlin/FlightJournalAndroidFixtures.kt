package android.util

import java.io.File
import java.io.FileOutputStream

// Android's streaming reader has the same API used here. No Android runtime or user files.
typealias JsonReader = com.google.gson.stream.JsonReader

class AtomicFile(val baseFile: File) {
    private val pending = File(baseFile.path + ".new")

    fun openRead() = baseFile.inputStream()

    fun startWrite() = FileOutputStream(pending)

    fun finishWrite(stream: FileOutputStream) {
        stream.close()
        check(pending.renameTo(baseFile))
    }

    fun failWrite(stream: FileOutputStream) {
        stream.close()
        pending.delete()
    }
}
