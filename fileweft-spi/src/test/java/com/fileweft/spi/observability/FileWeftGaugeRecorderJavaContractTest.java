package com.fileweft.spi.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Collections;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FileWeftGaugeRecorderJavaContractTest {
    @Test
    void aJavaAdapterImplementsOnlyThePrimarySetter() {
        JavaGaugeRecorder recorder = new JavaGaugeRecorder();

        Map<String, String> tags = Collections.singletonMap("state", "ready");
        recorder.set(FileWeftGauge.OUTBOX_BACKLOG, 3.0d, tags);

        assertEquals(FileWeftGauge.OUTBOX_BACKLOG, recorder.gauge);
        assertEquals(3.0d, recorder.value);
        assertEquals(tags, recorder.tags);
    }

    private static final class JavaGaugeRecorder implements FileWeftGaugeRecorder {
        private FileWeftGauge gauge;
        private double value;
        private Map<String, String> tags;

        @Override
        public void set(FileWeftGauge gauge, double value, Map<String, String> tags) {
            this.gauge = gauge;
            this.value = value;
            this.tags = tags;
        }
    }
}
