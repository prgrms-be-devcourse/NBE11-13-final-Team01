package com.zipick.external.housingdetail;

import org.apache.tika.Tika;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.util.List;

@Component
public class LhHwpHousingParser {

    private final LhPdfHousingParser textHousingParser;
    private final Tika tika = new Tika();

    public LhHwpHousingParser(LhPdfHousingParser textHousingParser) {
        this.textHousingParser = textHousingParser;
    }

    public List<DetailedHousingUnit> parse(byte[] bytes, LhAttachment attachment) {
        return parse(bytes, attachment, "LH_HWP");
    }

    List<DetailedHousingUnit> parse(byte[] bytes, LhAttachment attachment, String sourcePrefix) {
        try (ByteArrayInputStream input = new ByteArrayInputStream(bytes)) {
            String text = tika.parseToString(input);
            return textHousingParser.parseText(text, attachment, sourcePrefix);
        } catch (Exception exception) {
            throw new IllegalStateException("LH 공급주택 HWP를 읽지 못했습니다: " + attachment.fileName(), exception);
        }
    }
}
