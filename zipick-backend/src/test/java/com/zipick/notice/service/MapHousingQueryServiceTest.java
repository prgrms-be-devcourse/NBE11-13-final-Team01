package com.zipick.notice.service;

import com.zipick.global.error.BusinessException;
import com.zipick.notice.domain.Agency;
import com.zipick.notice.domain.Housing;
import com.zipick.notice.domain.HousingMedia;
import com.zipick.notice.domain.HousingSupply;
import com.zipick.notice.domain.HousingSupplyCondition;
import com.zipick.notice.domain.Notice;
import com.zipick.notice.domain.NoticeAttachment;
import com.zipick.notice.domain.NoticeStatus;
import com.zipick.notice.repository.HousingMediaRepository;
import com.zipick.notice.repository.HousingRepository;
import com.zipick.notice.repository.HousingSupplyConditionRepository;
import com.zipick.notice.repository.HousingSupplyRepository;
import com.zipick.notice.repository.NoticeAttachmentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MapHousingQueryServiceTest {

    private final HousingRepository housings = mock(HousingRepository.class);
    private final HousingSupplyRepository supplies = mock(HousingSupplyRepository.class);
    private final HousingSupplyConditionRepository conditions = mock(HousingSupplyConditionRepository.class);
    private final NoticeAttachmentRepository attachments = mock(NoticeAttachmentRepository.class);
    private final HousingMediaRepository media = mock(HousingMediaRepository.class);
    private final MapHousingQueryService service = new MapHousingQueryService(
            housings, supplies, conditions, attachments, media
    );

    @Test
    void returnsOpenMapListAndCompleteDetail() {
        Notice notice = notice(NoticeStatus.OPEN);
        Housing housing = housing(notice);
        HousingSupply supply = withId(new HousingSupply(housing, "s1", 7L), 3L);
        supply.synchronize(7L, "아파트", "국민임대", "101동", "101호", 1, 2, 1,
                new BigDecimal("39.5"), 2, 2, 10_000_000L, null, null, null,
                300_000L, "공급중", "강남역", new BigDecimal("0.3"), true, true,
                100, false, 0, "{}", Instant.now());
        HousingSupplyCondition condition = new HousingSupplyCondition(supply, "BASIC", "기본");
        condition.synchronize("기본", 10_000_000L, 300_000L, "{}");
        NoticeAttachment attachment = withId(new NoticeAttachment(
                notice, "a1", "FLOOR_PLAN", "https://files.lh.or.kr/a.png"
        ), 4L);
        attachment.synchronize("FLOOR_PLAN", "a.png", "https://files.lh.or.kr/a.png", "{}");
        HousingMedia image = withId(new HousingMedia(
                housing, "m1", "BUILDING_PHOTO", "https://files.lh.or.kr/m.jpg"
        ), 5L);
        image.synchronize("BUILDING_PHOTO", "m.jpg", "https://files.lh.or.kr/m.jpg", "{}");
        when(housings.findAllByActiveTrueAndLatitudeIsNotNullAndLongitudeIsNotNullAndNoticeStatusOrderByIdAsc(
                NoticeStatus.OPEN)).thenReturn(List.of(housing));
        when(housings.findByIdAndActiveTrue(2L)).thenReturn(Optional.of(housing));
        when(supplies.findAllByHousingIdAndActiveTrueOrderByIdAsc(2L)).thenReturn(List.of(supply));
        when(conditions.findAllByHousingSupplyIdIn(List.of(3L))).thenReturn(List.of(condition));
        when(attachments.findAllByNoticeIdOrderByIdAsc(1L)).thenReturn(List.of(attachment));
        when(media.findAllByHousingIdOrderByIdAsc(2L)).thenReturn(List.of(image));

        var list = service.findOpenHousings();
        var detail = service.findDetail(2L);

        assertThat(list.total()).isEqualTo(1);
        assertThat(list.housings().getFirst().agencyName()).isEqualTo("LH");
        assertThat(detail.housingId()).isEqualTo(2L);
        assertThat(detail.supplies().getFirst().rentalConditions()).hasSize(1);
        assertThat(detail.attachments()).hasSize(1);
        assertThat(detail.media()).hasSize(1);
    }

    @Test
    void rejectsMissingOrClosedHousingAndSupportsNoSupplies() {
        when(housings.findByIdAndActiveTrue(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.findDetail(99L)).isInstanceOf(BusinessException.class);

        Housing closed = housing(notice(NoticeStatus.CLOSED));
        when(housings.findByIdAndActiveTrue(2L)).thenReturn(Optional.of(closed));
        assertThatThrownBy(() -> service.findDetail(2L)).isInstanceOf(BusinessException.class);

        Housing open = housing(notice(NoticeStatus.OPEN));
        when(housings.findByIdAndActiveTrue(2L)).thenReturn(Optional.of(open));
        when(supplies.findAllByHousingIdAndActiveTrueOrderByIdAsc(2L)).thenReturn(List.of());
        when(attachments.findAllByNoticeIdOrderByIdAsc(1L)).thenReturn(List.of());
        when(media.findAllByHousingIdOrderByIdAsc(2L)).thenReturn(List.of());
        assertThat(service.findDetail(2L).supplies()).isEmpty();
    }

    private Notice notice(NoticeStatus status) {
        Agency agency = new Agency("LH", "LH", "https://lh.or.kr");
        Notice notice = withId(new Notice("LH", "n1", "공고", status), 1L);
        notice.assignAgency(agency);
        return notice;
    }

    private Housing housing(Notice notice) {
        Housing housing = withId(new Housing(notice, "h1", "단지", "서울 강남구 테헤란로 1"), 2L);
        housing.enrichAddress("서울 강남구 테헤란로 1", "서울", "강남구", "역삼동", "테헤란로",
                "단지", new BigDecimal("37.5"), new BigDecimal("127.0"));
        return housing;
    }

    private <T> T withId(T entity, Long id) {
        ReflectionTestUtils.setField(entity, "id", id);
        return entity;
    }
}
