package com.zipick.external.housingdetail;

import com.zipick.notice.dto.NoticeAttachmentResponse;

import java.util.List;
import java.util.Map;

public record HousingDetailResult(
        List<DetailedHousingUnit> housings,
        List<NoticeAttachmentResponse> attachments,
        Map<String, List<DetailedHousingMedia>> mediaByUnitKey,
        boolean preservesExistingHousings,
        List<com.zipick.notice.dto.ParseCheckpoint> parseCheckpoints
) {
    public HousingDetailResult(List<DetailedHousingUnit> housings, List<NoticeAttachmentResponse> attachments,
                               Map<String, List<DetailedHousingMedia>> mediaByUnitKey, boolean preservesExistingHousings) {
        this(housings, attachments, mediaByUnitKey, preservesExistingHousings, List.of());
    }
    public HousingDetailResult(List<DetailedHousingUnit> housings, List<NoticeAttachmentResponse> attachments,
                               Map<String, List<DetailedHousingMedia>> mediaByUnitKey) {
        this(housings, attachments, mediaByUnitKey, false, List.of());
    }
    public HousingDetailResult {
        housings = housings == null ? List.of() : List.copyOf(housings);
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
        mediaByUnitKey = mediaByUnitKey == null ? Map.of() : Map.copyOf(mediaByUnitKey);
        parseCheckpoints = parseCheckpoints == null ? List.of() : List.copyOf(parseCheckpoints);
    }

    public static HousingDetailResult empty() {
        return new HousingDetailResult(List.of(), List.of(), Map.of());
    }
}
