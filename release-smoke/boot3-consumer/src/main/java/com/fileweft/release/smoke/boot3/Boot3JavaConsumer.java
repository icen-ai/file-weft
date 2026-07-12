package com.fileweft.release.smoke.boot3;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fileweft.agent.AgentTaskScheduler;
import com.fileweft.core.id.IdentifierGenerator;
import com.fileweft.persistence.jdbc.JdbcDocumentDeliveryTargetRepository;
import com.fileweft.starter.boot3.FileWeftAutoConfiguration;
import com.fileweft.starter.boot3.FileWeftRuntimeConfiguration;
import com.fileweft.web.api.ApiResponse;
import com.fileweft.web.api.v1.document.DocumentDto;
import com.fileweft.web.spring.boot3.FileWeftWebBoot3AutoConfiguration;
import java.time.Clock;

/** Compiles only from the two recommended Boot 3 Maven coordinates. */
public final class Boot3JavaConsumer {
    public ObjectMapper mapper(FileWeftAutoConfiguration configuration) {
        return configuration.fileWeftObjectMapper();
    }

    public JdbcDocumentDeliveryTargetRepository repository(
        FileWeftRuntimeConfiguration configuration,
        Clock clock
    ) {
        return configuration.documentDeliveryTargets(clock);
    }

    public AgentTaskScheduler scheduler(
        FileWeftRuntimeConfiguration configuration,
        IdentifierGenerator identifiers,
        Clock clock
    ) {
        return configuration.agentTaskScheduler(identifiers, clock);
    }

    public ApiResponse<DocumentDto> webContract(
        FileWeftWebBoot3AutoConfiguration ignored,
        ApiResponse<DocumentDto> response
    ) {
        return response;
    }
}
