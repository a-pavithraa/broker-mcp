"""
Zerodha Console tradebook export automation.

Phase 2 companion to zerodha_login.py:
1. Log in to Console
2. Open Reports -> Tradebook
3. Set segment + date range
4. Download CSV into a local directory
5. Update MCP env so the next server start watches that directory for the downloaded CSV set

The selectors in Console can change. This script therefore uses a mix of role-
based selectors and DOM heuristics, and prints actionable errors when it cannot
find the expected controls.
"""

from __future__ import annotations

import argparse
import asyncio
import base64
import hashlib
import hmac
import os
import re
import struct
import sys
import time
from datetime import date, datetime, timedelta
from pathlib import Path

from dotenv import load_dotenv

sys.path.insert(0, str(Path(__file__).parent / "scripts"))
from mcp_config_writer import update_mcp_env, write_session_keys


load_dotenv()


def _require(key: str) -> str:
    value = os.getenv(key, "").strip()
    if not value:
        print(f"\n❌ Missing required env var: {key}")
        print("   Add it to your .env file and retry.\n")
        sys.exit(1)
    return value


def _optional(key: str, default: str = "") -> str:
    return os.getenv(key, default).strip()


ZERODHA_USER_ID = _optional("ZERODHA_USER_ID") or input("  Zerodha User ID: ").strip()
ZERODHA_PASSWORD = _optional("ZERODHA_PASSWORD") or input("  Zerodha Password: ").strip()
ZERODHA_TOTP_SECRET = _optional("ZERODHA_TOTP_SECRET")
ZERODHA_HEADLESS = _optional("ZERODHA_HEADLESS", "false").lower() == "true"
ZERODHA_TRADEBOOK_SEGMENT = _optional("ZERODHA_TRADEBOOK_SEGMENT", "Equity")
ZERODHA_TRADEBOOK_IMPORT_ROOT = _optional("ZERODHA_TRADEBOOK_IMPORT_ROOT", str(Path.home() / ".broker-mcp" / "imports"))
ZERODHA_TRADEBOOK_FILENAME = _optional("ZERODHA_TRADEBOOK_FILENAME", "zerodha-tradebook.csv")
ZERODHA_TRADEBOOK_TIMEOUT_SECONDS = int(_optional("ZERODHA_TRADEBOOK_TIMEOUT_SECONDS", "180"))
ZERODHA_TRADEBOOK_MAX_EXPORT_DAYS = 365
ZERODHA_BROWSER_STATE_DIR = Path(_optional(
    "ZERODHA_BROWSER_STATE_DIR",
    str(Path.home() / ".broker-mcp" / "playwright" / "zerodha"),
)).expanduser()

CONSOLE_TRADEBOOK_URL = "https://console.zerodha.com/reports/tradebook"


class EmptyTradebookReport(RuntimeError):
    """Raised when Zerodha Console shows an empty tradebook result set for a date range."""


def _generate_totp(secret: str, for_time: int | None = None, interval: int = 30) -> str:
    normalized = secret.replace(" ", "").upper()
    padded = normalized + "=" * ((8 - len(normalized) % 8) % 8)
    key = base64.b32decode(padded, casefold=True)
    timestamp = int(for_time if for_time is not None else time.time())
    counter = timestamp // interval
    digest = hmac.new(key, struct.pack(">Q", counter), hashlib.sha1).digest()
    offset = digest[-1] & 0x0F
    code = struct.unpack(">I", digest[offset:offset + 4])[0] & 0x7FFFFFFF
    return f"{code % 1_000_000:06d}"


def current_financial_year(today: date) -> tuple[date, date]:
    start_year = today.year if today.month >= 4 else today.year - 1
    return date(start_year, 4, 1), today


def resolve_history_years(configured_years: int | None = None) -> int:
    if configured_years is not None:
        return configured_years
    raw_value = _optional("BROKER_TRADE_HISTORY_YEARS", "5")
    try:
        years = int(raw_value)
    except ValueError as exc:
        raise ValueError(f"Invalid BROKER_TRADE_HISTORY_YEARS value: {raw_value}") from exc
    if years <= 0:
        raise ValueError("BROKER_TRADE_HISTORY_YEARS must be greater than 0.")
    return years


