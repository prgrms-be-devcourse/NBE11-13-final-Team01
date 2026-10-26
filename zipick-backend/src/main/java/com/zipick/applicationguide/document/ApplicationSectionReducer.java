package com.zipick.applicationguide.document;

import com.zipick.applicationguide.config.ApplicationGuideProperties;
import com.zipick.applicationguide.dto.GuideSourceRef;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 공고문에서 신청 준비와 관련된 부분만 남긴다. <b>이 기능의 토큰 절약은 거의 전부 여기서 나온다.</b>
 *
 * <h3>왜 전체를 보내지 않는가</h3>
 * 모집공고 PDF 는 100쪽이 넘기도 한다(18만 자 안팎). 그대로 보내면 무료 사용량의 분당 토큰 한도에
 * 바로 걸리고, 응답도 느려지고, 공급주택 목록·도면 설명처럼 신청 준비와 무관한 문맥이 섞여
 * 결과 품질까지 나빠진다.
 *
 * <h3>어떻게 고르는가</h3>
 * <ol>
 *   <li>덩어리(PDF 는 한 쪽)마다 네 영역 키워드가 몇 번 나오는지 센다. 소제목 줄은 3배로 친다.</li>
 *   <li>점수가 있는 덩어리를 고르고, 앞뒤 neighborPages 만큼을 문맥으로 더한다.
 *       제출서류 표가 쪽을 넘어가는 경우가 많기 때문이다.</li>
 *   <li>글자 예산(maxInputChars) 안에서 담는다. 이때 <b>영역별 최고 점수 덩어리를 먼저</b> 담아
 *       네 영역 중 하나가 통째로 빠지는 일이 없게 한다.</li>
 *   <li>문서 순서대로 정렬해 S1, S2... 를 매긴다. 순서가 뒤섞이면 AI 가 앞뒤 문맥을 못 잇는다.</li>
 * </ol>
 *
 * <h3>키워드를 하나도 못 찾으면</h3>
 * 전체 문서를 그대로 보내는 폴백은 <b>일부러 만들지 않았다.</b> 그 경우가 바로 150쪽을 통째로
 * 보내게 되는 상황이고, 무료 사용량을 한 번에 태우면서 품질도 보장할 수 없다.
 * "찾지 못했다" 고 알리고 원문을 안내하는 편이 낫다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApplicationSectionReducer {

    /** 소제목으로 볼 줄의 최대 길이. 짧은 줄에 키워드가 있으면 그 덩어리가 본론일 가능성이 높다 */
    private static final int HEADING_MAX_CHARS = 40;
    private static final int HEADING_WEIGHT = 3;

    private final ApplicationGuideProperties properties;

    /** 관련 영역을 하나도 못 찾으면 빈 값 */
    public Optional<ReducedDocument> reduce(ExtractedDocument document) {
        List<Scored> scored = document.blocks().stream().map(ApplicationSectionReducer::score).toList();
        List<Scored> matched = scored.stream().filter(Scored::matched).toList();
        if (matched.isEmpty()) {
            log.info("공고문에서 신청 준비 관련 영역을 찾지 못했습니다: file={}", document.fileName());
            return Optional.empty();
        }

        Set<Integer> selected = selectWithinBudget(scored, matched);
        List<DocumentBlock> blocks = selected.stream()
                .sorted()
                .map(i -> document.blocks().get(i))
                .toList();

        Map<String, GuideSourceRef> sources = new LinkedHashMap<>();
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < blocks.size(); i++) {
            DocumentBlock block = blocks.get(i);
            String sourceId = "S" + (i + 1);
            sources.put(sourceId, block.toSourceRef(document.fileName()));
            text.append(header(sourceId, document.fileName(), block)).append('\n')
                    .append(block.text()).append("\n\n");
        }

        Set<SectionCategory> found = EnumSet.noneOf(SectionCategory.class);
        matched.forEach(s -> found.addAll(s.categories()));

        int bodyChars = blocks.stream().mapToInt(DocumentBlock::length).sum();
        ReducedDocument reduced = new ReducedDocument(text.toString().strip(), sources, found,
                document.totalChars(), bodyChars,
                blocks.stream().map(DocumentBlock::page).filter(p -> p != null).toList());
        log.info("공고문 축약 완료: file={}, 원문 {}자 -> 본문 {}자({}% 덜어냄) + 출처 머리말 {}자 = 전송 {}자,"
                        + " 덩어리 {}/{}개, 찾은 영역={}",
                document.fileName(), reduced.originalChars(), reduced.bodyChars(), reduced.reductionPercent(),
                reduced.sourceHeaderChars(), reduced.sentChars(), blocks.size(), document.blockCount(), found);
        return Optional.of(reduced);
    }

    /**
     * 글자 예산 안에서 담을 덩어리를 고른다.
     *
     * 담는 순서가 곧 우선순위다.
     *   1) 영역별 최고 점수 덩어리  — 네 영역이 모두 입력에 들어가도록 보장한다
     *   2) 나머지 관련 덩어리       — 점수 높은 순
     *   3) 앞뒤 문맥 덩어리         — 표가 쪽을 넘어가는 경우 대비. 예산이 남을 때만
     */
    private Set<Integer> selectWithinBudget(List<Scored> scored, List<Scored> matched) {
        List<Integer> priority = new ArrayList<>();
        priority.addAll(topBlockPerCategory(matched));
        matched.stream()
                .sorted(Comparator.comparingInt(Scored::score).reversed())
                .map(Scored::index)
                .forEach(priority::add);
        priority.addAll(neighborIndexes(scored, matched));

        Set<Integer> selected = new LinkedHashSet<>();
        int used = 0;
        for (int index : priority) {
            if (selected.contains(index)) {
                continue;
            }
            int length = scored.get(index).length();
            if (used + length > properties.maxInputChars()) {
                // 하나도 못 담았는데 첫 덩어리가 예산보다 크면, 그거라도 잘라서 담는다
                if (selected.isEmpty()) {
                    selected.add(index);
                    used = properties.maxInputChars();
                }
                continue;
            }
            selected.add(index);
            used += length;
        }
        return selected;
    }

    /** 영역마다 가장 점수가 높은 덩어리 하나씩 */
    private static List<Integer> topBlockPerCategory(List<Scored> matched) {
        Map<SectionCategory, Scored> best = new EnumMap<>(SectionCategory.class);
        for (Scored block : matched) {
            for (SectionCategory category : block.categories()) {
                best.merge(category, block, (a, b) -> a.score() >= b.score() ? a : b);
            }
        }
        return best.values().stream().map(Scored::index).distinct().toList();
    }

    /** 관련 덩어리의 앞뒤 neighborPages 개 */
    private List<Integer> neighborIndexes(List<Scored> scored, List<Scored> matched) {
        Set<Integer> neighbors = new TreeSet<>();
        for (Scored block : matched) {
            for (int offset = 1; offset <= properties.neighborPages(); offset++) {
                addIfPresent(neighbors, scored, block.index() - offset);
                addIfPresent(neighbors, scored, block.index() + offset);
            }
        }
        return List.copyOf(neighbors);
    }

    private static void addIfPresent(Set<Integer> target, List<Scored> scored, int index) {
        if (index >= 0 && index < scored.size()) {
            target.add(index);
        }
    }

    private static String header(String sourceId, String fileName, DocumentBlock block) {
        StringBuilder header = new StringBuilder("[SOURCE_ID: ").append(sourceId).append("]\n")
                .append("FILE: ").append(fileName);
        if (block.page() != null) {
            header.append("\nPAGE: ").append(block.page());
        } else if (block.section() != null && !block.section().isBlank()) {
            header.append("\nSECTION: ").append(block.section());
        }
        return header.toString();
    }

    /** 덩어리 하나의 점수와 걸린 영역 */
    static Scored score(DocumentBlock block) {
        String squeezed = squeeze(block.text());
        Set<SectionCategory> categories = EnumSet.noneOf(SectionCategory.class);
        int total = 0;

        for (SectionCategory category : SectionCategory.values()) {
            int hits = 0;
            for (String keyword : category.keywords()) {
                hits += count(squeezed, keyword);
            }
            if (hits > 0) {
                categories.add(category);
                total += hits;
            }
        }
        total += headingBonus(block.text());
        return new Scored(block.index(), total, categories, block.length());
    }

    /** 짧은 줄(소제목)에 키워드가 있으면 그 덩어리가 본론이다 */
    private static int headingBonus(String text) {
        int bonus = 0;
        for (String line : text.split("\\n")) {
            String squeezed = squeeze(line);
            if (squeezed.isEmpty() || squeezed.length() > HEADING_MAX_CHARS) {
                continue;
            }
            for (SectionCategory category : SectionCategory.values()) {
                if (category.keywords().stream().anyMatch(squeezed::contains)) {
                    bonus += HEADING_WEIGHT;
                    break;
                }
            }
        }
        return bonus;
    }

    /**
     * 공백을 모두 지운다.
     * 공고문 PDF 는 표 안에서 "신 청 자 격" 처럼 글자 사이가 벌어진 채 추출되는 일이 흔하다.
     * 공백을 그대로 두고 비교하면 정작 중요한 쪽이 걸리지 않는다.
     */
    private static String squeeze(String text) {
        return text == null ? "" : text.replaceAll("\\s+", "");
    }

    private static int count(String haystack, String needle) {
        int hits = 0;
        int from = 0;
        while ((from = haystack.indexOf(needle, from)) >= 0) {
            hits++;
            from += needle.length();
        }
        return hits;
    }

    record Scored(int index, int score, Set<SectionCategory> categories, int length) {
        boolean matched() {
            return !categories.isEmpty();
        }
    }
}
