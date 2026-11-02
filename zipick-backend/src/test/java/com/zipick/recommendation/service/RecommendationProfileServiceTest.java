package com.zipick.recommendation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zipick.global.error.BusinessException;
import com.zipick.global.error.ErrorCode;
import com.zipick.recommendation.domain.RecommendationProfile;
import com.zipick.recommendation.dto.RecommendationProfileRequest;
import com.zipick.recommendation.dto.RecommendationProfileResponse;
import com.zipick.recommendation.repository.RecommendationProfileRepository;
import com.zipick.user.service.UserConditionChangedEvent;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 추천 기준(우선순위·규칙) 최대 3개, 하나만 사용 중. 입력값 범위를 검증한다. */
@ExtendWith(MockitoExtension.class)
class RecommendationProfileServiceTest {

    @Mock RecommendationProfileRepository repository;
    @InjectMocks RecommendationProfileService service;

    private static RecommendationProfile profile(boolean active) {
        return RecommendationProfile.builder().userId(1L).name("기본").active(active)
                .weights("{\"cost\":3}").rules("{}").infraWeights("{}").build();
    }

    private static RecommendationProfileRequest weights(Map<String, Integer> w) {
        return new RecommendationProfileRequest(null, w, null, null);
    }

    @Test
    void 첫_기준은_사용_중으로_만들고_기본_우선순위를_넣는다() {
        when(repository.countByUserId(1L)).thenReturn(0L);
        when(repository.save(any(RecommendationProfile.class))).thenAnswer(inv -> inv.getArgument(0));

        RecommendationProfileResponse r = service.create(1L, new RecommendationProfileRequest(null, null, null, null));

        assertThat(r.active()).isTrue();
        assertThat(r.name()).isEqualTo("기본");
        assertThat(r.weights()).containsEntry("cost", 3).containsEntry("region", 3);
    }

    @Test
    void 두_번째_기준은_사용_중이_아니다() {
        when(repository.countByUserId(1L)).thenReturn(1L);
        when(repository.save(any(RecommendationProfile.class))).thenAnswer(inv -> inv.getArgument(0));
        assertThat(service.create(1L, new RecommendationProfileRequest("주말용", Map.of("cost", 5), null, null)).active()).isFalse();
    }

    @Test
    void 최대_3개까지만_만들_수_있다() {
        when(repository.countByUserId(1L)).thenReturn((long) RecommendationProfile.MAX_PER_USER);
        assertThatThrownBy(() -> service.create(1L, weights(null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.PROFILE_LIMIT_EXCEEDED);
    }

    @Test
    void 우선순위는_알려진_항목이고_1에서_5_사이여야_한다() {
        when(repository.countByUserId(1L)).thenReturn(0L);
        assertThatThrownBy(() -> service.create(1L, weights(Map.of("unknown", 3)))).hasMessageContaining("알 수 없는 항목");
        assertThatThrownBy(() -> service.create(1L, weights(Map.of("cost", 6)))).hasMessageContaining("1~5");
        assertThatThrownBy(() -> service.create(1L, weights(Map.of("cost", 0)))).hasMessageContaining("1~5");
    }

    @Test
    void 규칙은_MUST_또는_PREFER만_된다() {
        when(repository.countByUserId(1L)).thenReturn(0L);
        when(repository.save(any(RecommendationProfile.class))).thenAnswer(inv -> inv.getArgument(0));
        assertThatThrownBy(() -> service.create(1L, new RecommendationProfileRequest(null, null, Map.of("region", "ALWAYS"), null)))
                .hasMessageContaining("MUST 또는 PREFER");
        assertThatThrownBy(() -> service.create(1L, new RecommendationProfileRequest(null, null, Map.of("nope", "MUST"), null)))
                .hasMessageContaining("알 수 없는 항목");
        assertThat(service.create(1L, new RecommendationProfileRequest(null, null, Map.of("region", "must"), null)).rules())
                .containsEntry("region", "must");
    }

    @Test
    void 인프라_가중치도_1에서_5_사이여야_한다() {
        when(repository.countByUserId(1L)).thenReturn(0L);
        assertThatThrownBy(() -> service.create(1L, new RecommendationProfileRequest(null, null, null, Map.of("SW8", 9))))
                .hasMessageContaining("인프라 가중치");
    }

    @Test
    void 수정은_null_인_필드를_그대로_둔다() {
        RecommendationProfile p = profile(true);
        when(repository.findByIdAndUserId(7L, 1L)).thenReturn(Optional.of(p));

        RecommendationProfileResponse r = service.update(1L, 7L, new RecommendationProfileRequest("새 이름", null, null, null));

        assertThat(r.name()).isEqualTo("새 이름");
        assertThat(r.weights()).containsEntry("cost", 3);
    }

    @Test
    void 남의_기준은_찾을_수_없다() {
        when(repository.findByIdAndUserId(7L, 1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.update(1L, 7L, weights(null)))
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.RECOMMENDATION_PROFILE_NOT_FOUND);
    }

    @Test
    void 우선순위_적용은_사용_중인_기준을_고치고_없으면_새로_만든다() {
        RecommendationProfile active = profile(true);
        when(repository.findByUserIdAndActiveTrue(1L)).thenReturn(Optional.of(active));
        when(repository.findByIdAndUserId(active.getId(), 1L)).thenReturn(Optional.of(active));
        assertThat(service.applyWeights(1L, Map.of("cost", 5)).weights()).containsEntry("cost", 5);

        when(repository.findByUserIdAndActiveTrue(2L)).thenReturn(Optional.empty());
        when(repository.countByUserId(2L)).thenReturn(0L);
        when(repository.save(any(RecommendationProfile.class))).thenAnswer(inv -> inv.getArgument(0));
        assertThat(service.applyWeights(2L, Map.of("area", 4)).weights()).containsEntry("area", 4);
    }

    @Test
    void 사용_전환은_나머지를_모두_끈다() {
        RecommendationProfile a = profile(true), b = profile(false);
        when(repository.findByIdAndUserId(8L, 1L)).thenReturn(Optional.of(b));
        when(repository.findAllByUserIdOrderByIdAsc(1L)).thenReturn(List.of(a, b));

        service.activate(1L, 8L);

        assertThat(a.isActive()).isFalse();
        assertThat(b.isActive()).isTrue();
        assertThat(service.list(1L)).hasSize(2);
    }

    @Test
    void 사용_중인_기준은_다른_기준이_있으면_지울_수_없다() {
        RecommendationProfile active = profile(true);
        when(repository.findByIdAndUserId(7L, 1L)).thenReturn(Optional.of(active));
        when(repository.countByUserId(1L)).thenReturn(2L);
        assertThatThrownBy(() -> service.delete(1L, 7L))
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.ACTIVE_PROFILE_REQUIRED);
        verify(repository, never()).delete(any());
    }

    @Test
    void 사용_중이_아니거나_하나뿐이면_지운다() {
        RecommendationProfile other = profile(false);
        when(repository.findByIdAndUserId(8L, 1L)).thenReturn(Optional.of(other));
        service.delete(1L, 8L);
        verify(repository).delete(other);
    }

    @Test
    void 사용자_조건이_바뀌면_사용_중인_기준의_버전을_올린다() {
        RecommendationProfile active = profile(true);
        when(repository.findByUserIdAndActiveTrue(1L)).thenReturn(Optional.of(active));
        int before = active.getVersion();

        service.onUserConditionChanged(new UserConditionChangedEvent(1L));

        assertThat(active.getVersion()).isEqualTo(before + 1);
        assertThat(service.findActive(1L)).contains(active);
    }
}