def subtract_years(value: date, years: int) -> date:
    try:
        return value.replace(year=value.year - years)
    except ValueError:
        # Handles Feb 29 by falling back to Feb 28 in non-leap target years.
        return value.replace(month=2, day=28, year=value.year - years)


def default_history_window(today: date, history_years: int) -> tuple[date, date]:
    return subtract_years(today, history_years), today


def chunk_date_range(from_date: date, to_date: date, max_span_days: int = ZERODHA_TRADEBOOK_MAX_EXPORT_DAYS) -> list[tuple[date, date]]:
    if from_date > to_date:
        raise ValueError("from_date must be on or before to_date")
    if max_span_days <= 0:
        raise ValueError("max_span_days must be greater than 0")

    chunks: list[tuple[date, date]] = []
    chunk_start = from_date
    while chunk_start <= to_date:
        chunk_end = min(chunk_start + timedelta(days=max_span_days), to_date)
        chunks.append((chunk_start, chunk_end))
        chunk_start = chunk_end + timedelta(days=1)
    return chunks


def chunk_filename(filename: str, from_date: date, to_date: date) -> str:
    path = Path(filename)
    return f"{path.stem}-{from_date.isoformat()}-to-{to_date.isoformat()}{path.suffix}"


def format_console_date(value: date) -> str:
    return value.strftime("%Y-%m-%d")


async def _fill_second_factor(page, code: str) -> bool:
    return await page.evaluate(
        """
        (code) => {
          const isVisible = (el) => {
            const style = window.getComputedStyle(el);
            const rect = el.getBoundingClientRect();
            return style.display !== 'none'
              && style.visibility !== 'hidden'
              && rect.width > 0
              && rect.height > 0;
          };

          const inputs = Array.from(document.querySelectorAll('input')).filter((el) => {
            const type = (el.type || 'text').toLowerCase();
            if (type === 'hidden') return false;
            if (!isVisible(el)) return false;
            const isLoginField = ['userid', 'password'].includes(el.id) && type !== 'number';
            if (isLoginField) return false;
            return ['text', 'password', 'tel', 'number'].includes(type);
          });

          if (!inputs.length) return false;

          const digitBoxes = inputs.filter((el) => Number(el.maxLength) === 1);
          if (digitBoxes.length >= code.length) {
            code.split('').forEach((digit, index) => {
              const target = digitBoxes[index];
              if (!target) return;
              target.focus();
              target.value = digit;
              target.dispatchEvent(new Event('input', { bubbles: true }));
              target.dispatchEvent(new Event('change', { bubbles: true }));
            });
            return true;
          }

          const target = inputs[0];
          target.focus();
          target.value = code;
          target.dispatchEvent(new Event('input', { bubbles: true }));
          target.dispatchEvent(new Event('change', { bubbles: true }));
          return true;
        }
        """,
        code,
    )


async def _submit_visible_form(page) -> bool:
    return await page.evaluate(
        """
        () => {
          const isVisible = (el) => {
            const style = window.getComputedStyle(el);
            const rect = el.getBoundingClientRect();
            return style.display !== 'none'
              && style.visibility !== 'hidden'
              && rect.width > 0
              && rect.height > 0;
          };

          const submitters = Array.from(document.querySelectorAll(
            'button[type="submit"], input[type="submit"], button'
          )).filter(isVisible);

          if (!submitters.length) return false;
          submitters[0].click();
          return true;
        }
        """
    )


async def _page_needs_second_factor(page) -> bool:
    return await page.evaluate(
        """
        () => {
          const isVisible = (el) => {
            const style = window.getComputedStyle(el);
            const rect = el.getBoundingClientRect();
            return style.display !== 'none'
              && style.visibility !== 'hidden'
              && rect.width > 0
              && rect.height > 0;
          };

          return Array.from(document.querySelectorAll('input')).some((el) => {
            const type = (el.type || 'text').toLowerCase();
            if (type === 'hidden') return false;
            if (!isVisible(el)) return false;
            const isLoginField = ['userid', 'password'].includes(el.id) && type !== 'number';
            if (isLoginField) return false;
            return ['text', 'password', 'tel', 'number'].includes(type);
          });
        }
        """
    )


