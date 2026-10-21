package com.zipick.notice.service;

import com.zipick.notice.domain.Agency;
import com.zipick.notice.domain.Housing;
import com.zipick.notice.domain.HousingMedia;
import com.zipick.notice.domain.HousingSupply;
import com.zipick.notice.domain.HousingSupplyCondition;
import com.zipick.notice.domain.Notice;
import com.zipick.notice.domain.NoticeAttachment;
import com.zipick.notice.domain.NoticeStatus;
import com.zipick.notice.dto.HousingMediaResponse;
import com.zipick.notice.dto.NoticeAttachmentResponse;
import com.zipick.notice.dto.NoticeHousingResponse;
import com.zipick.notice.dto.NoticeResponse;
import com.zipick.notice.dto.NoticeSupplyConditionResponse;
import com.zipick.notice.dto.NoticeSupplyResponse;
import com.zipick.notice.repository.AgencyRepository;
import com.zipick.notice.repository.HousingMediaRepository;
import com.zipick.notice.repository.HousingRepository;
import com.zipick.notice.repository.HousingSupplyConditionRepository;
import com.zipick.notice.repository.HousingSupplyRepository;
import com.zipick.notice.repository.NoticeAttachmentRepository;
import com.zipick.notice.repository.NoticeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NoticeSyncPersistenceServiceTest {

    private final AgencyRepository agencies = mock(AgencyRepository.class);
    private final NoticeRepository notices = mock(NoticeRepository.class);
    private final NoticeAttachmentRepository attachments = mock(NoticeAttachmentRepository.class);
    private final HousingRepository housings = mock(HousingRepository.class);
    private final HousingSupplyRepository supplies = mock(HousingSupplyRepository.class);
    private final HousingSupplyConditionRepository conditions = mock(HousingSupplyConditionRepository.class);
    private final HousingMediaRepository media = mock(HousingMediaRepository.class);
    private NoticeSyncPersistenceService service;

    @BeforeEach
    void setUp() {
        service = new NoticeSyncPersistenceService(
                agencies, notices, attachments, housings, supplies, conditions, media,
                new NoticeStatusEvaluator(), new NoticeTypeClassifier()
        );
        when(agencies.findByCode(anyString())).thenReturn(Optional.empty());
        when(agencies.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(notices.save(any())).thenAnswer(invocation -> withId(invocation.getArgument(0), 1L));
        when(attachments.findByNoticeIdAndSourceKey(anyLong(), anyString())).thenReturn(Optional.empty());
        when(housings.findAllByNoticeId(anyLong())).thenReturn(List.of());
        when(housings.save(any())).thenAnswer(invocation -> withId(invocation.getArgument(0), 2L));
        when(supplies.findAllByHousingId(anyLong())).thenReturn(List.of());
        when(supplies.save(any())).thenAnswer(invocation -> withId(invocation.getArgument(0), 3L));
        when(conditions.findByHousingSupplyIdAndConditionCode(anyLong(), anyString()))
                .thenReturn(Optional.empty());
        when(media.findByHousingIdAndSourceKey(anyLong(), anyString())).thenReturn(Optional.empty());
    }

    @Test
    void createsCompleteNoticeGraphAndParsesExternalValues() {
        when(notices.findAllBySource("LH")).thenReturn(List.of());
        NoticeSyncCommand command = new NoticeSyncCommand(notice(
                "LH", "n1", "한국토지주택공사", "2026.10.01", "20261020",
                List.of(housing("h1", "서울특별시 강남구 테헤란로 10", supply("s1", "1,200"))),
                List.of(
                        new NoticeAttachmentResponse("a1", "NOTICE", "공고.pdf", "https://lh.or.kr/a.pdf", "{}"),
                        new NoticeAttachmentResponse("", "NOTICE", "무시.pdf", "https://lh.or.kr/b.pdf", "{}")
                )
        ), "{\"raw\":true}");

        NoticeSyncResult result = service.synchronize(List.of(command), Instant.parse("2026-10-10T00:00:00Z"));

        assertThat(result.createdNotices()).isEqualTo(1);
        assertThat(result.synchronizedHousings()).isEqualTo(1);
        assertThat(result.synchronizedSupplies()).isEqualTo(1);
        verify(attachments).save(any(NoticeAttachment.class));
        verify(media).save(any(HousingMedia.class));
        verify(conditions).save(any(HousingSupplyCondition.class));
    }

    @Test
    void updatesExistingGraphByEquivalentAddressAndMarksRemovedChildrenInactive() {
        Instant collectedAt = Instant.parse("2026-10-10T00:00:00Z");
        LocalDate previousDay = LocalDate.of(2026, 10, 9);
        Notice existing = withId(new Notice("LH", "n1", "old", NoticeStatus.OPEN), 1L);
        Housing matched = withId(new Housing(existing, "old-key", "단지", "서울 강남구 테헤란로 10"), 2L);
        Housing removed = withId(new Housing(existing, "removed", "삭제", "서울 강남구 선릉로 20"), 20L);
        removed.markMissing(previousDay, 2);
        HousingSupply existingSupply = withId(new HousingSupply(matched, "s1", 1L), 3L);
        HousingSupply removedSupply = withId(new HousingSupply(removed, "removed-s", 2L), 30L);
        removedSupply.markMissing(previousDay, 2);
        NoticeAttachment existingAttachment = new NoticeAttachment(
                existing, "a1", "NOTICE", "https://lh.or.kr/old.pdf"
        );
        HousingMedia existingMedia = new HousingMedia(
                matched, "m1", "FLOOR_PLAN", "https://lh.or.kr/old.png"
        );
        HousingSupplyCondition existingCondition = new HousingSupplyCondition(existingSupply, "BASIC", "기존");

        when(notices.findAllBySource("LH")).thenReturn(List.of(existing));
        when(housings.findAllByNoticeId(1L)).thenReturn(List.of(matched, removed));
        when(supplies.findAllByHousingId(2L)).thenReturn(List.of(existingSupply));
        when(supplies.findAllByHousingId(20L)).thenReturn(List.of(removedSupply));
        when(attachments.findByNoticeIdAndSourceKey(1L, "a1")).thenReturn(Optional.of(existingAttachment));
        when(media.findByHousingIdAndSourceKey(2L, "m1")).thenReturn(Optional.of(existingMedia));
        when(conditions.findByHousingSupplyIdAndConditionCode(3L, "BASIC"))
                .thenReturn(Optional.of(existingCondition));

        NoticeHousingResponse updatedHousing = housing(
                "new-key", "서울특별시 강남구 테헤란로 10", supply("s1", "invalid")
        );
        NoticeResponse source = notice(
                "LH", "n1", "LH", "invalid-date", null,
                List.of(updatedHousing),
                List.of(new NoticeAttachmentResponse(
                        "a1", "NOTICE", "새공고.pdf", "https://lh.or.kr/new.pdf", "{}"
                ))
        );

        NoticeSyncResult result = service.synchronize(
                List.of(new NoticeSyncCommand(source, "{}")), collectedAt
        );

        assertThat(result.updatedNotices()).isEqualTo(1);
        assertThat(result.inactiveHousings()).isEqualTo(1);
        assertThat(result.inactiveSupplies()).isEqualTo(1);
        assertThat(matched.getSourceHousingKey()).isEqualTo("old-key");
        assertThat(existingAttachment.getFileName()).isEqualTo("새공고.pdf");
        assertThat(existingMedia.getFileName()).isEqualTo("도면.png");
        assertThat(existingCondition.getConditionName()).isEqualTo("기본조건");
    }

    @Test
    void completedShSourceClosesMissingNoticeImmediately() {
        LocalDate previousDay = LocalDate.of(2026, 10, 9);
        Notice missing = withId(new Notice("SH", "missing", "사라진 공고", NoticeStatus.OPEN), 10L);
        Housing housing = withId(new Housing(missing, "h", "단지", "서울시 강남구 테헤란로 1"), 11L);
        housing.markMissing(previousDay, 2);
        HousingSupply supply = withId(new HousingSupply(housing, "s", 1L), 12L);
        supply.markMissing(previousDay, 2);
        when(notices.findAllBySource("SH")).thenReturn(List.of(missing));
        when(housings.findAllByNoticeId(10L)).thenReturn(List.of(housing));
        when(supplies.findAllByHousingId(11L)).thenReturn(List.of(supply));

        NoticeSyncResult result = service.synchronize(
                List.of(), Instant.parse("2026-10-10T00:00:00Z"), Set.of("SH")
        );

        assertThat(result.missingClosedNotices()).isEqualTo(1);
        assertThat(result.inactiveHousings()).isEqualTo(1);
        assertThat(result.inactiveSupplies()).isEqualTo(1);
        assertThat(missing.getStatus()).isEqualTo(NoticeStatus.CLOSED);
    }

    private NoticeResponse notice(
            String source, String id, String agency, String start, String end,
            List<NoticeHousingResponse> housingRows, List<NoticeAttachmentResponse> attachmentRows
    ) {
        return new NoticeResponse(
                source, id, "공고중", "국민임대 모집", agency, null,
                "2026-10-01", "2026-12-01", start, end, "참고",
                "https://lh.or.kr/notice", null, null, housingRows, attachmentRows
        );
    }

    private NoticeHousingResponse housing(String key, String address, NoticeSupplyResponse supply) {
        return new NoticeHousingResponse(
                key, "테스트단지", "서울특별시", "강남구", address,
                "서울 강남구 역삼동 1", "서울 강남구 테헤란로 10",
                "테헤란로", "역삼동", "pnu", "지역난방", "1,000",
                List.of(supply), List.of(new HousingMediaResponse(
                        "m1", "FLOOR_PLAN", "도면.png", "https://lh.or.kr/plan.png", "{}"
                ))
        );
    }

    private NoticeSupplyResponse supply(String key, String houseCount) {
        return new NoticeSupplyResponse(
                key, 7L, "아파트", "국민임대", "101동", "101호", 1, 2,
                new BigDecimal("39.50"), 3L, houseCount, 10_000_000L,
                1_000_000L, 2_000_000L, 7_000_000L, 300_000L, "{}", 1,
                "공급중", "강남역", new BigDecimal("0.5"), true, true,
                100, false, 0, List.of(
                        new NoticeSupplyConditionResponse("BASIC", "기본조건", 10_000_000L, 300_000L, "{}"),
                        new NoticeSupplyConditionResponse("", "무시", null, null, null)
                )
        );
    }

    private <T> T withId(T entity, Long id) {
        ReflectionTestUtils.setField(entity, "id", id);
        return entity;
    }
}
