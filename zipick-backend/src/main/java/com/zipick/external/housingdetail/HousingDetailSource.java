package com.zipick.external.housingdetail;

import com.zipick.notice.dto.NoticeResponse;

public interface HousingDetailSource {

    boolean supports(NoticeResponse notice);

    HousingDetailResult extract(NoticeResponse notice);
}
