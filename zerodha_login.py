"""
Zerodha Kite Connect — Login Automation
=======================================
Automates the supported part of the Zerodha Kite Connect login flow:

1. Open the public connect login URL with the API key
2. Enter the Zerodha user ID and password
3. Complete 2FA:
   - automatically if ZERODHA_TOTP_SECRET is configured (the base32 seed from
     your authenticator app's "show key" / setup QR — same algorithm, same codes)
   - via terminal prompt if running headless without a stored secret
   - interactively in the visible browser otherwise
4. Capture the redirected `request_token`
5. Exchange it for an `access_token`
6. Write session tokens to `~/.broker-mcp/.env.session`

Important limitations:
  - Zerodha does not provide a supported fully headless username/password login API
  - If the account uses Kite app code, the user must still approve/enter it manually
  - External TOTP can be automated if `ZERODHA_TOTP_SECRET` is configured

Requirements:
  pip install playwright python-dotenv
  playwright install chromium
"""

from __future__ import annotations

import asyncio
import base64
import hashlib
import hmac
import json
import os
import re
import struct
import sys
import time
import urllib.parse
import urllib.request
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


ZERODHA_API_KEY = _require("ZERODHA_API_KEY")
ZERODHA_API_SECRET = _require("ZERODHA_API_SECRET")
ZERODHA_USER_ID = _optional("ZERODHA_USER_ID") or input("  Zerodha User ID: ").strip()
ZERODHA_PASSWORD = _optional("ZERODHA_PASSWORD") or input("  Zerodha Password: ").strip()

ZERODHA_TOTP_SECRET = _optional("ZERODHA_TOTP_SECRET")
ZERODHA_HEADLESS = _optional("ZERODHA_HEADLESS", "false").lower() == "true"
_SESSION_FILE = Path.home() / ".broker-mcp" / ".env.session"
ZERODHA_REDIRECT_PARAMS = _optional("ZERODHA_REDIRECT_PARAMS")
ZERODHA_LOGIN_TIMEOUT_SECONDS = int(_optional("ZERODHA_LOGIN_TIMEOUT_SECONDS", "180"))
ZERODHA_BROWSER_STATE_DIR = Path(_optional(
    "ZERODHA_BROWSER_STATE_DIR",
    str(Path.home() / ".broker-mcp" / "playwright" / "zerodha"),
)).expanduser()




def _mask(value: str) -> str:
    if not value:
        return ""
    if len(value) <= 4:
        return "*" * len(value)
    return value[:4] + "*" * (len(value) - 4)


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


def _extract_request_token(url: str) -> str | None:
    query = urllib.parse.urlparse(url).query
    token = urllib.parse.parse_qs(query).get("request_token", [])
    if token:
        return token[0]
    return None


def _kite_login_url() -> str:
    params = {"v": "3", "api_key": ZERODHA_API_KEY}
    if ZERODHA_REDIRECT_PARAMS:
        params["redirect_params"] = ZERODHA_REDIRECT_PARAMS
    return "https://kite.zerodha.com/connect/login?" + urllib.parse.urlencode(params)


