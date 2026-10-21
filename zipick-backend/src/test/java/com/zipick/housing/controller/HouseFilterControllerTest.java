package com.zipick.housing.controller;

import com.zipick.housing.dto.FilterOptionsResponse;
import com.zipick.housing.dto.HouseFilterParams;
import com.zipick.housing.dto.HouseListResponse;
import com.zipick.housing.service.HouseFilterService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HouseFilterControllerTest {

    @Test
    void delegatesSearchAndOptions() {
        HouseFilterService service = mock(HouseFilterService.class);
        HouseFilterController controller = new HouseFilterController(service);
        HouseFilterParams params = new HouseFilterParams();
        HouseListResponse list = new HouseListResponse(0, List.of());
        FilterOptionsResponse options = new FilterOptionsResponse(
                Map.of(), List.of(), Map.of(), Map.of(), List.of()
        );
        when(service.search(any(), eq(10))).thenReturn(list);
        when(service.options()).thenReturn(options);

        assertThat(controller.search(params, 10).data()).isSameAs(list);
        assertThat(controller.options().data()).isSameAs(options);
    }
}
