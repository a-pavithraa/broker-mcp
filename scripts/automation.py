"""
Breeze API — Automated Login Script
====================================
Automates the ICICI Direct Breeze login flow:

  Phase 1 — Login (#dvloginmain)
    #txtuid       → User ID
    #txtPass      → Password
    #chkssTnc     → T&C checkbox
    #btnSubmit    → calls submitform() which RSA-encrypts password, POSTs to /getotp

  Phase 2 — OTP (#dvgetotp, shown dynamically)
    input[tg-nm="otp"]  → 6 digit boxes (jQuery reads these)
    #hiotp              → hidden field populated by JS
    submitotp()         → validates, POSTs via PostForm AJAX

  Phase 3 — Redirect
    ICICI redirects to 127.0.0.1/?apisession=XXXXX (the configured app_url)
    We intercept and abort this request, extracting the token from the URL.

Usage:
  python breeze_login.py

Requirements:
  pip install playwright breeze-connect python-dotenv
  playwright install chromium

────────────────────────────────────────────────────
Gmail App Password — one-time setup (5 minutes)
────────────────────────────────────────────────────
The script reads the OTP from your Gmail inbox via IMAP.
Gmail requires an App Password (not your real Gmail password)
because IMAP access needs a dedicated credential.

Step 1 — Enable 2-Step Verification on your Google account
  (skip if already enabled)
  1. Go to https://myaccount.google.com/security
  2. Under "How you sign in to Google", click "2-Step Verification"
  3. Follow the prompts to turn it on

Step 2 — Generate an App Password
  1. Go to https://myaccount.google.com/apppasswords
     (You must be signed in; 2FA must be active — otherwise this page
      shows "The setting you are looking for is not available")
  2. In the "App name" field type:  Breeze OTP
  3. Click "Create"
  4. Google shows a 16-character password like:  abcd efgh ijkl mnop
  5. Copy it immediately — it is shown only once

Step 3 — Add to your .env file
  GMAIL_USER=your.address@gmail.com
  GMAIL_APP_PASSWORD=abcdefghijklmnop   # paste without spaces

  The script uses IMAP with readonly=True — it never marks emails as
  read or modifies your inbox in any way.

Step 4 — Allow IMAP in Gmail settings
  (skip if you already use any email client like Outlook / Thunderbird)
  1. Open Gmail → Settings (gear icon) → See all settings
  2. Go to the "Forwarding and POP/IMAP" tab
  3. Under "IMAP access", select "Enable IMAP"
  4. Click "Save Changes"

Troubleshooting:
  "Application-specific password required"  → App Password not set (Step 2)
  "Invalid credentials"                     → Wrong password or spaces left in
  "IMAP access disabled"                    → Complete Step 4
  Page at Step 2 says "not available"       → 2FA not enabled (Step 1)

────────────────────────────────────────────────────
Security notes
────────────────────────────────────────────────────
  - All secrets loaded from .env — never hardcoded
  - API key and password are NOT logged or printed
  - Session token is masked in console output
  - .env.session output contains only the session token (not the secret)
  - IMAP opens readonly — inbox is never modified
"""

from __future__ import annotations

import asyncio
import imaplib
import email
import email.header
import email.utils
import re
import os
import sys
import time
import urllib.parse
from datetime import datetime
from pathlib import Path

from dotenv import load_dotenv
from mcp_config_writer import update_mcp_env, write_session_keys

# ── Load config ────────────────────────────────────────────────────────────────
load_dotenv()

def _require(key: str) -> str:
    val = os.getenv(key, "").strip()
    if not val:
        print(f"\n❌ Missing required env var: {key}")
        print("   Add it to your .env file and retry.\n")
        sys.exit(1)
    return val

def _optional(key: str) -> str:
    return os.getenv(key, "").strip()

BREEZE_API_KEY     = _require("BREEZE_API_KEY")
BREEZE_SECRET      = _require("BREEZE_SECRET")
ICICI_USER_ID      = _optional("ICICI_USER_ID") or input("  ICICI Direct User ID: ").strip()
ICICI_PASSWORD     = _optional("ICICI_PASSWORD") or input("  ICICI Direct Password: ").strip()

GMAIL_USER         = _optional("GMAIL_USER")
GMAIL_APP_PASSWORD = _optional("GMAIL_APP_PASSWORD")

# Show browser window — set to True after confirming the script works
HEADLESS           = os.getenv("BREEZE_HEADLESS", "false").lower() == "true"

# Output file read by the MCP server
_SESSION_FILE = Path.home() / ".broker-mcp" / ".env.session"


# ── Gmail IMAP — fetch OTP from subject line ───────────────────────────────────
_ICICI_SENDERS = [
    "service@icicisecurities.com"
    
]
_OTP_SUBJECT_RE = re.compile(r"^(\d{6})\s*-")
_SESSION_RE     = re.compile(r"[?&]apisession=([^&\s]+)", re.IGNORECASE)


