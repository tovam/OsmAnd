package net.osmand.plus.plugins.flightmode

import net.osmand.IndexConstants
import net.osmand.plus.OsmandApplication
import java.io.File

/** Installs the bundled aircraft OBJ where OsmAnd's native model loader expects user models. */
internal object FlightAircraftModelProvider {

	const val MODEL_DIRECTORY_NAME = "flight_aircraft_v2"
	const val MODEL_KEY = IndexConstants.MODEL_NAME_PREFIX + MODEL_DIRECTORY_NAME

	private const val ASSET_VERSION = "2"
	private const val VERSION_FILE_NAME = ".flight-asset-version"
	private const val ASSET_ROOT = "flightmode/aircraft"

	private val modelFiles = mapOf(
		"$ASSET_ROOT/flight_airliner.obj" to "$MODEL_DIRECTORY_NAME.obj",
		"$ASSET_ROOT/flight_airliner.mtl" to "flight_airliner.mtl"
	)

	@Synchronized
	fun ensureInstalled(app: OsmandApplication): Boolean {
		val modelDirectory = File(app.getAppPath(IndexConstants.MODEL_3D_DIR), MODEL_DIRECTORY_NAME)
		val versionFile = File(modelDirectory, VERSION_FILE_NAME)
		if (versionFile.readTextOrNull() == ASSET_VERSION && modelFiles.values.all { name ->
				File(modelDirectory, name).let { it.isFile && it.length() > 0L }
			}
		) {
			return true
		}
		if (!modelDirectory.exists() && !modelDirectory.mkdirs()) return false
		if (!modelDirectory.isDirectory) return false

		return runCatching {
			modelFiles.forEach { (assetPath, targetName) ->
				copyAssetAtomically(app, assetPath, File(modelDirectory, targetName))
			}
			val versionPart = File(modelDirectory, "$VERSION_FILE_NAME.part")
			versionPart.writeText(ASSET_VERSION)
			replaceFile(versionPart, versionFile)
			true
		}.getOrDefault(false)
	}

	private fun copyAssetAtomically(app: OsmandApplication, assetPath: String, target: File) {
		val part = File(target.parentFile, "${target.name}.part")
		app.assets.open(assetPath).use { input ->
			part.outputStream().buffered().use { output -> input.copyTo(output) }
		}
		check(part.length() > 0L) { "Empty bundled aircraft asset: $assetPath" }
		replaceFile(part, target)
	}

	private fun replaceFile(part: File, target: File) {
		if (target.exists() && !target.delete()) {
			throw IllegalStateException("Cannot replace ${target.name}")
		}
		if (!part.renameTo(target)) {
			throw IllegalStateException("Cannot install ${target.name}")
		}
	}

	private fun File.readTextOrNull(): String? =
		runCatching { takeIf(File::isFile)?.readText() }.getOrNull()
}
