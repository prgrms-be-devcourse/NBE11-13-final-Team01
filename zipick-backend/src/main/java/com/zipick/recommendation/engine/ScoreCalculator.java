package com.zipick.recommendation.engine;

import com.fasterxml.jackson.core.type.TypeReference;
import com.zipick.global.util.JsonUtil;
import com.zipick.housing.domain.InfraSummary;
import com.zipick.housing.domain.RentalType;
import com.zipick.housing.domain.SupplyHouse;
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
 * 월 환산 비용 = 월 임대료 + 보증금 × 전환이율 ÷ 12
 * 최종 가중치 = 기본 가중치 × 사용자 우선순위(1~5), 계산 가능한 항목만으로 합이 1이 되게 정규화
 * 추천도     = 100 × Σ(가중치 × 항목 점수) × 지원조건 배수 (PASS 1.0, CHECK 0.7)
 * </pre>
 */
@Component
@RequiredArgsConstructor
public class ScoreCalculator {

    public static final String COST = "cost";
    public static final String REGION = "region";
    public static final String AREA = "area";
    public static final String INFRA = "infra";
    public static final String TYPE = "type";
    public static final String COVERAGE = "coverage";
    public static final List<String> ITEMS = List.of(COST, REGION, AREA, INFRA, TYPE);

    private final RecommendationProperties props;
    private final EligibilityChecker eligibilityChecker;

