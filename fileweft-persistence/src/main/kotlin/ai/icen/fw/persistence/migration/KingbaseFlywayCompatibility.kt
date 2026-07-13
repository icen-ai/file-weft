package ai.icen.fw.persistence.migration

import javax.sql.DataSource

/**
 * Stable, Java-friendly boundary for adapting a Flyway-selected DataSource.
 * The returned view never replaces the application's DataSource.
 */
object KingbaseFlywayCompatibility {
    /**
     * Returns a lazy Flyway-only view. Product detection happens on Flyway's
     * own connection attempt so its retry policy remains effective; non-
     * Kingbase connections are returned unchanged.
     */
    @JvmStatic
    fun adaptIfNecessary(dataSource: DataSource): DataSource {
        return if (dataSource is KingbaseFlywayDataSource) {
            dataSource
        } else {
            KingbaseFlywayDataSource(dataSource)
        }
    }
}
