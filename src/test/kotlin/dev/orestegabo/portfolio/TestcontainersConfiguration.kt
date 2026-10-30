package dev.orestegabo.portfolio

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.datasource.DriverManagerDataSource
import javax.sql.DataSource
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {
    @Bean
    @org.springframework.context.annotation.Primary
    fun marketTestPages() = CountingMarketTestPages()

    @Bean
    @ServiceConnection
    @ConditionalOnProperty(name=["struct.test.local-postgres"],havingValue="false",matchIfMissing=true)
    fun postgresContainer(): PostgreSQLContainer {
        return PostgreSQLContainer(DockerImageName.parse("postgres:18"))
    }

    /** Opt-in local test cluster only; never accept the application's database connection. */
    @Bean
    @ConditionalOnProperty(name=["struct.test.local-postgres"],havingValue="true")
    fun isolatedLocalPostgres(@Value("\${struct.test.database-url}") url: String): DataSource {
        require(Regex("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]{4,5}/struct_test_[a-z0-9_]+").matches(url)) {
            "Local integration tests require a disposable loopback struct_test_ database."
        }
        return DriverManagerDataSource(url,"struct_test","")
    }
}

/** Synthetic upstream: integration tests never fetch a live market website. */
class CountingMarketTestPages : MarketPages {
    val reads = java.util.concurrent.atomic.AtomicInteger()
    override fun get(path: String): String {
        reads.incrementAndGet()
        return "<html><table><tbody><tr><td>Synthetic IMR</td><td>90</td><td>89</td><td>1</td><td>1</td><td>90</td></tr></tbody></table></html>"
    }
}
