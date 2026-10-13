package com.zipick.external.myhome.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.util.List;

public record MyHomeApiResponse<T>(ApiResponse<T> response) {

    public record ApiResponse<T>(Header header, Body<T> body) {
    }

    public record Header(String resultCode, String resultMsg) {
    }

    public record Body<T>(
            String totalCount,
            String numOfRows,
            String pageNo,
            @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
            List<T> item
    ) {
        public Body {
            item = item == null ? List.of() : List.copyOf(item);
        }
    }
}
