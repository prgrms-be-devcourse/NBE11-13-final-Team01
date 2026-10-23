package com.zipick.notification.campaign;

import com.zipick.global.error.BusinessException;
import com.zipick.global.error.ErrorCode;
import com.zipick.notification.client.BatchRequest;
import com.zipick.notification.client.BatchResponse;
import com.zipick.notification.client.NotificationClient;
import com.zipick.notification.client.NotificationRequest;
import com.zipick.notification.config.NotificationProperties;
import com.zipick.notification.domain.NotificationSetting;
import com.zipick.notification.repository.NotificationSettingRepository;
import com.zipick.notification.service.MarketingWindow;
import com.zipick.notification.service.NotificationScheduler;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 소식 메일 캠페인. "누구에게·언제" 를 여기서 정하고, 알림센터에는 수신자 목록이 든 묶음 요청만 보낸다.
 *  - 대상: 알림이 켜져 있고 광고 수신에 동의했고 주소가 있는 사용자
 *  - 시각: 21~08시를 피한다 ({@link MarketingWindow})
 *  - 중복: dedupeKey CAMPAIGN:{id}:{userId} — 같은 캠페인을 두 번 예약해도 한 사람에게 한 번
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CampaignService {

    public static final String TEMPLATE = "MARKETING_NEWSLETTER";
    public static final String TAG_CAMPAIGN = "campaign";

    private final CampaignRepository campaigns;
    private final NotificationSettingRepository settings;
    private final NotificationClient client;
    private final NotificationProperties props;

    @Transactional
    public CampaignResponse create(Long adminId, CampaignRequest r) {
        LocalDateTime at = MarketingWindow.nextAllowed(r.scheduledAt() != null ? r.scheduledAt() : LocalDateTime.now(NotificationScheduler.SEOUL));
        Campaign c = campaigns.save(Campaign.draft(r.title(), r.headline(), r.message(), at, adminId));
        return CampaignResponse.from(c, null);
    }

    /** 대상을 뽑아 알림센터에 묶음으로 넘긴다. 채널 조합(이메일만/슬랙만/둘 다)마다 묶음 하나 */
    @Transactional
    public CampaignResponse schedule(Long id) {
        Campaign c = find(id);
        if (c.getStatus() != Campaign.Status.DRAFT) throw new BusinessException(ErrorCode.INVALID_INPUT, "초안 상태의 캠페인만 예약할 수 있습니다.");
        LocalDateTime at = MarketingWindow.nextAllowed(c.getScheduledAt().isBefore(LocalDateTime.now(NotificationScheduler.SEOUL))
                ? LocalDateTime.now(NotificationScheduler.SEOUL) : c.getScheduledAt());

        // 알림센터가 지금 보낼 수 있는 채널만 (이메일이 꺼져 있으면 슬랙만)
        Map<String, List<NotificationSetting>> byChannels = new LinkedHashMap<>();
        for (NotificationSetting s : settings.findAllByEnabledTrueAndMarketingConsentTrue()) {
            List<String> usable = client.usableChannels(s.channels());
            if (usable.isEmpty()) continue;
            byChannels.computeIfAbsent(String.join(",", usable), k -> new ArrayList<>()).add(s);
        }
        if (byChannels.isEmpty()) throw new BusinessException(ErrorCode.INVALID_INPUT, "광고 수신에 동의한 사용자가 없습니다.");

        List<Long> batchIds = new ArrayList<>();
        int recipients = 0;
        for (List<NotificationSetting> group : byChannels.values()) {
            List<BatchRequest.Recipient> list = group.stream().map(s -> new BatchRequest.Recipient(s.recipient(), variables(c),
                    "CAMPAIGN:" + c.getId() + ":" + s.getUserId(), Map.of(NotificationRequest.TAG_USER, String.valueOf(s.getUserId())))).toList();
            BatchResponse b = client.createBatch(new BatchRequest(NotificationRequest.SOURCE, TEMPLATE, client.usableChannels(group.get(0).channels()), at,
                    Map.of(TAG_CAMPAIGN, String.valueOf(c.getId())), list));
            batchIds.add(b.id());
            recipients += list.size();
        }
        c.scheduled(batchIds, recipients, at);
        log.info("캠페인 {} 예약: 수신자 {}명, 묶음 {}, 시각 {}", c.getId(), recipients, batchIds, at);
        return CampaignResponse.from(c, progress(c));
    }

    @Transactional
    public CampaignResponse cancel(Long id) {
        Campaign c = find(id);
        for (long batchId : c.batchIdList()) client.cancelBatch(batchId);
        c.cancelled();
        return CampaignResponse.from(c, progress(c));
    }

    @Transactional(readOnly = true)
    public CampaignResponse get(Long id) {
        Campaign c = find(id);
        return CampaignResponse.from(c, progress(c));
    }

    /** 목록은 집계 없이 (알림센터를 캠페인 수만큼 부르지 않게) */
    @Transactional(readOnly = true)
    public List<CampaignResponse> list() {
        return campaigns.findTop50ByOrderByIdDesc().stream().map(c -> CampaignResponse.from(c, null)).toList();
    }

    private Map<String, Object> variables(Campaign c) {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("headline", c.getHeadline());
        vars.put("message", c.getMessage());
        vars.put("unsubscribeUrl", props.appUrl());
        return vars;
    }

    private CampaignResponse.Progress progress(Campaign c) {
        List<Long> ids = c.batchIdList();
        if (ids.isEmpty()) return null;
        try {
            return CampaignResponse.Progress.sum(ids.stream().map(client::getBatch).toList());
        } catch (BusinessException e) {
            log.warn("캠페인 {} 집계를 받지 못함: {}", c.getId(), e.getMessage());
            return null;
        }
    }

    private Campaign find(Long id) {
        return campaigns.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "캠페인이 없습니다: " + id));
    }
}
