"""PDF 표의 전체 행을 저장된 파싱 결과와 필드 단위로 대조한다.

Gemini API를 호출하지 않는다. PDF 원문은 pdfplumber로 직접 읽고, 이미 저장된
hybrid-trial JSON을 비교 대상으로 사용한다.
"""

from __future__ import annotations

import csv
import json
import re
import os
import subprocess
import sys
from collections import Counter
from decimal import Decimal, InvalidOperation
from pathlib import Path

import pdfplumber


BACKEND = Path(__file__).resolve().parents[3]
PROJECT = BACKEND.parent
DOCUMENTS = PROJECT / ".parser-evaluation" / "documents"
RESULTS = PROJECT / ".parser-evaluation" / "results"

FILES = {
    "GH": {
        "pdf": DOCUMENTS / "gh-795-purchase-rental-housing-list.pdf",
        "actual": RESULTS / "hybrid-trial-gemini-3.5-flash-lite-medium-gh-795-purchase-rental-housing-list.json",
    },
    "LH": {
        "pdf": DOCUMENTS / "lh-2026-gwangju-jeonnam-youth-housing-list.pdf",
        "actual": RESULTS / "hybrid-trial-gemini-3.5-flash-lite-medium-lh-2026-gwangju-jeonnam-youth-housing-list.json",
    },
    "SH": {
        "pdf": DOCUMENTS / "sh-311521-seoripul-youth-art-town.pdf",
        "actual": RESULTS / "hybrid-trial-gemini-3.5-flash-lite-medium-sh-311521-seoripul-youth-art-town.json",
    },
}


def clean(value) -> str:
    if value is None:
        return ""
    return re.sub(r"\s+", " ", str(value)).strip()


def normalized(value) -> str:
    text = clean(value).lower().replace("서울시", "서울특별시")
    text = text.replace("여부", "").replace("·", ",").replace("ㆍ", ",")
    return re.sub(r"[\s,㎡원()%]", "", text)


def number(value) -> str | None:
    text = clean(value).replace(",", "").replace("원", "").replace("㎡", "")
    if not text or text in {"-", "해당없음"}:
        return None
    try:
        return str(Decimal(text).normalize())
    except InvalidOperation:
        return None


def same(expected, actual, kind="text") -> bool:
    if kind == "number":
        return number(expected) == number(actual)
    if kind == "bool":
        truth = {"유": "true", "y": "true", "있음": "true", "무": "false", "n": "false", "없음": "false"}
        return truth.get(normalized(expected), normalized(expected)) == normalized(actual)
    if kind == "address":
        left, right = normalized(expected), normalized(actual)
        return bool(left and right and (left in right or right in left))
    return normalized(expected) == normalized(actual)


def attributes(unit: dict) -> dict[str, list[str]]:
    payload = unit.get("rawPayload") or {}
    try:
        raw = payload if isinstance(payload, dict) else json.loads(payload)
    except (json.JSONDecodeError, TypeError):
        raw = {}
    found: dict[str, list[str]] = {}
    for key in ("documentAdditionalAttributes", "buildingAdditionalAttributes", "unitAdditionalAttributes"):
        for item in raw.get(key) or []:
            found.setdefault(normalized(item.get("name")), []).append(clean(item.get("value")))
    return found


def attr(unit: dict, *names: str) -> str:
    found = attributes(unit)
    for name in names:
        values = found.get(normalized(name), [])
        if values:
            return values[0]
    return ""


def gh_truth() -> list[dict]:
    result = []
    with pdfplumber.open(FILES["GH"]["pdf"]) as pdf:
        for page_no, page in enumerate(pdf.pages, 1):
            table = page.extract_tables()[0]
            for row in table[1:]:
                if len(row) != 19 or not clean(row[0]).isdigit():
                    continue
                city, district, road, building = map(clean, (row[2], row[3], row[4], row[5]))
                result.append({
                    "row": int(clean(row[0])), "page": page_no,
                    "fields": {
                        "supplyCenter": (clean(row[1]), "text", False),
                        "city": (city, "text", False),
                        "district": (district, "text", False),
                        "roadAddress": (" ".join(x for x in (city, district, road, building) if x), "address", True),
                        "lotAddress": (" ".join(x for x in (city, district, clean(row[6])) if x), "address", False),
                        "legalDong": (clean(row[7]), "text", False),
                        "complexName": (clean(row[8]), "text", True),
                        "unitNumber": (clean(row[9]), "text", True),
                        "roomCount": (clean(row[10]), "number", True),
                        "exclusiveArea": (clean(row[11]), "number", True),
                        "commonArea": (clean(row[12]), "number", True),
                        "rentalArea": (clean(row[13]), "number", True),
                        "housingType": (clean(row[14]), "text", True),
                        "elevatorAvailable": (clean(row[15]), "bool", True),
                        "rentDeposit": (clean(row[16]), "number", True),
                        "monthlyRent": (clean(row[17]), "number", True),
                        "remarks": (clean(row[18]), "text", False),
                    },
                })
    return result


