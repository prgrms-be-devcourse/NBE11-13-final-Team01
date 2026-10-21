package com.zipick.notice.service;

import com.zipick.external.myhome.dto.MyHomeNotice;
import com.zipick.notice.dto.NoticeHousingResponse;
import com.zipick.notice.dto.NoticeResponse;
import com.zipick.notice.dto.NoticeSupplyResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

@Component
public class MyHomeNoticeAggregator {

    public List<NoticeResponse> aggregate(List<MyHomeNotice> rows) {
        Map<String, List<MyHomeNotice>> rowsByNoticeId = new LinkedHashMap<>();

        for (MyHomeNotice row : rows) {
            if (row == null || !StringUtils.hasText(row.pblancId())) {
                continue;
            }
            rowsByNoticeId.computeIfAbsent(row.pblancId().trim(), ignored -> new ArrayList<>()).add(row);
        }

        return rowsByNoticeId.values().stream()
                .map(this::toNotice)
                .toList();
    }

    private NoticeResponse toNotice(List<MyHomeNotice> rows) {
        return new NoticeResponse(
                source(firstText(rows, MyHomeNotice::suplyInsttNm)),
                firstText(rows, MyHomeNotice::pblancId),
                firstText(rows, MyHomeNotice::sttusNm),
                firstText(rows, MyHomeNotice::pblancNm),
                firstText(rows, MyHomeNotice::suplyInsttNm),
                firstText(rows, MyHomeNotice::beforePblancId),
                firstText(rows, MyHomeNotice::rcritPblancDe),
                firstText(rows, MyHomeNotice::przwnerPresnatnDe),
                firstText(rows, MyHomeNotice::beginDe),
                firstText(rows, MyHomeNotice::endDe),
                firstText(rows, MyHomeNotice::refrnc),
                firstText(rows, MyHomeNotice::url),
                firstText(rows, MyHomeNotice::pcUrl),
                firstText(rows, MyHomeNotice::mobileUrl),
                aggregateHouses(rows),
                List.of()
        );
    }

    private String source(String institutionName) {
        if (!StringUtils.hasText(institutionName)) {
            return "MYHOME";
        }
        String normalized = institutionName.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
        if (normalized.equals("LH") || normalized.contains("한국토지주택공사")) {
            return "LH";
        }
        if (normalized.equals("BMC") || normalized.contains("부산도시공사")) {
            return "BMC";
        }
        if (normalized.equals("GBDC") || normalized.contains("경상북도개발공사")) {
            return "GBDC";
        }
        return "MYHOME";
    }

    private List<NoticeHousingResponse> aggregateHouses(List<MyHomeNotice> rows) {
        Map<String, List<MyHomeNotice>> rowsByLocation = new LinkedHashMap<>();
        List<MyHomeNotice> rowsWithoutLocation = new ArrayList<>();
        int rowSequence = 0;

        for (MyHomeNotice row : rows) {
            String key = locationKey(row);
            if (key == null) {
                rowsWithoutLocation.add(row);
            } else {
                rowsByLocation.computeIfAbsent(key, ignored -> new ArrayList<>()).add(row);
            }
        }

        for (MyHomeNotice row : rowsWithoutLocation) {
            String matchingLocation = matchingKnownLocation(row, rowsByLocation);
            if (matchingLocation != null) {
                rowsByLocation.get(matchingLocation).add(row);
                continue;
            }
            String key = unlocatedHousingKey(row, rowSequence++);
            rowsByLocation.computeIfAbsent(key, ignored -> new ArrayList<>()).add(row);
        }

        return rowsByLocation.entrySet().stream()
                .map(entry -> toHousing(entry.getKey(), entry.getValue()))
                .toList();
    }

    private String matchingKnownLocation(
            MyHomeNotice unlocated,
            Map<String, List<MyHomeNotice>> rowsByLocation
    ) {
        if (unlocated.houseSn() == null) {
            return null;
        }
        String match = null;
        for (Map.Entry<String, List<MyHomeNotice>> entry : rowsByLocation.entrySet()) {
            boolean sameHouse = entry.getValue().stream()
                    .anyMatch(row -> Objects.equals(row.houseSn(), unlocated.houseSn()));
            if (!sameHouse) {
                continue;
            }
            if (match != null) {
                return null;
            }
            match = entry.getKey();
        }
        return match;
    }