def _decode_subject(raw: str) -> str:
    parts = email.header.decode_header(raw)
    return " ".join(
        frag.decode(enc or "utf-8") if isinstance(frag, bytes) else frag
        for frag, enc in parts
    )


def fetch_otp_from_gmail(triggered_at: float, max_wait: int = 30) -> str | None:
    """
    Polls Gmail (IMAP) for a fresh ICICI OTP email that arrived after
    `triggered_at`. Retries every 3 s for up to `max_wait` seconds.
    Returns the 6-digit OTP or None if not found / Gmail not configured.
    """
    if not GMAIL_USER or not GMAIL_APP_PASSWORD:
        return None

    deadline = time.monotonic() + max_wait
    attempt  = 0

    while time.monotonic() < deadline:
        attempt += 1
        remaining = deadline - time.monotonic()
        sleep_for = min(3.0, remaining)
        if sleep_for > 0:
            print(f"  📬 Polling Gmail for OTP (attempt {attempt}, {int(remaining)}s left)...")
            time.sleep(sleep_for)

        mail: imaplib.IMAP4_SSL | None = None
        try:
            mail = imaplib.IMAP4_SSL("imap.gmail.com")
            mail.login(GMAIL_USER, GMAIL_APP_PASSWORD)
            mail.select("inbox", readonly=True)   # readonly — never mark as read

            # Try each known sender until we get results
            msg_ids: list[bytes] = []
            for sender in _ICICI_SENDERS:
                _, result = mail.search(None, f'FROM "{sender}"')
                if result and result[0]:
                    msg_ids = result[0].split()
                    break

            if not msg_ids:
                continue

            # Walk newest → oldest; stop once we hit emails older than trigger time
            for msg_id in reversed(msg_ids):
                _, data = mail.fetch(msg_id, "(RFC822)")
                if not data or not data[0]:
                    continue
                msg = email.message_from_bytes(data[0][1])

                # Filter by date — skip stale emails
                date_str = msg.get("Date", "")
                try:
                    email_ts = email.utils.parsedate_to_datetime(date_str).timestamp()
                except Exception:
                    email_ts = 0.0

                if email_ts < triggered_at - 5:   # 5 s clock-skew buffer
                    break                          # older than trigger — stop

                subject = _decode_subject(msg.get("Subject", ""))
                m = _OTP_SUBJECT_RE.match(subject.strip())
                if m:
                    otp = m.group(1)
                    print("  ✅ OTP received from Gmail.")
                    return otp

        except imaplib.IMAP4.error as exc:
            print(f"  ⚠️  IMAP auth error: {exc}")
            return None          # auth errors won't self-heal — bail immediately
        except Exception as exc:
            print(f"  ⚠️  Gmail IMAP error: {exc}")
        finally:
            if mail:
                try:
                    mail.logout()
                except Exception:
                    pass

    print(f"  ⚠️  No fresh OTP email after {max_wait}s.")
    return None