def lh_truth() -> list[dict]:
    result = []
    with pdfplumber.open(FILES["LH"]["pdf"]) as pdf:
        for page_no, page in enumerate(pdf.pages, 1):
            tables = page.extract_tables()
            if not tables or max((len(r) for r in tables[0]), default=0) != 23:
                continue
            for row in tables[0][2:]:
                if len(row) != 23 or not clean(row[0]).isdigit():
                    continue
                result.append({
                    "row": int(clean(row[0])), "page": page_no,
                    "fields": {
                        "regionalHeadquarters": (clean(row[1]), "text", False),
                        "managementDepartment": (clean(row[2]), "text", False),
                        "region": (clean(row[3]), "text", False),
                        "categoryName": (clean(row[4]), "text", False),
                        "address": (clean(row[5]), "address", True),
                        "buildingDong": (clean(row[6]), "text", True),
                        "unitNumber": (clean(row[7]), "text", True),
                        "housingGroupName": (clean(row[8]), "text", False),
                        "viewingSchedule": (clean(row[9]), "text", False),
                        "supplyType": (clean(row[10]), "text", True),
                        "genderEligibility": (clean(row[11]), "text", False),
                        "exclusiveArea": (clean(row[12]), "number", True),
                        "commonArea": (clean(row[13]), "number", True),
                        "rentalArea": (clean(row[14]), "number", True),
                        "roomCount": (clean(row[15]), "number", True),
                        "floor": (clean(row[16]).replace("층", ""), "number", True),
                        "elevatorAvailable": (clean(row[17]), "bool", True),
                        "housingType": (clean(row[18]), "text", True),
                        "priority1Deposit": (clean(row[19]), "number", True),
                        "priority1Rent": (clean(row[20]), "number", True),
                        "priority23Deposit": (clean(row[21]), "number", True),
                        "priority23Rent": (clean(row[22]), "number", True),
                    },
                })
    return result


def sh_truth() -> list[dict]:
    result = []
    with pdfplumber.open(FILES["SH"]["pdf"]) as pdf:
        table = pdf.pages[1].extract_tables()[0]
        for index, row in enumerate(table[2:], 1):
            if len(row) != 9 or not clean(row[1]).isdigit():
                continue
            result.append({
                "row": index, "page": 2, "section": clean(row[0]),
                "fields": {
                    "unitNumber": (clean(row[1]), "text", True),
                    "totalArea": (clean(row[2]), "number", False),
                    "exclusiveArea": (clean(row[3]), "number", True),
                    "commonArea": (clean(row[4]), "number", True),
                    "condition1Deposit": (clean(row[5]), "number", True),
                    "condition1Rent": (clean(row[6]), "number", True),
                    "condition2Deposit": (clean(row[7]), "number", True),
                    "condition2Rent": (clean(row[8]), "number", True),
                },
            })
    return result


def condition(unit: dict, priority: int, field: str) -> str:
    matches = []
    for item in unit.get("rentalConditions") or []:
        name = normalized(item.get("conditionName"))
        code = clean(item.get("conditionCode"))
        if priority == 1 and (code == "1" or code.endswith("_1") or "1순위" in name):
            matches.append(item)
        if priority == 23 and (code in {"2", "23"} or code.endswith("_2") or "2순위" in name or "23순위" in name):
            matches.append(item)
    if not matches:
        return ""
    return clean(matches[0].get(field))


