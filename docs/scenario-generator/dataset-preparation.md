# Dataset preparation

For the hackathon, use one fixed market and one prepared baseline dataset.

## Fixed hackathon market

All generated scenarios belong to one logical market:

```text
store_id: LONDON_CENTRAL
store_name: London Central
timezone: Europe/London
```

This is a deployment constraint for the MVP, not a restriction in the Kafka schema. The event contract keeps `store_id` and `store_name` so a later deployment can support multiple locations without changing the Promotion Agent contract.

`London Central` is a synthetic demo market. The retail dataset is used only as a source of realistic baseline sales and price patterns. We do not claim that the original dataset transactions happened in London.

That distinction matters because the context generator can safely use one London calendar/weather model while the baseline series remains reproducible.

## Recommended dataset

Use **dunnhumby — Breakfast at the Frat** as the first baseline source.

Official source page:

https://www.dunnhumby.com/source-files/

Why this dataset fits the MVP:

- 156 weeks of product/store sales history;
- unit sales by product, store, and week;
- base price and actual shelf price;
- promotional-support indicators;
- store and product lookup data;
- it is specifically suitable for price-sensitivity and promotional-effectiveness analysis.

This is more useful for the promotion-agent MVP than a very large generic retail dataset because the fields needed to identify historical promotion periods are already present.

### Download

1. Open the official dunnhumby Source Files page.
2. Find **Breakfast at the Frat**.
3. Download the dataset package from the page.
4. Extract it outside the application repository, for example:

```text
~/datasets/dunnhumby-breakfast-at-the-frat/
```

The package contains three logical tables:

- transaction data;
- product lookup;
- store lookup.

Common CSV names in copies of the package are:

```text
Transaction_Data.csv
Products_Lookup.csv
Store_Lookup.csv
```

Treat the table roles rather than exact filenames as the dependency, because redistributed copies sometimes rename them.

Do not commit the raw dataset into this repository unless its distribution terms explicitly allow that. Commit only the small normalized fixture produced for this project.

## What we use from the source

From transaction data we need only the equivalent of:

```text
WEEK_END_DATE
STORE_NUM
UPC
UNITS
PRICE
BASE_PRICE
FEATURE
DISPLAY
TPR_ONLY
```

From product lookup we need enough information to identify candidate UPCs. Store lookup is used only during one-time donor-store selection.

Everything else is irrelevant to the runtime MVP.

## Step 1: choose one donor store

The source contains many stores, but our hackathon world has exactly one market.

Select one source store once during offline preparation. A simple deterministic rule is:

1. group transaction rows by `STORE_NUM`;
2. calculate total `UNITS` and number of weeks with valid `PRICE` and `BASE_PRICE`;
3. discard stores with poor coverage;
4. choose the remaining store with the highest total units;
5. save that source store number in preparation metadata.

After this step, discard all other source stores.

The selected source store is a **baseline donor**, not the runtime identity. Runtime events still use:

```text
LONDON_CENTRAL / London Central
```

This avoids teaching the rest of the application about source geography.

## Step 2: select donor product series

The dunnhumby categories do not exactly match the six demo-friendly FMCG SKUs we want to show.

That is acceptable because the public dataset is providing realistic baseline shapes, not semantic ground truth for the simulator.

Choose six UPCs with:

- long history;
- few missing prices;
- non-zero sales in most periods;
- enough non-promotional weeks to estimate a baseline.

Map those six donor series to the demo product catalog in a tiny preparation config, for example:

```text
donor UPC A -> ICE500  -> Ice Cream 500ml -> ice_cream
donor UPC B -> BEER6   -> Beer 6 Pack      -> beer
donor UPC C -> COLA15  -> Soft Drink 1.5L  -> soft_drinks
donor UPC D -> CHIPS1  -> Chips             -> chips
donor UPC E -> MEAT1   -> Meat Pack         -> meat
donor UPC F -> YOG500  -> Yogurt 500g       -> yogurt
```

Do not claim that the donor UPC itself was ice cream or beer. It is simply a historical baseline series used to seed a controlled simulator.

## Step 3: remove historical promotion leakage

The Promotion Agent will choose a new `0/10/20/30%` action. Therefore historical promoted sales should not be treated as the clean baseline.

For baseline estimation, prefer rows where the source indicates no promotion.

A practical filter is:

```text
PRICE approximately equals BASE_PRICE
AND FEATURE is false/0
AND DISPLAY is false/0
AND TPR_ONLY is false/0
```

