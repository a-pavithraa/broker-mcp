# Scripts

## `generate_stock_universe_csv.py`

Builds a local CSV snapshot with:

- ICICI Security Master as the base stock universe
- official CDSL Corporate Group Repository as ownership-group enrichment
- one or more Nifty constituent CSVs as industry enrichment

Default Nifty enrichment set:

- `Nifty Total Market`
- `Nifty 500`
- `Nifty MidSmallcap 400`
- `Nifty Smallcap 500`
- `Nifty Microcap 250`

If one of the default Nifty URLs is slow or unavailable, the script logs a warning and continues with the others. It fails only if none of the Nifty sources can be loaded.

Optional NSE fallback:

- `--nse-fallback` queries the NSE quote API for rows still missing industry after Nifty enrichment
- fallback values are written into `industry` when Nifty has no match
- the script also writes `nse_macro`, `nse_sector`, `nse_industry_info`, and `nse_basic_industry`
- existing values from the current output CSV are reused as cache on later runs
- `group` and `group_source` are populated from the official CDSL repository by `ISIN`

Default output:

```bash
python3 scripts/generate_stock_universe_csv.py
```

Explicit output:

```bash
python3 scripts/generate_stock_universe_csv.py \
  --output src/main/resources/stock-universe.csv
```

Use a local corporate-group repository file:

```bash
python3 scripts/generate_stock_universe_csv.py \
  --group-source /tmp/corporate_group_repository.xlsx \
  --output src/main/resources/stock-universe.csv
```

Enable NSE fallback for still-missing rows:

```bash
python3 scripts/generate_stock_universe_csv.py \
  --output src/main/resources/stock-universe.csv \
  --nse-fallback
```

Limit fallback requests while testing:

```bash
python3 scripts/generate_stock_universe_csv.py \
  --output /tmp/stock-universe.csv \
  --nse-fallback \
  --nse-fallback-limit 25
```

Add more Nifty constituent files:

```bash
python3 scripts/generate_stock_universe_csv.py \
  --nifty-source https://www.niftyindices.com/IndexConstituent/ind_nifty500list.csv \
  --nifty-source /path/to/another-nifty-constituent.csv
```

Passing any `--nifty-source` arguments replaces the default Nifty source list for that run.

Offline/local-source mode:

```bash
python3 scripts/generate_stock_universe_csv.py \
  --security-master-source /tmp/SecurityMaster.zip \
  --nifty-source /tmp/ind_nifty500list.csv \
  --output /tmp/stock-universe.csv
```
