package com.zipick.external.housingdetail;

import com.zipick.notice.dto.NoticeResponse;

import java.util.List;

public interface HousingDetailSource {

    boolean supports(NoticeResponse notice);

    List<DetailedHousingUnit> extract(NoticeResponse notice);
}
