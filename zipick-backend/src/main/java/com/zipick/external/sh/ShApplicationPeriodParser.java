package com.zipick.external.sh;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
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
            "(?<!\\d)(?:(?<year>20\\d{2})\\s*[.년/-]\\s*)?"
                    + "(?<month>\\d{1,2})\\s*[.월/-]\\s*(?<day>\\d{1,2})(?!\\d)"
    );
    private static final Pattern APPLICATION_LABEL = Pattern.compile(
            "인터넷\\s*(?:청약\\s*)?(?:신청|접수)|온라인\\s*(?:청약\\s*)?(?:신청|접수)|"
                    + "청약\\s*신청기간|청약\\s*신청(?![가-힣])|청약\\s*접수(?![가-힣])|청약기간|신청기간|접수기간|접수일|신청접수"
    );
    private static final Pattern SPACED_PERIOD_LABEL = Pattern.compile("접\\s*수\\s*기\\s*간|신\\s*청\\s*기\\s*간");
    private static final Pattern SHORT_DATE_RANGE = Pattern.compile(
            "(?<!\\d)(?<sy>20\\d{2}|\\d{2})\\s*[./-]\\s*(?<sm>\\d{1,2})\\s*[./-]\\s*(?<sd>\\d{1,2})"
                    + "\\s*\\.?\\s*[~～-]\\s*(?<ey>20\\d{2}|\\d{2})\\s*[./-]\\s*(?<em>\\d{1,2})\\s*[./-]\\s*(?<ed>\\d{1,2})(?!\\d)"
    );
    private static final Pattern NEXT_SECTION = Pattern.compile(
            "[■○]\\s*(?:신청자격|신청방법|신청문의|모집호수|공급호수|기타|유의사항|"
                    + "서류|계약|예비입주자\\s*발표|당첨[^:：]{0,20}(?:발표)?|입주기간)"
    );

    public Optional<ShApplicationPeriod> parse(String html) {
        Document document = Jsoup.parse(html);
        Element content = document.selectFirst(".detailTable td.cont");
        return parseText(content == null ? document.wholeText() : content.wholeText());
    }

    public Optional<ShApplicationPeriod> parseText(String sourceText) {
        String text = sourceText == null ? "" : sourceText.replace('\u00a0', ' ');
        text = SPACED_PERIOD_LABEL.matcher(text).replaceAll(match ->
                match.group().replaceAll("\\s+", ""));
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

    /** 모집일정 표에서만 짧은 연도 표기를 사용한 서류접수 범위를 보완한다. */
    Optional<ShApplicationPeriod> parseDocumentText(String text) {
        if (text == null) return Optional.empty();
        Matcher schedule = Pattern.compile("모집\\s*일정").matcher(text);
        while (schedule.find()) {
            String section = text.substring(schedule.end(), Math.min(text.length(), schedule.end() + 1000));
            Matcher header = Pattern.compile("서류\\s*접수").matcher(section);
            if (!header.find()) continue;
            String window = section.substring(header.start(), Math.min(section.length(), header.start() + 400));
            Matcher range = SHORT_DATE_RANGE.matcher(window);
            if (range.find()) try {
                LocalDate start = LocalDate.of(normalizeYear(range.group("sy")),
                        Integer.parseInt(range.group("sm")), Integer.parseInt(range.group("sd")));
                LocalDate end = LocalDate.of(normalizeYear(range.group("ey")),
                        Integer.parseInt(range.group("em")), Integer.parseInt(range.group("ed")));
                if (!end.isBefore(start)) return Optional.of(new ShApplicationPeriod(start, end));
            } catch (DateTimeException ignored) {
                // 유효하지 않은 날짜는 접수기간으로 사용하지 않는다.
            }
            // PDF 표의 열이 뒤섞여 시작일과 '~종료일' 사이에 다른 열의 날짜가 끼는 경우.
            Matcher endMarker = Pattern.compile("[~～]\\s*(20\\d{2}|\\d{2})[./-](\\d{1,2})[./-](\\d{1,2})").matcher(window);
            if (endMarker.find()) {
                try {
                    LocalDate end = LocalDate.of(normalizeYear(endMarker.group(1)),
                            Integer.parseInt(endMarker.group(2)), Integer.parseInt(endMarker.group(3)));
                    Matcher before = Pattern.compile("(?<!\\d)(20\\d{2}|\\d{2})[./-](\\d{1,2})[./-](\\d{1,2})(?!\\d)")
                            .matcher(window.substring(0, endMarker.start()));
                    LocalDate start = null;
                    while (before.find()) {
                        LocalDate candidate = LocalDate.of(normalizeYear(before.group(1)),
                                Integer.parseInt(before.group(2)), Integer.parseInt(before.group(3)));
                        if (!candidate.isAfter(end) && (start == null || candidate.isAfter(start))) start = candidate;
                    }
                    if (start != null && !endMarker.find()) return Optional.of(new ShApplicationPeriod(start, end));
                } catch (DateTimeException ignored) {
                    // 식별할 수 없는 표는 확인 대기 상태로 남긴다.
                }
            }
        }
        return parseText(text);
    }

    private int normalizeYear(String value) {
        int year = Integer.parseInt(value);
        return year < 100 ? 2000 + year : year;
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
        List<Integer> starts = new ArrayList<>();
        List<Integer> ends = new ArrayList<>();
        while (matcher.find()) {
            dates.add(new PartialDate(
                    matcher.group("year") == null ? null : Integer.parseInt(matcher.group("year")),
                    Integer.parseInt(matcher.group("month")),
                    Integer.parseInt(matcher.group("day"))
            ));
            starts.add(matcher.start());
            ends.add(matcher.end());
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
            String betweenDates = line.substring(ends.getFirst(), starts.get(1));
            if (betweenDates.length() > 60 || !betweenDates.matches("(?s).*(?:~|～|\\s-\\s).*")) {
                return Optional.empty();
            }
            PartialDate second = dates.get(1);
            int endYear = second.year() == null ? start.getYear() : second.year();
            LocalDate end = LocalDate.of(endYear, second.month(), second.day());
            if (second.year() == null && end.isBefore(start)) {
                end = end.plusYears(1);
            }
            Matcher step = Pattern.compile("(?m)^[ \\t]*(?:특별공급|일반공급|\\d+순위(?:자)?)[^\\r\\n]*").matcher(line);
            while (step.find()) {
                Matcher stepDates = DATE.matcher(step.group());
                while (stepDates.find()) {
                    int year = stepDates.group("year") == null ? start.getYear()
                            : Integer.parseInt(stepDates.group("year"));
                    LocalDate date = LocalDate.of(year, Integer.parseInt(stepDates.group("month")),
                            Integer.parseInt(stepDates.group("day")));
                    if (stepDates.group("year") == null && date.isBefore(start)) date = date.plusYears(1);
                    if (date.isAfter(end)) end = date;
                }
            }
            return end.isBefore(start)
                    ? Optional.empty()
                    : Optional.of(new ShApplicationPeriod(start, end));
        } catch (DateTimeException exception) {
            return Optional.empty();
        }
    }

    private int priority(String line) {
        if (line.matches(".*(?:인터넷|온라인)\\s*(?:청약\\s*)?(?:신청|접수).*")
                || line.matches(".*청약\\s*(?:신청|접수).*")
                && !line.contains("신청기간")) return 0;
        if (line.matches(".*청약\\s*신청기간.*") || line.contains("청약기간")) return 1;
        if (line.contains("접수기간") || line.contains("접수일")) return 2;
        return 3;
    }

    private record PartialDate(Integer year, int month, int day) {
    }

    private record Candidate(int priority, ShApplicationPeriod period) {
    }
}
