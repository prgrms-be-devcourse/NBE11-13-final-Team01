package com.zipick.external.housingdetail;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class LhPdfHousingParser {

    private static final String PROVINCE = "(?:서울특별시|부산광역시|대구광역시|인천광역시|광주광역시|"
            + "대전광역시|울산광역시|세종특별자치시|경기도|강원특별자치도|강원도|충청북도|충청남도|"
            + "전북특별자치도|전라북도|전라남도|경상북도|경상남도|제주특별자치도|제주도)";
    private static final String ADMIN = "(?:\\s+[가-힣0-9]+(?:시|군|구)){1,2}(?:\\s+[가-힣0-9]+(?:읍|면))?";
    private static final String ROAD_NAME = "(?:[가-힣A-Za-z0-9·.\\-]+(?:대로|로|길)"
            + "(?:\\s*\\d+(?:번길|길))?|[가-힣A-Za-z0-9·.\\-]+\\s*\\d+(?:번길|길))";
    private static final Pattern ROAD_ADDRESS = Pattern.compile(
            "(?<address>" + PROVINCE + ADMIN
                    + "\\s+" + ROAD_NAME + "\\s+\\d+(?:-\\d+)?(?:\\s*\\([^)]*\\))?)"
    );
    private static final Pattern LOT_ADDRESS = Pattern.compile(
            "(?<address>" + PROVINCE + ADMIN
                    + "\\s+[가-힣A-Za-z0-9·.\\-]+(?:동|리|가)\\s+\\d+(?:-\\d+)?(?:\\s*\\([^)]*\\))?)"
    );
    private static final Pattern MULTI_LOT_ADDRESS = Pattern.compile(
            "(?<base>(?:" + PROVINCE + "\\s+)?(?:[가-힣0-9]+(?:시|군|구)\\s+){1,2}"
                    + "(?:[가-힣0-9]+(?:읍|면)\\s+)?[가-힣0-9]+(?:동|리|가)\\s+)"
                    + "(?<lots>\\d+(?:-\\d+)?(?:\\s*,\\s*\\d+(?:-\\d+)?)+)"
    );
    private static final Pattern SUPPLY_NAME = Pattern.compile(
            "공급주택\\s*[:：]\\s*(?<name>[^()\\r\\n]+?)\\s*\\("
    );
    private static final Pattern PROVINCE_PATTERN = Pattern.compile(PROVINCE);
    /** 일반구(시 아래 구). 자치구와 글자 모양은 같지만 여기서는 '시' 바로 뒤에 올 때만 본다 */
    private static final Pattern GENERAL_GU = Pattern.compile("^[가-힣]{1,5}구$");
    private static final Pattern NON_HOUSING_CONTEXT = Pattern.compile(
            "신청\\s*장소|접수\\s*장소|계약\\s*장소|서류\\s*제출|문의처|연락처|콜센터|"
                    + "지역본부|주거복지(?:지사|센터)|사업단|견본주택|모델하우스|홍보관"
    );

    public List<DetailedHousingUnit> parse(byte[] bytes, LhAttachment attachment) {
        return parse(bytes, attachment, "LH_PDF");
    }

    List<DetailedHousingUnit> parse(byte[] bytes, LhAttachment attachment, String sourcePrefix) {
        try (PDDocument document = Loader.loadPDF(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return parseText(stripper.getText(document), attachment, sourcePrefix);
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("LH 공급주택 PDF를 읽지 못했습니다: " + attachment.fileName(), exception);
        }
    }

    List<DetailedHousingUnit> parseText(String text, LhAttachment attachment) {
        return parseText(text, attachment, "LH_PDF");
    }

    List<DetailedHousingUnit> parseText(String text, LhAttachment attachment, String sourcePrefix) {
        List<DetailedHousingUnit> units = new ArrayList<>();
        Set<String> seenAddresses = new LinkedHashSet<>();
        String provinceHint = findSingleProvince(text);
        int rowNumber = 0;
        for (String line : text.split("\\R")) {
            if (NON_HOUSING_CONTEXT.matcher(line).find()) {
                continue;
            }
            String complexName = null;
            Matcher nameMatcher = SUPPLY_NAME.matcher(line);
            if (nameMatcher.find()) {
                complexName = nameMatcher.group("name").trim();
            }
            String normalizedLine = line.trim().replaceAll("\\s+", " ");
            for (String foundAddress : findAddresses(line)) {
                String address = addProvinceIfMissing(foundAddress, provinceHint);
                if (!seenAddresses.add(address.replaceAll("\\s+", ""))) {
                    continue;
                }
                rowNumber++;
                units.add(new DetailedHousingUnit(
                        sourcePrefix + ":" + attachment.fileId() + ":" + rowNumber,
                        complexName,
                        provinceName(address),
                        districtName(address),
                        address,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        "{\"sourceLine\":\"" + escapeJson(normalizedLine) + "\"}"
                ));
            }
        }
        return List.copyOf(units);
    }

    private List<String> findAddresses(String line) {
        Matcher multiLot = MULTI_LOT_ADDRESS.matcher(line);
        if (multiLot.find()) {
            String base = multiLot.group("base").trim().replaceAll("\\s+", " ");
            List<String> addresses = new ArrayList<>();
            for (String lot : multiLot.group("lots").split("\\s*,\\s*")) {
                addresses.add(base + " " + lot);
            }
            return List.copyOf(addresses);
        }
        String address = match(line, ROAD_ADDRESS);
        address = address == null ? match(line, LOT_ADDRESS) : address;
        return address == null ? List.of() : List.of(address);
    }

    private String findSingleProvince(String text) {
        Set<String> provinces = new LinkedHashSet<>();
        Matcher matcher = PROVINCE_PATTERN.matcher(text);
        while (matcher.find()) {
            provinces.add(matcher.group());
        }
        return provinces.size() == 1 ? provinces.iterator().next() : null;
    }

    private String addProvinceIfMissing(String address, String provinceHint) {
        if (PROVINCE_PATTERN.matcher(address).lookingAt() || provinceHint == null) {
            return address;
        }
        return provinceHint + " " + address;
    }

    private String provinceName(String address) {
        Matcher matcher = PROVINCE_PATTERN.matcher(address);
        return matcher.lookingAt() ? matcher.group() : null;
    }

    /**
     * 시군구. 일반구가 있는 도시는 <b>"천안시 서북구"처럼 구까지</b> 읽는다.
     *
     * <p>예전에는 토큰 하나만 읽어 "충청남도 천안시 서북구 …" 에서도 "천안시"만 남았다.
     * 그러면 같은 도시가 "천안시"와 "천안시 서북구" 두 지역으로 갈려 필터·추천 매칭이 어긋난다.
     * 주소에 구가 없으면 예전처럼 시까지만 돌려준다(없는 구를 추측하지 않는다).
     */
    private String districtName(String address) {
        String[] parts = address.trim().split("\\s+");
        int start = provinceName(address) == null ? 0 : 1;
        if (parts.length <= start) {
            return null;
        }
        String city = parts[start];
        if (city.endsWith("시") && parts.length > start + 1 && GENERAL_GU.matcher(parts[start + 1]).matches()) {
            return city + " " + parts[start + 1];
        }
        return city;
    }

    private String match(String line, Pattern pattern) {
        Matcher matcher = pattern.matcher(line);
        if (!matcher.find()) {
            return null;
        }
        return matcher.group("address").trim().replaceAll("\\s+", " ");
    }

    private String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
