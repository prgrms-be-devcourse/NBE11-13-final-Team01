package com.zipick.user.service;

/** 기본 정보·희망 조건이 바뀌면 발행한다. 추천 기준 버전을 올려 캐시를 무효화하는 데 쓴다. */
public record UserConditionChangedEvent(Long userId) {}
