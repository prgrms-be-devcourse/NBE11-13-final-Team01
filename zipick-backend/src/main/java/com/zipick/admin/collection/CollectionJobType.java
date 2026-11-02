package com.zipick.admin.collection;

/**
 * 수집기 프로세스 종류. BASIC/AI/INFRA 는 collect-notices-observed.sh 의 job_type 과 1:1 이고,
 * FULL 은 운영자 요청에서만 쓰는 묶음(basic → ai)이다.
 */
public enum CollectionJobType {
    BASIC,
    AI,
    INFRA,
    FULL
}
