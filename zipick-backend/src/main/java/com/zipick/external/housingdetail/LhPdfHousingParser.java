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
    private static final Pattern ROAD_ADDRESS = Pattern.compile(
            "(?<address>" + PROVINCE + ADMIN
                    + "\\s+[가-힣A-Za-z0-9·.\\-]+(?:대로|로|길)\\s*\\d+(?:-\\d+)?(?:\\s*\\([^)]*\\))?)"
    );
    private static final Pattern LOT_ADDRESS = Pattern.compile(
            "(?<address>" + PROVINCE + ADMIN
                    + "\\s+[가-힣A-Za-z0-9·.\\-]+(?:동|리|가)\\s+\\d+(?:-\\d+)?(?:\\s*\\([^)]*\\))?)"
    );

    public List<DetailedHousingUnit> parse(byte[] bytes, LhAttachment attachment) {
        try (PDDocument document = Loader.loadPDF(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return parseText(stripper.getText(document), attachment);
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("LH 공급주택 PDF를 읽지 못했습니다: " + attachment.fileName(), exception);
        }
    }

    List<DetailedHousingUnit> parseText(String text, LhAttachment attachment) {
        List<DetailedHousingUnit> units = new ArrayList<>();
        Set<String> seenRows = new LinkedHashSet<>();
        int rowNumber = 0;
        for (String line : text.split("\\R")) {
            String address = findAddress(line);
            if (address == null) {
                continue;
            }
            String normalizedLine = line.trim().replaceAll("\\s+", " ");
            if (!seenRows.add(normalizedLine)) {
                continue;
            }
            rowNumber++;
            units.add(new DetailedHousingUnit(
                    "LH_PDF:" + attachment.fileId() + ":" + rowNumber,
                    null,
                    addressPart(address, 0),
                    addressPart(address, 1),
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
        return List.copyOf(units);
    }

    private String findAddress(String line) {
        String address = match(line, ROAD_ADDRESS);
        return address == null ? match(line, LOT_ADDRESS) : address;
    }

    private String match(String line, Pattern pattern) {
        Matcher matcher = pattern.matcher(line);
        if (!matcher.find()) {
            return null;
        }
        return matcher.group("address").trim().replaceAll("\\s+", " ");
    }

    private String addressPart(String address, int index) {
        String[] parts = address.split(" ");
        return parts.length > index ? parts[index] : null;
    }

    private String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
