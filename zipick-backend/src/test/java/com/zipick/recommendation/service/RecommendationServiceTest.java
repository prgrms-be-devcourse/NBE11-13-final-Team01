package com.zipick.recommendation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zipick.global.error.BusinessException;
import com.zipick.global.error.ErrorCode;
import com.zipick.housing.domain.Candidate;
import com.zipick.housing.domain.HousingType;
import com.zipick.housing.domain.RentalType;
import com.zipick.housing.dto.HouseFilter;
import com.zipick.housing.infra.InfraDistanceProvider;
import com.zipick.housing.repository.CandidateQueryRepository;
import com.zipick.recommendation.config.RecommendationProperties;
import com.zipick.recommendation.domain.RecommendationProfile;
import com.zipick.recommendation.dto.RecommendationResponse;
import com.zipick.recommendation.dto.RecommendedHouseResponse;
import com.zipick.recommendation.dto.RelaxSuggestionResponse;
import com.zipick.recommendation.engine.EligibilityChecker;
import com.zipick.recommendation.engine.HouseScore;
import com.zipick.recommendation.engine.RecommendationRanker;
import com.zipick.recommendation.engine.ScoreCalculator;
import com.zipick.recommendation.engine.TestFixtures;
import com.zipick.recommendation.repository.RecommendationProfileRepository;
import com.zipick.user.repository.UserPreferenceRepository;
import com.zipick.user.repository.UserProfileRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 추천 파이프라인(후보 조회 → 판정 → 점수 → 주택별 대표 → 두 묶음)을 서비스 단위로 확인한다.
 * 점수 엔진(ScoreCalculator, RecommendationRanker)은 진짜를 쓰고, DB 저장소만 가짜로 바꾼다.
 */
class RecommendationServiceTest {

    private static final long USER = 1L;

    private final RecommendationProperties props = TestFixtures.props();
    private final CandidateQueryRepository candidateRepository = mock(CandidateQueryRepository.class);
    private final InfraDistanceProvider infra = mock(InfraDistanceProvider.class);
    private final UserProfileRepository profileRepository = mock(UserProfileRepository.class);
    private final UserPreferenceRepository preferenceRepository = mock(UserPreferenceRepository.class);
    private final RecommendationProfileRepository recProfileRepository = mock(RecommendationProfileRepository.class);
    private final RecommendationService service = new RecommendationService(props, candidateRepository, infra,
            profileRepository, preferenceRepository, recProfileRepository,
            new ScoreCalculator(props, new EligibilityChecker(props)), new RecommendationRanker(props));

    /** 마포구 다세대, 가격·면적이 희망 조건에 맞는 집 */
    private final Candidate good = TestFixtures.candidate(1, 1, RentalType.YOUTH_PURCHASE, "마포구", HousingType.MULTI_HOUSEHOLD,
            "29.4", 1500, 32, 1, 2015);
    /** 가격이 없는 집 → 정보 부족 묶음 */
    private final Candidate noPrice = TestFixtures.candidate(2, 2, RentalType.YOUTH_PURCHASE, "마포구", HousingType.MULTI_HOUSEHOLD,
            "29.4", null, null, 1, 2015);

    @BeforeEach
    void setUp() {
        lenient().when(profileRepository.findByUserId(USER)).thenReturn(Optional.of(TestFixtures.youngSingle()));
        lenient().when(preferenceRepository.findByUserId(USER)).thenReturn(Optional.of(TestFixtures.mapoPreference()));
        lenient().when(recProfileRepository.findByUserIdAndActiveTrue(USER)).thenReturn(Optional.empty());
        lenient().when(infra.nearest(any())).thenReturn(Map.of());
    }

    private void candidates(Candidate... cs) {
        when(candidateRepository.find(any(), eq(USER), anyInt())).thenReturn(List.of(cs));
    }

    @Test
    void 가격이_있는_집은_순위_묶음_없는_집은_정보_부족_묶음() {
        candidates(good, noPrice);

        RecommendationResponse r = service.recommend(USER, HouseFilter.openOnly(), false);

        assertThat(r.houses()).extracting(RecommendedHouseResponse::houseId).containsExactly(1L);
        assertThat(r.incomplete()).extracting(RecommendedHouseResponse::houseId).containsExactly(2L);
        assertThat(r.candidateTotal()).isEqualTo(2);
        assertThat(r.total()).isEqualTo(1);
        assertThat(r.profileId()).isNull();   // 추천 기준을 만든 적 없음
        assertThat(r.priorities().keySet()).containsExactlyElementsOf(ScoreCalculator.ITEMS);
    }

