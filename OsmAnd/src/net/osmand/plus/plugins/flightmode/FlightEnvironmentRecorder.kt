package net.osmand.plus.plugins.flightmode

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import java.util.ArrayDeque
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln1p
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

data class FlightEnvironmentReading(
	val soundDb: Float? = null,
	val soundSpectrum: List<Float>? = null,
	val vibrationHz: Float? = null
)

/**
 * Records real microphone and accelerometer measurements while a live flight is
 * open. Values are deliberately nullable: a missing permission or sensor never
 * produces synthetic bars or numbers.
 */
class FlightEnvironmentRecorder(
	context: Context,
	private val onReading: (FlightEnvironmentReading) -> Unit
) : SensorEventListener {

	private val sensorManager = context.applicationContext
		.getSystemService(Context.SENSOR_SERVICE) as SensorManager
	private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
	private val mainHandler = Handler(Looper.getMainLooper())
	private val gravity = FloatArray(3)
	private val vibrationSamples = ArrayDeque<TimedAcceleration>()
	private val readingLock = Any()
	@Volatile
	private var running = false
	private var audioRecord: AudioRecord? = null
	private var audioThread: Thread? = null
	private var latestReading = FlightEnvironmentReading()
	@Volatile
	private var lastEmissionMillis = 0L

	fun start(recordMicrophone: Boolean) {
		if (running) return
		gravity.fill(0f)
		vibrationSamples.clear()
		synchronized(readingLock) { latestReading = FlightEnvironmentReading() }
		lastEmissionMillis = 0L
		running = true
		accelerometer?.let {
			sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
		}
		if (recordMicrophone) startMicrophone()
	}

	fun stop() {
		if (!running && audioRecord == null) return
		running = false
		sensorManager.unregisterListener(this)
		val recorder = audioRecord
		audioRecord = null
		runCatching { recorder?.stop() }
		audioThread?.interrupt()
		audioThread = null
		runCatching { recorder?.release() }
		vibrationSamples.clear()
		mainHandler.removeCallbacksAndMessages(null)
	}

	override fun onSensorChanged(event: SensorEvent) {
		if (!running || event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
		for (axis in 0..2) {
			gravity[axis] = gravity[axis] * GRAVITY_FILTER + event.values[axis] * (1f - GRAVITY_FILTER)
		}
		val x = event.values[0] - gravity[0]
		val y = event.values[1] - gravity[1]
		val z = event.values[2] - gravity[2]
		val magnitude = sqrt(x * x + y * y + z * z)
		vibrationSamples.addLast(TimedAcceleration(event.timestamp, magnitude))
		while (vibrationSamples.size > MAXIMUM_VIBRATION_SAMPLES) vibrationSamples.removeFirst()
		val now = android.os.SystemClock.elapsedRealtime()
		if (now - lastEmissionMillis >= EMISSION_INTERVAL_MILLIS) {
			val vibration = dominantVibrationFrequency(vibrationSamples)
			synchronized(readingLock) {
				latestReading = latestReading.copy(vibrationHz = vibration)
			}
			emit(now)
		}
	}

	override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

	@SuppressLint("MissingPermission")
	private fun startMicrophone() {
		val minimum = AudioRecord.getMinBufferSize(
			AUDIO_SAMPLE_RATE_HZ,
			AudioFormat.CHANNEL_IN_MONO,
			AudioFormat.ENCODING_PCM_16BIT
		)
		if (minimum <= 0) return
		val bufferBytes = max(minimum, AUDIO_FRAME_SAMPLES * 2 * 2)
		val recorder = runCatching {
			AudioRecord.Builder()
				.setAudioSource(MediaRecorder.AudioSource.DEFAULT)
				.setAudioFormat(
					AudioFormat.Builder()
						.setEncoding(AudioFormat.ENCODING_PCM_16BIT)
						.setSampleRate(AUDIO_SAMPLE_RATE_HZ)
						.setChannelMask(AudioFormat.CHANNEL_IN_MONO)
						.build()
				)
				.setBufferSizeInBytes(bufferBytes)
				.build()
		}.getOrNull() ?: return
		if (recorder.state != AudioRecord.STATE_INITIALIZED) {
			recorder.release()
			return
		}
		audioRecord = recorder
		runCatching { recorder.startRecording() }.onFailure {
			audioRecord = null
			recorder.release()
			return
		}
		audioThread = Thread({ recordAudio(recorder) }, "FlightEnvironmentAudio").apply {
			priority = Thread.NORM_PRIORITY
			start()
		}
	}

	private fun recordAudio(recorder: AudioRecord) {
		val buffer = ShortArray(AUDIO_FRAME_SAMPLES)
		while (running && audioRecord === recorder && !Thread.currentThread().isInterrupted) {
			val count = recorder.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
			if (count <= 0) continue
			var energy = 0.0
			for (index in 0 until count) {
				val value = buffer[index].toDouble()
				energy += value * value
			}
			val rms = sqrt(energy / count.coerceAtLeast(1)).coerceAtLeast(1.0)
			val relativeDb = (20.0 * log10(rms)).toFloat().coerceIn(0f, 100f)
			val spectrum = audioSpectrum(buffer, count)
			synchronized(readingLock) {
				latestReading = latestReading.copy(soundDb = relativeDb, soundSpectrum = spectrum)
			}
			val now = android.os.SystemClock.elapsedRealtime()
			if (now - lastEmissionMillis >= EMISSION_INTERVAL_MILLIS) emit(now)
		}
	}

	private fun emit(now: Long) {
		if (!running) return
		lastEmissionMillis = now
		val reading = synchronized(readingLock) { latestReading }
		mainHandler.post { if (running) onReading(reading) }
	}

	private fun audioSpectrum(buffer: ShortArray, count: Int): List<Float> {
		return AUDIO_BAND_FREQUENCIES.map { frequency ->
			val omega = 2.0 * PI * frequency / AUDIO_SAMPLE_RATE_HZ
			val coefficient = 2.0 * cos(omega)
			var previous = 0.0
			var previousPrevious = 0.0
			for (index in 0 until count) {
				val window = 0.5 - 0.5 * cos(2.0 * PI * index / (count - 1).coerceAtLeast(1))
				val current = buffer[index] * window + coefficient * previous - previousPrevious
				previousPrevious = previous
				previous = current
			}
			val power = previousPrevious * previousPrevious + previous * previous -
				coefficient * previous * previousPrevious
			(ln1p(sqrt(power.coerceAtLeast(0.0))) / SPECTRUM_LOG_RANGE).toFloat().coerceIn(0f, 1f)
		}
	}

	private fun dominantVibrationFrequency(samples: Collection<TimedAcceleration>): Float? {
		if (samples.size < MINIMUM_VIBRATION_SAMPLES) return null
		val values = samples.toList()
		val durationSeconds = (values.last().timestampNanos - values.first().timestampNanos) / 1_000_000_000.0
		if (durationSeconds <= 0.25) return null
		val sampleRate = (values.size - 1) / durationSeconds
		val average = values.sumOf { it.value.toDouble() } / values.size
		val rms = sqrt(values.sumOf { (it.value - average) * (it.value - average) } / values.size)
		if (rms < MINIMUM_VIBRATION_RMS) return null
		val maximumFrequency = minOf(MAXIMUM_VIBRATION_FREQUENCY_HZ, sampleRate * 0.45)
		var bestFrequency = 0.0
		var bestPower = 0.0
		var frequency = MINIMUM_VIBRATION_FREQUENCY_HZ
		while (frequency <= maximumFrequency) {
			var real = 0.0
			var imaginary = 0.0
			values.forEachIndexed { index, value ->
				val angle = 2.0 * PI * frequency * index / sampleRate
				val centered = value.value - average
				real += centered * cos(angle)
				imaginary -= centered * sin(angle)
			}
			val power = real * real + imaginary * imaginary
			if (power > bestPower) {
				bestPower = power
				bestFrequency = frequency
			}
			frequency += VIBRATION_FREQUENCY_STEP_HZ
		}
		return bestFrequency.takeIf { bestPower > 0.0 }?.toFloat()
	}

	private data class TimedAcceleration(val timestampNanos: Long, val value: Float)

	private companion object {
		const val GRAVITY_FILTER = 0.90f
		const val AUDIO_SAMPLE_RATE_HZ = 8_000
		const val AUDIO_FRAME_SAMPLES = 1_024
		const val SPECTRUM_LOG_RANGE = 10.0
		const val EMISSION_INTERVAL_MILLIS = 500L
		const val MAXIMUM_VIBRATION_SAMPLES = 128
		const val MINIMUM_VIBRATION_SAMPLES = 32
		const val MINIMUM_VIBRATION_RMS = 0.025
		const val MINIMUM_VIBRATION_FREQUENCY_HZ = 1.0
		const val MAXIMUM_VIBRATION_FREQUENCY_HZ = 40.0
		const val VIBRATION_FREQUENCY_STEP_HZ = 0.5
		val AUDIO_BAND_FREQUENCIES = listOf(63.0, 90.0, 125.0, 180.0, 250.0, 355.0, 500.0, 710.0,
			1_000.0, 1_400.0, 2_000.0, 2_800.0, 3_400.0, 3_700.0)
	}
}
