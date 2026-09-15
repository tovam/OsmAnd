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

    fun canOpenLocal(state: FlightUiState, localRemovalInProgress: Boolean = false): Boolean =
        local != null && !state.loadingTrip && !localRemovalInProgress

    fun matchesLocation(filter: Int): Boolean =
        filter == 0 || (filter == 1 && local != null) || (filter == 2 && remote != null)

    fun versionState(localDirty: Boolean, serverVerified: Boolean): FlightVersionState {
        if (!serverVerified) return FlightVersionState.UNVERIFIED
        if (remote == null) return FlightVersionState.NOT_SENT
        if (local == null) return FlightVersionState.SERVER_ONLY
        val localChanged = localDirty || binding?.localUpdatedAt != local.updatedAtMillis
        val remoteChanged = binding?.revision != remote.revision
        return when {
            remoteChanged && localChanged -> FlightVersionState.BOTH_CHANGED
            remoteChanged -> FlightVersionState.SERVER_CHANGED
            localChanged -> FlightVersionState.LOCAL_CHANGED
            binding?.allLocalPhotosIncluded != true -> FlightVersionState.PARTIAL
            else -> FlightVersionState.SENT
        }
    }
}

internal enum class FlightVersionState {
    UNVERIFIED,
    NOT_SENT,
    SERVER_ONLY,
    BOTH_CHANGED,
    SERVER_CHANGED,
    LOCAL_CHANGED,
    PARTIAL,
    SENT,
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
    includeMeasurements: Boolean = true,
): Boolean =
    journeyId == source.journeyId &&
        journeyName == source.journeyName &&
        plan == source.plan &&
        photos == source.photos &&
        flightSpans == source.flightSpans &&
        (!includeMeasurements || batteryHistory == source.batteryHistory) &&
        (!includeTrip || trip == source.trip)
