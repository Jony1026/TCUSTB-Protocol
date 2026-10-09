package net.tdteam.ustb.academic.api;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import net.tdteam.ustb.academic.application.TeacherScheduleService;
import net.tdteam.ustb.common.error.ApiErrors;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 教师查询接口鉴权和缓存头测试。@author itsjony01 @date 2026-10-01 */
class TeacherScheduleControllerTest {
    @Test
    void requiresSessionAndDoesNotCacheDirectory() throws Exception {
        var service = mock(TeacherScheduleService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new TeacherScheduleController(service))
                .setControllerAdvice(new ApiErrors()).build();
        mvc.perform(get("/api/v1/academic/teachers").param("semester", "2026-2027-1")
                        .param("college", "college-a"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
        when(service.directory("token", "2026-2027-1", "college-a"))
                .thenReturn(new TeacherScheduleService.Directory("2026-2027-1", "college-a", List.of("张三")));
        mvc.perform(get("/api/v1/academic/teachers").param("semester", "2026-2027-1")
                        .param("college", "college-a")
                        .header("Authorization", "Bearer token"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.names[0]").value("张三"));
    }

    @Test
    void acceptsMissingCollegeForAllTeacherSearch() throws Exception {
        var service = mock(TeacherScheduleService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new TeacherScheduleController(service))
                .setControllerAdvice(new ApiErrors()).build();
        when(service.directory("token", "2026-2027-1", ""))
                .thenReturn(new TeacherScheduleService.Directory("2026-2027-1", "", List.of("张三", "李四")));
        mvc.perform(get("/api/v1/academic/teachers").param("semester", "2026-2027-1")
                        .header("Authorization", "Bearer token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.college").value(""))
                .andExpect(jsonPath("$.names[1]").value("李四"));
    }
}
