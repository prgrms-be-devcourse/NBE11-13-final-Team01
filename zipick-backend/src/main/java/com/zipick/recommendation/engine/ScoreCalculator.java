package com.zipick.recommendation.engine;

import com.zipick.housing.domain.Candidate;
import com.zipick.recommendation.config.RecommendationProperties;
import com.zipick.user.domain.UserPreference;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 2~3단계: 희망 주거조건 일치도(항목별 0~1)와 가중합 추천도(0~100).
 *
 * <pre>
 * 월 환산 비용 = 월세 + 보증금 × 환산 이율 ÷ 12      (이율 = min(10%, 기준금리 + 2%p))
 * 최종 가중치 = 기본 가중치 × 사용자 우선순위(1~5), 계산 가능한 항목만으로 합이 1이 되게 정규화
 * 추천도     = 100 × Σ(가중치 × 항목 점수) × 지원조건 배수 (PASS 1.0, CHECK 0.7)
 * </pre>
 *
 * 순위에 꼭 필요한 항목(required-items: 비용·지역)이 하나라도 없으면 complete=false 로 표시한다.
 * 그런 집은 점수로 줄 세우지 않고 '정보 부족' 묶음으로 따로 보여 준다(설계 결정 D안).
 */
@Component
@RequiredArgsConstructor
public class ScoreCalculator {

    public static final String COST = "cost";
    public static final String REGION = "region";
    public static final String AREA = "area";
    public static final String ROOMS = "rooms";
    public static final String INFRA = "infra";
    public static final String AGE = "age";
    public static final String TYPE = "type";
    public static final List<String> ITEMS = List.of(COST, REGION, AREA, ROOMS, INFRA, AGE, TYPE);

    private final RecommendationProperties props;
    private final EligibilityChecker eligibilityChecker;

    /**
     * @param nearest 이 주택의 주변 시설 최단 거리 (카테고리 → m). 없으면 null
     */
    public HouseScore score(UserContext ctx, Candidate c, Map<String, Integer> nearest) {
        EligibilityResult eligibility = eligibilityChecker.check(ctx, c);

        Map<String, ItemScore> items = new LinkedHashMap<>();
        items.put(COST, scoreCost(ctx, c));
        items.put(REGION, scoreRegion(ctx, c));
        items.put(AREA, scoreArea(ctx, c));
        items.put(ROOMS, scoreRooms(ctx, c));
        items.put(INFRA, scoreInfra(ctx, nearest));
        items.put(AGE, scoreAge(ctx, c));
        items.put(TYPE, scoreType(ctx, c));

        boolean complete = required().stream().allMatch(i -> items.get(i) != null && items.get(i).available());

        // MUST 규칙: 점수가 0인 항목이 있으면 제외
        String excludedBy = null;
        for (var e : items.entrySet()) {
            ItemScore s = e.getValue();
            if (ctx.isMust(e.getKey()) && s.available() && s.score() <= 0) {
                excludedBy = e.getKey();
                break;
            }
        }

        Map<String, Double> weights = normalizedWeights(ctx, items);
        double weighted = 0;
        for (var e : weights.entrySet()) {
            weighted += e.getValue() * items.get(e.getKey()).score();
        }
        double multiplier = switch (eligibility.status()) {
            case PASS -> 1.0;
            case CHECK -> props.checkMultiplier();
            case FAIL -> 0.0;
        };
        int score = (int) Math.round(100 * weighted * multiplier);

        List<Reason> reasons = new ArrayList<>(eligibility.reasons());
        items.values().forEach(s -> reasons.add(s.reason()));
        if (!complete) {
            List<String> missing = required().stream()
                    .filter(i -> items.get(i) == null || !items.get(i).available()).map(ScoreCalculator::label).toList();
            reasons.add(Reason.check("required", "정보 부족: " + String.join("·", missing) + " 정보가 없어 순위에서 제외 (공고문 확인 필요)"));
        }
        return new HouseScore(c, score, items, weights, eligibility, reasons, excludedBy, complete);
    }

    /** 기본 가중치 × 우선순위를 계산 가능한 항목만으로 정규화한다. */
    public Map<String, Double> normalizedWeights(UserContext ctx, Map<String, ItemScore> items) {
        Map<String, Double> raw = new LinkedHashMap<>();
        double sum = 0;
        for (String item : ITEMS) {
            ItemScore s = items.get(item);
            if (s == null || !s.available()) continue;
            int base = props.baseWeights().getOrDefault(item, 0);
            int priority = clampPriority(ctx.weights().getOrDefault(item, 3));
            double w = base * priority;
            raw.put(item, w);
            sum += w;
        }
        Map<String, Double> out = new LinkedHashMap<>();
        for (var e : raw.entrySet()) {
            out.put(e.getKey(), sum == 0 ? 0 : round4(e.getValue() / sum));
        }
        return out;
    }

    // ---- 항목별 점수 ----

