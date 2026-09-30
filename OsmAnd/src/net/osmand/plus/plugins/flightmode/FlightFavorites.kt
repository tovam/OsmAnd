package net.osmand.plus.plugins.flightmode

/** Personal device preferences, independent of journal edits and cloud revisions. */
internal data class FlightFavorites(
    val journeys: Set<String> = emptySet(),
    val photos: Set<String> = emptySet(),
) {
    fun contains(row: FlightLibraryRow): Boolean =
        row.logicalCloudId?.let(journeys::contains) == true

    fun toggle(row: FlightLibraryRow): FlightFavorites {
        val id = row.logicalCloudId ?: return this
        return copy(journeys = if (contains(row)) journeys - id else journeys + id)
    }

    fun togglePhoto(id: String): FlightFavorites =
        copy(photos = if (id in photos) photos - id else photos + id)
}
