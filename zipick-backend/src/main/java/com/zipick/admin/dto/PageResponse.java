package com.zipick.admin.dto;

import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/** 운영자 API 공통 페이지 응답. page 는 1부터 (NoticePageResponse 의 pageNo 와 같은 기준) */
public record PageResponse<T>(
        int page,
        int size,
        long totalElements,
        int totalPages,
        List<T> items
) {
    public static <E, T> PageResponse<T> of(Page<E> page, Function<E, T> mapper) {
        return new PageResponse<>(
                page.getNumber() + 1,
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.getContent().stream().map(mapper).toList()
        );
    }
}
