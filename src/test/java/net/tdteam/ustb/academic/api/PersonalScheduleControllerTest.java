package net.tdteam.ustb.academic.api;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import net.tdteam.ustb.academic.application.PersonalScheduleService;
import net.tdteam.ustb.academic.model.PersonalScheduleResponse;
import net.tdteam.ustb.common.error.ApiErrors;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 个人课程入口必须鉴权并禁止浏览器缓存。@author itsjony01 @date 2026-10-01 */
class PersonalScheduleControllerTest {
    @Test
    void requiresBearerAndReturnsPrivateResponseWithNoStore() throws Exception {
        var service = mock(PersonalScheduleService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new PersonalScheduleController(service))
                .setControllerAdvice(new ApiErrors()).build();
        mvc.perform(get("/api/v1/academic/my/schedule").param("date", "2026-10-01"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
        when(service.schedule("token", "2026-10-01"))
                .thenReturn(new PersonalScheduleResponse("2026-10-01", "", "", "", false, List.of()));
        mvc.perform(get("/api/v1/academic/my/schedule").param("date", "2026-10-01")
                .header("Authorization", "Bearer token"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.date").value("2026-10-01"));
        verify(service).schedule("token", "2026-10-01");
    }
}