    ItemScore scoreCost(UserContext ctx, Candidate c) {
        if (c.deposit() == null && c.monthlyRent() == null) {
            return ItemScore.unavailable(COST, "보증금·임대료 정보 없음");
        }
        Integer budget = monthlyBudget(ctx.preference());
        if (budget == null || budget <= 0) {
            return ItemScore.unavailable(COST, "월 예산이 없어 비용 점수 제외");
        }
        double monthly = monthlyCost(c);
        double ratio = monthly / budget;
        String note = c.monthlyRent() == null ? ", 월세 정보 없어 보증금만 환산" : "";
        String text = String.format("월 환산 %.0f만 원 / 예산 %d만 원%s", monthly, budget, note);
        if (ratio <= 1.0) {
            return ItemScore.of(COST, 1.0, Reason.pass(COST, "월 비용 예산 이내 (" + text + ")"));
        }
        double over = ratio - 1.0;
        double score = 1.0 - over / props.costOverBudgetZeroRatio();
        return ItemScore.of(COST, score,
                Reason.partial(COST, String.format("예산 %.0f%% 초과 (%s)", over * 100, text)));
    }

    ItemScore scoreRegion(UserContext ctx, Candidate c) {
        String key = c.regionKey();
        if (key == null) return ItemScore.unavailable(REGION, "지역 정보 없음");
        Integer rank = ctx.regionRanks().get(key);
        if (rank == null) {
            return ItemScore.of(REGION, 0, Reason.partial(REGION, "관심 지역 밖 (" + key + ")"));
        }
        var rs = props.regionScores();
        double score = switch (rank) {
            case 1 -> rs.rank1();
            case 2 -> rs.rank2();
            default -> rs.rank3();
        };
        Reason reason = score >= 1.0
                ? Reason.pass(REGION, "희망 지역 일치 (1순위)")
                : Reason.partial(REGION, "희망 지역 일치 (" + rank + "순위)");
        return ItemScore.of(REGION, score, reason);
    }

    ItemScore scoreArea(UserContext ctx, Candidate c) {
        BigDecimal area = c.exclusiveArea();
        BigDecimal min = ctx.preference().getAreaMin();
        BigDecimal max = ctx.preference().getAreaMax();
        if (area == null) return ItemScore.unavailable(AREA, "전용면적 정보 없음");
        if (min == null && max == null) return ItemScore.unavailable(AREA, "희망 면적이 없어 면적 점수 제외");

        String actual = area.stripTrailingZeros().toPlainString() + "㎡";
        String minimumNote = minimumAreaNote(ctx, area);

        BigDecimal dist = BigDecimal.ZERO;
        if (min != null && area.compareTo(min) < 0) dist = min.subtract(area);
        else if (max != null && area.compareTo(max) > 0) dist = area.subtract(max);

        if (dist.signum() == 0) {
            Reason r = minimumNote == null
                    ? Reason.pass(AREA, "희망 면적 범위 안 (" + actual + ")")
                    : Reason.partial(AREA, "희망 면적 범위 안 (" + actual + ")" + minimumNote);
            return ItemScore.of(AREA, 1.0, r);
        }
        String range = (min == null ? "" : min.stripTrailingZeros().toPlainString()) + "~"
                + (max == null ? "" : max.stripTrailingZeros().toPlainString()) + "㎡";
        int steps = dist.divide(BigDecimal.valueOf(props.areaStepM2()), 0, RoundingMode.CEILING).intValue();
        double score = 1.0 - 0.25 * steps;
        return ItemScore.of(AREA, score, Reason.partial(AREA,
                "희망 면적 미충족 (희망 " + range + ", 이 집 " + actual + ")" + (minimumNote == null ? "" : minimumNote)));
    }

    /** 세대원 수에 맞는 최저주거기준보다 작으면 근거에 덧붙인다 */
    private String minimumAreaNote(UserContext ctx, BigDecimal area) {
        Map<Integer, Integer> table = props.minimumAreaByHousehold();
        if (table == null || table.isEmpty()) return null;
        int size = Math.max(1, Math.min(ctx.profile().getHouseholdSize(),
                table.keySet().stream().max(Integer::compare).orElse(1)));
        Integer minimum = table.get(size);
        if (minimum == null || area.compareTo(BigDecimal.valueOf(minimum)) >= 0) return null;
        return " · 최저주거기준(" + size + "인 " + minimum + "㎡) 미달";
    }

    ItemScore scoreRooms(UserContext ctx, Candidate c) {
        Integer want = ctx.preference().getRoomsMin();
        if (want == null) return ItemScore.unavailable(ROOMS, "희망 방 수가 없어 방 수 점수 제외");
        if (c.roomCount() == null) return ItemScore.unavailable(ROOMS, "방 수 정보 없음");
        if (c.roomCount() >= want) {
            return ItemScore.of(ROOMS, 1.0, Reason.pass(ROOMS, "방 " + c.roomCount() + "개 (희망 " + want + "개 이상)"));
        }
        double score = 1.0 - props.roomStepPenalty() * (want - c.roomCount());
        return ItemScore.of(ROOMS, score, Reason.partial(ROOMS, "방 " + c.roomCount() + "개 (희망 " + want + "개 이상)"));
    }