Use a small numeric tolerance for price equality rather than exact floating-point equality.

Rows outside this filter can still be useful later for simulator calibration, but they should not directly become `baseline_sales` observations.

## Step 4: convert weekly sales to daily baseline

Breakfast at the Frat is weekly, while the MVP promotion action is for one day.

For each selected UPC/store/week:

```text
baseline_weekly_units = robust baseline estimate
baseline_sales = round(baseline_weekly_units / 7)
```

For the simplest MVP, use the source week's non-promotional `UNITS` directly before dividing by seven.

A slightly better preparation step uses a rolling median of nearby non-promotional weeks for the same UPC. Median is preferable to mean here because a few ugly retail weeks should not become mythology in xmemory.

The simulator later applies its own deterministic weekday/weekend, weather, event, discount, and noise effects.

## Step 5: derive fields missing from the dataset

The dataset does not provide every input used by our simulator. Generate missing fields deterministically during fixture preparation.

### Price

Use the non-promotional `BASE_PRICE` as scenario `price`.

Do not use an already discounted shelf price as the scenario base price, otherwise the simulator can accidentally discount a discount.

### Cost

Cost is synthetic in the MVP.

Keep a fixed `cost_ratio` per demo SKU, for example:

```text
cost = price * cost_ratio
```

Store the ratios in preparation configuration so the same fixture is reproducible.

The exact ratios are simulator parameters, not facts learned from dunnhumby.

### Stock

The source does not represent our desired stock signal. Derive stock from baseline demand using a deterministic multiplier:

```text
normal stock = baseline_sales * normal_stock_multiplier
high stock   = baseline_sales * high_stock_multiplier
```

For example, use roughly `1.5x` and `2.5x` and tune only if the simulator produces obviously bad scenarios.

The fixture may contain the numeric `stock`; `stock_level` can be derived by Scenario Generator application code.

## Step 6: write the normalized fixture

The runtime `DatasetBaselineSource` should read a small project-specific CSV, not the raw public dataset.

Recommended columns:

```text
source_reference,
sku_id,
sku_name,
category,
price,
cost,
baseline_sales,
stock
```

See [`baseline-fixture.example.csv`](baseline-fixture.example.csv).

Example source reference:

```text
dunnhumby-batf:store-12:upc-123456:week-2011-04-02
```

This preserves traceability without leaking source-specific fields into the Kafka event.

The Scenario Generator then adds the fixed market identity and generated context:

```text
baseline fixture
      +
London Central market config
      +
deterministic day/weather/event context
      =
PromotionScenarioEvent v1
```

## Suggested preparation script

Keep preparation outside the Kotlin runtime, for example:

```text
tools/
  prepare_dunnhumby.py
```

The script should:

1. load the three source tables;
2. select one donor store;
3. select/configure six donor UPCs;
4. filter baseline observations to non-promotional periods;
5. calculate daily baseline sales;
6. derive synthetic cost and stock;
7. write a deterministic fixture;
8. print a small summary for review.

Expected summary:

```text
source: dunnhumby Breakfast at the Frat
donor store: <STORE_NUM>
demo market: LONDON_CENTRAL
SKUs: 6
fixture rows: 300
missing price rows: 0
seed: 42
```

A few hundred rows are enough. Do not import hundreds of thousands of rows into the runtime just to prove that CSV files can be large.

## Training and benchmark split

Prepare the fixture once with a fixed seed and split it into two immutable sets:

```text
training scenarios: 200-300
benchmark scenarios: 50
```

The training set is allowed to populate xmemory.

The benchmark set must not be used to create lessons before evaluation. Run those same fixed scenarios against:

- clean xmemory;
- trained xmemory.

This prevents the benchmark from becoming a particularly elaborate way of asking the agent whether it remembers its homework.

## Alternative: M5

Use M5 only if downloading Breakfast at the Frat becomes inconvenient.

M5 provides daily unit sales, store/item IDs, weekly sell prices, and calendar events. It is useful, but for this MVP it requires more work to infer a clean base price/discount relationship and its Kaggle download requires accepting the competition rules.

The Scenario Generator architecture does not change. Only the offline preparation script changes:

```text
M5 raw files
    -> prepare_m5.py
    -> same baseline fixture format
    -> same DatasetBaselineSource
    -> same PromotionScenarioEvent v1
```

That is the source-adapter principle applied one level earlier: raw dataset choice should not infect the runtime contract.
