package net.osmand.test.junit

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
