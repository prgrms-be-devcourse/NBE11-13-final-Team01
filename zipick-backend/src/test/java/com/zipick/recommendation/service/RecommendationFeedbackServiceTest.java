package com.zipick.recommendation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zipick.global.error.BusinessException;
import com.zipick.global.error.ErrorCode;
import com.zipick.notice.repository.HousingRepository;
import com.zipick.recommendation.domain.RecommendationFeedback;
import com.zipick.recommendation.domain.RecommendationFeedback.Reason;
import com.zipick.recommendation.domain.RecommendationFeedback.Type;
import com.zipick.recommendation.dto.FeedbackRequest;
import com.zipick.recommendation.dto.FeedbackResponse;
import com.zipick.recommendation.repository.RecommendationFeedbackRepository;
import com.zipick.user.domain.UserPreference;
import com.zipick.user.repository.UserPreferenceRepository;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 숨김·별로예요. 같은 이유가 2번 쌓이면 희망 조건 변경을 제안한다(반영은 사용자가 수락할 때만). */
@ExtendWith(MockitoExtension.class)
class RecommendationFeedbackServiceTest {

    @Mock RecommendationFeedbackRepository repository;
    @Mock HousingRepository housingRepository;
    @Mock UserPreferenceRepository preferenceRepository;
    @InjectMocks RecommendationFeedbackService service;

    private void houseExists() {
        when(housingRepository.existsById(100L)).thenReturn(true);
        when(repository.findByUserIdAndHousingId(1L, 100L)).thenReturn(Optional.empty());
        when(repository.save(any(RecommendationFeedback.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private void preference(Integer rentMax, String areaMin) {
        when(preferenceRepository.findByUserId(1L)).thenReturn(Optional.of(UserPreference.builder()
                .userId(1L).regions("[]").rentMax(rentMax).areaMin(areaMin == null ? null : new BigDecimal(areaMin)).build()));
    }

    @Test
    void 없는_집에는_남길_수_없다() {
        when(housingRepository.existsById(100L)).thenReturn(false);
        assertThatThrownBy(() -> service.submit(1L, new FeedbackRequest(100L, Type.HIDE, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.HOUSE_NOT_FOUND);
    }

    @Test
    void 별로예요에는_이유가_필요하다() {
        when(housingRepository.existsById(100L)).thenReturn(true);
        assertThatThrownBy(() -> service.submit(1L, new FeedbackRequest(100L, Type.DISLIKE, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("이유");
    }

    @Test
    void 숨김은_이유_없이_저장되고_제안이_없다() {
        houseExists();
        FeedbackResponse r = service.submit(1L, new FeedbackRequest(100L, Type.HIDE, null));
        assertThat(r.hidden()).isTrue();
        assertThat(r.suggestion()).isNull();
    }

    @Test
    void 같은_집에_다시_남기면_이전_것을_지운다() {
        RecommendationFeedback old = RecommendationFeedback.builder().userId(1L).housingId(100L).type(Type.HIDE).build();
        when(housingRepository.existsById(100L)).thenReturn(true);
        when(repository.findByUserIdAndHousingId(1L, 100L)).thenReturn(Optional.of(old));
        when(repository.save(any(RecommendationFeedback.class))).thenAnswer(inv -> inv.getArgument(0));

        service.submit(1L, new FeedbackRequest(100L, Type.HIDE, null));

        verify(repository).delete(old);
    }

    @Test
    void 이유가_한_번뿐이면_제안하지_않는다() {
        houseExists();
        when(repository.countByUserIdAndReason(1L, Reason.EXPENSIVE)).thenReturn(1L);
        assertThat(service.submit(1L, new FeedbackRequest(100L, Type.DISLIKE, Reason.EXPENSIVE)).suggestion()).isNull();
    }

    @Test
    void 비싸다가_2번이면_월세_상한을_5만원_낮추자고_제안한다() {
        houseExists();
        when(repository.countByUserIdAndReason(1L, Reason.EXPENSIVE)).thenReturn(2L);
        preference(40, null);

        FeedbackResponse.Suggestion s = service.submit(1L, new FeedbackRequest(100L, Type.DISLIKE, Reason.EXPENSIVE)).suggestion();

        assertThat(s.field()).isEqualTo("rentMax");
        assertThat(s.from()).isEqualTo(40);
        assertThat(s.to()).isEqualTo(35);
    }

    @Test
    void 월세_상한이_없으면_비싸다_제안은_없다() {
        houseExists();
        when(repository.countByUserIdAndReason(1L, Reason.EXPENSIVE)).thenReturn(2L);
        preference(null, null);
        assertThat(service.submit(1L, new FeedbackRequest(100L, Type.DISLIKE, Reason.EXPENSIVE)).suggestion()).isNull();
    }

    @Test
    void 좁다가_2번이면_최소_면적을_5제곱미터_올리자고_제안한다() {
        houseExists();
        when(repository.countByUserIdAndReason(1L, Reason.SMALL)).thenReturn(3L);
        preference(null, "26.5");

        FeedbackResponse.Suggestion s = service.submit(1L, new FeedbackRequest(100L, Type.DISLIKE, Reason.SMALL)).suggestion();

        assertThat(s.field()).isEqualTo("areaMin");
        assertThat((BigDecimal) s.to()).isEqualByComparingTo("31.5");
    }

    @Test
    void 지역_인프라_노후는_규칙이나_우선순위를_제안한다() {
        houseExists();
        preference(null, null);
        when(repository.countByUserIdAndReason(1L, Reason.REGION)).thenReturn(2L);
        when(repository.countByUserIdAndReason(1L, Reason.INFRA)).thenReturn(2L);
        when(repository.countByUserIdAndReason(1L, Reason.OLD)).thenReturn(2L);
        when(repository.countByUserIdAndReason(1L, Reason.ETC)).thenReturn(2L);

        assertThat(service.submit(1L, new FeedbackRequest(100L, Type.DISLIKE, Reason.REGION)).suggestion().to()).isEqualTo("MUST");
        assertThat(service.submit(1L, new FeedbackRequest(100L, Type.DISLIKE, Reason.INFRA)).suggestion().field()).isEqualTo("weights.infra");
        assertThat(service.submit(1L, new FeedbackRequest(100L, Type.DISLIKE, Reason.OLD)).suggestion().field()).isEqualTo("weights.age");
        assertThat(service.submit(1L, new FeedbackRequest(100L, Type.DISLIKE, Reason.ETC)).suggestion()).isNull();
    }

    @Test
    void 희망_조건이_없으면_제안하지_않는다() {
        houseExists();
        when(repository.countByUserIdAndReason(1L, Reason.REGION)).thenReturn(2L);
        when(preferenceRepository.findByUserId(1L)).thenReturn(Optional.empty());
        assertThat(service.submit(1L, new FeedbackRequest(100L, Type.DISLIKE, Reason.REGION)).suggestion()).isNull();
    }

    @Test
    void 남의_피드백은_지울_수_없다() {
        when(repository.findByIdAndUserId(5L, 1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.delete(1L, 5L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.FEEDBACK_NOT_FOUND);
        verify(repository, never()).delete(any());
    }

    @Test
    void 내_피드백은_지운다() {
        RecommendationFeedback f = RecommendationFeedback.builder().userId(1L).housingId(100L).type(Type.HIDE).build();
        when(repository.findByIdAndUserId(5L, 1L)).thenReturn(Optional.of(f));
        service.delete(1L, 5L);
        verify(repository).delete(f);
    }
}
