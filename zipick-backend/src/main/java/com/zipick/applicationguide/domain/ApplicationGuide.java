package com.zipick.applicationguide.domain;

import com.zipick.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 사용자 한 명이 공고 하나에 대해 받은 AI 신청 준비 가이드. (user_id, notice_id) 당 1행이다.
 *
 * 결과가 사용자 상황에 따라 달라지므로 캐시 단위도 user + notice 다.
 * 이력은 남기지 않고 다시 만들면 덮어쓴다.
 *
 * <h3>캐시를 언제 버리는가</h3>
 * 기획 초안에는 "AI 입력 전체의 SHA-256(inputHash)" 가 있었지만 쓰지 않았다.
 * 해시를 계산하려면 캐시를 확인하기 전에 PDF 를 내려받아 텍스트를 뽑고 축약까지 끝내야 해서,
 * 캐시가 맞았을 때도 가장 느린 일을 매번 하게 된다. 캐시의 목적을 스스로 깎아먹는다.
 *
 * 대신 "이 결과가 무엇에 의존하는가" 를 컬럼으로 드러냈다.
 *   promptVersion    프롬프트를 고치면 전건 다시 만든다
 *   reducerVersion   문서 선별·축약 규칙을 고치면 전건 다시 만든다
 *   model            모델을 바꾸면 전건 다시 만든다
 *   profileUpdatedAt 그 사용자가 내 정보를 고치면 그 사람 것만 다시 만든다
 * 네 값이 모두 현재 값과 같으면 DB 한 번 읽고 바로 돌려준다.
 *
 * notice 의 updatedAt 은 일부러 넣지 않았다. 공고 수집이 돌 때마다
 * Notice.synchronize() 가 rawPayload·collectedAt 을 다시 써서 내용이 그대로여도
 * updatedAt 이 바뀐다. 캐시 조건에 넣으면 수집이 한 번 돌 때마다 전건 무효가 된다.
 * 공고문 파일은 게시 후 사실상 바뀌지 않고, 바뀌었다면 사용자가 '다시 만들기' 를 누르면 된다.
 */
@Entity
@Getter
@Table(
        name = "notice_application_guide",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_notice_application_guide_user_notice",
                columnNames = {"user_id", "notice_id"}
        )
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ApplicationGuide extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "notice_application_guide_id")
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "notice_id", nullable = false)
    private Long noticeId;

    /** GuideResult 를 직렬화한 JSON. 안을 조회하지 않고 통째로 읽고 쓴다 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result_json", columnDefinition = "jsonb", nullable = false)
    private String resultJson;

    /** {"S1":{"fileName":"모집공고.pdf","page":24}} */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "sources_json", columnDefinition = "jsonb", nullable = false)
    private String sourcesJson;

    @Column(name = "prompt_version", nullable = false, length = 50)
    private String promptVersion;

    @Column(name = "reducer_version", nullable = false, length = 50)
    private String reducerVersion;

    @Column(name = "model", nullable = false, length = 100)
    private String model;

    /** 생성 당시 UserProfile.updatedAt 복사본 */
    @Column(name = "profile_updated_at", nullable = false)
    private LocalDateTime profileUpdatedAt;

    @Column(name = "generated_at", nullable = false)
    private LocalDateTime generatedAt;

    public ApplicationGuide(Long userId, Long noticeId, String resultJson, String sourcesJson,
                            String promptVersion, String reducerVersion, String model,
                            LocalDateTime profileUpdatedAt, LocalDateTime generatedAt) {
        this.userId = userId;
        this.noticeId = noticeId;
        refresh(resultJson, sourcesJson, promptVersion, reducerVersion, model, profileUpdatedAt, generatedAt);
    }

    /** 다시 만들었을 때만 호출한다. 실패했으면 기존 값을 건드리지 않는다 */
    public void refresh(String resultJson, String sourcesJson, String promptVersion, String reducerVersion,
                        String model, LocalDateTime profileUpdatedAt, LocalDateTime generatedAt) {
        this.resultJson = resultJson;
        this.sourcesJson = sourcesJson;
        this.promptVersion = promptVersion;
        this.reducerVersion = reducerVersion;
        this.model = model;
        this.profileUpdatedAt = profileUpdatedAt;
        this.generatedAt = generatedAt;
    }

    /** 저장해 둔 결과를 그대로 써도 되는지. 하나라도 다르면 다시 만든다 */
    public boolean isReusable(String currentPromptVersion, String currentReducerVersion,
                              String currentModel, LocalDateTime currentProfileUpdatedAt) {
        return promptVersion.equals(currentPromptVersion)
                && reducerVersion.equals(currentReducerVersion)
                && model.equals(currentModel)
                && Objects.equals(profileUpdatedAt, currentProfileUpdatedAt);
    }
}
