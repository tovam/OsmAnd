package net.osmand.plus.plugins.flightmode

/** Keep generated route names current without replacing a deliberately customized journal title. */
internal object FlightJourneyNaming {
    fun route(plan: FlightPlan): String =
        plan.stops.map { it.name.trim() }.filter { it.isNotEmpty() }.joinToString(" → ")

    fun updated(name: String, previous: FlightPlan, next: FlightPlan): String {
        fun normalized(value: String) =
            value.replace("->", "→").split('→').joinToString("→") {
                it.trim().lowercase(java.util.Locale.ROOT)
            }
        val generic = normalized(name) in setOf("départ→arrivée", "departure→arrival")
        return if (name.isBlank() || generic || normalized(name) == normalized(route(previous)))
            route(next)
        else name
    }
}
