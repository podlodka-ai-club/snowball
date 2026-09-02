#!/usr/bin/env python3
"""Turn the raw dunnhumby "Breakfast at the Frat" tables into the normalized fixture.

Offline preparation only: the runtime never reads the raw dataset, and the raw dataset is
never committed. Follows docs/scenario-generator/dataset-preparation.md, with the two
columns that openspec/changes/implement-scenario-generator adds - `date` and `split`.

The dataset is not downloadable without accepting terms on the dunnhumby site, so obtain it
manually and point this script at the extracted directory:

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
from collections import defaultdict
from datetime import date, timedelta
from pathlib import Path

# Donor UPCs are mapped onto the demo catalogue. The donor series supplies a realistic
# baseline shape, not semantic truth: nobody claims the donor product was ice cream.
DEMO_CATALOGUE = [
    ("ICE500", "Ice Cream 500ml", "ice_cream", 0.60),
    ("BEER6", "Beer 6 Pack", "beer", 0.62),
    ("COLA15", "Soft Drink 1.5L", "soft_drinks", 0.57),
    ("CHIPS1", "Chips", "chips", 0.55),
    ("MEAT1", "Meat Pack", "meat", 0.70),
    ("YOG500", "Yogurt 500g", "yogurt", 0.58),
]

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


def find_table(raw_dir: Path, *keywords: str) -> Path:
    """Locate a table by role rather than by exact filename, as the guide advises."""
    candidates = [
        path
        for path in raw_dir.rglob("*.csv")
        if all(word in path.name.lower() for word in keywords)
    ]
    if not candidates:
        raise SystemExit(f"no CSV under {raw_dir} matching {keywords}")
    return sorted(candidates)[0]


def read_rows(path: Path) -> list[dict[str, str]]:
    with path.open(newline="", encoding="utf-8-sig") as handle:
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
    for fmt in ("%d-%b-%y", "%Y-%m-%d", "%m/%d/%Y", "%d.%m.%Y"):
        try:
            from datetime import datetime

            return datetime.strptime(value.strip(), fmt).date()
        except (ValueError, AttributeError):
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

    transactions = read_rows(find_table(args.raw, "transaction"))

    # Step 1: one donor store, chosen by coverage then volume.
    per_store: dict[str, list[dict[str, str]]] = defaultdict(list)
    for row in transactions:
        per_store[column(row, "STORE_NUM")].append(row)
    donor_store = max(
        per_store,
        key=lambda store: (
            sum(1 for r in per_store[store] if is_non_promotional(r)),
            sum(to_float(column(r, "UNITS")) or 0 for r in per_store[store]),
        ),
    )
    store_rows = [r for r in per_store[donor_store] if is_non_promotional(r)]

    # Step 2: six donor UPCs with the longest clean history.
    per_upc: dict[str, list[dict[str, str]]] = defaultdict(list)
    for row in store_rows:
        per_upc[column(row, "UPC")].append(row)
    donors = sorted(per_upc, key=lambda upc: (-len(per_upc[upc]), upc))[: len(DEMO_CATALOGUE)]
    if len(donors) < len(DEMO_CATALOGUE):
        raise SystemExit(f"only {len(donors)} usable donor series in store {donor_store}")

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
            if baseline_sales <= 0:
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
