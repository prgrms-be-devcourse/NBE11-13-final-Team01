import com.zipick.notice.support.RegionNormalizer;

import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 로컬 개발 DB 의 housing 행정구역(province_name / district_name)을 지금 코드 기준으로 맞춰 주는 도구.
 *
 * <p><b>언제 쓰나.</b> 배포 환경은 DB 를 비우고 전체 재수집하므로 이 도구가 필요 없다.
 * 수집 경로가 이미 {@link RegionNormalizer} 를 거치기 때문이다(Housing#normalizeRegion).
 * 이 도구는 <b>이미 쌓아 둔 로컬 DB 를 재수집 없이 빠르게 정리하고 싶을 때만</b> 쓴다.
 *
 * <p><b>어떻게 안전을 지키나.</b>
 * <ul>
 *   <li>어떤 소스 세트에도 들어 있지 않다. Gradle 이 컴파일하지 않고 jar 에도 들어가지 않는다.
 *       스케줄러·스타트업 훅·마이그레이션 도구가 이 파일을 실행할 경로는 없다.</li>
 *   <li>판정은 전부 운영 코드인 {@link RegionNormalizer} 가 한다. 여기에 별도 규칙을 두지 않는다 —
 *       정책이 둘로 갈라지면 DB 와 수집 결과가 어긋난다.</li>
 *   <li>{@code --apply} 를 직접 붙였을 때만 UPDATE 한다. 기본값은 dry-run 이다.</li>
 *   <li>{@code --apply} 는 백업 경로를 반드시 요구하고, 한 트랜잭션에서 행 수가 어긋나면 ROLLBACK 한다.</li>
 * </ul>
 *
 * <p><b>무엇을 바꾸나.</b> {@code housing.province_name} / {@code housing.district_name} 두 컬럼뿐이다.
 * 주소·좌표·공고·공급 정보는 건드리지 않는다.
 *
 * <p>사용법은 {@code docs/region-normalization.md} 참고.
 */
public final class RegionBackfill {

    private static final String DEFAULT_URL = "jdbc:postgresql://localhost:5432/zipick";
    /** dry-run 에서 한 번에 찍을 변경 내역 최대 줄 수. 넘으면 유형별 집계만 본다 */
    private static final int SAMPLE_LIMIT = 200;

    private record Row(long id, String province, String district, String address, String normalized,
                       String lat, String lng) {
        String coordKey() {
            return lat == null || lng == null ? null : lat + "," + lng;
        }
    }

    private record Change(long id, String oldProvince, String oldDistrict,
                          String newProvince, String newDistrict, String kind) {}

    public static void main(String[] args) throws Exception {
        Map<String, String> options = parseArgs(args);
        boolean apply = options.containsKey("apply");
        String backupPath = options.get("backup");

        if (apply && (backupPath == null || backupPath.isBlank())) {
            System.err.println("--apply 에는 --backup=<경로> 가 필요합니다. 저장소 밖 경로를 쓰세요.");
            System.exit(1);
        }

        String url = options.getOrDefault("url", DEFAULT_URL);
        String user = value(options, "user", "ZIPICK_DB_USER");
        String password = value(options, "password", "ZIPICK_DB_PASSWORD");
        if (user == null) {
            System.err.println("DB 계정이 없습니다. --user=... --password=... 또는 "
                    + "ZIPICK_DB_USER / ZIPICK_DB_PASSWORD 환경 변수를 쓰세요.");
            System.exit(1);
        }

        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            connection.setAutoCommit(false);

            List<Row> rows = readHousings(connection);
            System.out.printf("읽은 housing: %d건 (%s)%n", rows.size(), url);

            List<Change> changes = plan(rows);
            report(changes);

            if (!apply) {
                connection.rollback();
                System.out.println();
                System.out.println("[dry-run] 아무것도 바꾸지 않았습니다. 적용하려면 --apply --backup=<경로> 를 붙이세요.");
                return;
            }
            if (changes.isEmpty()) {
                connection.rollback();
                System.out.println();
                System.out.println("바꿀 행이 없습니다. 이미 지금 정책과 같은 상태입니다.");
                return;
            }

            writeBackup(backupPath, changes);
            System.out.println("백업 저장: " + backupPath);

            int affected = 0;
            int badRows = 0;
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE housing SET province_name = ?, district_name = ? WHERE housing_id = ?")) {
                for (Change change : changes) {
                    statement.setString(1, change.newProvince());
                    statement.setString(2, change.newDistrict());
                    statement.setLong(3, change.id());
                    int updated = statement.executeUpdate();
                    if (updated != 1) {
                        badRows++;
                    }
                    affected += updated;
                }
            }

            // 계획한 수와 실제 수가 다르면 중간에 데이터가 바뀐 것이다. 그대로 두지 않는다.
            if (affected != changes.size() || badRows != 0) {
                connection.rollback();
                System.out.printf("!! 계획 %d건 / 실제 %d건(행당 1건이 아닌 경우 %d) → ROLLBACK%n",
                        changes.size(), affected, badRows);
                System.exit(2);
            }

            connection.commit();
            System.out.printf("COMMIT 완료: %d건%n", affected);
            System.out.println("다시 --dry-run 으로 실행하면 변경 대상이 0건이어야 합니다.");
        }
    }

    private static List<Row> readHousings(Connection connection) throws Exception {
        List<Row> rows = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT housing_id, province_name, district_name, address, normalized_address,
                            latitude::text, longitude::text
                     FROM housing ORDER BY housing_id""")) {
            while (result.next()) {
                rows.add(new Row(result.getLong(1), result.getString(2), result.getString(3),
                        result.getString(4), result.getString(5), result.getString(6), result.getString(7)));
            }
        }
        return rows;
    }

    /**
     * 바꿀 행을 고른다. 2패스다.
     *
     * <p>1패스는 운영 코드 {@link RegionNormalizer} 를 그대로 돌린다 — 재수집했을 때 저장될 값과 같다.
     *
     * <p>2패스는 <b>이 도구에만 있는 보정</b>이다. 1패스가 시군구는 확정했는데 시도를 확정하지 못한 행을,
     * <b>같은 좌표</b>에 있으면서 1패스가 시도까지 확정한 다른 주택을 근거로 채운다.
     * 후보 시도가 정확히 하나일 때만 채우고, 둘 이상이면 건드리지 않는다.
     * 재수집하면 원본 필드에서 같은 값이 나오므로 결과가 어긋나지 않는다.
     *
     * <p>⚠️ 주소만 보고 시군구를 "추측"하는 보정은 하지 않는다(예: 천안시 → 천안시 서북구).
     * 근거가 없으면 1패스 결과를 그대로 쓴다.
     */
    private static List<Change> plan(List<Row> rows) {
        Map<Long, RegionNormalizer.Region> normalized = new LinkedHashMap<>();
        for (Row row : rows) {
            normalized.put(row.id(),
                    RegionNormalizer.normalize(row.province(), row.district(), row.address(), row.normalized()));
        }

        // 좌표 → (시군구 → 시도 후보). 1패스가 둘 다 확정한 행만 근거가 된다.
        Map<String, Map<String, Set<String>>> donors = new HashMap<>();
        for (Row row : rows) {
            RegionNormalizer.Region region = normalized.get(row.id());
            if (region.province() == null || region.district() == null || row.coordKey() == null) {
                continue;
            }
            donors.computeIfAbsent(row.coordKey(), key -> new HashMap<>())
                    .computeIfAbsent(region.district(), key -> new TreeSet<>())
                    .add(region.province());
        }

        List<Change> changes = new ArrayList<>();
        for (Row row : rows) {
            RegionNormalizer.Region region = normalized.get(row.id());
            String province = region.province();
            String district = region.district();
            boolean byCoordinate = false;

            if (province == null && district != null && row.coordKey() != null) {
                Set<String> candidates = donors.getOrDefault(row.coordKey(), Map.of())
                        .getOrDefault(district, Set.of());
                if (candidates.size() == 1) {
                    province = candidates.iterator().next();
                    byCoordinate = true;
                }
            }

            if (Objects.equals(row.province(), province) && Objects.equals(row.district(), district)) {
                continue;
            }
            changes.add(new Change(row.id(), row.province(), row.district(), province, district,
                    kindOf(row.province(), row.district(), province, district, byCoordinate)));
        }
        return changes;
    }

    /**
     * 변경 유형 라벨. <b>정책을 다시 구현하지 않고</b> 바뀐 값만 보고 붙인다.
     * 어떤 종류의 보정이 얼마나 일어나는지 사람이 훑어보기 위한 것이다.
     */
    private static String kindOf(String oldProvince, String oldDistrict,
                                 String newProvince, String newDistrict, boolean byCoordinate) {
        if (byCoordinate) {
            return "좌표 근거 시도 복구";
        }
        if (newProvince == null && newDistrict == null) {
            return "판정 불가 → 비움";
        }
        boolean provinceChanged = !Objects.equals(oldProvince, newProvince);
        boolean districtChanged = !Objects.equals(oldDistrict, newDistrict);
        if (provinceChanged && districtChanged) {
            return "시도+시군구 동시 보정";
        }
        if (provinceChanged) {
            return oldProvince == null ? "시도 채움" : "시도 표준화";
        }
        return oldDistrict == null ? "시군구 채움" : "시군구 보정";
    }

    private static void report(List<Change> changes) {
        System.out.printf("변경 대상: %d건%n", changes.size());
        if (changes.isEmpty()) {
            return;
        }

        Map<String, Integer> byKind = new TreeMap<>();
        for (Change change : changes) {
            byKind.merge(change.kind(), 1, Integer::sum);
        }
        System.out.println();
        System.out.println("[유형별]");
        byKind.forEach((kind, count) -> System.out.printf("  %-20s %4d건%n", kind, count));

        System.out.println();
        System.out.println("[before → after]");
        int printed = 0;
        for (Change change : changes) {
            if (printed++ >= SAMPLE_LIMIT) {
                System.out.printf("  … 외 %d건 (유형별 집계 참고)%n", changes.size() - SAMPLE_LIMIT);
                break;
            }
            System.out.printf("  #%-6d %-14s / %-16s  →  %-14s / %-16s  [%s]%n", change.id(),
                    display(change.oldProvince()), display(change.oldDistrict()),
                    display(change.newProvince()), display(change.newDistrict()), change.kind());
        }
    }

    private static void writeBackup(String path, List<Change> changes) throws Exception {
        Path target = Path.of(path);
        if (target.getParent() != null) {
            Files.createDirectories(target.getParent());
        }
        try (Writer writer = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
            writer.write("housing_id,old_province_name,old_district_name,new_province_name,new_district_name\n");
            for (Change change : changes) {
                writer.write("%d,%s,%s,%s,%s%n".formatted(change.id(),
                        csv(change.oldProvince()), csv(change.oldDistrict()),
                        csv(change.newProvince()), csv(change.newDistrict())));
            }
        }
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> options = new LinkedHashMap<>();
        for (String arg : args) {
            if (!arg.startsWith("--")) {
                continue;
            }
            String body = arg.substring(2);
            int equals = body.indexOf('=');
            if (equals < 0) {
                options.put(body, "");
            } else {
                options.put(body.substring(0, equals), body.substring(equals + 1));
            }
        }
        return options;
    }

    private static String value(Map<String, String> options, String key, String environmentKey) {
        String fromArgs = options.get(key);
        if (fromArgs != null && !fromArgs.isBlank()) {
            return fromArgs;
        }
        String fromEnvironment = System.getenv(environmentKey);
        return fromEnvironment == null || fromEnvironment.isBlank() ? null : fromEnvironment;
    }

    private static String display(String value) {
        return value == null ? "(없음)" : value;
    }

    private static String csv(String value) {
        if (value == null) {
            return "";
        }
        return value.contains(",") || value.contains("\"")
                ? "\"" + value.replace("\"", "\"\"") + "\""
                : value;
    }

    private RegionBackfill() {
    }
}
