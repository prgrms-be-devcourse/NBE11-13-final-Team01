package com.zipick.applicationguide.dto;

/**
 * notice_attachment 한 행의 복사본.
 *
 * 엔티티를 그대로 들고 다니지 않는 이유는, 가이드 생성이 트랜잭션 밖에서(문서 다운로드·AI 호출)
 * 오래 돌기 때문이다. open-in-view 가 꺼져 있어 지연 로딩 엔티티를 밖으로 들고 나가면 터진다.
 */
public record GuideAttachment(
        Long attachmentId,
        String attachmentType,
        String fileName,
        String sourceUrl
) {}