    @Test
    void 미리보기는_기준_점수와_관계없이_후보를_돌려준다() {
        candidates(good);
        assertThat(service.recommend(USER, HouseFilter.openOnly(), true).houses()).hasSize(1);
    }

    @Test
    void 추천_기준이_있으면_id_와_버전을_응답에_싣는다() {
        RecommendationProfile rp = RecommendationProfile.builder().userId(USER).name("기본").active(true)
                .weights("{\"cost\":5}").rules("{}").infraWeights("{}").build();
        when(recProfileRepository.findByUserIdAndActiveTrue(USER)).thenReturn(Optional.of(rp));
        candidates(good);

        RecommendationResponse r = service.recommend(USER, HouseFilter.openOnly(), false);

        assertThat(r.profileVersion()).isEqualTo(rp.getVersion());
        assertThat(r.priorities()).containsEntry("cost", 5);
    }

    @Test
    void 기본_정보나_희망_조건이_없으면_추천할_수_없다() {
        when(profileRepository.findByUserId(USER)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.recommend(USER, HouseFilter.openOnly(), false))
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.PROFILE_NOT_FOUND);

        when(profileRepository.findByUserId(USER)).thenReturn(Optional.of(TestFixtures.youngSingle()));
        when(preferenceRepository.findByUserId(USER)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.currentPriorities(USER))
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.PREFERENCE_NOT_FOUND);
    }

    @Test
    void 주택_하나의_점수와_없는_주택() {
        when(candidateRepository.findByHousingIds(List.of(1L))).thenReturn(List.of(good));
        when(candidateRepository.findByHousingIds(List.of(9L))).thenReturn(List.of());

        assertThat(service.scoreOne(USER, 1L).houseId()).isEqualTo(1L);
        assertThatThrownBy(() -> service.scoreOne(USER, 9L))
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.HOUSE_NOT_FOUND);
    }

    @Test
    void 주택_목록이_비면_DB_를_보지_않는다() {
        assertThat(service.scoreHouses(USER, List.of())).isEmpty();
        verify(candidateRepository, never()).findByHousingIds(anyCollection());
    }

    @Test
    void 새_공고_역매칭은_그_공고들만_모집_중_필터로_찾는다() {
        candidates(good);

        List<HouseScore> ranked = service.rankForNotices(USER, Set.of(1L, 2L));

        ArgumentCaptor<HouseFilter> filter = ArgumentCaptor.forClass(HouseFilter.class);
        verify(candidateRepository).find(filter.capture(), eq(USER), anyInt());
        assertThat(filter.getValue().noticeIds()).containsExactlyInAnyOrder(1L, 2L);
        assertThat(filter.getValue().statuses()).isEqualTo(HouseFilter.OPEN_STATUSES);
        assertThat(ranked).extracting(HouseScore::housingId).containsExactly(1L);
    }

    @Test
    void 모집_중인_후보는_제외된_집도_함께_돌려준다() {
        candidates(good, noPrice);
        assertThat(service.scoredCandidates(USER)).hasSize(2);
    }

    @Test
    void 추천이_충분하면_완화_제안을_하지_않는다() {
        Candidate[] many = new Candidate[props.minResultsBeforeRelax()];
        for (int i = 0; i < many.length; i++) {
            many[i] = TestFixtures.candidate(10 + i, 10 + i, RentalType.YOUTH_PURCHASE, "마포구", HousingType.MULTI_HOUSEHOLD,
                    "29.4", 1500, 32, 1, 2015);
        }
        candidates(many);

        RelaxSuggestionResponse r = service.relaxSuggestions(USER, HouseFilter.openOnly());

        assertThat(r.currentCount()).isGreaterThanOrEqualTo(props.minResultsBeforeRelax());
        assertThat(r.suggestions()).isEmpty();
    }

    @Test
    void 추천이_적으면_조건을_하나씩_풀어_보고_늘어나는_것만_제안한다() {
        // 희망 면적(20~40㎡)보다 조금 큰 집, 월 예산을 조금 넘는 집 → 각각 풀면 늘어난다
        Candidate tooBig = TestFixtures.candidate(3, 3, RentalType.YOUTH_PURCHASE, "마포구", HousingType.MULTI_HOUSEHOLD,
                "44", 1500, 32, 1, 2015);
        candidates(good, tooBig);

        RelaxSuggestionResponse r = service.relaxSuggestions(USER, HouseFilter.openOnly());

        assertThat(r.currentCount()).isLessThan(props.minResultsBeforeRelax());
        assertThat(r.suggestions()).allSatisfy(s -> assertThat(s.countAfter()).isGreaterThan(r.currentCount()));
    }
}
