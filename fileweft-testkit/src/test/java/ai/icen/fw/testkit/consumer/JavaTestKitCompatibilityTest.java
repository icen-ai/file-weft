package ai.icen.fw.testkit.consumer;

import ai.icen.fw.core.context.TraceContext;
import ai.icen.fw.metadata.api.MetadataProcessor;
import ai.icen.fw.metadata.api.MetadataSchema;
import ai.icen.fw.metadata.api.MetadataSchemaContext;
import ai.icen.fw.metadata.api.MetadataSchemaResolver;
import ai.icen.fw.spi.observability.FileWeftGaugeRecorder;
import ai.icen.fw.spi.observability.FileWeftLogger;
import ai.icen.fw.spi.observability.FileWeftMetric;
import ai.icen.fw.spi.observability.FileWeftMetrics;
import ai.icen.fw.spi.observability.LogContext;
import ai.icen.fw.spi.observability.TraceContextScope;
import ai.icen.fw.spi.plugin.FileWeftPlugin;
import ai.icen.fw.testkit.observability.FileWeftGaugeRecorderContractTest;
import ai.icen.fw.testkit.observability.FileWeftLoggerContractTest;
import ai.icen.fw.testkit.observability.FileWeftMetricsContractTest;
import ai.icen.fw.testkit.observability.TraceContextScopeContractTest;
import ai.icen.fw.testkit.metadata.MetadataProcessorContractTest;
import ai.icen.fw.testkit.metadata.MetadataSchemaResolverContractTest;
import ai.icen.fw.testkit.plugin.FileWeftPluginContractTest;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class JavaTestKitCompatibilityTest {
    @Test
    void public_contracts_are_subclassable_from_java() {
        assertNotNull(new LoggerContract());
        assertNotNull(new MetricsContract());
        assertNotNull(new GaugeContract());
        assertNotNull(new TraceScopeContract());
        assertNotNull(new PluginContract());
        assertNotNull(new MetadataResolverContract());
        assertNotNull(new MetadataProcessingContract());
    }

    private static final class LoggerContract extends FileWeftLoggerContractTest {
        @Override
        protected FileWeftLogger getFileWeftLogger() {
            return NoOpLogger.INSTANCE;
        }
    }

    private static final class MetricsContract extends FileWeftMetricsContractTest {
        @Override
        protected FileWeftMetrics getFileWeftMetrics() {
            return new FileWeftMetrics() {
                @Override
                public void increment(FileWeftMetric metric, Map<String, String> tags) { }

                @Override
                public void increment(FileWeftMetric metric) { }
            };
        }
    }

    private static final class GaugeContract extends FileWeftGaugeRecorderContractTest {
        @Override
        protected FileWeftGaugeRecorder getGaugeRecorder() {
            return (gauge, value, tags) -> { };
        }
    }

    private static final class TraceScopeContract extends TraceContextScopeContractTest {
        @Override
        protected TraceContextScope getTraceContextScope() {
            return new TraceContextScope() {
                private TraceContext current;

                @Override
                public TraceContext currentTraceContext() {
                    return current;
                }

                @Override
                public void bindTraceContext(TraceContext traceContext) {
                    current = traceContext;
                }
            };
        }
    }

    private static final class PluginContract extends FileWeftPluginContractTest {
        @Override
        protected FileWeftPlugin getFileWeftPlugin() {
            return (FileWeftPlugin) Proxy.newProxyInstance(
                    FileWeftPlugin.class.getClassLoader(),
                    new Class<?>[] { FileWeftPlugin.class },
                    (proxy, method, arguments) -> {
                        if (method.getName().equals("id")) {
                            return "java-testkit-plugin";
                        }
                        throw new UnsupportedOperationException(method.getName());
                    }
            );
        }
    }

    private static final class MetadataResolverContract extends MetadataSchemaResolverContractTest {
        @Override
        protected MetadataSchemaResolver getMetadataSchemaResolver() {
            return context -> context.getTenantId().equals("java-tenant")
                    ? new MetadataSchema("java-schema", "v1", Collections.emptyList())
                    : null;
        }

        @Override
        protected MetadataSchemaContext knownContext() {
            return metadataContext("java-tenant");
        }

        @Override
        protected MetadataSchemaContext absentContext() {
            return metadataContext("other-tenant");
        }
    }

    private static final class MetadataProcessingContract extends MetadataProcessorContractTest {
        @Override
        protected MetadataProcessor getMetadataProcessor() {
            return (context, metadata) -> Collections.unmodifiableMap(metadata);
        }

        @Override
        protected MetadataSchemaContext processingContext() {
            return metadataContext("java-tenant");
        }

        @Override
        protected Map<String, String> validInput() {
            return Collections.singletonMap("title", "Contract");
        }

        @Override
        protected Map<String, String> expectedCanonicalOutput() {
            return validInput();
        }
    }

    private static MetadataSchemaContext metadataContext(String tenantId) {
        return new MetadataSchemaContext(tenantId, "java-schema", "DOCUMENT", "WRITE", "v1");
    }

    private enum NoOpLogger implements FileWeftLogger {
        INSTANCE;

        @Override
        public void info(String message, LogContext context) { }

        @Override
        public void warn(String message, LogContext context) { }

        @Override
        public void error(String message, Throwable throwable, LogContext context) { }

        @Override
        public void debug(String message, LogContext context) { }
    }
}
