package net.osmand.plus.plugins.flightmode

/** EXIF transforms in image coordinates (x right, y down), independent of user photo rotation. */
internal object FlightPhotoOrientation {
    fun swapsAxes(orientation: Int): Boolean = orientation in 5..8

    /** Android Matrix row order. Translation keeps the upright image in positive coordinates. */
    fun matrix(orientation: Int, width: Int, height: Int): FloatArray {
        val w = width.toFloat()
        val h = height.toFloat()
        return when (orientation) {
            2 -> floatArrayOf(-1f, 0f, w, 0f, 1f, 0f, 0f, 0f, 1f)
            3 -> floatArrayOf(-1f, 0f, w, 0f, -1f, h, 0f, 0f, 1f)
            4 -> floatArrayOf(1f, 0f, 0f, 0f, -1f, h, 0f, 0f, 1f)
            5 -> floatArrayOf(0f, 1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
            6 -> floatArrayOf(0f, -1f, h, 1f, 0f, 0f, 0f, 0f, 1f)
            7 -> floatArrayOf(0f, -1f, h, -1f, 0f, w, 0f, 0f, 1f)
            8 -> floatArrayOf(0f, 1f, 0f, -1f, 0f, w, 0f, 0f, 1f)
            else -> floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        }
    }
}
