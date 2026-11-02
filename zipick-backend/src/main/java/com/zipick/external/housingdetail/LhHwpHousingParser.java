package com.zipick.external.housingdetail;

import org.apache.tika.Tika;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.zip.ZipInputStream;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;

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
            String text = attachment.fileName().toLowerCase(java.util.Locale.ROOT).endsWith(".hwpx")
                    ? extractHwpxText(bytes) : tika.parseToString(input);
            return textHousingParser.parseText(text, attachment, sourcePrefix);
        } catch (Exception exception) {
            throw new IllegalStateException("LH 공급주택 HWP를 읽지 못했습니다: " + attachment.fileName(), exception);
        }
    }

    private String extractHwpxText(byte[] bytes) throws Exception {
        StringBuilder text = new StringBuilder();
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (!entry.getName().matches("Contents/section[0-9]+\\.xml")) continue;
                var reader = factory.createXMLStreamReader(new java.io.FilterInputStream(zip) {
                    @Override public void close() { }
                });
                boolean inText = false;
                while (reader.hasNext()) {
                    int event = reader.next();
                    if (event == XMLStreamConstants.START_ELEMENT && "t".equals(reader.getLocalName())) inText = true;
                    else if (event == XMLStreamConstants.CHARACTERS && inText) {
                        text.append(reader.getText());
                        if (text.length() > 5_000_000) throw new IllegalStateException("HWPX 텍스트 크기 제한 초과");
                    } else if (event == XMLStreamConstants.END_ELEMENT) {
                        if ("t".equals(reader.getLocalName())) inText = false;
                        if ("p".equals(reader.getLocalName())) text.append('\n');
                    }
                }
                reader.close();
            }
        }
        return text.toString();
    }
}