async def login_to_console(page) -> None:
    await page.goto(CONSOLE_TRADEBOOK_URL, wait_until="domcontentloaded")
    # Allow JS-driven redirects (e.g. session check → login page) to settle
    await page.wait_for_timeout(2_000)

    if "console.zerodha.com" in page.url and "login" not in page.url:
        return

    password = page.locator("#password")
    user_id = page.locator("#userid")

    # Wait for at least the password field to be visible (covers both fresh login
    # and the "remembered user" state where #userid is hidden but password is shown)
    try:
        await password.first.wait_for(state="visible", timeout=15_000)
    except Exception:
        if "console.zerodha.com" in page.url and "login" not in page.url:
            return
        raise RuntimeError(f"Console login form not found. Current URL: {page.url}")

    print("  👤 Logging in to Zerodha Console...")
    user_id_visible = await user_id.first.is_visible()
    if user_id_visible:
        await user_id.first.fill(ZERODHA_USER_ID)
        await user_id.first.press("Tab")
        await password.first.wait_for(state="visible", timeout=5_000)

    await password.first.fill(ZERODHA_PASSWORD)
    await page.locator('button[type="submit"]').first.click()

    deadline = time.monotonic() + ZERODHA_TRADEBOOK_TIMEOUT_SECONDS
    last_totp_window: int | None = None
    printed_manual_message = False

    while time.monotonic() < deadline:
        if "console.zerodha.com/reports/tradebook" in page.url:
            return

        needs_2fa = await _page_needs_second_factor(page)
        if needs_2fa:
            if ZERODHA_TOTP_SECRET:
                current_window = int(time.time()) // 30
                if current_window != last_totp_window:
                    code = _generate_totp(ZERODHA_TOTP_SECRET)
                    print("  🔐 Submitting TOTP...")
                    filled = await _fill_second_factor(page, code)
                    if not filled:
                        raise ValueError("Unable to locate the Zerodha 2FA input field.")
                    clicked = await _submit_visible_form(page)
                    if not clicked:
                        await page.keyboard.press("Enter")
                    last_totp_window = current_window
                    await page.wait_for_timeout(3_000)
            elif not printed_manual_message:
                printed_manual_message = True
                if ZERODHA_HEADLESS:
                    print("\n  📱 2FA required. Enter the 6-digit authenticator code:")
                    loop = asyncio.get_event_loop()
                    code = await loop.run_in_executor(None, lambda: input("  Code: ").strip())
                    if not re.fullmatch(r"\d{6}", code):
                        raise ValueError("Expected a 6-digit 2FA code.")
                    filled = await _fill_second_factor(page, code)
                    if not filled:
                        raise ValueError("Unable to locate the Zerodha 2FA input field.")
                    clicked = await _submit_visible_form(page)
                    if not clicked:
                        await page.keyboard.press("Enter")
                    await page.wait_for_timeout(3_000)
                else:
                    print(
                        "\n  ⏳ Complete Zerodha Console 2FA in the opened browser."
                        "\n     The script will continue automatically once the Tradebook page loads."
                    )

        await page.wait_for_timeout(1_000)

    raise TimeoutError(f"Timed out after {ZERODHA_TRADEBOOK_TIMEOUT_SECONDS}s waiting for Console login.")


async def _click_first(page, selectors: list[str], timeout_ms: int = 3_000) -> bool:
    for selector in selectors:
        locator = page.locator(selector).first
        try:
            if await locator.count() and await locator.is_visible():
                await locator.click(timeout=timeout_ms)
                return True
        except Exception:
            continue
    return False


async def _has_visible(page, selectors: list[str]) -> bool:
    for selector in selectors:
        locator = page.locator(selector).first
        try:
            if await locator.count() and await locator.is_visible():
                return True
        except Exception:
            continue
    return False


async def _fill_first(page, selectors: list[str], value: str, timeout_ms: int = 3_000) -> bool:
    for selector in selectors:
        locator = page.locator(selector).first
        try:
            if await locator.count() and await locator.is_visible():
                await locator.fill(value, timeout=timeout_ms)
                return True
        except Exception:
            continue
    return False


