package com.lazydevs.wristotle.apps

/**
 * In-memory fake of [InstalledAppDao] used by JVM unit tests. Matches
 * the SQL semantics declared in the @Query annotations: case-sensitive
 * normalized-label matching, length-based ordering for prefix/contains,
 * and the 4-char minimum-label guard on reverse-contains.
 */
internal class FakeInstalledAppDao(initial: List<InstalledApp> = emptyList()) : InstalledAppDao {

    private val apps: MutableList<InstalledApp> = initial.toMutableList()

    override suspend fun all(): List<InstalledApp> =
        apps.sortedBy { it.label.lowercase() }

    override suspend fun count(): Int = apps.size

    override suspend fun latestScanAt(): Long? =
        apps.maxOfOrNull { it.lastScannedAtMs }

    override suspend fun findExact(norm: String): InstalledApp? =
        apps.firstOrNull { it.normalizedLabel == norm }

    override suspend fun findExactByPackage(norm: String): InstalledApp? =
        apps.firstOrNull { it.normalizedPackage == norm }

    override suspend fun findByPrefix(prefix: String): InstalledApp? =
        apps.filter { it.normalizedLabel.startsWith(prefix) }
            .minByOrNull { it.normalizedLabel.length }

    override suspend fun findByPrefixOfPackage(prefix: String): InstalledApp? =
        apps.filter { it.normalizedPackage.startsWith(prefix) }
            .minByOrNull { it.normalizedPackage.length }

    override suspend fun findByContains(needle: String): InstalledApp? =
        apps.filter { it.normalizedLabel.contains(needle) }
            .minByOrNull { it.normalizedLabel.length }

    override suspend fun findByContainsInPackage(needle: String): InstalledApp? =
        apps.filter { it.normalizedPackage.contains(needle) }
            .minByOrNull { it.normalizedPackage.length }

    override suspend fun findByReverseContains(needle: String): InstalledApp? =
        apps.filter { needle.contains(it.normalizedLabel) && it.normalizedLabel.length >= 4 }
            .maxByOrNull { it.normalizedLabel.length }

    override suspend fun findByReverseContainsOfPackage(needle: String): InstalledApp? =
        apps.filter { needle.contains(it.normalizedPackage) && it.normalizedPackage.length >= 4 }
            .maxByOrNull { it.normalizedPackage.length }

    override suspend fun upsertAll(apps: List<InstalledApp>) {
        for (app in apps) {
            this.apps.removeAll { it.packageId == app.packageId }
            this.apps.add(app)
        }
    }

    override suspend fun deleteAll() {
        apps.clear()
    }
}
