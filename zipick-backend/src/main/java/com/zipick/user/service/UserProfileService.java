package com.zipick.user.service;

import com.zipick.global.error.BusinessException;
import com.zipick.global.error.ErrorCode;
import com.zipick.global.util.JsonUtil;
import com.zipick.user.domain.UserPreference;
import com.zipick.user.domain.UserProfile;
import com.zipick.user.dto.UserPreferenceRequest;
import com.zipick.user.dto.UserPreferenceResponse;
import com.zipick.user.dto.UserProfileRequest;
import com.zipick.user.dto.UserProfileResponse;
import com.zipick.user.repository.UserPreferenceRepository;
import com.zipick.user.repository.UserProfileRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserProfileService {

    private final UserProfileRepository profileRepository;
    private final UserPreferenceRepository preferenceRepository;
    private final ApplicationEventPublisher events;

    public UserProfileResponse getProfile(Long userId) {
        return UserProfileResponse.from(profileRepository.findByUserId(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PROFILE_NOT_FOUND)));
    }

    @Transactional
    public UserProfileResponse saveProfile(Long userId, UserProfileRequest r) {
        UserProfile profile = profileRepository.findByUserId(userId)
                .map(p -> {
                    p.update(r.birthDate(), r.job(), r.married(), r.householdSize(), r.monthlyIncome(),
                            r.totalAssets(), r.carValue(), r.homeless());
                    return p;
                })
                .orElseGet(() -> profileRepository.save(UserProfile.builder()
                        .userId(userId).birthDate(r.birthDate()).job(r.job()).married(r.married())
                        .householdSize(r.householdSize()).monthlyIncome(r.monthlyIncome())
                        .totalAssets(r.totalAssets()).carValue(r.carValue()).homeless(r.homeless())
                        .build()));
        events.publishEvent(new UserConditionChangedEvent(userId));
        return UserProfileResponse.from(profile);
    }

    public UserPreferenceResponse getPreference(Long userId) {
        return UserPreferenceResponse.from(preferenceRepository.findByUserId(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PREFERENCE_NOT_FOUND)));
    }

    @Transactional
    public UserPreferenceResponse savePreference(Long userId, UserPreferenceRequest r) {
        validateRanges(r);
        String regions = JsonUtil.write(r.regions());
        String housingTypes = JsonUtil.write(r.housingTypes() == null ? List.of() : r.housingTypes());
        String rentalTypes = JsonUtil.write(r.rentalTypes() == null ? List.of() : r.rentalTypes());

        UserPreference pref = preferenceRepository.findByUserId(userId)
                .map(p -> {
                    p.update(regions, r.depositMin(), r.depositMax(), r.rentMin(), r.rentMax(), r.areaMin(),
                            r.areaMax(), housingTypes, rentalTypes, r.monthlyBudget());
                    return p;
                })
                .orElseGet(() -> preferenceRepository.save(UserPreference.builder()
                        .userId(userId).regions(regions).depositMin(r.depositMin()).depositMax(r.depositMax())
                        .rentMin(r.rentMin()).rentMax(r.rentMax()).areaMin(r.areaMin()).areaMax(r.areaMax())
                        .housingTypes(housingTypes).rentalTypes(rentalTypes).monthlyBudget(r.monthlyBudget())
                        .build()));
        events.publishEvent(new UserConditionChangedEvent(userId));
        return UserPreferenceResponse.from(pref);
    }

    private void validateRanges(UserPreferenceRequest r) {
        if (r.depositMin() != null && r.depositMax() != null && r.depositMin() > r.depositMax())
            throw new BusinessException(ErrorCode.INVALID_INPUT, "보증금 범위가 올바르지 않습니다.");
        if (r.rentMin() != null && r.rentMax() != null && r.rentMin() > r.rentMax())
            throw new BusinessException(ErrorCode.INVALID_INPUT, "월 임대료 범위가 올바르지 않습니다.");
        if (r.areaMin() != null && r.areaMax() != null && r.areaMin().compareTo(r.areaMax()) > 0)
            throw new BusinessException(ErrorCode.INVALID_INPUT, "전용면적 범위가 올바르지 않습니다.");
        long distinctRanks = r.regions().stream().map(UserPreferenceRequest.Region::rank).distinct().count();
        if (distinctRanks != r.regions().size())
            throw new BusinessException(ErrorCode.INVALID_INPUT, "관심 지역 순위가 중복됩니다.");
    }
}