def is_empty_tradebook_text(text: str) -> bool:
    normalized = " ".join((text or "").lower().split())
    empty_markers = (
        "report's empty",
        "reports empty",
        "report is empty",
        "we couldn't find any results for your query",
        "we could not find any results for your query",
        "no results for your query",
    )
    return any(marker in normalized for marker in empty_markers)


async def read_tradebook_page_text(page) -> str:
    try:
        return await page.evaluate("() => document.body?.innerText || ''")
    except Exception:
        return ""


async def wait_for_tradebook_ready(page, timeout_ms: int | None = None) -> None:
    timeout_ms = timeout_ms or (ZERODHA_TRADEBOOK_TIMEOUT_SECONDS * 1_000)
    deadline = time.monotonic() + (timeout_ms / 1_000)
    last_url = ""

    while time.monotonic() < deadline:
        last_url = page.url
        if "login" in last_url:
            raise RuntimeError(f"Console session is not active. Current URL: {last_url}")

        ready = await page.evaluate(
            """
            () => {
              const isVisible = (el) => {
                const style = window.getComputedStyle(el);
                const rect = el.getBoundingClientRect();
                return style.display !== 'none'
                  && style.visibility !== 'hidden'
                  && rect.width > 0
                  && rect.height > 0;
              };

              const dateInput = Array.from(document.querySelectorAll('input')).find((el) => {
                if (!isVisible(el)) return false;
                const attrs = [
                  el.name || '',
                  el.id || '',
                  el.placeholder || '',
                  el.getAttribute('aria-label') || '',
                  el.className || '',
                ].join(' ').toLowerCase();
                return attrs.includes('date') || attrs.includes('range') || el.type === 'date';
              });

              const select = Array.from(document.querySelectorAll('select')).find(isVisible);
              return Boolean(dateInput || select);
            }
            """
        )
        if ready:
            return

        try:
            await page.wait_for_load_state("networkidle", timeout=2_000)
        except Exception:
            pass
        await page.wait_for_timeout(1_000)

    raise RuntimeError(f"Timed out waiting for the Console tradebook page to become interactive. Current URL: {last_url}")


async def _set_tradebook_date_range(page, range_value: str) -> bool:
    selectors = [
        "input[name='date']",
        "input[placeholder*='date' i]",
        "input[aria-label*='date' i]",
        "input[type='date']",
    ]
    for selector in selectors:
        locator = page.locator(selector).first
        try:
            if await locator.count() and await locator.is_visible():
                await locator.click(click_count=3)
                await locator.fill(range_value)
                await locator.press("Enter")
                return True
        except Exception:
            continue

    return await page.evaluate(
        """
        (rangeValue) => {
          const isVisible = (el) => {
            const style = window.getComputedStyle(el);
            const rect = el.getBoundingClientRect();
            return style.display !== 'none'
              && style.visibility !== 'hidden'
              && rect.width > 0
              && rect.height > 0;
          };

          const inputs = Array.from(document.querySelectorAll('input')).filter((el) => {
            if (!isVisible(el)) return false;
            const attrs = [
              el.name || '',
              el.id || '',
              el.placeholder || '',
              el.getAttribute('aria-label') || '',
              el.className || '',
            ].join(' ').toLowerCase();
            return attrs.includes('date') || attrs.includes('range') || el.type === 'date';
          });

          const target = inputs[0];
          if (!target) return false;
          target.focus();
          target.value = rangeValue;
          target.dispatchEvent(new Event('input', { bubbles: true }));
          target.dispatchEvent(new Event('change', { bubbles: true }));
          return true;
        }
        """,
        range_value,
    )


async def set_tradebook_filters(page, segment: str, from_date: date, to_date: date) -> None:
    print(f"  📅 Setting Tradebook filters: segment={segment}, from={from_date}, to={to_date}")
    if "console.zerodha.com/reports/tradebook" not in page.url:
        await page.goto(CONSOLE_TRADEBOOK_URL, wait_until="domcontentloaded")
    await wait_for_tradebook_ready(page)

    # Set segment via <select> (the only select on the page)
    segment_select = page.locator("select").first
    try:
        if await segment_select.count() and await segment_select.is_visible():
            await segment_select.select_option(label=segment)
    except Exception:
        pass

    # Date range is a single input[name="date"] with value "YYYY-MM-DD ~ YYYY-MM-DD"
    from_value = format_console_date(from_date)
    to_value = format_console_date(to_date)
    range_value = f"{from_value} ~ {to_value}"

    if not await _set_tradebook_date_range(page, range_value):
        raise RuntimeError("Unable to locate the Console tradebook date range input.")

    # Click the → submit button
    clicked_view = await _click_first(page, [
        "button.button-blue",
        "button:has(svg)",
        "button[type='submit']",
        "[role='button']:has(svg)",
    ])
    if not clicked_view:
        await page.keyboard.press("Enter")

    await page.wait_for_timeout(3_000)


