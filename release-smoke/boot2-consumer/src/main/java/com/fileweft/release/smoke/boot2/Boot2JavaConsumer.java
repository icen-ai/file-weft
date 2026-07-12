package com.fileweft.release.smoke.boot2;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fileweft.agent.AgentTaskScheduler;
import com.fileweft.core.id.IdentifierGenerator;
import com.fileweft.persistence.jdbc.JdbcDocumentDeliveryTargetRepository;
import com.fileweft.starter.boot2.FileWeftAutoConfiguration;
import com.fileweft.starter.boot2.FileWeftRuntimeConfiguration;
import com.fileweft.web.api.ApiResponse;
import com.fileweft.web.api.v1.document.DocumentDto;
import com.fileweft.web.spring.boot2.FileWeftWebBoot2AutoConfiguration;
import java.time.Clock;

/** Compiles only from the two recommended Boot 2 Maven coordinates. */
public final class Boot2JavaConsumer {
    public ObjectMapper mapper(FileWeftAutoConfiguration configuration) {
        return configuration.fileWeftObjectMapper();
    }

    public JdbcDocumentDeliveryTargetRepository repository(
        FileWeftRuntimeConfiguration configuration,
        Clock clock
    ) {
        return configuration.fileWeftDocumentDeliveryTargetRepository(clock);
    }

    public AgentTaskScheduler scheduler(
        FileWeftRuntimeConfiguration configuration,
        IdentifierGenerator identifiers,
        Clock clock
    ) {
        return configuration.fileWeftAgentTaskScheduler(identifiers, clock);
    }

    public ApiResponse<DocumentDto> webContract(
        FileWeftWebBoot2AutoConfiguration ignored,
        ApiResponse<DocumentDto> response
    ) {
        return response;
    }
}
