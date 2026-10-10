package app.linkfill.core.find

/**
 * The group's "circle": a member is outside when even the most generous reading of both GPS fixes puts them
 * beyond the radius, so GPS noise alone cannot raise the alarm. They are back inside only below 80 % of the
 * radius; between the two the previous answer holds (hysteresis), so the alarm does not flap at the edge.
 */
object Circle {
    val RADII_M = listOf(30, 50, 100, 200)
    const val DEFAULT_M = 50

    fun outside(distanceM: Double, myAccuracyM: Double, theirAccuracyM: Double, radiusM: Double, wasOutside: Boolean): Boolean = when {
        distanceM - myAccuracyM - theirAccuracyM > radiusM -> true
        distanceM < radiusM * 0.8 -> false
        else -> wasOutside
    }
}