def actual_value(agency: str, unit: dict, field: str) -> str:
    direct = {
        "complexName", "unitNumber", "roomCount", "exclusiveArea", "housingType",
        "elevatorAvailable", "rentDeposit", "monthlyRent", "address", "roadAddress",
        "lotAddress", "buildingDong", "supplyType", "floor",
    }
    if field in direct:
        return clean(unit.get(field))
    if field == "city":
        return clean(unit.get("districtName") or unit.get("address"))
    if field == "district":
        return clean(unit.get("districtName") or unit.get("address"))
    if field == "legalDong":
        return clean(unit.get("buildingDong"))
    if field == "commonArea":
        return attr(unit, "공용면적", "주거공용면적")
    if field == "rentalArea":
        return attr(unit, "임대면적", "면적계")
    if field == "viewingSchedule":
        return attr(unit, "주택열람일정")
    if field == "genderEligibility":
        return attr(unit, "성별용도", "성별용도 구분")
    if field == "priority1Deposit":
        return condition(unit, 1, "rentDeposit") or attr(unit, "청년 1순위 임대보증금")
    if field == "priority1Rent":
        return condition(unit, 1, "monthlyRent") or attr(unit, "청년 1순위 월임대료")
    if field == "priority23Deposit":
        return condition(unit, 23, "rentDeposit") or attr(unit, "청년 2·3순위 임대보증금")
    if field == "priority23Rent":
        return condition(unit, 23, "monthlyRent") or attr(unit, "청년 2·3순위 월임대료")
    if field == "condition1Deposit":
        return clean(unit.get("rentDeposit")) or condition(unit, 1, "rentDeposit")
    if field == "condition1Rent":
        return clean(unit.get("monthlyRent")) or condition(unit, 1, "monthlyRent")
    if field == "condition2Deposit":
        if agency == "SH":
            primary = number(unit.get("rentDeposit"))
            for item in unit.get("rentalConditions") or []:
                if number(item.get("rentDeposit")) != primary:
                    return clean(item.get("rentDeposit"))
        return condition(unit, 23, "rentDeposit") or condition(unit, 2, "rentDeposit")
    if field == "condition2Rent":
        if agency == "SH":
            primary = number(unit.get("monthlyRent"))
            for item in unit.get("rentalConditions") or []:
                if number(item.get("monthlyRent")) != primary:
                    return clean(item.get("monthlyRent"))
        return condition(unit, 23, "monthlyRent") or condition(unit, 2, "monthlyRent")
    if field == "totalArea":
        return attr(unit, "계", "주택공급면적", "면적계")
    return attr(unit, field)


def actual_units(agency: str) -> tuple[dict[int, dict], list[dict]]:
    root = json.loads(FILES[agency]["actual"].read_text(encoding="utf-8"))
    units = root.get("units") or []
    by_row = {}
    if agency == "SH":
        for index, unit in enumerate(units, 1):
            by_row[index] = unit
    else:
        for unit in units:
            row = int(unit.get("sourceRowNumber") or 0)
            if row:
                by_row[row] = unit
    return by_row, units


def evaluate(agency: str, truth: list[dict]) -> tuple[dict, list[dict]]:
    actual_by_row, all_actual = actual_units(agency)
    details = []
    counts = Counter()
    core_counts = Counter()
    for expected in truth:
        row = expected["row"]
        actual = actual_by_row.get(row)
        if actual is None:
            counts["missing_rows"] += 1
            core_counts["missing_rows"] += 1
        for field, (expected_value, kind, core) in expected["fields"].items():
            actual_value_text = "" if actual is None else actual_value(agency, actual, field)
            if not clean(expected_value):
                status = "SOURCE_BLANK" if not clean(actual_value_text) else "UNEXPECTED_VALUE"
            elif not clean(actual_value_text):
                status = "MISSING"
            elif same(expected_value, actual_value_text, kind):
                status = "CORRECT"
            else:
                status = "WRONG"
            counts[status] += 1
            if clean(expected_value):
                counts["asserted"] += 1
            if core:
                core_counts[status] += 1
                if clean(expected_value):
                    core_counts["asserted"] += 1
            if status not in {"CORRECT", "SOURCE_BLANK"}:
                details.append({
                    "agency": agency, "row": row, "page": expected["page"], "field": field,
                    "core": core, "expected": expected_value, "actual": actual_value_text, "status": status,
                })

    row_numbers = [int(unit.get("sourceRowNumber") or index + 1) for index, unit in enumerate(all_actual)]
    duplicates = sorted(row for row, count in Counter(row_numbers).items() if row and count > 1)
    duplicate_conditions = []
    for index, unit in enumerate(all_actual, 1):
        condition_keys = []
        for item in unit.get("rentalConditions") or []:
            name = normalized(item.get("conditionName"))
            code = clean(item.get("conditionCode"))
            priority = "1" if code == "1" or code.endswith("_1") or "1순위" in name else "23" if code in {"2", "23"} or code.endswith("_2") or "2순위" in name or "23순위" in name else name
            condition_keys.append((priority, number(item.get("rentDeposit")), number(item.get("monthlyRent"))))
        if len(condition_keys) != len(set(condition_keys)):
            duplicate_conditions.append(int(unit.get("sourceRowNumber") or index))
    summary = {
        "agency": agency,
        "expectedRows": len(truth),
        "actualRows": len(all_actual),
        "matchedRows": len(truth) - counts["missing_rows"],
        "duplicateSourceRows": duplicates,
        "duplicateRentalConditionRows": duplicate_conditions,
        "allSourceFields": dict(counts),
        "dbMappedCoreFields": dict(core_counts),
    }
    for target in (summary["allSourceFields"], summary["dbMappedCoreFields"]):
        asserted = target.get("asserted", 0)
        target["accuracyPercent"] = round(target.get("CORRECT", 0) * 100 / asserted, 2) if asserted else 0
        target["missingPercent"] = round(target.get("MISSING", 0) * 100 / asserted, 2) if asserted else 0
        target["wrongPercent"] = round(target.get("WRONG", 0) * 100 / asserted, 2) if asserted else 0
    return summary, details


