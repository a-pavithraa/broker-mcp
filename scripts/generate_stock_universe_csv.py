#!/usr/bin/env python3

from __future__ import annotations

import argparse
import csv
import io
import json
import sys
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
import zipfile
from dataclasses import dataclass
from http.cookiejar import CookieJar
from pathlib import Path
from typing import Iterable
from urllib.parse import urlparse

DEFAULT_SECURITY_MASTER_SOURCE = "https://directlink.icicidirect.com/NewSecurityMaster/SecurityMaster.zip"
DEFAULT_GROUP_SOURCE = "https://www.cdslindia.com/Downloads/investors/CorporateGroupRepository/corporate_group_repository.xlsx"
DEFAULT_NIFTY_SOURCES = [
    "https://www.niftyindices.com/IndexConstituent/ind_niftytotalmarket_list.csv",
    "https://www.niftyindices.com/IndexConstituent/ind_nifty500list.csv",
    "https://www.niftyindices.com/IndexConstituent/ind_niftymidsmallcap400list.csv",
    "https://www.niftyindices.com/IndexConstituent/ind_NiftySmallcap500_list.csv",
    "https://www.niftyindices.com/IndexConstituent/ind_niftymicrocap250_list.csv",
]
DEFAULT_OUTPUT = Path("src/main/resources/stock-universe.csv")
SUPPORTED_SERIES = {"EQ", "BE"}


@dataclass(frozen=True)
class SecurityMasterRow:
    icici_code: str
    nse_symbol: str
    company_name: str
    series: str
    isin_code: str


@dataclass(frozen=True)
class NiftyRow:
    symbol: str
    company_name: str
    industry: str
    source: str


@dataclass(frozen=True)
class NseIndustryRow:
    macro: str
    sector: str
    industry_info: str
    basic_industry: str
    industry: str
    source: str


