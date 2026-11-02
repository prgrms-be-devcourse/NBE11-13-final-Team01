package com.zipick.interest.service;

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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관심 공고 저장·목록·일정.
 * 일정은 공고의 날짜(공고일·접수 시작·접수 마감·당첨 발표)를 그대로 쓴다. 따로 입력받지 않는다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InterestNoticeService {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final InterestNoticeRepository repository;
    private final NoticeSummaryQueryRepository noticeRepository;

    public List<InterestNoticeResponse> list(Long userId) {
        List<InterestNotice> saved = repository.findAllByUserIdOrderByIdDesc(userId);
        Map<Long, NoticeSummary> notices = noticeRepository.findByIds(saved.stream().map(InterestNotice::getNoticeId).toList());
        LocalDate today = LocalDate.now(SEOUL);
        return saved.stream()
                .filter(s -> notices.containsKey(s.getNoticeId()))   // 공고가 지워졌으면 숨긴다
                .map(s -> {
                    NoticeSummary n = notices.get(s.getNoticeId());
                    return new InterestNoticeResponse(s.getId(), n, s.getMemo(), dDay(today, n.applyEndAt()),
                            s.getCreatedAt() == null ? null : s.getCreatedAt().toLocalDate());
                })
                .toList();
    }

    public List<Long> savedNoticeIds(Long userId) {
        return repository.findAllByUserIdOrderByIdDesc(userId).stream().map(InterestNotice::getNoticeId).toList();
    }

    @Transactional
    public InterestNoticeResponse save(Long userId, InterestNoticeRequest r) {
        if (!noticeRepository.exists(r.noticeId())) throw new BusinessException(ErrorCode.ANNOUNCEMENT_NOT_FOUND);
        InterestNotice saved = repository.findByUserIdAndNoticeId(userId, r.noticeId())
                .map(s -> { s.updateMemo(r.memo()); return s; })
                .orElseGet(() -> repository.save(InterestNotice.builder().userId(userId).noticeId(r.noticeId()).memo(r.memo()).build()));
        NoticeSummary n = noticeRepository.findByIds(List.of(r.noticeId())).get(r.noticeId());
        return new InterestNoticeResponse(saved.getId(), n, saved.getMemo(), dDay(LocalDate.now(SEOUL), n.applyEndAt()), LocalDate.now(SEOUL));
    }

    @Transactional
    public void remove(Long userId, Long noticeId) {
        repository.findByUserIdAndNoticeId(userId, noticeId).ifPresent(repository::delete);
    }

    /** 관심 공고의 일정. from~to 를 주면 그 범위만, 없으면 전체 */
    public List<ScheduleEventResponse> schedule(Long userId, LocalDate from, LocalDate to) {
        List<ScheduleEventResponse> out = new ArrayList<>();
        LocalDate today = LocalDate.now(SEOUL);
        for (InterestNoticeResponse s : list(userId)) {
            NoticeSummary n = s.notice();
            add(out, n, Kind.ANNOUNCED, n.announcedAt(), today);
            add(out, n, Kind.APPLY_START, n.applyStartAt(), today);
            add(out, n, Kind.APPLY_END, n.applyEndAt(), today);
            add(out, n, Kind.WINNER, n.winnerAnnouncedAt(), today);
        }
        return out.stream()
                .filter(e -> (from == null || !e.date().isBefore(from)) && (to == null || !e.date().isAfter(to)))
                .sorted(Comparator.comparing(ScheduleEventResponse::date).thenComparing(ScheduleEventResponse::noticeId))
                .toList();
    }

    private static void add(List<ScheduleEventResponse> out, NoticeSummary n, Kind kind, LocalDate date, LocalDate today) {
        if (date == null) return;
        out.add(new ScheduleEventResponse(kind.name().toLowerCase() + "-" + n.noticeId(), n.noticeId(), n.title(), kind, date,
                dDay(today, date), n.detailUrl()));
    }

    static Integer dDay(LocalDate today, LocalDate date) {
        return date == null ? null : (int) ChronoUnit.DAYS.between(today, date);
    }
}