async def download_tradebook_csv(page, download_dir: Path, filename: str) -> Path:
    download_dir.mkdir(parents=True, exist_ok=True)
    print(f"  ⬇️  Downloading tradebook CSV to {download_dir}...")

    _csv_selectors = [
        "text=/^CSV$/i",
        "button:has-text('CSV')",
        "a:has-text('CSV')",
        "[role='button']:has-text('CSV')",
        "[role='menuitem']:has-text('CSV')",
        "[title*='CSV' i]",
        "[aria-label*='CSV' i]",
        "[title*='complete results' i]",
    ]
    _download_selectors = [
        "button:has-text('Download')",
        "[role='button']:has-text('Download')",
        "a:has-text('Download')",
        "text=/Download/i",
    ]

    has_csv = await _has_visible(page, _csv_selectors)
    has_download = has_csv or await _has_visible(page, _download_selectors)
    if not has_download:
        page_text = await read_tradebook_page_text(page)
        if is_empty_tradebook_text(page_text):
            raise EmptyTradebookReport("No tradebook rows were found for the selected date range.")
        raise RuntimeError("Unable to locate the Console download controls.")

    async with page.expect_download(timeout=30_000) as download_info:
        clicked_csv = await _click_first(page, _csv_selectors)
        if not clicked_csv:
            clicked_download = await _click_first(page, _download_selectors)
            if not clicked_download:
                page_text = await read_tradebook_page_text(page)
                if is_empty_tradebook_text(page_text):
                    raise EmptyTradebookReport("No tradebook rows were found for the selected date range.")
                raise RuntimeError("Unable to locate the Console download controls.")
            await page.wait_for_timeout(1_000)
            clicked_csv = await _click_first(page, _csv_selectors)
            if not clicked_csv:
                raise RuntimeError("Download menu opened, but CSV option was not found.")

    download = await download_info.value
    target_path = download_dir / filename
    await download.save_as(str(target_path))
    return target_path


def update_tradebook_env(downloaded_path: Path) -> None:
    env_values = {
        "ZERODHA_TRADEBOOK_IMPORT_ROOT": str(downloaded_path.parent),
    }
    for key in (
        "ZERODHA_TRADEBOOK_STORE_PATH",
        "SPRING_PROFILES_ACTIVE",
        "MCP_SERVER_PROTOCOL",
        "BROKER_TOOLS_MODE",
    ):
        value = _optional(key)
        if value:
            env_values[key] = value

    write_session_keys({k: v for k, v in env_values.items()
                        if k in ("ZERODHA_TRADEBOOK_IMPORT_ROOT", "ZERODHA_TRADEBOOK_STORE_PATH")})
    update_mcp_env(
        probe_keys=["ZERODHA_API_KEY", "ZERODHA_ACCESS_TOKEN"],
        env_values=env_values,
        restart=False,
    )


async def export_tradebook_chunk(
        page,
        chunk_from: date,
        chunk_to: date,
        download_dir: Path,
        filename: str,
        segment: str,
        chunk_index: int,
        total_chunks: int,
) -> Path | None:
    print(f"  📦 Exporting chunk {chunk_index}/{total_chunks}: {chunk_from} to {chunk_to}")
    await set_tradebook_filters(page, segment, chunk_from, chunk_to)
    try:
        downloaded_path = await download_tradebook_csv(page, download_dir, filename)
    except EmptyTradebookReport:
        print(f"  ⚪ Skipping empty chunk {chunk_index}/{total_chunks}: {chunk_from} to {chunk_to}")
        return None
    print(f"  ✅ Tradebook downloaded: {downloaded_path}")
    return downloaded_path