def sh_db_units() -> list[dict]:
    psql = Path(r"C:\Program Files\PostgreSQL\18\bin\psql.exe")
    if not psql.exists():
        raise RuntimeError("psql.exe not found")
    sql = r"""
select json_build_object(
  'unitNumber', hs.unit_number,
  'exclusiveArea', hs.exclusive_area,
  'rentDeposit', hs.rent_deposit,
  'monthlyRent', hs.monthly_rent,
  'rawPayload', hs.raw_payload,
  'rentalConditions', coalesce((
    select json_agg(json_build_object(
      'conditionCode', c.condition_code,
      'conditionName', c.condition_name,
      'rentDeposit', c.rent_deposit,
      'monthlyRent', c.monthly_rent,
      'rawPayload', c.raw_payload
    ) order by c.housing_supply_condition_id)
    from housing_supply_condition c
    where c.housing_supply_id = hs.housing_supply_id
  ), '[]'::json)
)::text
from notice n
join housing h on h.notice_id = n.notice_id
join housing_supply hs on hs.housing_id = h.housing_id
where n.title like '%서리풀 청년아트타운%'
order by hs.housing_supply_id;
"""
    env = os.environ.copy()
    if env.get("DB_PASSWORD"):
        env["PGPASSWORD"] = env["DB_PASSWORD"]
    completed = subprocess.run(
        [str(psql), "-h", "localhost", "-p", "5432", "-U", "postgres", "-d", "zipick", "-At", "-c", sql],
        text=True, encoding="utf-8", capture_output=True, env=env, check=True,
    )
    return [json.loads(line) for line in completed.stdout.splitlines() if line.strip()]


def evaluate_sh_db(truth: list[dict]) -> tuple[dict, list[dict]]:
    db_units = sh_db_units()
    by_unit = {clean(unit.get("unitNumber")): unit for unit in db_units}
    counts = Counter()
    details = []
    for expected in truth:
        unit_number = clean(expected["fields"]["unitNumber"][0])
        actual = by_unit.get(unit_number)
        for field, (expected_value, kind, core) in expected["fields"].items():
            if not core:
                continue
            actual_text = "" if actual is None else actual_value("SH", actual, field)
            status = "MISSING" if not clean(actual_text) else "CORRECT" if same(expected_value, actual_text, kind) else "WRONG"
            counts[status] += 1
            counts["asserted"] += 1
            if status != "CORRECT":
                details.append({"agency": "SH_DB", "row": expected["row"], "page": 2, "field": field,
                                "core": True, "expected": expected_value, "actual": actual_text, "status": status})
    asserted = counts["asserted"]
    return {
        "agency": "SH_DB", "expectedRows": len(truth), "actualRows": len(db_units),
        "matchedRows": sum(1 for row in truth if clean(row["fields"]["unitNumber"][0]) in by_unit),
        "dbMappedCoreFields": {
            **dict(counts),
            "accuracyPercent": round(counts["CORRECT"] * 100 / asserted, 2) if asserted else 0,
            "missingPercent": round(counts["MISSING"] * 100 / asserted, 2) if asserted else 0,
            "wrongPercent": round(counts["WRONG"] * 100 / asserted, 2) if asserted else 0,
        },
    }, details


def main() -> None:
    truth_by_agency = {"GH": gh_truth(), "LH": lh_truth(), "SH": sh_truth()}
    summaries = []
    details = []
    for agency, truth in truth_by_agency.items():
        summary, agency_details = evaluate(agency, truth)
        summaries.append(summary)
        details.extend(agency_details)
    if "--db" in sys.argv:
        db_summary, db_details = evaluate_sh_db(truth_by_agency["SH"])
        summaries.append(db_summary)
        details.extend(db_details)

    output = {"method": "PDF_FULL_TABLE_ROWS_V1", "apiCalls": 0, "documents": summaries, "differences": details}
    RESULTS.mkdir(parents=True, exist_ok=True)
    (RESULTS / "full-pdf-field-validation.json").write_text(
        json.dumps(output, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    with (RESULTS / "full-pdf-field-differences.csv").open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=["agency", "row", "page", "field", "core", "expected", "actual", "status"])
        writer.writeheader()
        writer.writerows(details)
    print(json.dumps(summaries, ensure_ascii=False, indent=2))
    print(f"differences={len(details)}")
    print(f"report={RESULTS / 'full-pdf-field-validation.json'}")


if __name__ == "__main__":
    sys.exit(main())
