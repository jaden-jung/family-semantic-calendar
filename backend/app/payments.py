from __future__ import annotations

import re
from datetime import datetime
from decimal import Decimal


AMOUNT_RE = re.compile(r"([\d,]+)\s*원")


def parse_card_sms(raw_text: str, received_at: datetime) -> dict:
    amount_match = AMOUNT_RE.search(raw_text)
    amount = Decimal(amount_match.group(1).replace(",", "")) if amount_match else None

    compact = " ".join(raw_text.split())
    merchant = None
    if amount_match:
        tail = compact[amount_match.end() :].strip()
        merchant = tail.split()[0] if tail else None

    category = categorize_merchant(merchant or raw_text)
    title = f"{merchant or '카드결제'} {amount:,.0f}원" if amount else merchant or "카드결제"

    return {
        "title": title,
        "body": compact,
        "starts_at": received_at,
        "merchant": merchant,
        "amount": amount,
        "category": category,
    }


def categorize_merchant(text: str) -> str:
    lowered = text.lower()
    rules = {
        "식비": ["식당", "카페", "커피", "배달", "마트", "편의점", "restaurant", "cafe"],
        "교통": ["택시", "버스", "지하철", "주유", "충전", "parking"],
        "의료": ["병원", "약국", "의원", "clinic", "pharmacy"],
        "쇼핑": ["쿠팡", "네이버", "스토어", "mall", "shop"],
        "생활": ["관리비", "통신", "전기", "가스", "water"],
    }
    for category, keywords in rules.items():
        if any(keyword in lowered for keyword in keywords):
            return category
    return "기타"