@dataclass(frozen=True)
class GroupRow:
    company_name: str
    ownership_group: str
    source: str


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Generate a stock-universe CSV using ICICI Security Master as the base and Nifty constituent CSVs as enrichment."
    )
    parser.add_argument(
        "--security-master-source",
        default=DEFAULT_SECURITY_MASTER_SOURCE,
        help="URL or local path for SecurityMaster.zip",
    )
    parser.add_argument(
        "--nifty-source",
        action="append",
        dest="nifty_sources",
        help="URL or local path for a Nifty constituent CSV. Repeat to layer multiple sources.",
    )
    parser.add_argument(
        "--output",
        default=str(DEFAULT_OUTPUT),
        help=f"Output CSV path. Default: {DEFAULT_OUTPUT}",
    )
    parser.add_argument(
        "--timeout-seconds",
        type=int,
        default=60,
        help="HTTP timeout in seconds for remote downloads. Default: 60",
    )
    parser.add_argument(
        "--group-source",
        default=DEFAULT_GROUP_SOURCE,
        help="URL or local path for the official corporate group repository XLSX",
    )
    parser.add_argument(
        "--nse-fallback",
        action="store_true",
        help="Fetch missing industry values from NSE quote APIs for rows not covered by Nifty enrichment.",
    )
    parser.add_argument(
        "--nse-fallback-limit",
        type=int,
        default=0,
        help="Maximum number of missing rows to query from NSE when --nse-fallback is enabled. 0 means no limit.",
    )
    parser.add_argument(
        "--nse-fallback-delay-ms",
        type=int,
        default=100,
        help="Delay between NSE fallback requests in milliseconds. Default: 100",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    nifty_sources = args.nifty_sources or DEFAULT_NIFTY_SOURCES

    try:
        security_rows = load_security_master(args.security_master_source, args.timeout_seconds)
        group_rows = load_group_rows(args.group_source, args.timeout_seconds)
        nifty_rows, loaded_sources = load_nifty_rows(nifty_sources, args.timeout_seconds)
        output_path = Path(args.output)
        existing_rows = load_existing_output(output_path)
        nse_rows: dict[str, NseIndustryRow] = {}
        nse_fetched = 0
        if args.nse_fallback:
            nse_rows, nse_fetched = load_nse_fallback_rows(
                security_rows=security_rows,
                nifty_rows=nifty_rows,
                existing_rows=existing_rows,
                timeout_seconds=args.timeout_seconds,
                request_limit=args.nse_fallback_limit,
                delay_ms=args.nse_fallback_delay_ms,
            )
        write_output(output_path, security_rows, group_rows, nifty_rows, nse_rows, existing_rows)
    except Exception as exc:
        print(f"Error: {exc}", file=sys.stderr)
        return 1

    enriched = sum(
        1
        for row in security_rows
        if (
            row.nse_symbol in nifty_rows
            or row.nse_symbol in nse_rows
            or has_cached_industry(existing_rows.get(row.nse_symbol))
        )
    )
    print(f"Wrote {len(security_rows)} rows to {output_path}")
    print(f"Enriched {enriched} rows with industry from {loaded_sources} Nifty source(s)")
    grouped = sum(1 for row in security_rows if row.isin_code and row.isin_code in group_rows)
    print(f"Enriched {grouped} rows with ownership group data")
    if args.nse_fallback:
        print(f"Fetched NSE fallback data for {nse_fetched} row(s)")
    return 0


def load_security_master(source: str, timeout_seconds: int) -> list[SecurityMasterRow]:
    payload = read_source_bytes(source, timeout_seconds)
    rows: list[SecurityMasterRow] = []

    with zipfile.ZipFile(io.BytesIO(payload)) as archive:
        entry_name = "NSEScripMaster.txt"
        if entry_name not in archive.namelist():
            raise ValueError(f"{entry_name} not found in Security Master archive")

        with archive.open(entry_name) as stream:
            text_stream = io.TextIOWrapper(stream, encoding="utf-8", newline="")
            reader = csv.reader(text_stream)
            header = next(reader, None)
            if header is None:
                raise ValueError("Security Master file is empty")

            for fields in reader:
                if len(fields) < 61:
                    continue
                icici_code = normalize(fields[1])
                series = normalize(fields[2])
                company_name = title_case(unquote(fields[3]))
                isin_code = normalize(fields[10])
                nse_symbol = normalize(fields[-1])

                if series not in SUPPORTED_SERIES:
                    continue
                if not icici_code or not nse_symbol:
                    continue

                rows.append(SecurityMasterRow(
                    icici_code=icici_code,
                    nse_symbol=nse_symbol,
                    company_name=company_name,
                    series=series,
                    isin_code=isin_code,
                ))

    rows.sort(key=lambda row: (row.icici_code, row.nse_symbol))
    return rows


def load_nifty_rows(sources: Iterable[str], timeout_seconds: int) -> tuple[dict[str, NiftyRow], int]:
    merged: dict[str, NiftyRow] = {}
    loaded_sources = 0
    for source in sources:
        source_label = source_name(source)
        try:
            payload = read_source_bytes(source, timeout_seconds)
            text = payload.decode("utf-8-sig")
            reader = csv.DictReader(io.StringIO(text))

            required_columns = {"Company Name", "Industry", "Symbol"}
            if reader.fieldnames is None or not required_columns.issubset(set(reader.fieldnames)):
                raise ValueError("unsupported CSV format")

            for raw_row in reader:
                symbol = normalize(raw_row.get("Symbol"))
                industry = clean(raw_row.get("Industry"))
                company_name = clean(raw_row.get("Company Name"))
                if not symbol or not industry:
                    continue
                merged.setdefault(symbol, NiftyRow(
                    symbol=symbol,
                    company_name=company_name,
                    industry=industry,
                    source=source_label,
                ))
            loaded_sources += 1
        except Exception as exc:
            print(f"Warning: skipping Nifty source {source}: {exc}", file=sys.stderr)

    if loaded_sources == 0:
        raise ValueError("none of the Nifty sources could be loaded")

    return merged, loaded_sources


def load_existing_output(output_path: Path) -> dict[str, dict[str, str]]:
    if not output_path.exists():
        return {}

    with output_path.open(newline="", encoding="utf-8") as handle:
        reader = csv.DictReader(handle)
        rows: dict[str, dict[str, str]] = {}
        for row in reader:
            symbol = normalize(row.get("nse_symbol"))
            if not symbol:
                continue
            rows[symbol] = {key: value for key, value in row.items() if key is not None}
        return rows


def load_group_rows(source: str, timeout_seconds: int) -> dict[str, GroupRow]:
    payload = read_source_bytes(source, timeout_seconds)
    rows = parse_xlsx_rows(payload)
    if not rows:
        raise ValueError("corporate group repository is empty")

    header = [clean(value) for value in rows[0]]
    required = {"Company Name", "Ownership Group", "ISIN"}
    if not required.issubset(set(header)):
        raise ValueError("corporate group repository has an unexpected format")

    by_name = {column: index for index, column in enumerate(header)}
    result: dict[str, GroupRow] = {}
    source_label = source_name(source)
    for row in rows[1:]:
        isin = normalize(cell(row, by_name["ISIN"]))
        ownership_group = clean(cell(row, by_name["Ownership Group"]))
        company_name = clean(cell(row, by_name["Company Name"]))
        if not isin or not ownership_group:
            continue
        result[isin] = GroupRow(
            company_name=company_name,
            ownership_group=ownership_group,
            source=source_label,
        )
    return result


def load_nse_fallback_rows(
    security_rows: list[SecurityMasterRow],
    nifty_rows: dict[str, NiftyRow],
    existing_rows: dict[str, dict[str, str]],
    timeout_seconds: int,
    request_limit: int,
    delay_ms: int,
) -> tuple[dict[str, NseIndustryRow], int]:
    client = NseQuoteClient(timeout_seconds=timeout_seconds)
    results: dict[str, NseIndustryRow] = {}
    remaining = 0
    not_found_symbols: list[str] = []
    failed_symbols: list[str] = []

    for security_row in security_rows:
        if security_row.nse_symbol in nifty_rows:
            continue
        if has_cached_industry(existing_rows.get(security_row.nse_symbol)):
            continue
        remaining += 1

    print(f"NSE fallback: attempting lookup for {remaining} missing row(s)", file=sys.stderr)
    fetched = 0
    for security_row in security_rows:
        if security_row.nse_symbol in nifty_rows:
            continue
        if has_cached_industry(existing_rows.get(security_row.nse_symbol)):
            continue
        if request_limit > 0 and fetched >= request_limit:
            break
        try:
            industry_row = client.fetch(security_row.nse_symbol, security_row.series)
            if industry_row is not None:
                results[security_row.nse_symbol] = industry_row
        except urllib.error.HTTPError as exc:
            if exc.code == 404:
                not_found_symbols.append(f"{security_row.nse_symbol}/{security_row.series}")
            else:
                failed_symbols.append(f"{security_row.nse_symbol}/{security_row.series} ({exc.code})")
                print(
                    f"Warning: NSE fallback failed for {security_row.nse_symbol}/{security_row.series}: HTTP {exc.code}",
                    file=sys.stderr,
                )
        except Exception as exc:
            failed_symbols.append(f"{security_row.nse_symbol}/{security_row.series}")
            print(
                f"Warning: NSE fallback failed for {security_row.nse_symbol}/{security_row.series}: {exc}",
                file=sys.stderr,
            )
        fetched += 1
        if delay_ms > 0:
            time.sleep(delay_ms / 1000)

    if not_found_symbols:
        sample = ", ".join(not_found_symbols[:10])
        suffix = " ..." if len(not_found_symbols) > 10 else ""
        print(
            f"NSE fallback: {len(not_found_symbols)} symbol(s) returned 404 and were skipped. "
            f"These are usually stale, renamed, delisted, or non-quoteable NSE symbols. "
            f"Sample: {sample}{suffix}",
            file=sys.stderr,
        )
    if failed_symbols:
        print(
            f"NSE fallback: {len(failed_symbols)} symbol(s) failed due to non-404 errors.",
            file=sys.stderr,
        )

    return results, fetched


def has_cached_industry(existing_row: dict[str, str] | None) -> bool:
    if not existing_row:
        return False
    return bool(clean(existing_row.get("industry")) and clean(existing_row.get("industry_source")))


def write_output(
    output_path: Path,
    security_rows: list[SecurityMasterRow],
    group_rows: dict[str, GroupRow],
    nifty_rows: dict[str, NiftyRow],
    nse_rows: dict[str, NseIndustryRow],
    existing_rows: dict[str, dict[str, str]],
) -> None:
    output_path.parent.mkdir(parents=True, exist_ok=True)

    with output_path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(
            handle,
            fieldnames=[
                "icici_code",
                "nse_symbol",
                "company_name",
                "series",
                "isin_code",
                "group",
                "group_source",
                "industry",
                "industry_source",
                "nifty_company_name",
                "nse_macro",
                "nse_sector",
                "nse_industry_info",
                "nse_basic_industry",
            ],
        )
        writer.writeheader()

        for security_row in security_rows:
            group_row = group_rows.get(security_row.isin_code)
            nifty_row = nifty_rows.get(security_row.nse_symbol)
            nse_row = nse_rows.get(security_row.nse_symbol)
            cached_row = existing_rows.get(security_row.nse_symbol, {})
            industry = nifty_row.industry if nifty_row else (
                nse_row.industry if nse_row else clean(cached_row.get("industry"))
            )
            industry_source = nifty_row.source if nifty_row else (
                nse_row.source if nse_row else clean(cached_row.get("industry_source"))
            )
            writer.writerow({
                "icici_code": security_row.icici_code,
                "nse_symbol": security_row.nse_symbol,
                "company_name": security_row.company_name,
                "series": security_row.series,
                "isin_code": security_row.isin_code,
                "group": group_row.ownership_group if group_row else clean(cached_row.get("group")),
                "group_source": group_row.source if group_row else clean(cached_row.get("group_source")),
                "industry": industry,
                "industry_source": industry_source,
                "nifty_company_name": nifty_row.company_name if nifty_row else "",
                "nse_macro": nse_row.macro if nse_row else clean(cached_row.get("nse_macro")),
                "nse_sector": nse_row.sector if nse_row else clean(cached_row.get("nse_sector")),
                "nse_industry_info": nse_row.industry_info if nse_row else clean(cached_row.get("nse_industry_info")),
                "nse_basic_industry": nse_row.basic_industry if nse_row else clean(cached_row.get("nse_basic_industry")),
            })


def read_source_bytes(source: str, timeout_seconds: int) -> bytes:
    if is_url(source):
        request = urllib.request.Request(source, headers={"User-Agent": "Mozilla/5.0"})
        with urllib.request.urlopen(request, timeout=timeout_seconds) as response:
            return response.read()
    return Path(source).read_bytes()


def is_url(source: str) -> bool:
    return source.startswith("http://") or source.startswith("https://")


def source_name(source: str) -> str:
    if is_url(source):
        return Path(urlparse(source).path).name or source
    return Path(source).name


def normalize(value: str | None) -> str:
    return clean(value).upper()


def clean(value: str | None) -> str:
    if value is None:
        return ""
    return unquote(value).strip()


def unquote(value: str) -> str:
    trimmed = value.strip()
    if trimmed.startswith('"') and trimmed.endswith('"'):
        return trimmed[1:-1].strip()
    return trimmed


def title_case(value: str) -> str:
    if not value:
        return value
    lowered = value.lower()
    chars: list[str] = []
    capitalize_next = True
    for char in lowered:
        if char.isspace() or char in "(-":
            chars.append(char)
            capitalize_next = True
            continue
        chars.append(char.upper() if capitalize_next else char)
        capitalize_next = False
    return "".join(chars)


def cell(row: list[str], index: int) -> str:
    if index < 0 or index >= len(row):
        return ""
    return row[index]


def parse_xlsx_rows(payload: bytes) -> list[list[str]]:
    ns = {"main": "http://schemas.openxmlformats.org/spreadsheetml/2006/main"}
    rows: list[list[str]] = []
    with zipfile.ZipFile(io.BytesIO(payload)) as workbook:
        shared_strings = load_shared_strings(workbook, ns)
        sheet_path = find_first_sheet_path(workbook, ns)
        with workbook.open(sheet_path) as sheet_stream:
            root = ET.parse(sheet_stream).getroot()
        for row_node in root.findall(".//main:sheetData/main:row", ns):
            current_row: list[str] = []
            expected_index = 0
            for cell_node in row_node.findall("main:c", ns):
                ref = cell_node.attrib.get("r", "")
                column_index = excel_column_index(ref)
                while expected_index < column_index:
                    current_row.append("")
                    expected_index += 1
                current_row.append(read_xlsx_cell(cell_node, shared_strings, ns))
                expected_index += 1
            rows.append(current_row)
    return rows


def load_shared_strings(workbook: zipfile.ZipFile, ns: dict[str, str]) -> list[str]:
    if "xl/sharedStrings.xml" not in workbook.namelist():
        return []
    with workbook.open("xl/sharedStrings.xml") as shared_stream:
        root = ET.parse(shared_stream).getroot()
    strings: list[str] = []
    for item in root.findall("main:si", ns):
        parts = [node.text or "" for node in item.findall(".//main:t", ns)]
        strings.append("".join(parts))
    return strings


def find_first_sheet_path(workbook: zipfile.ZipFile, ns: dict[str, str]) -> str:
    with workbook.open("xl/workbook.xml") as workbook_stream:
        workbook_root = ET.parse(workbook_stream).getroot()
    sheets = workbook_root.findall("main:sheets/main:sheet", ns)
    if not sheets:
        raise ValueError("corporate group repository workbook has no sheets")
    sheet_id = sheets[0].attrib.get("{http://schemas.openxmlformats.org/officeDocument/2006/relationships}id")
    if not sheet_id:
        raise ValueError("corporate group repository workbook is missing relationship ids")
    with workbook.open("xl/_rels/workbook.xml.rels") as rel_stream:
        rel_root = ET.parse(rel_stream).getroot()
    for rel in rel_root:
        if rel.attrib.get("Id") == sheet_id:
            target = rel.attrib.get("Target", "")
            if target.startswith("/"):
                return target.lstrip("/")
            return f"xl/{target}" if not target.startswith("xl/") else target
    raise ValueError("could not locate the first worksheet in the corporate group repository workbook")


def read_xlsx_cell(cell_node: ET.Element, shared_strings: list[str], ns: dict[str, str]) -> str:
    value_node = cell_node.find("main:v", ns)
    if value_node is None or value_node.text is None:
        inline_node = cell_node.find("main:is/main:t", ns)
        return inline_node.text if inline_node is not None and inline_node.text is not None else ""
    raw_value = value_node.text
    if cell_node.attrib.get("t") == "s":
        try:
            return shared_strings[int(raw_value)]
        except (ValueError, IndexError):
            return ""
    return raw_value


def excel_column_index(reference: str) -> int:
    letters = "".join(char for char in reference if char.isalpha()).upper()
    index = 0
    for char in letters:
        index = index * 26 + (ord(char) - ord("A") + 1)
    return max(index - 1, 0)


class NseQuoteClient:

    def __init__(self, timeout_seconds: int) -> None:
        self.timeout_seconds = timeout_seconds
        self.opener = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(CookieJar())
        )
        self.opener.addheaders = [
            ("User-Agent", "Mozilla/5.0"),
            ("Accept", "application/json,text/html,application/xhtml+xml"),
            ("Accept-Language", "en-US,en;q=0.9"),
        ]

    def fetch(self, symbol: str, series: str) -> NseIndustryRow | None:
        api_url = (
            "https://www.nseindia.com/api/NextApi/apiClient/GetQuoteApi"
            f"?functionName=getSymbolData&marketType=N&series={urllib.parse.quote(series)}"
            f"&symbol={urllib.parse.quote(symbol)}"
        )
        print(api_url)
        payload = self._read_json(api_url)
        return self._parse_symbol_data(payload)

    def _read_json(self, url: str) -> dict:
        with self.opener.open(url, timeout=self.timeout_seconds) as response:
            return json.loads(response.read().decode("utf-8"))

    def _parse_symbol_data(self, payload: dict) -> NseIndustryRow | None:
        response_rows = payload.get("equityResponse")
        if not isinstance(response_rows, list) or not response_rows:
            return None

        sec_info = response_rows[0].get("secInfo")
        if not isinstance(sec_info, dict):
            return None

        basic_industry = clean(sec_info.get("basicIndustry"))
        industry_info = clean(sec_info.get("industryInfo"))
        sector = clean(sec_info.get("sector"))
        macro = clean(sec_info.get("macro"))
        chosen_industry = basic_industry or industry_info or sector or macro
        if not chosen_industry:
            return None

        source_field = (
            "nse_getSymbolData.basicIndustry" if basic_industry else
            "nse_getSymbolData.industryInfo" if industry_info else
            "nse_getSymbolData.sector" if sector else
            "nse_getSymbolData.macro"
        )

        return NseIndustryRow(
            macro=macro,
            sector=sector,
            industry_info=industry_info,
            basic_industry=basic_industry,
            industry=chosen_industry,
            source=source_field,
        )


if __name__ == "__main__":
    raise SystemExit(main())
