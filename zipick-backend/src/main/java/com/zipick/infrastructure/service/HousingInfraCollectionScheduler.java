package com.zipick.infrastructure.service;

import com.zipick.infrastructure.config.InfraProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 웹 서버에서 주변 인프라 수집을 부르는 시점만 정한다. 수집 자체는 {@link HousingInfraCollectionService}.
 *
 * <h3>실행 시점 — 진입점 셋, 로직 하나</h3>
 * <ol>
 *   <li><b>기동 수집</b> — 서버가 뜨고 startup-delay 뒤에 한 번.
 *       대상이 없어질 때까지 batch 를 연속으로 처리한다.</li>
 *   <li><b>보정 실행</b> — 기동 후 catch-up-window 동안만 catch-up-interval 간격으로 반복한다.
 *       공고 수집과 이 배치는 서로 모르는 사이라, 기동 수집이 먼저 끝난 뒤에 좌표가 채워지는 주택이 생긴다.
 *       이 창이 없으면 그런 주택은 다음 날 cron 까지 인프라 없이 남는다.
 *       대상이 0건이면 인덱스를 타는 SELECT 한 번으로 끝나므로 창을 열어 두는 비용이 사실상 없다.</li>
 *   <li><b>일일 정기 수집</b> — daily-cron 시각에 한 번. TTL 만료분과 새 공고를 함께 처리한다.</li>
 * </ol>
 *
 * 웹 애플리케이션에서만 등록된다. 수집기 진입점(WebApplicationType.NONE)은 공고 수집 직후 서비스를 직접 부른다.
 * 운영 웹 서버는 zipick.infra.collection.enabled=false 라서 여기서 불러도 바로 돌아온다.
 */
@Component
@ConditionalOnWebApplication
public class HousingInfraCollectionScheduler {

    private static final Logger log = LoggerFactory.getLogger(HousingInfraCollectionScheduler.class);

    private final HousingInfraCollectionService collectionService;
    private final InfraProperties properties;

    /** 보정 창의 기준 시각. 빈이 만들어지는 시점이 곧 서버 기동 시점이다. */
    private final Instant startedAt = Instant.now();

    private volatile boolean startupCollected;
    private volatile boolean catchUpRetired;

    public HousingInfraCollectionScheduler(
            HousingInfraCollectionService collectionService,
            InfraProperties properties
    ) {
        this.collectionService = collectionService;
        this.properties = properties;
    }

    /**
     * 기동 수집과 그 뒤의 짧은 보정 실행.
     *
     * 최초 1회는 반드시 실행하고, 그 뒤로는 기동 후 catch-up-window 가 지날 때까지만 반복한다.
     * 창이 닫히면 이 메서드는 아무것도 하지 않고 이후 수집은 일일 cron 이 맡는다.
     * (fixedDelay 는 애너테이션이라 중간에 끌 수 없어 호출 자체는 계속 들어온다. 비용은 조건 검사뿐이다.)
     */
    @Scheduled(
            initialDelayString = "${zipick.infra.collection.startup-delay:PT10S}",
            fixedDelayString = "${zipick.infra.collection.catch-up-interval:PT10M}"
    )
    public void collectOnStartup() {
        if (catchUpRetired) {
            return;
        }
        if (startupCollected && Instant.now().isAfter(startedAt.plus(properties.collection().catchUpWindow()))) {
            catchUpRetired = true;
            log.info("주변 인프라 보정 실행 창이 끝났습니다. 이후에는 하루 1회 정기 수집만 실행합니다.");
            return;
        }
        String trigger = startupCollected ? "보정" : "기동";
        startupCollected = true;
        collectionService.collect(trigger);
    }

    /**
     * 하루 1회 정기 수집. 시각은 설정으로 둔다.
     * 공고 수집 스케줄이 정해지면 그 뒤 시각으로 맞추기 위해서다(코드 수정 없이 바꾼다).
     */
    @Scheduled(
            cron = "${zipick.infra.collection.daily-cron:0 30 4 * * *}",
            zone = "${zipick.infra.collection.cron-zone:Asia/Seoul}"
    )
    public void collectDaily() {
        collectionService.collect("일일 정기");
    }
}
