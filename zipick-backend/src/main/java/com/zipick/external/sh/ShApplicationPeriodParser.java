package com.zipick.external.sh;

import org.jsoup.Jsoup;
import org.springframework.stereotype.Component;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ShApplicationPeriodParser {

    private static final Pattern DATE = Pattern.compile(
            "(?:(?<year>20\\d{2})\\s*[.년/-]\\s*)?(?<month>\\d{1,2})\\s*[.월/-]\\s*(?<day>\\d{1,2})"
    );
    private static final Pattern APPLICATION_LABEL = Pattern.compile(
            "인터넷\\s*청약신청|청약\\s*신청기간|청약기간|신청기간|접수기간|접수일|신청접수"
    );
    private static final Pattern NEXT_SECTION = Pattern.compile(
            "[■○]\\s*(?:서류|계약|당첨[^:：]{0,20}(?:발표)?|입주기간)"
    );

    public Optional<ShApplicationPeriod> parse(String html) {
        String text = Jsoup.parse(html).wholeText().replace('\u00a0', ' ');
        List<Candidate> candidates = new ArrayList<>();
        Matcher labelMatcher = APPLICATION_LABEL.matcher(text);
        while (labelMatcher.find()) {
            String prefix = text.substring(Math.max(0, labelMatcher.start() - 12), labelMatcher.start());
            if (prefix.matches(".*(?:서류|계약|당첨|발표|입주).*$")) {
                continue;
            }
            String section = applicationSection(text, labelMatcher.start());
            parseLine(section).ifPresent(period ->
                    candidates.add(new Candidate(priority(labelMatcher.group()), period)));
        }
        return candidates.stream()
                .min(Comparator.comparingInt(Candidate::priority))
                .map(Candidate::period);
    }

    private String applicationSection(String text, int startIndex) {
        Matcher nextSection = NEXT_SECTION.matcher(text);
        int endIndex = text.length();
        if (nextSection.find(startIndex)) {
            endIndex = nextSection.start();
        }
        // 한 구간에서 연락처나 다른 일정을 과도하게 읽지 않는다.
        endIndex = Math.min(endIndex, startIndex + 1_000);
        return text.substring(startIndex, endIndex).trim();
    }

    private Optional<ShApplicationPeriod> parseLine(String line) {
        Matcher matcher = DATE.matcher(line);
        List<PartialDate> dates = new ArrayList<>();
        while (matcher.find()) {
            dates.add(new PartialDate(
                    matcher.group("year") == null ? null : Integer.parseInt(matcher.group("year")),
                    Integer.parseInt(matcher.group("month")),
                    Integer.parseInt(matcher.group("day"))
            ));
        }
        if (dates.isEmpty() || dates.getFirst().year() == null) {
            return Optional.empty();
        }
        try {
            PartialDate first = dates.getFirst();
            LocalDate start = LocalDate.of(first.year(), first.month(), first.day());
            if (dates.size() == 1) {
                return Optional.of(new ShApplicationPeriod(start, start));
            }
            PartialDate second = dates.getLast();
            int endYear = second.year() == null ? start.getYear() : second.year();
            LocalDate end = LocalDate.of(endYear, second.month(), second.day());
            if (second.year() == null && end.isBefore(start)) {
                end = end.plusYears(1);
            }
            return end.isBefore(start)
                    ? Optional.empty()
                    : Optional.of(new ShApplicationPeriod(start, end));
        } catch (DateTimeException exception) {
            return Optional.empty();
        }
    }

    private int priority(String line) {
        if (line.matches(".*인터넷\\s*청약신청.*")) return 0;
        if (line.matches(".*청약\\s*신청기간.*") || line.contains("청약기간")) return 1;
        if (line.contains("접수기간") || line.contains("접수일")) return 2;
        return 3;
    }

    private record PartialDate(Integer year, int month, int day) {
    }

    private record Candidate(int priority, ShApplicationPeriod period) {
    }
}