# ── Playwright — browser automation ────────────────────────────────────────────
async def get_breeze_session() -> str:
    from playwright.async_api import async_playwright, Route

    # API key goes in the URL as required by ICICI — we don't log it
    login_url = (
        "https://api.icicidirect.com/apiuser/login"
        f"?api_key={urllib.parse.quote_plus(BREEZE_API_KEY)}"
    )

    async with async_playwright() as p:
        browser = await p.chromium.launch(headless=HEADLESS)
        context = await browser.new_context(
            # Minimal fingerprint — no extra permissions
            java_script_enabled=True,
        )
        page = await context.new_page()

        try:
            # ── Phase 1: Login ─────────────────────────────────────────────────
            print("\n🌐 Opening Breeze login page...")
            await page.goto(login_url, wait_until="domcontentloaded")
            await page.wait_for_selector("#dvloginmain", state="visible", timeout=15_000)

            print("  👤 Entering credentials...")
            await page.fill("#txtuid",  ICICI_USER_ID)
            await page.fill("#txtPass", ICICI_PASSWORD)

            tnc = page.locator("#chkssTnc")
            if not await tnc.is_checked():
                await tnc.check()

            login_clicked_at = time.time()
            await page.click("#btnSubmit")

            # ── Phase 2: OTP ───────────────────────────────────────────────────
            print("\n  ⏳ Waiting for OTP panel...")
            await page.wait_for_selector("#dvgetotp", state="visible", timeout=15_000)

            otp = fetch_otp_from_gmail(triggered_at=login_clicked_at, max_wait=30)

            if not otp:
                print("\n  📱 Auto-OTP unavailable — enter it manually:")
                otp = input("  OTP: ").strip()

            if not re.fullmatch(r"\d{6}", otp):
                raise ValueError(f"Invalid OTP — must be exactly 6 digits.")

            print("  🔢 Submitting OTP...")

            # Set each digit via jQuery (confirmed working path).
            # Digits are sanitised above (re.fullmatch \d{6}) — safe to interpolate.
            await page.evaluate("""
                (digits) => {
                    var boxes = $('#pnlOTP').find('input[tg-nm=otp]');
                    digits.forEach(function(d, i) { $(boxes[i]).val(d); });
                }
            """, list(otp))                              # pass as JS array — no f-string

            # Sync #hiotp hidden field (submitotp() may re-read it)
            await page.evaluate("(v) => { document.getElementById('hiotp').value = v; }", otp)

            # Sanity check — verify jQuery sees the right value before submitting
            jq_val = await page.evaluate("""
                () => $('#pnlOTP').find('input[tg-nm=otp]').map(function() {
                    return $(this).val();
                }).get().join('')
            """)
            if jq_val != otp:
                raise ValueError(f"OTP mismatch before submit — jQuery sees '{jq_val}', expected '{otp}'")

            # ── Phase 3: Intercept redirect and capture token ──────────────────
            api_session: str | None = None

            async def on_route(route: Route) -> None:
                nonlocal api_session
                url = route.request.url
                # ICICI redirects to the configured app_url (127.0.0.1) with
                # the session token in the query string. Abort before Playwright
                # tries to load 127.0.0.1 (which would fail with connection refused).
                if "127.0.0.1" in url:
                    m = _SESSION_RE.search(url)
                    if m:
                        api_session = m.group(1)
                    await route.abort()
                else:
                    await route.continue_()

            await page.route("**/*", on_route)

            await page.evaluate("submitotp()")

            # Wait up to 15 s for the intercept to fire
            for _ in range(30):
                await page.wait_for_timeout(500)
                if api_session:
                    print("  ✅ Session token captured.")
                    break

            if not api_session:
                # Fallback: ask user to paste token (never print the URL itself)
                print("\n  ⚠️  Could not auto-capture session token.")
                print("  ➡️  Look at the browser address bar for a URL like:")
                print("      http://127.0.0.1/?apisession=XXXXXX")
                api_session = input("  Paste the token value after 'apisession=': ").strip()

        finally:
            await browser.close()

    if not api_session:
        raise ValueError("Failed to obtain session token.")

    return api_session


# ── Validate session and write .env.breeze ─────────────────────────────────────
def validate_and_save(api_session: str) -> None:
    from breeze_connect import BreezeConnect

    print("\n🔐 Validating session...")
    breeze = BreezeConnect(api_key=BREEZE_API_KEY)
    breeze.generate_session(api_secret=BREEZE_SECRET, session_token=api_session)
    print("  ✅ Session is valid.")

    write_session_keys({
        "BREEZE_ENABLED":  "true",
        "BREEZE_API_KEY":  BREEZE_API_KEY,
        "BREEZE_SECRET":   BREEZE_SECRET,
        "BREEZE_SESSION":  api_session,
    })
    print(f"\n💾 Session saved to: {_SESSION_FILE}")

    update_mcp_env(
        probe_keys=["BREEZE_API_KEY", "BREEZE_SESSION"],
        env_values={
            "BREEZE_ENABLED":  "true",
            "BREEZE_API_KEY":  BREEZE_API_KEY,
            "BREEZE_SECRET":   BREEZE_SECRET,
            "BREEZE_SESSION":  api_session,
        },
    )


# ── Entry point ────────────────────────────────────────────────────────────────
async def main() -> None:
    print("=" * 50)
    print("  Breeze API — Automated Login")
    print("=" * 50)

    if not GMAIL_USER:
        print(
            "\n⚠️  Gmail not configured — OTP will be entered manually."
            "\n"
            "\n   To enable automatic OTP reading, add to your .env:"
            "\n     GMAIL_USER=your.address@gmail.com"
            "\n     GMAIL_APP_PASSWORD=abcdefghijklmnop  (no spaces)"
            "\n"
            "\n   One-time App Password setup:"
            "\n     1. Enable 2FA  →  https://myaccount.google.com/security"
            "\n     2. Generate    →  https://myaccount.google.com/apppasswords"
            "\n     3. Enable IMAP →  Gmail Settings → Forwarding and POP/IMAP"
            "\n"
            "\n   Full step-by-step instructions are in the docstring at the top of this file.\n"
        )

    try:
        api_session = await get_breeze_session()
        masked = api_session[:4] + "*" * max(0, len(api_session) - 4)
        print(f"\n🎉 Session token: {masked}")
        validate_and_save(api_session)
        print("\n✅ Done. Start your MCP server now.")
    except KeyboardInterrupt:
        print("\n\nAborted.")
        sys.exit(0)
    except Exception as exc:
        print(f"\n❌ Login failed: {exc}")
        import traceback
        traceback.print_exc()
        sys.exit(1)


if __name__ == "__main__":
    asyncio.run(main())