    public HouseScore score(UserContext ctx, SupplyHouse house, InfraSummary infra) {
        EligibilityResult eligibility = eligibilityChecker.check(ctx, house);

        Map<String, ItemScore> items = new LinkedHashMap<>();
        items.put(COST, scoreCost(ctx, house));
        items.put(REGION, scoreRegion(ctx, house));
        items.put(AREA, scoreArea(ctx, house));
        items.put(INFRA, scoreInfra(ctx, infra));
        items.put(TYPE, scoreType(ctx, house));

        String excludedBy = null;

        // 정보 커버리지: 계산 가능한 항목의 기본 가중치 합이 기준 미만이면 '정보 부족'으로 제외
        double coverage = coverage(items);
        if (coverage < props.minCoverage()) {
            excludedBy = COVERAGE;
        }

        // MUST 규칙: 점수가 0인 항목이 있으면 제외
        if (excludedBy == null) {
            for (var e : items.entrySet()) {
                ItemScore s = e.getValue();
                if (ctx.isMust(e.getKey()) && s.available() && s.score() <= 0) {
                    excludedBy = e.getKey();
                    break;
                }
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
        if (COVERAGE.equals(excludedBy)) {
            reasons.add(Reason.check(COVERAGE, String.format("정보 부족 (확인 가능한 항목 %d%%)", Math.round(coverage * 100))));
        }

        return new HouseScore(house, score, items, weights, eligibility, reasons, excludedBy);
    }

    /** 계산 가능한 항목의 기본 가중치 합 / 전체 기본 가중치 합 (0~1) */
    public double coverage(Map<String, ItemScore> items) {
        double total = 0, available = 0;
        for (String item : ITEMS) {
            int base = props.baseWeights().getOrDefault(item, 0);
            total += base;
            ItemScore s = items.get(item);
            if (s != null && s.available()) available += base;
        }
        return total == 0 ? 0 : available / total;
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

    ItemScore scoreCost(UserContext ctx, SupplyHouse h) {
        if (h.getDeposit() == null || h.getMonthlyRent() == null) {
            return ItemScore.unavailable(COST, "보증금·임대료 정보 없음");
        }
        Integer budget = monthlyBudget(ctx.preference());
        if (budget == null || budget <= 0) {
            return ItemScore.unavailable(COST, "월 예산이 없어 비용 점수 제외");
        }
        double monthly = monthlyCost(h);
        double ratio = monthly / budget;
        String text = String.format("월 환산 %.0f만 원 / 예산 %d만 원", monthly, budget);
        if (ratio <= 1.0) {
            return ItemScore.of(COST, 1.0, Reason.pass(COST, "월 비용 예산 이내 (" + text + ")"));
        }
        double over = ratio - 1.0;
        double score = 1.0 - over / props.costOverBudgetZeroRatio();
        int pct = (int) Math.round(over * 100);
        return ItemScore.of(COST, score, Reason.partial(COST, "예산 " + pct + "% 초과 (" + text + ")"));
    }

    ItemScore scoreRegion(UserContext ctx, SupplyHouse h) {
        if (h.getSigunguCode() == null) {
            return ItemScore.unavailable(REGION, "지역 정보 없음");
        }
        Integer rank = ctx.regionRanks().get(h.getSigunguCode());
        if (rank == null) {
            return ItemScore.of(REGION, 0, Reason.partial(REGION, "관심 지역 밖"));
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

    ItemScore scoreArea(UserContext ctx, SupplyHouse h) {
        BigDecimal area = h.getExclusiveArea();
        BigDecimal min = ctx.preference().getAreaMin();
        BigDecimal max = ctx.preference().getAreaMax();
        if (area == null) return ItemScore.unavailable(AREA, "전용면적 정보 없음");
        if (min == null && max == null) return ItemScore.unavailable(AREA, "희망 면적이 없어 면적 점수 제외");

        String range = (min == null ? "" : min.stripTrailingZeros().toPlainString()) + "~"
                + (max == null ? "" : max.stripTrailingZeros().toPlainString()) + "㎡";
        String actual = area.stripTrailingZeros().toPlainString() + "㎡";

        BigDecimal dist = BigDecimal.ZERO;
        if (min != null && area.compareTo(min) < 0) dist = min.subtract(area);
        else if (max != null && area.compareTo(max) > 0) dist = area.subtract(max);

        if (dist.signum() == 0) {
            return ItemScore.of(AREA, 1.0, Reason.pass(AREA, "희망 면적 범위 안 (" + actual + ")"));
        }
        int steps = dist.divide(BigDecimal.valueOf(props.areaStepM2()), 0, RoundingMode.CEILING).intValue();
        double score = 1.0 - 0.25 * steps;
        return ItemScore.of(AREA, score,
                Reason.partial(AREA, "희망 면적 미충족 (희망 " + range + ", 이 집 " + actual + ")"));
    }

    ItemScore scoreInfra(UserContext ctx, InfraSummary infra) {
        if (infra == null) return ItemScore.unavailable(INFRA, "주변 인프라 정보 없음");
        Map<String, Map<String, Object>> nearest = JsonUtil.read(infra.getNearest(),
                new TypeReference<Map<String, Map<String, Object>>>() {}, Map.of());
        if (nearest.isEmpty()) return ItemScore.unavailable(INFRA, "주변 인프라 정보 없음");

        // 가중치가 없으면 수집된 모든 카테고리를 같은 비중으로 본다
        Map<String, Integer> weights = ctx.infraWeights().isEmpty()
                ? nearest.keySet().stream().collect(LinkedHashMap::new, (m, k) -> m.put(k, 1), Map::putAll)
                : ctx.infraWeights();

        double wsum = 0, acc = 0;
        List<String> far = new ArrayList<>();
        for (var e : weights.entrySet()) {
            int w = clampPriority(e.getValue());
            Map<String, Object> f = nearest.get(e.getKey());
            double s;
            if (f == null || f.get("distanceM") == null) {
                s = 0;
                far.add(categoryName(e.getKey()) + " 없음");
            } else {
                double d = ((Number) f.get("distanceM")).doubleValue();
                s = distanceScore(d);
                if (s < 0.5) far.add(categoryName(e.getKey()) + " " + Math.round(d) + "m");
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

    /**
     * 유형 = 주택 유형(아파트·다세대…)과 공공임대 유형(매입임대·행복주택…) 일치도의 평균.
     * 희망을 고르지 않았거나 집 정보가 없는 쪽은 빼고 계산한다.
     */
    ItemScore scoreType(UserContext ctx, SupplyHouse h) {
        List<Double> parts = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        if (!ctx.preferredHousingTypes().isEmpty() && h.getHousingType() != null) {
            boolean match = ctx.preferredHousingTypes().contains(h.getHousingType());
            parts.add(match ? 1.0 : 0.0);
            texts.add(match ? "희망 주택 유형 일치" : "희망 주택 유형 아님");
        }
        RentalType rental = h.getAnnouncement() == null ? null : h.getAnnouncement().getRentalType();
        if (!ctx.preferredRentalTypes().isEmpty() && rental != null) {
            boolean match = ctx.preferredRentalTypes().contains(rental);
            parts.add(match ? 1.0 : 0.0);
            texts.add((match ? "희망 공공임대 유형 일치 (" : "희망 공공임대 유형 아님 (") + rental.getLabel() + ")");
        }
        if (parts.isEmpty()) {
            return ItemScore.unavailable(TYPE, ctx.preferredHousingTypes().isEmpty() && ctx.preferredRentalTypes().isEmpty()
                    ? "희망 유형이 없어 유형 점수 제외" : "주택 유형 정보 없음");
        }
        double score = parts.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        String text = String.join(" · ", texts);
        return score >= 1.0
                ? ItemScore.of(TYPE, 1.0, Reason.pass(TYPE, text))
                : ItemScore.of(TYPE, score, Reason.partial(TYPE, text));
    }

    // ---- 보조 계산 ----

    /** 월 환산 비용(만 원) = 월세 + 보증금 × 연이율 ÷ 12 */
    public double monthlyCost(SupplyHouse h) {
        BigDecimal rate = h.getConversionRate() != null ? h.getConversionRate() : props.defaultConversionRate();
        double deposit = h.getDeposit() == null ? 0 : h.getDeposit();
        double rent = h.getMonthlyRent() == null ? 0 : h.getMonthlyRent();
        return rent + deposit * rate.doubleValue() / 100.0 / 12.0;
    }

    /** 사용자 월 예산. 직접 입력이 없으면 보증금 상한·월세 상한으로 계산 */
    public Integer monthlyBudget(UserPreference p) {
        if (p.getMonthlyBudget() != null) return p.getMonthlyBudget();
        if (p.getRentMax() == null && p.getDepositMax() == null) return null;
        double rate = props.defaultConversionRate().doubleValue();
        double rent = p.getRentMax() == null ? 0 : p.getRentMax();
        double deposit = p.getDepositMax() == null ? 0 : p.getDepositMax();
        return (int) Math.round(rent + deposit * rate / 100.0 / 12.0);
    }

    double distanceScore(double meters) {
        double full = props.infraFullScoreM(), zero = props.infraZeroScoreM();
        if (meters <= full) return 1.0;
        if (meters >= zero) return 0.0;
        return 1.0 - (meters - full) / (zero - full);
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
            case "PARK" -> "공원";
            case "GYM" -> "헬스장";
            case "BUS" -> "버스정류장";
            default -> code;
        };
    }
}
