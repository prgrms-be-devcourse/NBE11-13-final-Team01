package com.zipick.housing.repository;

import com.zipick.housing.domain.SupplyHouse;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SupplyHouseRepository extends JpaRepository<SupplyHouse, Long>, SupplyHouseQueryRepository {
}
