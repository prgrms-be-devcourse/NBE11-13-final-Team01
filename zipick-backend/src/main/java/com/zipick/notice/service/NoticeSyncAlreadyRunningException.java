package com.zipick.notice.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
public class NoticeSyncAlreadyRunningException extends RuntimeException {

    public NoticeSyncAlreadyRunningException() {
        super("공고 동기화가 이미 실행 중입니다.");
    }
}