async def export_tradebooks(from_date: date, to_date: date, download_dir: Path, filename: str, segment: str) -> list[Path]:
    chunk_ranges = chunk_date_range(from_date, to_date, ZERODHA_TRADEBOOK_MAX_EXPORT_DAYS)
    from playwright.async_api import async_playwright

    async with async_playwright() as playwright:
        ZERODHA_BROWSER_STATE_DIR.mkdir(parents=True, exist_ok=True)
        context = await playwright.chromium.launch_persistent_context(
            user_data_dir=str(ZERODHA_BROWSER_STATE_DIR),
            headless=ZERODHA_HEADLESS,
            accept_downloads=True,
        )
        page = context.pages[0] if context.pages else await context.new_page()

        try:
            print("\n🌐 Opening Zerodha Console...")
            await login_to_console(page)
            downloaded_paths: list[Path] = []
            for index, (chunk_from, chunk_to) in enumerate(chunk_ranges, start=1):
                chunk_name = chunk_filename(filename, chunk_from, chunk_to) if len(chunk_ranges) > 1 else filename
                downloaded_path = await export_tradebook_chunk(
                    page=page,
                    chunk_from=chunk_from,
                    chunk_to=chunk_to,
                    download_dir=download_dir,
                    filename=chunk_name,
                    segment=segment,
                    chunk_index=index,
                    total_chunks=len(chunk_ranges),
                )
                if downloaded_path is not None:
                    downloaded_paths.append(downloaded_path)
            return downloaded_paths
        except Exception:
            screenshot = download_dir / "zerodha-tradebook-error.png"
            try:
                download_dir.mkdir(parents=True, exist_ok=True)
                await page.screenshot(path=str(screenshot), full_page=True)
                print(f"  📸 Saved failure screenshot: {screenshot}")
            except Exception:
                pass
            raise
        finally:
            await context.close()


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Download Zerodha Console tradebook CSV")
    parser.add_argument("--fy-current", action="store_true", help="Export only the current Indian financial year")
    parser.add_argument("--from-date", help="Start date in YYYY-MM-DD")
    parser.add_argument("--to-date", help="End date in YYYY-MM-DD")
    parser.add_argument("--years", type=int, help="Years of trade history to export when no explicit date range is supplied")
    parser.add_argument("--download-dir", default=ZERODHA_TRADEBOOK_IMPORT_ROOT, help="Directory to save the CSV")
    parser.add_argument("--filename", default=ZERODHA_TRADEBOOK_FILENAME, help="Downloaded CSV filename")
    parser.add_argument("--segment", default=ZERODHA_TRADEBOOK_SEGMENT, help="Console segment label, e.g. Equity")
    parser.add_argument("--no-mcp-update", action="store_true", help="Do not write the import root back into MCP env")
    return parser.parse_args()


async def main() -> None:
    args = parse_args()
    today = datetime.now().date()
    if args.from_date and args.to_date:
        from_date = datetime.strptime(args.from_date, "%Y-%m-%d").date()
        to_date = datetime.strptime(args.to_date, "%Y-%m-%d").date()
    elif args.fy_current:
        from_date, to_date = current_financial_year(today)
    else:
        from_date, to_date = default_history_window(today, resolve_history_years(args.years))

    print("=" * 56)
    print("  Zerodha Console Tradebook Export")
    print("=" * 56)

    downloaded_paths = await export_tradebooks(
        from_date=from_date,
        to_date=to_date,
        download_dir=Path(args.download_dir).expanduser(),
        filename=args.filename,
        segment=args.segment,
    )
    if not downloaded_paths:
        print("\n⚠️ No tradebook CSVs were downloaded because the requested date range returned no rows.")
        return
    if not args.no_mcp_update:
        update_tradebook_env(downloaded_paths[-1])
        print("  ✅ MCP env updated with ZERODHA_TRADEBOOK_IMPORT_ROOT")
    print("\n✅ Done.")


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("\n\nAborted.")
        sys.exit(0)
    except Exception as exc:
        print(f"\n❌ Zerodha tradebook export failed: {exc}")
        import traceback

        traceback.print_exc()
        sys.exit(1)
