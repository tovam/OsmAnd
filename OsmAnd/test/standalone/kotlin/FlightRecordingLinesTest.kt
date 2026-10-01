package net.osmand.test.junit

import net.osmand.plus.plugins.flightmode.*
import java.io.File
import net.osmand.plus.plugins.flightmode.FlightRecordingLines
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Host-only crash-recovery fixtures. */
class FlightRecordingLinesTest {
    // This is intentionally inside the checkout, never the user's flight data or system temp root.
    @get:Rule val folder = TemporaryFolder(File(System.getProperty("user.dir")))

    @Test
    fun recordingSnapshotsStayImmutableAcrossChunksAndRestorationKeepsDistance() {
        val history = FlightRecordedHistory()
        val saved = mutableListOf<List<FlightSample>>()
        repeat(1030) { index ->
            history.append(FlightSample(index, 0, index * 1000L, 0.0, index * .0001,
                1000.0, 20f, 90f, 5f))
            if (index in listOf(0, 254, 255, 256, 1023)) saved += listOf(history.snapshot())
        }
        for (snapshot in saved) {
            assertEquals(snapshot.size - 1, snapshot.last().index)
            assertEquals((0 until snapshot.size).toList(), snapshot.map { it.index })
        }
        val restored = FlightRecordedHistory(history.snapshot())
        val full = recordedFlightTrip("Synthetic", history.snapshot())
        assertEquals(full.totalDistanceMeters, history.distanceMeters, 1e-6)
        assertEquals(history.distanceMeters, restored.distanceMeters, 1e-6)
        assertEquals(full, recordedFlightTrip("Synthetic", history.snapshot(), history.distanceMeters))
    }

    @Test
    fun completedRecordsAreUnchanged() {
        val f = folder.newFile("samples.jsonl")
        val data = "{\"sample\":1}\n{\"battery\":90}\n"
        f.writeText(data)
        FlightRecordingLines.recoverTail(f)
        assertEquals(data, f.readText())
    }

    @Test
    fun missingSeparatorIsRestoredWithoutLosingACompleteSample() {
        val f = folder.newFile("samples.jsonl")
        val data = "{\"sample\":1}\n{\"sample\":2}"
        f.writeText(data)
        FlightRecordingLines.recoverTail(f)
        assertEquals(data + "\n", f.readText())
    }

    @Test
    fun incompleteTailIsBackedUpBeforeTheNextAppend() {
        val f = folder.newFile("samples.jsonl")
        val prefix = "{\"sample\":1}\n"
        val tail = "{\"sample\":"
        f.writeText(prefix + tail)
        FlightRecordingLines.recoverTail(f)
        assertEquals(prefix, f.readText())
        val backup =
            folder.root.listFiles()!!.single { it.name.startsWith("samples.jsonl.interrupted-") }
        assertEquals(tail, backup.readText())
        f.appendText("{\"sample\":2}\n")
        FlightRecordingLines.recoverTail(f)
        assertEquals(prefix + "{\"sample\":2}\n", f.readText())
    }
}
