package com.zipick.external.myhome.controller;

import com.zipick.external.myhome.client.MyHomeApiClient;
import com.zipick.external.myhome.dto.MyHomeApiResponse;
import com.zipick.external.myhome.dto.MyHomeComplex;
import com.zipick.external.myhome.dto.MyHomeComplexQuery;
import com.zipick.external.myhome.dto.MyHomeNotice;
import com.zipick.external.myhome.dto.MyHomeNoticeQuery;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/external/myhome")
public class MyHomeApiController {

    private final MyHomeApiClient myHomeApiClient;

    public MyHomeApiController(MyHomeApiClient myHomeApiClient) {
        this.myHomeApiClient = myHomeApiClient;
    }

    @GetMapping("/notices")
    public MyHomeApiResponse<MyHomeNotice> getNotices(
            @RequestParam(required = false) String brtcCode,
            @RequestParam(required = false) String signguCode,
            @RequestParam(defaultValue = "1") @Min(1) int pageNo,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int numOfRows,
            @RequestParam(required = false) String suplyTy,
            @RequestParam(required = false) String houseTy,
            @RequestParam(required = false) String lfstsTyAt,
            @RequestParam(required = false) String bassMtRntchrgSe,
            @RequestParam(required = false) String yearMtBegin,
            @RequestParam(required = false) String yearMtEnd
    ) {
        MyHomeNoticeQuery query = new MyHomeNoticeQuery(
                brtcCode,
                signguCode,
                pageNo,
                numOfRows,
                suplyTy,
                houseTy,
                lfstsTyAt,
                bassMtRntchrgSe,
                yearMtBegin,
                yearMtEnd
        );
        return myHomeApiClient.getNotices(query);
    }

    @GetMapping("/complexes")
    public MyHomeApiResponse<MyHomeComplex> getComplexes(
            @RequestParam @NotBlank String brtcCode,
            @RequestParam @NotBlank String signguCode,
            @RequestParam(defaultValue = "1") @Min(1) int pageNo,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int numOfRows
    ) {
        return myHomeApiClient.getComplexes(
                new MyHomeComplexQuery(brtcCode, signguCode, pageNo, numOfRows)
        );
    }
}