    ItemScore scoreInfra(UserContext ctx, Map<String, Integer> nearest) {
        if (nearest == null || nearest.isEmpty()) return ItemScore.unavailable(INFRA, "주변 시설 정보 없음");

        // 가중치가 없으면 수집된 모든 카테고리를 같은 비중으로 본다
        Map<String, Integer> weights = ctx.infraWeights().isEmpty()
                ? nearest.keySet().stream().collect(LinkedHashMap::new, (m, k) -> m.put(k, 1), Map::putAll)
                : ctx.infraWeights();

        double wsum = 0, acc = 0;
        List<String> far = new ArrayList<>();
        for (var e : weights.entrySet()) {
            int w = clampPriority(e.getValue());
            Integer d = nearest.get(e.getKey());
            double s;
            if (d == null) {
                s = 0;
                far.add(categoryName(e.getKey()) + " 없음");
            } else {
                s = distanceScore(d);
                if (s < 0.5) far.add(categoryName(e.getKey()) + " " + d + "m");
            }
            acc += w * s;
            wsum += w;
        }
        if (wsum == 0) return ItemScore.unavailable(INFRA, "인프라 가중치 없음");
        double score = acc / wsum;
        Reason reason = score >= 0.8
                ? Reason.pass(INFRA, "주요 시설 가까움")
                : Reason.partial(INFRA, far.isEmpty() ? "주요 시설 다소 멂" : String.join(", ", far));
        return ItemScore.of(INFRA, score, reason);
    }

    ItemScore scoreAge(UserContext ctx, Candidate c) {
        if (c.buildYear() == null) return ItemScore.unavailable(AGE, "준공연도 정보 없음");
        int years = Math.max(0, ctx.today().getYear() - c.buildYear());
        double score = 1.0 - (double) years / props.ageZeroYears();
        String text = c.buildYear() + "년 준공 (" + years + "년)";
        return score >= 0.8
                ? ItemScore.of(AGE, score, Reason.pass(AGE, text))
                : ItemScore.of(AGE, score, Reason.partial(AGE, text));
    }

    ItemScore scoreType(UserContext ctx, Candidate c) {
        if (ctx.preferredHousingTypes().isEmpty()) return ItemScore.unavailable(TYPE, "희망 주택 유형이 없어 유형 점수 제외");
        if (c.housingType() == null) return ItemScore.unavailable(TYPE, "주택 유형 정보 없음");
        boolean match = ctx.preferredHousingTypes().contains(c.housingType());
        return match
                ? ItemScore.of(TYPE, 1.0, Reason.pass(TYPE, "희망 주택 유형 일치 (" + c.housingType().getLabel() + ")"))
                : ItemScore.of(TYPE, 0.0, Reason.partial(TYPE, "희망 주택 유형 아님 (" + c.housingType().getLabel() + ")"));
    }

    // ---- 보조 계산 ----

    /** 월 환산 비용(만 원) = 월세 + 보증금 × 연이율 ÷ 12. 월세가 없으면 0으로 본다(전세형) */
    public double monthlyCost(Candidate c) {
        double deposit = c.deposit() == null ? 0 : c.deposit();
        double rent = c.monthlyRent() == null ? 0 : c.monthlyRent();
        return rent + deposit * props.conversionRate() / 100.0 / 12.0;
    }

    /** 사용자 월 예산. 직접 입력이 없으면 보증금 상한·월세 상한으로 계산 */
    public Integer monthlyBudget(UserPreference p) {
        if (p.getMonthlyBudget() != null) return p.getMonthlyBudget();
        if (p.getRentMax() == null && p.getDepositMax() == null) return null;
        double rent = p.getRentMax() == null ? 0 : p.getRentMax();
        double deposit = p.getDepositMax() == null ? 0 : p.getDepositMax();
        return (int) Math.round(rent + deposit * props.conversionRate() / 100.0 / 12.0);
    }

    double distanceScore(double meters) {
        double full = props.infraFullScoreM(), zero = props.infraZeroScoreM();
        if (meters <= full) return 1.0;
        if (meters >= zero) return 0.0;
        return 1.0 - (meters - full) / (zero - full);
    }

    private List<String> required() {
        return props.requiredItems() == null ? List.of() : props.requiredItems();
    }

    public static String label(String item) {
        return switch (item) {
            case COST -> "비용";
            case REGION -> "지역";
            case AREA -> "면적";
            case ROOMS -> "방 수";
            case INFRA -> "인프라";
            case AGE -> "노후도";
            case TYPE -> "주택 유형";
            default -> item;
        };
    }

    private static int clampPriority(int v) {
        return Math.max(1, Math.min(5, v));
    }

    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    private static String categoryName(String code) {
        return switch (code) {
            case "SW8" -> "지하철역";
            case "MT1" -> "대형마트";
            case "CS2" -> "편의점";
            case "HP8" -> "병원";
            case "PM9" -> "약국";
            case "SC4" -> "학교";
            case "BK9" -> "은행";
            case "CE7" -> "카페";
            default -> code;
        };
    }
}
