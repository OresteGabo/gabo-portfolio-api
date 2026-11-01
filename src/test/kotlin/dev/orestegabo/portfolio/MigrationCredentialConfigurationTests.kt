package dev.orestegabo.portfolio

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.MutablePropertySources
import org.springframework.core.env.PropertiesPropertySource
import org.springframework.core.env.PropertySourcesPropertyResolver
import java.util.Properties

class MigrationCredentialConfigurationTests {
    private fun config(overrides: Map<String, Any> = emptyMap()): PropertySourcesPropertyResolver {
        val properties = Properties().apply {
            MigrationCredentialConfigurationTests::class.java.getResourceAsStream("/application-cloudrun.properties")!!.use { load(it) }
        }
        val env = mapOf<String, Any>(
            "GABO_DATABASE_URL" to "jdbc:postgresql://database.example.test/portfolio",
            "GABO_DATABASE_USER" to "runtime_role", "GABO_DATABASE_PASSWORD" to "synthetic-runtime"
        ) + overrides
        return PropertySourcesPropertyResolver(MutablePropertySources().apply {
            addLast(MapPropertySource("synthetic", env))
            addLast(PropertiesPropertySource("cloudrun", properties))
        })
    }

    @Test fun `existing deployment credential defaults remain compatible`() {
        val config = config()
        assertEquals("runtime_role", config.getProperty("spring.flyway.user"))
        assertEquals("synthetic-runtime", config.getProperty("spring.flyway.password"))
        assertEquals(config.getProperty("spring.datasource.url"), config.getProperty("spring.flyway.url"))
    }

    @Test fun `migration role and direct connection do not change runtime credentials`() {
        val config = config(mapOf(
            "GABO_MIGRATION_DATABASE_URL" to "jdbc:postgresql://direct.example.test/portfolio",
            "GABO_MIGRATION_DATABASE_USER" to "migration_role",
            "GABO_MIGRATION_DATABASE_PASSWORD" to "synthetic-migration"
        ))
        assertEquals("migration_role", config.getProperty("spring.flyway.user"))
        assertEquals("synthetic-migration", config.getProperty("spring.flyway.password"))
        assertEquals("jdbc:postgresql://direct.example.test/portfolio", config.getProperty("spring.flyway.url"))
        assertEquals("runtime_role", config.getProperty("spring.datasource.username"))
        assertEquals("synthetic-runtime", config.getProperty("spring.datasource.password"))
        assertEquals("jdbc:postgresql://database.example.test/portfolio", config.getProperty("spring.datasource.url"))
    }
}