    private NoticeHousingResponse toHousing(String sourceHousingKey, List<MyHomeNotice> rows) {
        return new NoticeHousingResponse(
                sourceHousingKey,
                firstText(rows, MyHomeNotice::hsmpNm),
                firstText(rows, MyHomeNotice::brtcNm),
                firstText(rows, MyHomeNotice::signguNm),
                firstText(rows, MyHomeNotice::fullAdres),
                firstText(rows, MyHomeNotice::rnCodeNm),
                firstText(rows, MyHomeNotice::refrnLegaldongNm),
                firstText(rows, MyHomeNotice::pnu),
                firstText(rows, MyHomeNotice::heatMthdNm),
                firstText(rows, MyHomeNotice::totHshldCo),
                aggregateSupplies(rows),
                List.of()
        );
    }

    private List<NoticeSupplyResponse> aggregateSupplies(List<MyHomeNotice> rows) {
        Map<String, List<MyHomeNotice>> rowsBySupply = new LinkedHashMap<>();
        int rowSequence = 0;

        for (MyHomeNotice row : rows) {
            String supplyKey = supplyKey(row, rowSequence++);
            rowsBySupply.computeIfAbsent(supplyKey, ignored -> new ArrayList<>()).add(row);
        }

        return rowsBySupply.entrySet().stream()
                .map(entry -> toSupply(entry.getKey(), entry.getValue()))
                .toList();
    }

    private NoticeSupplyResponse toSupply(String sourceSupplyKey, List<MyHomeNotice> rows) {
        return new NoticeSupplyResponse(
                sourceSupplyKey,
                firstValue(rows, MyHomeNotice::houseSn),
                firstText(rows, MyHomeNotice::houseTyNm),
                firstText(rows, MyHomeNotice::suplyTyNm),
                null,
                null,
                null,
                null,
                null,
                firstValue(rows, MyHomeNotice::sumSuplyCo),
                firstText(rows, MyHomeNotice::suplyHoCo),
                firstValue(rows, MyHomeNotice::rentGtn),
                firstValue(rows, MyHomeNotice::enty),
                firstValue(rows, MyHomeNotice::prtpay),
                firstValue(rows, MyHomeNotice::surlus),
                firstValue(rows, MyHomeNotice::mtRntchrg),
                null
        );
    }

    private String locationKey(MyHomeNotice row) {
        if (StringUtils.hasText(row.fullAdres())) {
            return "ADDRESS:" + normalize(row.fullAdres());
        }
        if (StringUtils.hasText(row.pnu())) {
            return "PNU:" + normalize(row.pnu());
        }
        return null;
    }

    private String unlocatedHousingKey(MyHomeNotice row, int rowSequence) {
        if (row.houseSn() != null) {
            return "UNLOCATED:HOUSE_SN:" + row.houseSn();
        }
        return "UNLOCATED:ROW:" + rowSequence;
    }

    private String supplyKey(MyHomeNotice row, int rowSequence) {
        if (row.houseSn() != null) {
            return "HOUSE_SN:" + row.houseSn();
        }
        return "SUPPLY:" + normalize(row.houseTyNm())
                + ":" + Objects.toString(row.rentGtn(), "")
                + ":" + Objects.toString(row.mtRntchrg(), "")
                + ":" + rowSequence;
    }

    private String normalize(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.replaceAll("\\s+", "")
                .toLowerCase(Locale.ROOT);
    }

    private String firstText(List<MyHomeNotice> rows, Function<MyHomeNotice, String> getter) {
        return rows.stream()
                .map(getter)
                .filter(StringUtils::hasText)
                .map(String::trim)
                .findFirst()
                .orElse(null);
    }

    private <T> T firstValue(List<MyHomeNotice> rows, Function<MyHomeNotice, T> getter) {
        return rows.stream()
                .map(getter)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }
}
