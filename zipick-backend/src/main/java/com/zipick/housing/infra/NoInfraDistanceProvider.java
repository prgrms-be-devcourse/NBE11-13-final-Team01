package com.zipick.housing.infra;

import com.zipick.housing.domain.Candidate;
import java.util.Collection;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 인프라 데이터가 아직 없을 때의 기본 구현. 실제 구현이 생기면 그쪽을 @Primary 로 등록한다. */
@Component
public class NoInfraDistanceProvider implements InfraDistanceProvider {

    @Override
    public Map<Long, Map<String, Integer>> nearest(Collection<Candidate> candidates) {
        return Map.of();
    }
}
