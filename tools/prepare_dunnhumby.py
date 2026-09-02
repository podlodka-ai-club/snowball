#!/usr/bin/env python3
"""Turn the raw dunnhumby "Breakfast at the Frat" tables into the normalized fixture.

Offline preparation only: the runtime never reads the raw dataset, and the raw dataset is
never committed. Follows docs/scenario-generator/dataset-preparation.md, with the two
columns that openspec/changes/implement-scenario-generator adds - `date` and `split`.

The dataset is not downloadable without accepting terms on the dunnhumby site, so obtain it
manually and point this script at the extracted directory. dunnhumby ships one .xlsx workbook
rather than the three CSV tables the guide describes; both layouts are handled, and the xlsx
reader is built on the standard library so nobody needs to install anything.

    python3 tools/prepare_dunnhumby.py --raw ~/datasets/dunnhumby-breakfast-at-the-frat \
        --out src/test/resources/fixtures/baseline.csv

Everything here is deterministic: same input plus same seed gives a byte-identical fixture.
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import statistics
import sys
import xml.etree.ElementTree as ElementTree
import zipfile
from collections import defaultdict
from datetime import date, datetime, timedelta
from pathlib import Path

SPREADSHEET_NS = "{http://schemas.openxmlformats.org/spreadsheetml/2006/main}"
EXCEL_EPOCH = date(1899, 12, 30)

# Donor UPCs are mapped onto the demo catalogue. The donor series supplies a realistic
# baseline shape, not semantic truth: nobody claims the donor product was ice cream.
# The cost ratios are calibration, not data: the guide says cost is synthetic and that these
# are simulator parameters rather than facts from dunnhumby, and the simulator README requires
# calibrating against the prepared fixture before the first benchmark.
#
# They were fitted by replaying all four actions over the whole fixture. At the ratios first
# tried (0.55-0.70, i.e. a realistic FMCG margin of 30-45%) the oracle chose 0% in 250 of 300
# scenarios and the best action beat "always 0%" by 0.09 on average: an agent that always
# answers 0% would have been near-optimal and there would have been nothing to learn. The
# ratios below spread the oracle across 0/10/20% roughly evenly and, more importantly, make it
# differ per SKU - meat almost always wants 0%, ice cream and chips want 20% - so a Lesson
# keyed on SKU carries real information.
#
# The resulting margins (44-68%) are higher than real grocery retail. That is a deliberate
# property of the synthetic world, needed to make the action space discriminable, and it must
# be stated as such rather than presented as a finding about the data.
DEMO_CATALOGUE = [
    ("ICE500", "Ice Cream 500ml", "ice_cream", 0.32),
    ("CHIPS1", "Chips", "chips", 0.36),
    ("COLA15", "Soft Drink 1.5L", "soft_drinks", 0.42),
    ("BEER6", "Beer 6 Pack", "beer", 0.46),
    ("YOG500", "Yogurt 500g", "yogurt", 0.50),
    ("MEAT1", "Meat Pack", "meat", 0.56),
]

MIN_CLEAN_WEEKS = 80
# Below this the discount lifts disappear into integer rounding and the row teaches nothing.
MIN_DAILY_BASELINE = 5
NORMAL_STOCK_MULTIPLIER = 1.5
HIGH_STOCK_MULTIPLIER = 2.5
PRICE_TOLERANCE = 0.01
FIXTURE_COLUMNS = [
    "source_reference",
    "date",
    "split",
    "sku_id",
    "sku_name",
    "category",
    "price",
    "cost",
    "baseline_sales",
    "stock",
]


def stable_choice(key: str, modulo: int) -> int:
    """A hash that is stable across processes, unlike Python's salted hash()."""
    digest = hashlib.sha256(key.encode("utf-8")).digest()
    return int.from_bytes(digest[:8], "big") % modulo


def read_shared_strings(book: zipfile.ZipFile) -> list[str]:
    if "xl/sharedStrings.xml" not in book.namelist():
        return []
    strings = []
    with book.open("xl/sharedStrings.xml") as stream:
        for _, element in ElementTree.iterparse(stream, events=("end",)):
            if element.tag == SPREADSHEET_NS + "si":
                strings.append("".join(t.text or "" for t in element.iter(SPREADSHEET_NS + "t")))
                element.clear()
    return strings


