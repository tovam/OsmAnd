package net.osmand.test.junit

import java.io.File
import java.io.StringReader
import net.osmand.plus.plugins.flightmode.FlightJournalSummaries
import net.osmand.plus.plugins.flightmode.FlightJourneySummary
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Synthetic journal fixtures for compact library metadata and sidecar migration. */
class FlightJournalSummaryMetadataTest {
    @get:Rule val folder = TemporaryFolder(File(System.getProperty("user.dir")))

    @Test
    fun oldFiveFieldSidecarIsMigratedToTheCurrentMetadata() {
        val journal = folder.newFile("old.json")
        journal.writeText(
            journalJson(
                id = "old",
                name = "Old",
                simulation = true,
                offlineAssets = "{\"terrainTiles\":[{\"z\":1}],\"standardSatelliteTiles\":[{\"z\":2}]}"
            )
        )
        val oldSidecar = JSONObject()
            .put("length", journal.length())
            .put("modified", journal.lastModified())
            .put("id", "old")
            .put("name", "Old")
            .put("updated", 42L)
            .put("samples", 0)
            .put("photos", 0)
        FlightJournalSummaries.sidecar(journal).writeText(oldSidecar.toString())

        val summary = FlightJournalSummaries.read(journal)

        assertTrue(summary.simulation)
        assertEquals(1234L, summary.departureMillis)
        assertEquals(1, summary.terrainTileCount)
        assertEquals(1, summary.satelliteTileCount)
        assertEquals(2, JSONObject(FlightJournalSummaries.sidecar(journal).readText()).getInt("schemaVersion"))
    }

    @Test
    fun newSidecarRoundTripsAllMetadata() {
        val journal = folder.newFile("new.json")
        journal.writeText(journalJson(id = "new", name = "New", simulation = true))
        val expected = FlightJourneySummary(
            id = "new",
            name = "New",
            updatedAtMillis = 9L,
            sampleCount = 4,
            photoCount = 2,
            simulation = true,
            departureMillis = 1234L,
            terrainTileCount = 7,
            satelliteTileCount = 8,
        )
        FlightJournalSummaries.write(journal, expected)

        val actual = FlightJournalSummaries.read(journal)

        assertEquals(expected, actual)
    }

    @Test
    fun missingAndNullMetadataUseEmptyDefaults() {
        val summary = FlightJournalSummaries.scan(
            StringReader(
                journalJson(
                    id = "missing",
                    name = "Missing",
                    simulation = null,
                    plan = "\"preparation\":null,\"stops\":[]",
                    offlineAssets = "null",
                )
            ),
            modified = 17L,
        )

        assertFalse(summary.simulation)
        assertNull(summary.departureMillis)
        assertEquals(0, summary.terrainTileCount)
        assertEquals(0, summary.satelliteTileCount)
    }

    @Test
    fun simulationAndRecordedJournalsKeepDistinctMetadata() {
        val simulated = FlightJournalSummaries.scan(
            StringReader(journalJson(id = "sim", name = "Sim", simulation = true, sampleCount = 0)),
            modified = 1L,
        )
        val recorded = FlightJournalSummaries.scan(
            StringReader(journalJson(id = "recorded", name = "Recorded", simulation = false, sampleCount = 2)),
            modified = 2L,
        )

        assertTrue(simulated.simulation)
        assertEquals(0, simulated.sampleCount)
        assertFalse(recorded.simulation)
        assertEquals(2, recorded.sampleCount)
    }

    @Test
    fun offlineRequestsAreNotCountedAsPresentAssets() {
        val summary = FlightJournalSummaries.scan(
            StringReader(
                journalJson(
                    id = "assets",
                    name = "Assets",
                    simulation = false,
                    offlineAssets = "{\"terrainTiles\":[{\"z\":1}],\"standardSatelliteTiles\":[]}",
                    offlineRequest = "{\"terrainTiles\":[{\"z\":1},{\"z\":2}],\"standardSatelliteTiles\":[{\"z\":3}]}"
                )
            ),
            modified = 3L,
        )

        assertEquals(1, summary.terrainTileCount)
        assertEquals(0, summary.satelliteTileCount)
    }

    @Test
    fun largeAssetArraysAreCountedByStreamingScan() {
        val terrain = (1..20_000).joinToString(",") { "{\"z\":12,\"x\":$it,\"y\":1}" }
        val summary = FlightJournalSummaries.scan(
            StringReader(
                journalJson(
                    id = "large",
                    name = "Large",
                    simulation = false,
                    offlineAssets = "{\"terrainTiles\":[$terrain],\"standardSatelliteTiles\":[]}",
                )
            ),
            modified = 4L,
        )

        assertEquals(20_000, summary.terrainTileCount)
        assertEquals(0, summary.satelliteTileCount)
    }

    private fun journalJson(
        id: String,
        name: String,
        simulation: Boolean?,
        sampleCount: Int = 0,
        plan: String = "\"preparation\":{\"departure\":1234},\"stops\":[]",
        offlineAssets: String = "{\"terrainTiles\":[],\"standardSatelliteTiles\":[]}",
        offlineRequest: String = "{\"terrainTiles\":[],\"standardSatelliteTiles\":[]}",
    ): String {
        val samples = (1..sampleCount).joinToString(",") { "{}" }
        val simulationJson = simulation?.toString() ?: "null"
        return """
            {
              "id":"$id",
              "name":"$name",
              "updatedAtMillis":99,
              "simulation":$simulationJson,
              "plan":{$plan},
              "trip":{"samples":[$samples]},
              "photos":[],
              "offlineAssets":$offlineAssets,
              "offlineRequest":$offlineRequest
            }
        """.trimIndent()
    }
}
