package com.zipick.user.domain;

/** 대학생·취업준비생 여부. 청년 계층 자격(만 19~39세 '또는' 대학생·취준생)에 쓴다. */
public enum StudentStatus {
    NONE,
    /** 대학 재학·입학·복학 예정 */
    UNIVERSITY,
    /** 고등학교·대학교 졸업·중퇴 후 2년 이내 미취업자 */
    JOB_SEEKER
}
