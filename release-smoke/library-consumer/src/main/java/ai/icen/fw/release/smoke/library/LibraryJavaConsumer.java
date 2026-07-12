package ai.icen.fw.release.smoke.library;

import com.fasterxml.jackson.databind.ObjectMapper;
import ai.icen.fw.adapter.micrometer.MicrometerFileWeftMetrics;
import ai.icen.fw.agent.AgentTaskHandler;
import ai.icen.fw.application.document.DocumentQueryRepository;
import ai.icen.fw.application.task.LeasedTaskHandler;
import ai.icen.fw.persistence.jdbc.JdbcDoctorReportRepository;
import ai.icen.fw.persistence.jdbc.JdbcDocumentQueryRepository;
import ai.icen.fw.spi.plugin.FileWeftPlugin;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;

/** Exercises public ABI that was previously hidden behind runtime-only POM scopes. */
public final class LibraryJavaConsumer {
    public FileWeftPlugin plugin(FileWeftPlugin plugin) {
        return plugin;
    }

    public LeasedTaskHandler taskHandler(AgentTaskHandler handler) {
        return handler;
    }

    public DocumentQueryRepository documentQueries() {
        return new JdbcDocumentQueryRepository();
    }

    public JdbcDoctorReportRepository doctorReports(ObjectMapper mapper, Clock clock) {
        return new JdbcDoctorReportRepository(mapper, clock);
    }

    public MicrometerFileWeftMetrics metrics(MeterRegistry registry) {
        return new MicrometerFileWeftMetrics(registry);
    }
}
