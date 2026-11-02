package com.zipick.admin.collection;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface CollectionRequestRepository extends JpaRepository<CollectionRequest, Long> {

    List<CollectionRequest> findAllByStatusIn(Collection<CollectionRequest.Status> statuses);

    boolean existsByStatusIn(Collection<CollectionRequest.Status> statuses);

    Page<CollectionRequest> findAllByOrderByIdDesc(Pageable pageable);
}