def find_sheet(book: zipfile.ZipFile, *keywords: str) -> str:
    """Resolve a sheet by role, the way the guide resolves tables by role."""
    import re

    workbook = book.read("xl/workbook.xml").decode("utf-8", "replace")
    rels = book.read("xl/_rels/workbook.xml.rels").decode("utf-8", "replace")
    targets = dict(re.findall(r'Id="([^"]+)"[^>]*Target="([^"]+)"', rels))
    for name, rid in re.findall(r'<sheet[^>]*name="([^"]+)"[^>]*r:id="([^"]+)"', workbook):
        if all(word in name.lower() for word in keywords):
            return "xl/" + targets[rid].lstrip("/")
    raise SystemExit(f"no sheet matching {keywords} in the workbook")


def read_xlsx_rows(book: zipfile.ZipFile, sheet: str, strings: list[str]) -> list[dict[str, str]]:
    """Stream one sheet. The transaction sheet is ~230 MB of XML, so rows are cleared as we go.

    Row 1 is the workbook title and row 2 carries the column names, which is why the header is
    not simply the first row.
    """
    rows: list[dict[str, str]] = []
    header: list[str] | None = None
    with book.open(sheet) as stream:
        for _, element in ElementTree.iterparse(stream, events=("end",)):
            if element.tag != SPREADSHEET_NS + "row":
                continue
            values = []
            for cell in element:
                value_node = cell.find(SPREADSHEET_NS + "v")
                value = value_node.text if value_node is not None else None
                if cell.get("t") == "s" and value is not None:
                    value = strings[int(value)]
                values.append(value)
            element.clear()
            if header is None:
                if values and values[0]:
                    header = [v or "" for v in values]
                continue
            if any(v is not None for v in values):
                padded = values + [None] * (len(header) - len(values))
                rows.append({k: v for k, v in zip(header, padded)})
    return rows


def load_transactions(raw_dir: Path) -> list[dict[str, str]]:
    """dunnhumby ships one workbook; redistributed copies sometimes ship CSVs instead."""
    workbooks = sorted(raw_dir.rglob("*.xlsx"))
    if workbooks:
        book = zipfile.ZipFile(workbooks[0])
        strings = read_shared_strings(book)
        return read_xlsx_rows(book, find_sheet(book, "transaction"), strings)
    candidates = [p for p in raw_dir.rglob("*.csv") if "transaction" in p.name.lower()]
    if not candidates:
        raise SystemExit(f"no .xlsx workbook and no transaction CSV under {raw_dir}")
    with sorted(candidates)[0].open(newline="", encoding="utf-8-sig") as handle:
        return list(csv.DictReader(handle))


def column(row: dict[str, str], *names: str) -> str:
    """Redistributed copies rename columns; accept any of the known spellings."""
    for name in names:
        for key, value in row.items():
            if key.strip().upper() == name:
                return value
    raise KeyError(names[0])


def to_float(value: str) -> float | None:
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def parse_week(value: str) -> date | None:
    """The workbook stores dates as Excel serial numbers; CSV copies store text."""
    if value is None:
        return None
    text = str(value).strip()
    if text.isdigit():
        return EXCEL_EPOCH + timedelta(days=int(text))
    for fmt in ("%d-%b-%y", "%Y-%m-%d", "%m/%d/%Y", "%d.%m.%Y"):
        try:
            return datetime.strptime(text, fmt).date()
        except ValueError:
            continue
    return None


