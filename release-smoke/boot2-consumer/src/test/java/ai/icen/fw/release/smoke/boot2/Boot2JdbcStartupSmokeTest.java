package ai.icen.fw.release.smoke.boot2;

import ai.icen.fw.application.document.DocumentDraftService;
import ai.icen.fw.application.transaction.ApplicationTransaction;
import ai.icen.fw.web.runtime.v1.document.DocumentApiWriteFacade;
import ai.icen.fw.web.spring.boot2.DocumentV1WriteController;
import com.zaxxer.hikari.HikariDataSource;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Path;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Starts a clean Boot 2 consumer from the same dependency set recommended in the documentation. */
class Boot2JdbcStartupSmokeTest {
    @TempDir
    Path storageRoot;

    @Test
    void hostJdbcStarterCreatesTheDataSourceAndFormalDocumentWriteEntry() throws IOException {
        SpringApplication application = new SpringApplicationBuilder(SmokeApplication.class)
            .web(WebApplicationType.SERVLET)
            .properties(
                "spring.main.banner-mode=off",
                "server.port=0",
                "spring.datasource.generate-unique-name=true",
                "spring.flyway.enabled=false",
                "fileweft.default-tenant-enabled=true",
                "fileweft.default-tenant-id=release-smoke",
                "fileweft.storage.local-enabled=true",
                "fileweft.storage.local-root=" + storageRoot.toAbsolutePath(),
                "fileweft.persistence.migration-mode=disabled",
                "fileweft.worker.enabled=false"
            )
            .build();

        try (ConfigurableApplicationContext context = application.run()) {
            DataSource dataSource = context.getBean(DataSource.class);
            assertTrue(dataSource instanceof HikariDataSource, "Boot should create its host-owned JDBC pool");
            assertNotNull(context.getBean(ApplicationTransaction.class));
            assertNotNull(context.getBean(DocumentDraftService.class));
            assertNotNull(context.getBean(DocumentApiWriteFacade.class));
            assertNotNull(context.getBean(DocumentV1WriteController.class));
            assertFormalHealthEndpoint(context);
        }
    }

    private static void assertFormalHealthEndpoint(ConfigurableApplicationContext context) throws IOException {
        int port = context.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
        HttpURLConnection connection = (HttpURLConnection) new URL(
            "http://127.0.0.1:" + port + "/fileweft/v1/health"
        ).openConnection();
        connection.setConnectTimeout(5_000);
        connection.setReadTimeout(5_000);
        try {
            assertEquals(200, connection.getResponseCode());
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), UTF_8))) {
                String body = reader.lines().collect(Collectors.joining());
                assertTrue(body.contains("\"status\":\"UP\""), "Formal health response must report UP: " + body);
            }
        } finally {
            connection.disconnect();
        }
    }

    @SpringBootConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class SmokeApplication {
    }
}
