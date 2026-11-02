package com.zipick.notice.service;

import com.zipick.notice.domain.Housing;
import com.zipick.notice.repository.HousingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class HousingGeocodingPersistenceService {

    private final HousingRepository housingRepository;

    public HousingGeocodingPersistenceService(HousingRepository housingRepository) {
        this.housingRepository = housingRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean updateIfCoordinatesMissing(Long housingId, GeocodedAddress geocoded) {
        Housing housing = housingRepository.findById(housingId).orElse(null);
        if (housing == null || !housing.isActive() || housing.hasCoordinates()) {
            return false;
        }

        housing.enrichAddress(
                geocoded.normalizedAddress(),
                geocoded.provinceName(),
                geocoded.districtName(),
                geocoded.legalDongName(),
                geocoded.roadName(),
                geocoded.buildingName(),
                geocoded.latitude(),
                geocoded.longitude()
        );
        return true;
    }
}