def is_non_promotional(row: dict[str, str]) -> bool:
    """Historical promoted sales must not become the clean baseline."""
    price = to_float(column(row, "PRICE"))
    base_price = to_float(column(row, "BASE_PRICE"))
    if price is None or base_price is None or base_price <= 0:
        return False
    if abs(price - base_price) > PRICE_TOLERANCE:
        return False
    for flag in ("FEATURE", "DISPLAY", "TPR_ONLY"):
        try:
            if (to_float(column(row, flag)) or 0) != 0:
                return False
        except KeyError:
            pass
    return True


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--raw", required=True, type=Path, help="extracted dataset directory")
    parser.add_argument("--out", required=True, type=Path, help="fixture CSV to write")
    parser.add_argument("--training", type=int, default=250, help="training rows")
    parser.add_argument("--benchmark", type=int, default=50, help="benchmark rows")
    parser.add_argument("--seed", type=int, default=42)
    args = parser.parse_args()

    if not args.raw.is_dir():
        raise SystemExit(f"raw dataset directory not found: {args.raw}")

    transactions = load_transactions(args.raw)
    print(f"loaded {len(transactions)} transaction rows", file=sys.stderr)

    # Steps 1-2 together: the donor store is the one whose six best series are strongest,
    # not the one with the most rows. Picking by history length alone yields series selling a
    # couple of units a day, and at that volume the simulator's discount lifts vanish into
    # integer rounding - every action produces the same units sold and there is nothing to
    # learn. Volume is therefore the selection criterion, and the sixth series is what limits
    # the set, so that is what gets maximised.
    clean_by_store_upc: dict[tuple[str, str], list[dict[str, str]]] = defaultdict(list)
    for row in transactions:
        if is_non_promotional(row):
            clean_by_store_upc[(column(row, "STORE_NUM"), column(row, "UPC"))].append(row)

    per_store_series: dict[str, list[tuple[float, str]]] = defaultdict(list)
    for (store, upc), rows in clean_by_store_upc.items():
        if len(rows) < MIN_CLEAN_WEEKS:
            continue
        units = [to_float(column(r, "UNITS")) or 0 for r in rows]
        per_store_series[store].append((statistics.median(units), upc))

    ranked = []
    for store, series in per_store_series.items():
        if len(series) < len(DEMO_CATALOGUE):
            continue
        top = sorted(series, reverse=True)[: len(DEMO_CATALOGUE)]
        ranked.append((top[-1][0], store, [upc for _, upc in top]))
    if not ranked:
        raise SystemExit(
            f"no store has {len(DEMO_CATALOGUE)} series with at least {MIN_CLEAN_WEEKS} clean weeks"
        )
    weakest_median, donor_store, donors = max(ranked)
    per_upc = {upc: clean_by_store_upc[(donor_store, upc)] for upc in donors}
    print(
        f"donor selection: weakest of six series has median {weakest_median:.0f} units/week "
        f"({weakest_median / 7:.0f}/day)",
        file=sys.stderr,
    )

    # Steps 3-5: weekly non-promotional units become a daily baseline; cost and stock are
    # synthetic and deterministic.
    records = []
    for donor_upc, (sku_id, sku_name, category, cost_ratio) in zip(donors, DEMO_CATALOGUE):
        weeks = []
        for row in per_upc[donor_upc]:
            week = parse_week(column(row, "WEEK_END_DATE"))
            units = to_float(column(row, "UNITS"))
            base_price = to_float(column(row, "BASE_PRICE"))
            if week and units and base_price:
                weeks.append((week, units, base_price))
        weeks.sort()
        for index, (week, units, base_price) in enumerate(weeks):
            window = [u for _, u, _ in weeks[max(0, index - 2) : index + 3]]
            baseline_sales = round(statistics.median(window) / 7)
            if baseline_sales < MIN_DAILY_BASELINE:
                continue
            # The fixture date is a real day inside the real source week, picked
            # deterministically so day_type varies without being invented wholesale.
            day_offset = stable_choice(f"{args.seed}|{sku_id}|{week.isoformat()}", 7)
            scenario_date = week - timedelta(days=day_offset)
            high_stock = stable_choice(f"{args.seed}|stock|{sku_id}|{week.isoformat()}", 2) == 1
            multiplier = HIGH_STOCK_MULTIPLIER if high_stock else NORMAL_STOCK_MULTIPLIER
            records.append(
                {
                    "source_reference": f"dunnhumby-batf:store-{donor_store}:upc-{donor_upc}:week-{week.isoformat()}",
                    "date": scenario_date.isoformat(),
                    "split": "",
                    "sku_id": sku_id,
                    "sku_name": sku_name,
                    "category": category,
                    "price": f"{base_price:.2f}",
                    "cost": f"{base_price * cost_ratio:.2f}",
                    "baseline_sales": str(baseline_sales),
                    "stock": str(round(baseline_sales * multiplier)),
                }
            )

    # The split is by time, as AGENTS.md requires: every benchmark date is strictly later
    # than every training date.
    records.sort(key=lambda r: (r["date"], r["sku_id"]))
    wanted = args.training + args.benchmark
    if len(records) < wanted:
        raise SystemExit(f"only {len(records)} usable rows, need {wanted}")
    records = records[-wanted:]
    boundary = records[args.training]["date"]
    if records[args.training - 1]["date"] == boundary:
        raise SystemExit("training and benchmark would share a date; adjust the split sizes")
    for index, record in enumerate(records):
        record["split"] = "training" if index < args.training else "benchmark"

    args.out.parent.mkdir(parents=True, exist_ok=True)
    with args.out.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=FIXTURE_COLUMNS, lineterminator="\n")
        writer.writeheader()
        writer.writerows(records)

    print(f"source: dunnhumby Breakfast at the Frat")
    print(f"donor store: {donor_store}")
    print(f"demo market: LONDON_CENTRAL")
    print(f"SKUs: {len(DEMO_CATALOGUE)}")
    print(f"fixture rows: {len(records)}")
    print(f"training: {args.training}  benchmark: {args.benchmark}")
    print(f"split boundary: first benchmark date {boundary}")
    print(f"seed: {args.seed}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
