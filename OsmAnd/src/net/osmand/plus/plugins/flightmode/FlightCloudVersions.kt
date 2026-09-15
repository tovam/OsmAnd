package net.osmand.plus.plugins.flightmode

/**
 * A local copy remembers the exact server revision it was based on, independently of other copies.
 */
internal data class FlightCloudBinding(
    val localId: String,
    val remoteId: String,
    val revision: String,
    val localUpdatedAt: Long,
    val allLocalPhotosIncluded: Boolean = false,
)

internal data class FlightLibraryRow(
    val local: FlightJourneySummary?,
    val remote: FlightCloudEntry?,
    val binding: FlightCloudBinding?,
) {
    val key
        get() = local?.let { "local:${it.id}" } ?: "remote:${remote!!.id}"

    val name
        get() = local?.name ?: remote!!.name
}

internal fun flightCloudRows(
    local: List<FlightJourneySummary>,
    remote: List<FlightCloudEntry>,
    bindings: List<FlightCloudBinding>,
): List<FlightLibraryRow> {
    val used = mutableSetOf<String>()
    val rows =
        local.map { journal ->
            val binding = bindings.firstOrNull { it.localId == journal.id }
            val entry = remote.firstOrNull { it.id == (binding?.remoteId ?: journal.id) }
            // A recorded server version must also appear in Past, even if this phone still has its
            // plan.
            if (entry != null && (entry.samples == 0) == (journal.sampleCount == 0))
                used += entry.id
            FlightLibraryRow(journal, entry, binding)
        } + remote.filterNot { it.id in used }.map { FlightLibraryRow(null, it, null) }
    return rows.sortedByDescending { it.local?.updatedAtMillis ?: it.remote!!.updatedAt }
}

/** A completed disk write must not acknowledge edits made after its snapshot was captured. */
internal fun FlightUiState.hasSameJournalContentAs(
    source: FlightUiState,
    includeTrip: Boolean = true,
): Boolean =
    journeyId == source.journeyId &&
        journeyName == source.journeyName &&
        plan == source.plan &&
        photos == source.photos &&
        flightSpans == source.flightSpans &&
        batteryHistory == source.batteryHistory &&
        (!includeTrip || trip == source.trip)
