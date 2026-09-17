package net.osmand.plus.plugins.flightmode

/** Volume keys are camera controls only while the live camera dialog owns the key dispatch. */
internal object FlightCameraKeyPolicy {
    enum class Action {
        IGNORE,
        CONSUME,
        CAPTURE,
    }

    fun action(
        isVolumeKey: Boolean,
        isDown: Boolean,
        isRepeat: Boolean,
        alreadyPressed: Boolean,
        busy: Boolean,
        captureReady: Boolean,
    ): Action {
        if (!isVolumeKey) return Action.IGNORE
        if (isDown && !isRepeat && !alreadyPressed && !busy && captureReady)
            return Action.CAPTURE
        return Action.CONSUME
    }
}
