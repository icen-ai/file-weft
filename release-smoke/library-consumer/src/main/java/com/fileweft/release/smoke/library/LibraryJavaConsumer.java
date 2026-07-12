package com.fileweft.release.smoke.library;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fileweft.adapter.micrometer.MicrometerFileWeftMetrics;
import com.fileweft.agent.AgentTaskHandler;
import com.fileweft.application.document.DocumentQueryRepository;
import com.fileweft.application.task.LeasedTaskHandler;
import com.fileweft.persistence.jdbc.JdbcDoctorReportRepository;
import com.fileweft.persistence.jdbc.JdbcDocumentQueryRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;

/** Exercises public ABI that was previously hidden behind runtime-only POM scopes. */
public final class LibraryJavaConsumer {
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
