package com.zipick.interest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zipick.global.error.BusinessException;
import com.zipick.global.error.ErrorCode;
import com.zipick.interest.domain.InterestNotice;
import com.zipick.interest.dto.InterestNoticeRequest;
import com.zipick.interest.dto.InterestNoticeResponse;
import com.zipick.interest.dto.NoticeSummary;
import com.zipick.interest.dto.ScheduleEventResponse;
import com.zipick.interest.dto.ScheduleEventResponse.Kind;
import com.zipick.interest.repository.InterestNoticeRepository;
import com.zipick.interest.repository.NoticeSummaryQueryRepository;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 관심 공고 저장·목록·일정. 일정은 공고 날짜에서 만들어지고, 지워진 공고는 목록에서 숨긴다. */
@ExtendWith(MockitoExtension.class)
class InterestNoticeServiceTest {

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Seoul"));

    @Mock InterestNoticeRepository repository;
    @Mock NoticeSummaryQueryRepository noticeRepository;
    @InjectMocks InterestNoticeService service;

    private static NoticeSummary notice(long id, LocalDate announced, LocalDate start, LocalDate end, LocalDate winner) {
        return new NoticeSummary(id, "공고 " + id, "행복주택", null, null, "LH", "https://lh.or.kr/" + id,
                announced, start, end, winner, 3, List.of("경상북도 구미시"));
    }

    private static InterestNotice saved(long userId, long noticeId) {
        return InterestNotice.builder().userId(userId).noticeId(noticeId).memo("메모").build();
    }

    @Test
    void 목록은_공고가_지워진_항목을_숨기고_마감까지_남은_날을_계산한다() {
        when(repository.findAllByUserIdOrderByIdDesc(1L)).thenReturn(List.of(saved(1, 10), saved(1, 99)));
        when(noticeRepository.findByIds(List.of(10L, 99L)))
                .thenReturn(Map.of(10L, notice(10, null, null, TODAY.plusDays(3), null)));

        List<InterestNoticeResponse> list = service.list(1L);

        assertThat(list).hasSize(1);
        assertThat(list.get(0).notice().noticeId()).isEqualTo(10L);
        assertThat(list.get(0).dDay()).isEqualTo(3);
        assertThat(list.get(0).memo()).isEqualTo("메모");
    }

    @Test
    void 저장된_공고_id만_돌려준다() {
        when(repository.findAllByUserIdOrderByIdDesc(1L)).thenReturn(List.of(saved(1, 10), saved(1, 11)));
        assertThat(service.savedNoticeIds(1L)).containsExactly(10L, 11L);
    }

    @Test
    void 없는_공고는_저장할_수_없다() {
        when(noticeRepository.exists(5L)).thenReturn(false);
        assertThatThrownBy(() -> service.save(1L, new InterestNoticeRequest(5L, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.ANNOUNCEMENT_NOT_FOUND);
        verify(repository, never()).save(any());
    }

    @Test
    void 처음_저장하면_새로_만든다() {
        when(noticeRepository.exists(10L)).thenReturn(true);
        when(repository.findByUserIdAndNoticeId(1L, 10L)).thenReturn(Optional.empty());
        when(repository.save(any(InterestNotice.class))).thenAnswer(inv -> inv.getArgument(0));
        when(noticeRepository.findByIds(anyList())).thenReturn(Map.of(10L, notice(10, null, null, TODAY, null)));

        InterestNoticeResponse r = service.save(1L, new InterestNoticeRequest(10L, "꼭 신청"));

        assertThat(r.memo()).isEqualTo("꼭 신청");
        assertThat(r.dDay()).isZero();
        verify(repository).save(any(InterestNotice.class));
    }

    @Test
    void 이미_저장한_공고는_메모만_바꾼다() {
        InterestNotice existing = saved(1, 10);
        when(noticeRepository.exists(10L)).thenReturn(true);
        when(repository.findByUserIdAndNoticeId(1L, 10L)).thenReturn(Optional.of(existing));
        when(noticeRepository.findByIds(anyList())).thenReturn(Map.of(10L, notice(10, null, null, null, null)));

        InterestNoticeResponse r = service.save(1L, new InterestNoticeRequest(10L, "바뀐 메모"));

        assertThat(existing.getMemo()).isEqualTo("바뀐 메모");
        assertThat(r.dDay()).isNull();   // 마감일이 없는 공고
        verify(repository, never()).save(any());
    }

    @Test
    void 해제는_저장돼_있을_때만_지운다() {
        InterestNotice existing = saved(1, 10);
        when(repository.findByUserIdAndNoticeId(1L, 10L)).thenReturn(Optional.of(existing));
        when(repository.findByUserIdAndNoticeId(1L, 11L)).thenReturn(Optional.empty());

        service.remove(1L, 10L);
        service.remove(1L, 11L);

        verify(repository).delete(existing);
    }

    @Test
    void 일정은_공고_날짜_네_종류를_날짜순으로_만들고_없는_날짜는_건너뛴다() {
        when(repository.findAllByUserIdOrderByIdDesc(1L)).thenReturn(List.of(saved(1, 10)));
        when(noticeRepository.findByIds(List.of(10L))).thenReturn(Map.of(10L,
                notice(10, TODAY.minusDays(5), TODAY.plusDays(1), TODAY.plusDays(4), null)));

        List<ScheduleEventResponse> events = service.schedule(1L, null, null);

        assertThat(events).extracting(ScheduleEventResponse::kind)
                .containsExactly(Kind.ANNOUNCED, Kind.APPLY_START, Kind.APPLY_END);
        assertThat(events).extracting(ScheduleEventResponse::dDay).containsExactly(-5, 1, 4);
        assertThat(events.get(0).id()).isEqualTo("announced-10");
    }

    @Test
    void 일정은_기간으로_거를_수_있다() {
        when(repository.findAllByUserIdOrderByIdDesc(1L)).thenReturn(List.of(saved(1, 10)));
        when(noticeRepository.findByIds(List.of(10L))).thenReturn(Map.of(10L,
                notice(10, TODAY.minusDays(5), TODAY.plusDays(1), TODAY.plusDays(4), TODAY.plusDays(30))));

        List<ScheduleEventResponse> events = service.schedule(1L, TODAY, TODAY.plusDays(10));

        assertThat(events).extracting(ScheduleEventResponse::kind).containsExactly(Kind.APPLY_START, Kind.APPLY_END);
    }

    @Test
    void 남은_날_계산() {
        assertThat(InterestNoticeService.dDay(TODAY, TODAY.plusDays(2))).isEqualTo(2);
        assertThat(InterestNoticeService.dDay(TODAY, TODAY.minusDays(1))).isEqualTo(-1);
        assertThat(InterestNoticeService.dDay(TODAY, null)).isNull();
    }
}
