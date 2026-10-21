package com.zipick.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zipick.global.error.BusinessException;
import com.zipick.global.error.ErrorCode;
import com.zipick.housing.domain.HousingType;
import com.zipick.user.domain.StudentStatus;
import com.zipick.user.domain.UserPreference;
import com.zipick.user.domain.UserProfile;
import com.zipick.user.dto.UserPreferenceRequest;
import com.zipick.user.dto.UserPreferenceRequest.Region;
import com.zipick.user.dto.UserPreferenceResponse;
import com.zipick.user.dto.UserProfileRequest;
import com.zipick.user.dto.UserProfileResponse;
import com.zipick.user.repository.UserPreferenceRepository;
import com.zipick.user.repository.UserProfileRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/** 기본 정보·희망 조건 저장. 저장하면 추천 기준 버전을 올리도록 이벤트를 낸다. */
@ExtendWith(MockitoExtension.class)
class UserProfileServiceTest {

    @Mock UserProfileRepository profileRepository;
    @Mock UserPreferenceRepository preferenceRepository;
    @Mock ApplicationEventPublisher events;
    @InjectMocks UserProfileService service;

    private static UserProfileRequest profileRequest(int household) {
        return new UserProfileRequest(LocalDate.of(1999, 3, 1), false, household, 250, 5000, 0, true, StudentStatus.NONE);
    }

    private static UserPreferenceRequest preferenceRequest(List<Region> regions, String areaMin, String areaMax) {
        return new UserPreferenceRequest(regions, 3000, 40, areaMin == null ? null : new BigDecimal(areaMin),
                areaMax == null ? null : new BigDecimal(areaMax), List.of(HousingType.APARTMENT), null, 60, 1);
    }

    private static final List<Region> TWO_REGIONS = List.of(
            new Region("경상북도 구미시", "구미시", 1), new Region("경상북도 김천시", "김천시", 2));

    @Test
    void 기본_정보가_없으면_PROFILE_NOT_FOUND() {
        when(profileRepository.findByUserId(1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getProfile(1L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.PROFILE_NOT_FOUND);
    }

    @Test
    void 기본_정보를_처음_저장하면_새로_만들고_이벤트를_낸다() {
        when(profileRepository.findByUserId(1L)).thenReturn(Optional.empty());
        when(profileRepository.save(any(UserProfile.class))).thenAnswer(inv -> inv.getArgument(0));

        UserProfileResponse r = service.saveProfile(1L, profileRequest(2));

        assertThat(r.householdSize()).isEqualTo(2);
        verify(events).publishEvent(new UserConditionChangedEvent(1L));
    }

    @Test
    void 기본_정보가_있으면_고친다() {
        UserProfile existing = UserProfile.builder().userId(1L).birthDate(LocalDate.of(1999, 3, 1)).married(false)
                .householdSize(1).homeless(true).build();
        when(profileRepository.findByUserId(1L)).thenReturn(Optional.of(existing));

        service.saveProfile(1L, profileRequest(3));

        assertThat(existing.getHouseholdSize()).isEqualTo(3);
        verify(profileRepository, never()).save(any());
        assertThat(service.getProfile(1L).householdSize()).isEqualTo(3);
    }

    @Test
    void 희망_조건이_없으면_PREFERENCE_NOT_FOUND() {
        when(preferenceRepository.findByUserId(1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getPreference(1L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.PREFERENCE_NOT_FOUND);
    }

    @Test
    void 희망_조건을_처음_저장하면_새로_만들고_이벤트를_낸다() {
        when(preferenceRepository.findByUserId(1L)).thenReturn(Optional.empty());
        when(preferenceRepository.save(any(UserPreference.class))).thenAnswer(inv -> inv.getArgument(0));

        UserPreferenceResponse r = service.savePreference(1L, preferenceRequest(TWO_REGIONS, "20", "60"));

        assertThat(r.rentMax()).isEqualTo(40);
        assertThat(r.regions()).hasSize(2);
        verify(events).publishEvent(new UserConditionChangedEvent(1L));
    }

    @Test
    void 희망_조건이_있으면_고친다() {
        when(preferenceRepository.findByUserId(1L)).thenReturn(Optional.empty());
        when(preferenceRepository.save(any(UserPreference.class))).thenAnswer(inv -> inv.getArgument(0));
        service.savePreference(1L, preferenceRequest(TWO_REGIONS, null, null));
        UserPreference saved = org.mockito.Mockito.mockingDetails(preferenceRepository).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("save")).map(i -> (UserPreference) i.getArgument(0)).findFirst().orElseThrow();
        when(preferenceRepository.findByUserId(1L)).thenReturn(Optional.of(saved));

        service.savePreference(1L, new UserPreferenceRequest(TWO_REGIONS, 5000, 30, null, null, null, null, null, 2));

        assertThat(saved.getRentMax()).isEqualTo(30);
        assertThat(saved.getRoomsMin()).isEqualTo(2);
    }

    @Test
    void 면적_범위가_뒤집히면_거부한다() {
        assertThatThrownBy(() -> service.savePreference(1L, preferenceRequest(TWO_REGIONS, "60", "20")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("전용면적");
        verify(preferenceRepository, never()).save(any());
    }

    @Test
    void 관심_지역_순위가_겹치면_거부한다() {
        List<Region> dup = List.of(new Region("경상북도 구미시", "구미시", 1), new Region("경상북도 김천시", "김천시", 1));
        assertThatThrownBy(() -> service.savePreference(1L, preferenceRequest(dup, null, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("순위");
    }
}
