package ai.icen.fw.release.smoke.boot3;

import com.fasterxml.jackson.databind.ObjectMapper;
import ai.icen.fw.agent.AgentTaskScheduler;
import ai.icen.fw.core.id.IdentifierGenerator;
import ai.icen.fw.persistence.jdbc.JdbcDocumentDeliveryTargetRepository;
import ai.icen.fw.starter.boot3.FileWeftAutoConfiguration;
import ai.icen.fw.starter.boot3.FileWeftRuntimeConfiguration;
import ai.icen.fw.web.api.ApiResponse;
import ai.icen.fw.web.api.v1.document.DocumentDto;
import ai.icen.fw.web.spring.boot3.FileWeftWebBoot3AutoConfiguration;
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
