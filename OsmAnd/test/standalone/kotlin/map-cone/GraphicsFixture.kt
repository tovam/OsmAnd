package android.graphics

class Canvas { fun drawPath(path: Path, paint: Paint) {} }
class Path {
    fun moveTo(x: Float, y: Float) {}
    fun lineTo(x: Float, y: Float) {}
    fun close() {}
}
class Paint(flags: Int) {
    var color: Int = 0
    companion object { const val ANTI_ALIAS_FLAG = 1 }
}
