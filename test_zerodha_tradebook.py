import os
import unittest
from datetime import date
from pathlib import Path
from unittest.mock import AsyncMock, patch


os.environ.setdefault("ZERODHA_USER_ID", "test-user")
os.environ.setdefault("ZERODHA_PASSWORD", "test-password")

import zerodha_tradebook


class ZerodhaTradebookTests(unittest.IsolatedAsyncioTestCase):

    def test_is_empty_tradebook_text_recognizes_console_empty_state(self):
        self.assertTrue(zerodha_tradebook.is_empty_tradebook_text(
            "Report's empty\nWe couldn't find any results for your query"
        ))

    async def test_download_tradebook_csv_raises_empty_report_when_controls_are_missing(self):
        page = AsyncMock()

        with patch.object(zerodha_tradebook, "_has_visible", AsyncMock(return_value=False)), \
                patch.object(zerodha_tradebook, "read_tradebook_page_text", AsyncMock(
                    return_value="Report's empty\nWe couldn't find any results for your query"
                )), \
                patch("builtins.print"):
            with self.assertRaises(zerodha_tradebook.EmptyTradebookReport):
                await zerodha_tradebook.download_tradebook_csv(page, Path("imports"), "tradebook.csv")

    async def test_export_tradebook_chunk_skips_empty_report(self):
        page = AsyncMock()

        with patch.object(zerodha_tradebook, "set_tradebook_filters", AsyncMock()), \
                patch.object(
                    zerodha_tradebook,
                    "download_tradebook_csv",
                    AsyncMock(side_effect=zerodha_tradebook.EmptyTradebookReport("empty"))
                ), \
                patch("builtins.print"):
            result = await zerodha_tradebook.export_tradebook_chunk(
                page=page,
                chunk_from=date(2021, 3, 20),
                chunk_to=date(2022, 3, 20),
                download_dir=Path("imports"),
                filename="tradebook.csv",
                segment="Equity",
                chunk_index=1,
                total_chunks=3,
            )

        self.assertIsNone(result)


if __name__ == "__main__":
    unittest.main()
