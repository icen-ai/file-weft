package ai.icen.fw.web.api;

import ai.icen.fw.web.api.v1.doctor.DoctorCheckDto;
import ai.icen.fw.web.api.v1.doctor.DoctorReportDto;
import ai.icen.fw.web.api.v1.doctor.DoctorTaskDetailDto;
import ai.icen.fw.web.api.v1.doctor.DoctorTaskDto;
import ai.icen.fw.web.api.v1.doctor.DoctorTaskReceiptDto;
import ai.icen.fw.web.api.v1.doctor.SystemDoctorReportDto;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class JavaDoctorApiContractInteropTest {
    @Test
    void constructsEveryFormalDoctorContractFromJava() {
        DoctorCheckDto check = new DoctorCheckDto("storage", "HEALTHY", "Storage check passed.");
        DoctorReportDto report = new DoctorReportDto(
            "document-1",
            "HEALTHY",
            Collections.singletonList(check),
            100L
        );
        SystemDoctorReportDto system = new SystemDoctorReportDto(
            "HEALTHY",
            Collections.singletonList(check),
            100L
        );
        DoctorTaskDto task = new DoctorTaskDto("task-1", "document-1", "SUCCESS", 90L, 100L);
        DoctorTaskReceiptDto receipt = new DoctorTaskReceiptDto("task-1", "document-1", "PENDING");
        DoctorTaskDetailDto completed = new DoctorTaskDetailDto(task, report);
        DoctorTaskDetailDto pending = new DoctorTaskDetailDto(task);

        assertEquals("storage", system.getChecks().get(0).getCheckerName());
        assertEquals("task-1", receipt.getTaskId());
        assertEquals("document-1", completed.getReport().getDocumentId());
        assertNull(pending.getReport());
    }
}
