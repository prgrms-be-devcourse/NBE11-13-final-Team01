package com.zipick.notice.service;

import com.zipick.global.error.BusinessException;
import com.zipick.notice.domain.Agency;
import com.zipick.notice.domain.Housing;
import com.zipick.notice.domain.HousingMedia;
import com.zipick.notice.domain.HousingSupply;
import com.zipick.notice.domain.HousingSupplyCondition;
import com.zipick.notice.domain.Notice;
import com.zipick.notice.domain.NoticeAttachment;
import com.zipick.housing.dto.HouseFilter;
import com.zipick.notice.domain.NoticeStatus;
import com.zipick.notice.dto.MapHousingSummaryResponse;
import com.zipick.notice.repository.HousingMediaRepository;
import com.zipick.notice.repository.HousingPinQueryRepository;
import com.zipick.notice.repository.HousingRepository;
import com.zipick.notice.repository.HousingSupplyConditionRepository;
import com.zipick.notice.repository.HousingSupplyRepository;
import com.zipick.notice.repository.NoticeAttachmentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MapHousingQueryServiceTest {

    private final HousingRepository housings = mock(HousingRepository.class);
    private final HousingSupplyRepository supplies = mock(HousingSupplyRepository.class);
    private final HousingSupplyConditionRepository conditions = mock(HousingSupplyConditionRepository.class);
    private final NoticeAttachmentRepository attachments = mock(NoticeAttachmentRepository.class);
    private final HousingMediaRepository media = mock(HousingMediaRepository.class);
    private final HousingPinQueryRepository pins = mock(HousingPinQueryRepository.class);
    private final MapHousingQueryService service = new MapHousingQueryService(
            housings, pins, supplies, conditions, attachments, media
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

    @Test
    void allowsUpcomingDetailSoAnonymousUsersCanOpenListedNotices() {
        // 목록(/api/v1/houses)은 공고 예정 + 접수 중을 보여 준다.
        // 공개 상세가 접수 중만 허용하면 목록에서 누른 공고 예정 주택이 404가 된다.
        Housing upcoming = housing(notice(NoticeStatus.UPCOMING));
        when(housings.findByIdAndActiveTrue(2L)).thenReturn(Optional.of(upcoming));
        when(supplies.findAllByHousingIdAndActiveTrueOrderByIdAsc(2L)).thenReturn(List.of());
        when(attachments.findAllByNoticeIdOrderByIdAsc(1L)).thenReturn(List.of());
        when(media.findAllByHousingIdOrderByIdAsc(2L)).thenReturn(List.of());

        var detail = service.findDetail(2L);

        assertThat(detail.housingId()).isEqualTo(2L);
        assertThat(detail.status()).isEqualTo(NoticeStatus.UPCOMING.name());
    }

    @Test
    void keepsMapPinListOpenOnlyEvenThoughDetailAllowsUpcoming() {
        // 상세 범위를 넓혀도 지도 핀 목록은 접수 중만 조회한다(핀 개수가 달라지면 안 된다).
        when(housings.findAllByActiveTrueAndLatitudeIsNotNullAndLongitudeIsNotNullAndNoticeStatusOrderByIdAsc(
                NoticeStatus.OPEN)).thenReturn(List.of(housing(notice(NoticeStatus.OPEN))));

        assertThat(service.findOpenHousings().total()).isEqualTo(1);
        verify(housings).findAllByActiveTrueAndLatitudeIsNotNullAndLongitudeIsNotNullAndNoticeStatusOrderByIdAsc(
                NoticeStatus.OPEN);
    }

    @Test
    void rejectsCancelledDetail() {
        Housing cancelled = housing(notice(NoticeStatus.CANCELLED));
        when(housings.findByIdAndActiveTrue(2L)).thenReturn(Optional.of(cancelled));

        assertThatThrownBy(() -> service.findDetail(2L)).isInstanceOf(BusinessException.class);
    }

    @Test
    void exposesApplyScheduleAsSeoulDates() {
        // 접수 일정은 Instant 로 저장되지만 화면은 날짜만 쓴다. 한국 시간 기준으로 내려야 D-day가 맞는다.
        // 2026-10-15 23:30 KST = 2026-10-15T14:30Z. UTC로 자르면 하루가 밀린다.
        Notice notice = notice(NoticeStatus.OPEN);
        notice.synchronize(notice.getAgency(), null, "공고", null, null, null, NoticeStatus.OPEN, null, null,
                null, null, Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-10-15T14:30:00Z"),
                null, null, null, null, "{}", Instant.now());
        Housing housing = housing(notice);
        when(housings.findByIdAndActiveTrue(2L)).thenReturn(Optional.of(housing));
        when(supplies.findAllByHousingIdAndActiveTrueOrderByIdAsc(2L)).thenReturn(List.of());
        when(attachments.findAllByNoticeIdOrderByIdAsc(1L)).thenReturn(List.of());
        when(media.findAllByHousingIdOrderByIdAsc(2L)).thenReturn(List.of());

        var detail = service.findDetail(2L);

        assertThat(detail.applyStartAt())
                .isEqualTo(LocalDate.ofInstant(Instant.parse("2026-10-01T00:00:00Z"), ZoneId.of("Asia/Seoul")));
        assertThat(detail.applyEndAt()).isEqualTo(LocalDate.of(2026, 10, 15));
    }

    @Test
    void keepsApplyScheduleNullWhenNoticeHasNoDates() {
        // 일정이 없으면 null 그대로 내려 화면이 임의 날짜를 만들지 않게 한다.
        Housing housing = housing(notice(NoticeStatus.UPCOMING));
        when(housings.findByIdAndActiveTrue(2L)).thenReturn(Optional.of(housing));
        when(supplies.findAllByHousingIdAndActiveTrueOrderByIdAsc(2L)).thenReturn(List.of());
        when(attachments.findAllByNoticeIdOrderByIdAsc(1L)).thenReturn(List.of());
        when(media.findAllByHousingIdOrderByIdAsc(2L)).thenReturn(List.of());

        var detail = service.findDetail(2L);

        assertThat(detail.applyStartAt()).isNull();
        assertThat(detail.applyEndAt()).isNull();
    }

    @Test
    void mapsPinsToSummariesAndCountsHousings() {
        // 지도 목록은 주택 단위라 total 이 곧 주택 수다. 호실 수에 따라 달라지지 않는다.
        Housing first = housing(notice(NoticeStatus.OPEN));
        Housing second = withId(new Housing(notice(NoticeStatus.UPCOMING), "h2", "단지2", "서울 마포구 1"), 5L);
        second.enrichAddress("서울 마포구 1", "서울", "마포구", "공덕동", "마포대로",
                "단지2", new BigDecimal("37.6"), new BigDecimal("126.9"));
        HouseFilter filter = HouseFilter.openOnly();
        when(pins.findPins(filter)).thenReturn(List.of(first, second));

        var list = service.findHousings(filter);

        assertThat(list.total()).isEqualTo(2);
        assertThat(list.housings()).extracting(MapHousingSummaryResponse::housingId).containsExactly(2L, 5L);
        assertThat(list.housings()).extracting(MapHousingSummaryResponse::status)
                .containsExactly(NoticeStatus.OPEN.name(), NoticeStatus.UPCOMING.name());
        // 행정구역별 군집화가 주소 문자열을 쪼개지 않도록 정규화된 시도·시군구를 그대로 내려준다.
        // 픽스처는 Kakao 축약형("서울")을 넣지만 Housing 이 표준명으로 저장한다(RegionNormalizer).
        assertThat(list.housings()).extracting(MapHousingSummaryResponse::province, MapHousingSummaryResponse::district)
                .containsExactly(tuple("서울특별시", "강남구"), tuple("서울특별시", "마포구"));
        // 같은 좌표에 겹친 주택을 공고 단위로 구분하려면 공고 id 가 필요하다 (locationGroup)
        assertThat(list.housings()).extracting(MapHousingSummaryResponse::noticeId).containsOnly(1L);
    }

    @Test
    void returnsEmptyListWhenNoHousingMatches() {
        HouseFilter filter = HouseFilter.openOnly();
        when(pins.findPins(filter)).thenReturn(List.of());

        var list = service.findHousings(filter);

        assertThat(list.total()).isZero();
        assertThat(list.housings()).isEmpty();
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
