package com.zipick.admin.notice;

import com.zipick.admin.dto.PageResponse;
import com.zipick.global.error.BusinessException;
import com.zipick.global.error.ErrorCode;
import com.zipick.infrastructure.domain.HousingInfraSnapshot;
import com.zipick.infrastructure.repository.HousingInfraPlaceRepository;
import com.zipick.infrastructure.repository.HousingInfraSnapshotRepository;
import com.zipick.notice.domain.Housing;
import com.zipick.notice.domain.Notice;
import com.zipick.notice.domain.NoticeStatus;
import com.zipick.notice.domain.NoticeStatusReason;
import com.zipick.notice.repository.HousingRepository;
import com.zipick.notice.repository.NoticeRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** 운영자 화면 "공고·주택 관리": 공고 상태 변경, 좌표 미해결 주택 보정, 주택 숨김, 인프라 재수집 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminNoticeService {

    private final NoticeRepository notices;
    private final HousingRepository housings;
    private final HousingInfraSnapshotRepository snapshots;
    private final HousingInfraPlaceRepository places;

    public record NoticeRow(Long id, String source, String externalNoticeId, String title, String agency,
                            String status, String statusReason, String region, String noticeType, String rentalType,
                            Instant applyStartAt, Instant applyEndAt, String detailUrl, Instant lastSeenAt) {
    }

    public record HousingRow(Long id, Long noticeId, String noticeTitle, String name, String address,
                             BigDecimal latitude, BigDecimal longitude, boolean active, boolean hasInfra,
                             Instant infraCollectedAt) {
    }

    public PageResponse<NoticeRow> notices(List<NoticeStatus> statuses, String source, String q, int page, int size) {
        Specification<Notice> spec = (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (statuses != null && !statuses.isEmpty()) ps.add(root.get("status").in(statuses));
            if (source != null && !source.isBlank()) ps.add(cb.equal(root.get("source"), source.trim()));
            if (q != null && !q.isBlank()) {
                String like = "%" + q.trim().toLowerCase() + "%";
                ps.add(cb.or(
                        cb.like(cb.lower(root.get("title")), like),
                        cb.like(cb.lower(root.get("externalNoticeId")), like)
                ));
            }
            return cb.and(ps.toArray(Predicate[]::new));
        };
        PageRequest pageable = PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "id"));
        return PageResponse.of(notices.findAll(spec, pageable), this::toRow);
    }

    public NoticeRow notice(Long id) {
        return toRow(notices.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.ANNOUNCEMENT_NOT_FOUND)));
    }

    /** 운영자가 직접 바꾼 상태는 statusReason=ADMIN 으로 남긴다. 다음 수집에서 원본 상태가 바뀌면 수집기가 다시 덮어쓸 수 있다 */
    @Transactional
    public NoticeRow changeStatus(Long id, NoticeStatus status) {
        Notice notice = notices.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.ANNOUNCEMENT_NOT_FOUND));
        notice.changeStatus(status, NoticeStatusReason.ADMIN);
        return toRow(notice);
    }

    public List<HousingRow> housingsOf(Long noticeId) {
        notices.findById(noticeId).orElseThrow(() -> new BusinessException(ErrorCode.ANNOUNCEMENT_NOT_FOUND));
        return withInfra(housings.findAllByNoticeId(noticeId));
    }

    /** 활성 주택 가운데 좌표가 없는 것. 카카오가 못 찾은 주소라 운영자가 주소·좌표를 직접 넣어 준다 */
    public List<HousingRow> unresolvedHousings() {
        return withInfra(housings.findActiveWithoutCoordinates());
    }

    @Transactional
    public HousingRow updateCoordinates(Long housingId, BigDecimal latitude, BigDecimal longitude) {
        Housing housing = housings.findById(housingId).orElseThrow(() -> new BusinessException(ErrorCode.HOUSE_NOT_FOUND));
        if (latitude.compareTo(BigDecimal.valueOf(33)) < 0 || latitude.compareTo(BigDecimal.valueOf(39)) > 0
                || longitude.compareTo(BigDecimal.valueOf(124)) < 0 || longitude.compareTo(BigDecimal.valueOf(132)) > 0) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "국내 범위를 벗어난 좌표입니다 (위도 33~39, 경도 124~132).");
        }
        housing.updateCoordinates(latitude, longitude);
        // 좌표가 바뀌면 기존 인프라는 맞지 않으므로 지운다. 다음 수집(또는 인프라 재수집 요청)에서 다시 채운다
        dropInfra(housingId);
        return withInfra(List.of(housing)).get(0);
    }

    @Transactional
    public HousingRow changeActive(Long housingId, boolean active) {
        Housing housing = housings.findById(housingId).orElseThrow(() -> new BusinessException(ErrorCode.HOUSE_NOT_FOUND));
        housing.changeActive(active);
        return withInfra(List.of(housing)).get(0);
    }

    /** 인프라 스냅샷을 지워 다음 인프라 수집의 대상으로 만든다 (웹 서버는 수집을 직접 하지 않는다) */
    @Transactional
    public HousingRow refreshInfra(Long housingId) {
        Housing housing = housings.findById(housingId).orElseThrow(() -> new BusinessException(ErrorCode.HOUSE_NOT_FOUND));
        dropInfra(housingId);
        return withInfra(List.of(housing)).get(0);
    }

    private void dropInfra(Long housingId) {
        snapshots.findByHousingId(housingId).ifPresent(s -> {
            places.deleteBySnapshotId(s.getId());
            snapshots.delete(s);
        });
    }

    private List<HousingRow> withInfra(List<Housing> list) {
        Set<Long> ids = list.stream().map(Housing::getId).collect(Collectors.toSet());
        var snaps = ids.isEmpty() ? List.<HousingInfraSnapshot>of() : snapshots.findAllByHousingIdIn(ids);
        var byHousing = snaps.stream().collect(Collectors.toMap(HousingInfraSnapshot::getHousingId, s -> s, (a, b) -> a));
        return list.stream().map(h -> {
            HousingInfraSnapshot s = byHousing.get(h.getId());
            Notice n = h.getNotice();
            return new HousingRow(h.getId(), n == null ? null : n.getId(), n == null ? null : n.getTitle(),
                    h.getName(), h.getAddress(), h.getLatitude(), h.getLongitude(), h.isActive(),
                    s != null, s == null ? null : s.getCollectedAt());
        }).toList();
    }

    private NoticeRow toRow(Notice n) {
        return new NoticeRow(n.getId(), n.getSource(), n.getExternalNoticeId(), n.getTitle(),
                n.getAgency() == null ? null : n.getAgency().getName(),
                n.getStatus() == null ? null : n.getStatus().name(),
                n.getStatusReason() == null ? null : n.getStatusReason().name(),
                n.getRegion(), n.getNoticeType(), n.getRentalType(),
                n.getApplyStartAt(), n.getApplyEndAt(), n.getDetailUrl(), n.getLastSeenAt());
    }
}
