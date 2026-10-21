package com.zipick.notice.service;

import com.zipick.notice.dto.NoticeResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NoticeRevisionPolicyTest {

    @Test
    void keepsOnlyLatestRevisionWhenSourceProvidesOriginalAndCorrection() {
        NoticeSyncCommand original = command("100", null, "일반공고");
        NoticeSyncCommand correction = command("101", "100", "정정공고");

        List<NoticeSyncCommand> result = NoticeRevisionPolicy.latestOnly(List.of(original, correction));

        assertThat(result).extracting(command -> command.notice().noticeId()).containsExactly("101");
        assertThat(NoticeRevisionPolicy.previousNoticeIds(List.of(original, correction)))
                .containsExactly("100");
    }

    @Test
    void doesNotMergeNoticesOnlyBecauseTheirTitlesAreEqual() {
        NoticeSyncCommand first = command("100", null, "같은 제목");
        NoticeSyncCommand second = command("200", null, "같은 제목");

        assertThat(NoticeRevisionPolicy.latestOnly(List.of(first, second))).hasSize(2);
    }

    private NoticeSyncCommand command(String id, String previousId, String title) {
        NoticeResponse notice = new NoticeResponse(
                "TEST", id, "공고중", title, "테스트기관", previousId,
                "2026-10-22", null, null, null, null,
                "https://example.com/" + id, null, null, List.of(), List.of()
        );
        return new NoticeSyncCommand(notice, "{}");
    }
}
