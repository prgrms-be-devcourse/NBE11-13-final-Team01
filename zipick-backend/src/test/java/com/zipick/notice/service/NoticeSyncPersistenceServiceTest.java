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
import org.mockito.ArgumentCaptor;
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

    @Test
    void 같은_지역의_유일한_동일_블록만_주소표현이_달라도_같은단지로_판정한다() {
        Notice notice = new Notice("LH", "n", "공고", NoticeStatus.OPEN);
        Housing existing = new Housing(notice, "old", "구리갈매역세권 A-1BL", "경기도 구리시 갈매동 산 14");
        var same = new NoticeHousingResponse("new", "구리갈매역세권 A-1블록 신혼희망타운 행복주택",
                "경기도", "구리시", "경기도 구리시 갈매동 일원 A-1블록", null, null, null, null, null, null, null,
                List.of(), List.of());
        var wrongRegion = new NoticeHousingResponse("other", "구리갈매역세권 A-1블록 신혼희망타운 행복주택",
                "경기도", "남양주시", "경기도 남양주시 일원 A-1블록", null, null, null, null, null, null, null,
                List.of(), List.of());
        var conflicting = new NoticeHousingResponse("other2", "구리갈매역세권 A-1블록 신혼희망타운 행복주택",
                "경기도", "구리시", "경기도 구리시 갈매동 50", null, null, null, null, null, null, null,
                List.of(), List.of());
        assertThat(NoticeSyncPersistenceService.sameVerifiedComplex(existing, same)).isTrue();
        assertThat(NoticeSyncPersistenceService.sameVerifiedComplex(existing, wrongRegion)).isFalse();
        assertThat(NoticeSyncPersistenceService.sameVerifiedComplex(existing, conflicting)).isFalse();
    }

    @Test
    void 원문확인한_기숙사비와_일치할때만_기존_잘못된_월세를_비운다() {
        var supply = new HousingSupply(mock(Housing.class), "GEMINI:test", null);
        ReflectionTestUtils.setField(supply, "monthlyRent", 120000L);
        assertThat(NoticeSyncPersistenceService.verifiedDormitoryFee("{\"verifiedDormitoryFee\":{\"amount\":120000,\"page\":5,\"evidence\":\"기숙사 - - 120,000원\"}}")).isEqualTo(120000L);
        assertThat(NoticeSyncPersistenceService.verifiedDormitoryFee("{\"verifiedDormitoryFee\":{\"amount\":120000}}" )).isNull();
        supply.clearMonthlyRentIfEqualTo(90000L);
        assertThat(supply.getMonthlyRent()).isEqualTo(120000L);
        supply.clearMonthlyRentIfEqualTo(120000L);
        assertThat(supply.getMonthlyRent()).isNull();
    }

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
    void successfulSaveReplacesPriorFingerprintForSameDocument() {
        var history = mock(com.zipick.notice.repository.DocumentParseHistoryRepository.class);
        service.setDocumentHistory(history);
        NoticeResponse original = notice("LH", "n1", "LH", "2026.10.01", null,
                List.of(housing("h1", "서울 강남구 테헤란로 10", supply("s1", "1200"))), List.of());
        var source = new NoticeResponse(original.source(), original.noticeId(), original.statusName(), original.title(),
                original.supplyInstitutionName(), original.previousNoticeId(), original.announcementDate(),
                original.winnerAnnouncementDate(), original.applicationStartDate(), original.applicationEndDate(),
                original.reference(), original.url(), original.pcUrl(), original.mobileUrl(), original.houses(),
                original.attachments(), false,
                List.of(new com.zipick.notice.dto.ParseCheckpoint("LH:document:newhash", "V2", 10, 1)));
        service.synchronize(List.of(new NoticeSyncCommand(source, "{}")), Instant.parse("2026-10-10T00:00:00Z"));
        verify(history).deleteAllByDocumentKeyStartingWithAndParserVersion("LH:document:", "V2");
        verify(history).save(any(com.zipick.notice.domain.DocumentParseHistory.class));
    }

    @Test
    void parseHistoryIsNotRecordedWhenHousingSaveFails() {
        var history = mock(com.zipick.notice.repository.DocumentParseHistoryRepository.class);
        service.setDocumentHistory(history);
        NoticeResponse original = notice("LH", "n1", "LH", "2026.10.01", null,
                List.of(housing("h1", "서울 강남구 테헤란로 10", supply("s1", "1200"))), List.of());
        var checkpoint = new com.zipick.notice.dto.ParseCheckpoint("LH:test", "V2", 10, 1);
        var source = new NoticeResponse(original.source(), original.noticeId(), original.statusName(), original.title(),
                original.supplyInstitutionName(), original.previousNoticeId(), original.announcementDate(),
                original.winnerAnnouncementDate(), original.applicationStartDate(), original.applicationEndDate(),
                original.reference(), original.url(), original.pcUrl(), original.mobileUrl(), original.houses(),
                original.attachments(), false, List.of(checkpoint));
        org.mockito.Mockito.doThrow(new IllegalStateException("save failed")).when(housings).save(any());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.synchronize(
                List.of(new NoticeSyncCommand(source, "{}")), Instant.parse("2026-10-10T00:00:00Z")))
                .isInstanceOf(IllegalStateException.class);
        org.mockito.Mockito.verify(history, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void skippedDocumentPreservesPreviouslySavedHousingAndSupply() {
        Notice existing = withId(new Notice("SH", "n1", "공고", NoticeStatus.OPEN), 1L);
        Housing retained = withId(new Housing(existing, "retained", "기존 주택", "서울 강남구 선릉로 20"), 20L);
        HousingSupply retainedSupply = withId(new HousingSupply(retained, "retained-s", 2L), 30L);
        retained.markMissing(LocalDate.of(2026, 10, 9), 2);
        retainedSupply.markMissing(LocalDate.of(2026, 10, 9), 2);
        when(notices.findAllBySource("SH")).thenReturn(List.of(existing));
        when(housings.findAllByNoticeId(1L)).thenReturn(List.of(retained));
        when(supplies.findAllByHousingId(20L)).thenReturn(List.of(retainedSupply));
        NoticeResponse original = notice("SH", "n1", "SH", "2026.10.01", null,
                List.of(housing("new", "서울 강남구 테헤란로 10", supply("s1", "1200"))), List.of());
        NoticeResponse source = new NoticeResponse(original.source(), original.noticeId(), original.statusName(),
                original.title(), original.supplyInstitutionName(), original.previousNoticeId(),
                original.announcementDate(), original.winnerAnnouncementDate(), original.applicationStartDate(),
                original.applicationEndDate(), original.reference(), original.url(), original.pcUrl(),
                original.mobileUrl(), original.houses(), original.attachments(), true);

        NoticeSyncResult result = service.synchronize(List.of(new NoticeSyncCommand(source, "{}")),
                Instant.parse("2026-10-10T00:00:00Z"));

        assertThat(result.inactiveHousings()).isZero();
        assertThat(result.inactiveSupplies()).isZero();
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

    @Test
    void updatesExistingExpiredShDatesButDoesNotCreateNewExpiredNotice() {
        Notice existing = withId(new Notice("SH", "existing", "공고", NoticeStatus.OPEN), 10L);
        when(notices.findAllBySource("SH")).thenReturn(List.of(existing));
        NoticeSyncCommand update = new NoticeSyncCommand(notice(
                "SH", "existing", "SH", "2026-08-03", "2026-08-06", List.of(), List.of()), "{}");
        NoticeSyncCommand old = new NoticeSyncCommand(notice(
                "SH", "new-old", "SH", "2026-08-03", "2026-08-06", List.of(), List.of()), "{}");

        NoticeSyncResult result = service.synchronize(List.of(update, old), Instant.parse("2026-10-27T00:00:00Z"));

        assertThat(result.updatedNotices()).isEqualTo(1);
        assertThat(result.createdNotices()).isZero();
        assertThat(existing.getStatus()).isEqualTo(NoticeStatus.CLOSED);
        assertThat(existing.getApplyEndAt()).isEqualTo(Instant.parse("2026-08-06T14:59:59.999999999Z"));
    }

    @Test
    void aiDetailMergeDoesNotDeactivateExistingRowsMissingFromPartialResult() {
        Notice existing = withId(new Notice("SH", "n1", "공고", NoticeStatus.OPEN), 1L);
        Housing previousHousing = withId(
                new Housing(existing, "previous", "기존단지", "서울시 강남구 선릉로 20"), 20L);
        HousingSupply previousSupply = withId(new HousingSupply(previousHousing, "previous-s", 2L), 30L);
        LocalDate previousDay = LocalDate.of(2026, 10, 9);
        previousHousing.markMissing(previousDay, 2);
        previousSupply.markMissing(previousDay, 2);
        when(notices.findById(1L)).thenReturn(Optional.of(existing));
        when(housings.findAllByNoticeId(1L)).thenReturn(List.of(previousHousing));
        when(supplies.findAllByHousingId(20L)).thenReturn(List.of(previousSupply));

        NoticeResponse partialAiResult = notice(
                "SH", "n1", "SH", null, null,
                List.of(housing("new", "서울특별시 강남구 테헤란로 10", supply("new-s", "1"))),
                List.of()
        );

        service.synchronizeDetails(1L, partialAiResult, Instant.parse("2026-10-10T00:00:00Z"));

        assertThat(previousHousing.isActive()).isTrue();
        assertThat(previousSupply.isActive()).isTrue();
    }

    /**
     * AI 상세 수집이 <b>이미 지오코딩을 마친</b> 주택을 다시 저장해도 지역이 깨지지 않아야 한다.
     *
     * <p>실제로 깨졌던 경로다. 수집 경로가 중복 비교용 축약형을 {@code normalized_address} 에 써 넣어
     * 지오코딩이 확정한 "경북 영주시 …" 를 덮었고, 주소에 시도가 없는 경상북도개발공사(GBDC) 공고에서는
     * 시도를 복구할 근거가 사라져 {@code province_name} 이 비었다.
     */
    @Test
    void aiDetailMergeKeepsGeocodedRegionOfExistingHousing() {
        Notice existing = withId(new Notice("GBDC", "n1", "공고", NoticeStatus.OPEN), 1L);
        Housing geocoded = withId(
                new Housing(existing, "DETAIL:ADDRESS:abc", "더 포레", "영주시 대동로34번길 23-3"), 307L);
        geocoded.enrichAddress("경북 영주시 대동로34번길 23-3", "경북", "영주시", null, "대동로34번길", null,
                new BigDecimal("36.81"), new BigDecimal("128.60"));
        when(notices.findById(1L)).thenReturn(Optional.of(existing));
        when(housings.findAllByNoticeId(1L)).thenReturn(List.of(geocoded));

        // AI 상세 결과는 시도 자리에 시가 밀려 들어온 값을 넘긴다
        NoticeResponse aiResult = notice(
                "GBDC", "n1", "경상북도개발공사", null, null,
                List.of(shiftedRegionHousing("DETAIL:ADDRESS:abc", "영주시 대동로34번길 23-3", "영주시", "대동로34번길")),
                List.of()
        );

        service.synchronizeDetails(1L, aiResult, Instant.parse("2026-10-30T00:00:00Z"));

        assertThat(geocoded.getProvinceName()).isEqualTo("경상북도");
        assertThat(geocoded.getDistrictName()).isEqualTo("영주시");
        assertThat(geocoded.getNormalizedAddress()).isEqualTo("경북 영주시 대동로34번길 23-3");
    }

    @Test
    void aiDetailMergeKeepsGeocodedRegionForCounty() {
        Notice existing = withId(new Notice("GBDC", "n2", "공고", NoticeStatus.OPEN), 2L);
        Housing geocoded = withId(
                new Housing(existing, "DETAIL:ADDRESS:def", "중리마을", "칠곡군 석적읍 중리3길 10"), 318L);
        geocoded.enrichAddress("경북 칠곡군 석적읍 중리3길 10", "경북", "칠곡군", "석적읍", "중리3길", null,
                new BigDecimal("36.03"), new BigDecimal("128.44"));
        when(notices.findById(2L)).thenReturn(Optional.of(existing));
        when(housings.findAllByNoticeId(2L)).thenReturn(List.of(geocoded));

        service.synchronizeDetails(2L, notice(
                "GBDC", "n2", "경상북도개발공사", null, null,
                List.of(shiftedRegionHousing("DETAIL:ADDRESS:def", "칠곡군 석적읍 중리3길 10", "칠곡군", "석적읍")),
                List.of()
        ), Instant.parse("2026-10-30T00:00:00Z"));

        assertThat(geocoded.getProvinceName()).isEqualTo("경상북도");
        assertThat(geocoded.getDistrictName()).isEqualTo("칠곡군");
    }

    /**
     * 신규 주택에는 표준 주소가 아직 없다. 중복 비교용 축약형을 대신 넣지 않는다 —
     * 그 값이 들어가면 다음 지오코딩 결과를 덮지는 않지만, 사용자에게 보이는 주소가 되고
     * 재수집 때 시도 복구 근거를 지운다.
     */
    @Test
    void newHousingDoesNotStoreDedupKeyAsNormalizedAddress() {
        when(notices.findAllBySource("LH")).thenReturn(List.of());
        NoticeSyncCommand command = new NoticeSyncCommand(notice(
                "LH", "n3", "한국토지주택공사", "2026.10.01", "20261020",
                List.of(housing("h1", "서울특별시 강남구 테헤란로 10", supply("s1", "1"))),
                List.of()), "{}");

        service.synchronize(List.of(command), Instant.parse("2026-10-30T00:00:00Z"));

        ArgumentCaptor<Housing> saved = ArgumentCaptor.forClass(Housing.class);
        verify(housings).save(saved.capture());
        assertThat(saved.getValue().getNormalizedAddress()).isNull();
    }

    private NoticeHousingResponse shiftedRegionHousing(
            String key, String address, String province, String district
    ) {
        return new NoticeHousingResponse(
                key, "더 포레", province, district, address,
                null, null, null, null, null, null, null,
                List.of(supply(key + "-s", "1")), List.of()
        );
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
