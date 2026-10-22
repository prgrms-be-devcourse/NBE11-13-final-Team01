package com.zipick.notice.service;

import org.springframework.util.StringUtils;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class NoticeRevisionPolicy {

    private NoticeRevisionPolicy() {
    }

    public static List<NoticeSyncCommand> latestOnly(List<NoticeSyncCommand> commands) {
        Set<String> replacedIds = previousNoticeIds(commands);
        return commands.stream()
                .filter(command -> !replacedIds.contains(command.notice().noticeId()))
                .toList();
    }

    public static Set<String> previousNoticeIds(List<NoticeSyncCommand> commands) {
        Set<String> result = new HashSet<>();
        for (NoticeSyncCommand command : commands) {
            String previousId = command.notice().previousNoticeId();
            if (StringUtils.hasText(previousId)) {
                result.add(previousId.trim());
            }
        }
        return Set.copyOf(result);
    }
}
