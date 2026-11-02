package com.zipick.notice.service;

import com.zipick.notice.domain.Housing;
import com.zipick.notice.domain.Notice;
import com.zipick.notice.domain.NoticeStatus;
import com.zipick.notice.repository.HousingRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HousingGeocodingPersistenceServiceTest {

    @Test
    void updatesOnlyActiveHousingWithoutCoordinates() {
        HousingRepository repository = mock(HousingRepository.class);
        HousingGeocodingPersistenceService service = new HousingGeocodingPersistenceService(repository);
        Housing housing = new Housing(new Notice("LH", "n", "공고", NoticeStatus.OPEN),
                "h", null, "서울 강남구 테헤란로 1");
        GeocodedAddress address = new GeocodedAddress(
                "서울특별시 강남구 테헤란로 1", "서울특별시", "강남구", "역삼동",
                "테헤란로", "빌딩", new BigDecimal("37.5"), new BigDecimal("127.0")
        );
        when(repository.findById(1L)).thenReturn(Optional.of(housing));

        assertThat(service.updateIfCoordinatesMissing(1L, address)).isTrue();
        assertThat(housing.hasCoordinates()).isTrue();
        assertThat(housing.getName()).isEqualTo("빌딩");
        assertThat(service.updateIfCoordinatesMissing(1L, address)).isFalse();

        when(repository.findById(2L)).thenReturn(Optional.empty());
        assertThat(service.updateIfCoordinatesMissing(2L, address)).isFalse();
    }
}
