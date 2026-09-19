#!/usr/bin/env python3
"""Convert the Markdown CHANGELOG into Telegram-friendly HTML.

Reads Markdown on stdin and writes HTML suitable for parse_mode=HTML. Handles
the subset used by CHANGELOG.md: headings, block quotes, bullet lists, bold,
inline code and links. Blank lines are preserved so sections stay spaced out.
"""

import html
import re
import sys

HEADING_EMOJI = {
    "compatibility": "🔧",
    "added": "✨",
    "fixed": "🐛",
    "changed": "♻️",
    "removed": "🗑️",
    "deprecated": "⚠️",
    "security": "🔒",
    "notes": "📝",
}


def inline(text: str) -> str:
    text = html.escape(text, quote=False)
    text = re.sub(r"\[([^\]]+)\]\((https?://[^)\s]+)\)", r'<a href="\2">\1</a>', text)
    text = re.sub(r"\*\*(.+?)\*\*", r"<b>\1</b>", text)
    text = re.sub(r"(?<!\*)\*(?!\*)(.+?)(?<!\*)\*(?!\*)", r"<i>\1</i>", text)
    text = re.sub(r"`([^`]+)`", r"<code>\1</code>", text)
    return text


def heading(text: str) -> str:
    plain = text.strip().lower()
    emoji = HEADING_EMOJI.get(plain)
    label = f"{emoji} {text.strip()}" if emoji else text.strip()
    return f"<b>{inline(label)}</b>"


def convert(markdown: str) -> str:
    out = []
    quote = []

    def flush_quote():
        if quote:
            body = " ".join(quote).strip()
            if body:
                out.append(f"<blockquote>{body}</blockquote>")
            quote.clear()

    for raw in markdown.splitlines():
        stripped = raw.strip()

        if not stripped:
            flush_quote()
            out.append("")
            continue

        if stripped.startswith(">"):
            quote.append(inline(stripped.lstrip(">").strip()))
            continue

        flush_quote()

        if stripped.startswith("#"):
            out.append(heading(stripped.lstrip("#").strip()))
        elif stripped.startswith(("- ", "* ")):
            out.append("• " + inline(stripped[2:].strip()))
        elif len(stripped) >= 3 and set(stripped) <= {"-", "*", "_"}:
            continue  # horizontal rule: Telegram has no equivalent, drop it
        else:
            out.append(inline(stripped))

    flush_quote()
    return "\n".join(out).strip()


if __name__ == "__main__":
    sys.stdout.write(convert(sys.stdin.read()))