def exchange_request_token(request_token: str) -> dict[str, object]:
    checksum = hashlib.sha256(
        f"{ZERODHA_API_KEY}{request_token}{ZERODHA_API_SECRET}".encode("utf-8")
    ).hexdigest()
    body = urllib.parse.urlencode(
        {
            "api_key": ZERODHA_API_KEY,
            "request_token": request_token,
            "checksum": checksum,
        }
    ).encode("utf-8")

    request = urllib.request.Request(
        "https://api.kite.trade/session/token",
        data=body,
        method="POST",
        headers={
            "X-Kite-Version": "3",
            "Content-Type": "application/x-www-form-urlencoded",
        },
    )

    with urllib.request.urlopen(request, timeout=30) as response:
        payload = json.loads(response.read().decode("utf-8"))

    if payload.get("status") != "success" or "data" not in payload:
        raise ValueError(f"Token exchange failed: {payload}")

    return payload["data"]


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
            // Zerodha reuses id="userid" for the TOTP field (type="number", maxLength=6)
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
            // Zerodha reuses id="userid" for the TOTP field (type="number", maxLength=6)
            const isLoginField = ['userid', 'password'].includes(el.id) && type !== 'number';
            if (isLoginField) return false;
            return ['text', 'password', 'tel', 'number'].includes(type);
          });
        }
        """
    )


async def get_request_token() -> str:
    from playwright.async_api import async_playwright

    captured_token: str | None = None
    login_url = _kite_login_url()
    last_totp_window: int | None = None
    printed_manual_message = False
    submitted_credentials = False

    async with async_playwright() as playwright:
        ZERODHA_BROWSER_STATE_DIR.mkdir(parents=True, exist_ok=True)
        context = await playwright.chromium.launch_persistent_context(
            user_data_dir=str(ZERODHA_BROWSER_STATE_DIR),
            headless=ZERODHA_HEADLESS,
        )
        page = context.pages[0] if context.pages else await context.new_page()

        def capture_from_url(url: str) -> None:
            nonlocal captured_token
            token = _extract_request_token(url)
            if token:
                captured_token = token

        page.on("framenavigated", lambda frame: capture_from_url(frame.url))

        page.on("request", lambda request: capture_from_url(request.url))

        try:
            print("\n🌐 Opening Zerodha Kite Connect login...")
            await page.goto(login_url, wait_until="domcontentloaded")

            deadline = time.monotonic() + ZERODHA_LOGIN_TIMEOUT_SECONDS
            while time.monotonic() < deadline:
                if captured_token:
                    print("  ✅ request_token captured.")
                    return captured_token

                token = _extract_request_token(page.url)
                if token:
                    print("  ✅ request_token captured.")
                    return token

                user_id = page.locator("#userid")
                password = page.locator("#password")
                password_visible = (
                    not submitted_credentials
                    and await password.count() > 0
                    and await password.first.is_visible()
                )
                if password_visible:
                    user_id_visible = await user_id.count() > 0 and await user_id.first.is_visible()
                    print("  👤 Entering credentials...")
                    if user_id_visible:
                        await user_id.first.fill(ZERODHA_USER_ID)
                        # Tab commits the user ID and triggers Vue's v-model update on the userid field
                        await user_id.first.press("Tab")
                        await password.first.wait_for(state="visible", timeout=5_000)
                    await password.first.fill(ZERODHA_PASSWORD)
                    await page.locator('button[type="submit"]').first.click()
                    submitted_credentials = True
                    await page.wait_for_timeout(1_000)
                    continue

                needs_2fa = await _page_needs_second_factor(page)
                if needs_2fa:
                    if ZERODHA_TOTP_SECRET:
                        current_window = int(time.time()) // 30
                        if current_window != last_totp_window:
                            code = _generate_totp(ZERODHA_TOTP_SECRET)
                            print("  🔐 Submitting TOTP (from stored secret)...")
                            filled = await _fill_second_factor(page, code)
                            if not filled:
                                raise ValueError("Unable to locate the Zerodha 2FA input field.")
                            clicked = await _submit_visible_form(page)
                            if not clicked:
                                await page.keyboard.press("Enter")
                            last_totp_window = current_window
                            # Give the page time to validate and respond before looping
                            await page.wait_for_timeout(3_000)
                    elif not printed_manual_message:
                        printed_manual_message = True
                        if ZERODHA_HEADLESS:
                            # Browser is hidden — prompt for the code in the terminal
                            print("\n  📱 2FA required. Open your authenticator app and enter the 6-digit code:")
                            loop = asyncio.get_event_loop()
                            code = await loop.run_in_executor(None, lambda: input("  Code: ").strip())
                            if not re.fullmatch(r"\d{6}", code):
                                print("  ⚠️  Expected exactly 6 digits — skipping auto-fill.")
                            else:
                                filled = await _fill_second_factor(page, code)
                                if not filled:
                                    raise ValueError("Unable to locate the Zerodha 2FA input field.")
                                clicked = await _submit_visible_form(page)
                                if not clicked:
                                    await page.keyboard.press("Enter")
                                await page.wait_for_timeout(3_000)
                        else:
                            print(
                                "\n  ⏳ Complete Zerodha 2FA in the opened browser."
                                "\n     Enter the code from your authenticator app or Kite app."
                                "\n     The script will capture the redirect automatically."
                            )

                await page.wait_for_timeout(1_000)

        finally:
            await context.close()

    raise TimeoutError(
        f"Timed out after {ZERODHA_LOGIN_TIMEOUT_SECONDS}s waiting for request_token."
    )


def save_session(session: dict[str, object], request_token: str) -> None:
    access_token = str(session.get("access_token", ""))
    if not access_token:
        raise ValueError("Token exchange succeeded but access_token is missing.")

    session_keys: dict[str, str] = {
        "ZERODHA_ENABLED": "true",
        "ZERODHA_API_KEY": ZERODHA_API_KEY,
        "ZERODHA_USER_ID": str(session.get("user_id", "")),
        "ZERODHA_ACCESS_TOKEN": access_token,
    }
    for key in (
        "BROKER_TOOLS_MODE",
        "SPRING_PROFILES_ACTIVE",
        "MCP_SERVER_PROTOCOL",
        "ZERODHA_TRADEBOOK_IMPORT_ROOT",
        "ZERODHA_TRADEBOOK_STORE_PATH",
    ):
        value = os.getenv(key, "").strip()
        if value:
            session_keys[key] = value

    write_session_keys(session_keys)
    print(f"\n💾 Zerodha session saved to: {_SESSION_FILE}")

    update_mcp_env(
        probe_keys=["ZERODHA_API_KEY", "ZERODHA_ACCESS_TOKEN"],
        env_values=session_keys,
    )


async def main() -> None:
    print("=" * 50)
    print("  Zerodha Kite Connect — Login")
    print("=" * 50)

    if not ZERODHA_TOTP_SECRET:
        if ZERODHA_HEADLESS:
            print(
                "\nℹ️  ZERODHA_TOTP_SECRET is not configured."
                "\n   Running headless — you will be prompted in this terminal for the 2FA code."
                "\n   To skip this prompt, set ZERODHA_TOTP_SECRET to the base32 seed from your"
                "\n   authenticator app's 'Show key' or setup screen.\n"
            )
        else:
            print(
                "\nℹ️  ZERODHA_TOTP_SECRET is not configured."
                "\n   The browser will open — complete 2FA there manually."
                "\n   To automate it, set ZERODHA_TOTP_SECRET to the base32 seed from your"
                "\n   authenticator app's 'Show key' or setup screen.\n"
            )

    try:
        request_token = await get_request_token()
        print(f"\n🎟️  request_token: {_mask(request_token)}")
        print("\n🔐 Exchanging request_token for access_token...")
        session = exchange_request_token(request_token)
        print(f"  ✅ access_token: {_mask(str(session.get('access_token', '')))}")
        save_session(session, request_token)
        print("\n✅ Done.")
    except KeyboardInterrupt:
        print("\n\nAborted.")
        sys.exit(0)
    except Exception as exc:
        print(f"\n❌ Zerodha login failed: {exc}")
        import traceback

        traceback.print_exc()
        sys.exit(1)


if __name__ == "__main__":
    asyncio.run(main())
