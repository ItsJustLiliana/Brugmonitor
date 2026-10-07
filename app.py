import os
import sys
import re
import threading
import time
from datetime import datetime, timedelta
from pathlib import Path

from dotenv import load_dotenv
from zoneinfo import ZoneInfo

load_dotenv(Path(__file__).resolve().parent / ".env")

from flask import Flask, jsonify, send_from_directory
from selenium import webdriver
from selenium.webdriver.chrome.options import Options
from selenium.webdriver.chrome.service import Service
from selenium.common.exceptions import WebDriverException, TimeoutException

URL = "https://www.brug-open.nl/brug/sas%20van%20gent/sas%20van%20gent%20brug"
CHECK_INTERVAL = max(3, min(300, int(os.environ.get("CHECK_INTERVAL_SECONDS", "3"))))
TIMEZONE = ZoneInfo(os.environ.get("TZ", "Europe/Amsterdam"))
PAGE_LOAD_TIMEOUT = 25
RENDER_WAIT = 1.0

BASE_DIR = Path(__file__).resolve().parent
app = Flask(__name__, static_folder=None)

lock = threading.Lock()
driver = None
stop_event = threading.Event()
relay = None

state = {
    "status": None,              # Alleen OPEN of DICHT; nooit ONBEKEND
    "live_text": "",
    "message": "Status wordt geladen…",
    "detail": "",
    "last_checked": None,
    "last_success": None,
    "last_change": None,
    "stale": False,
    "error": None,
    "history": [],
}


def norm(text):
    return re.sub(r"\s+", " ", (text or "")).strip()


def parse_live_indicator(text):
    """
    Betekenis zoals vastgesteld op Brug-open:
    - 'nog +/- X minuten'       => brug is NU OPEN
    - 'langer open dan verwacht' => brug is NU OPEN, resterende tijd onbekend
    - 'X minuten geleden'       => brug is NU DICHT
    - 'langer dicht dan verwacht' => brug is NU DICHT
    """
    t = norm(text).lower()

    m = re.fullmatch(r"nog\s*\+/-\s*(\d+)\s*(minuut|minuten)", t)
    if m:
        return {
            "status": "OPEN",
            "minutes": int(m.group(1)),
            "raw": norm(text),
            "type": "remaining",
        }

    if re.fullmatch(r"langer\s+open\s+dan\s+verwacht", t):
        return {
            "status": "OPEN",
            "raw": norm(text),
            "type": "open_overdue",
        }

    m = re.fullmatch(
        r"(\d+)\s*(seconde|seconden|minuut|minuten|uur|uren)\s+geleden",
        t
    )
    if m:
        return {
            "status": "DICHT",
            "amount": int(m.group(1)),
            "unit": m.group(2),
            "raw": norm(text),
            "type": "ago",
        }

    if re.fullmatch(r"langer\s+dicht\s+dan\s+verwacht", t):
        return {
            "status": "DICHT",
            "raw": norm(text),
            "type": "closed_overdue",
        }

    if re.fullmatch(r"onlangs\s+gesloten", t):
        return {
            "status": "DICHT",
            "raw": norm(text),
            "type": "recently_closed",
        }

    return None


def make_driver():
    opts = Options()
    opts.add_argument("--headless=new")
    opts.add_argument("--disable-gpu")
    if os.environ.get("CHROME_NO_SANDBOX", "0") == "1":
        opts.add_argument("--no-sandbox")
    opts.add_argument("--disable-dev-shm-usage")
    opts.add_argument("--window-size=1500,1200")
    opts.add_argument("--lang=nl-NL")
    if os.environ.get("CHROME_BINARY"):
        opts.binary_location = os.environ["CHROME_BINARY"]
    service = Service(executable_path=os.environ["CHROMEDRIVER_PATH"]) if os.environ.get("CHROMEDRIVER_PATH") else Service()
    d = webdriver.Chrome(service=service, options=opts)
    d.set_page_load_timeout(PAGE_LOAD_TIMEOUT)
    return d


def scrape_once():
    global driver

    try:
        driver.get(URL)
    except TimeoutException:
        pass

    time.sleep(RENDER_WAIT)

    raw = driver.execute_script(r"""
        const clean = s => (s || '').replace(/\s+/g, ' ').trim();

        function visible(el) {
            const st = getComputedStyle(el);
            const r = el.getBoundingClientRect();
            return st.display !== 'none' &&
                   st.visibility !== 'hidden' &&
                   r.width > 0 && r.height > 0;
        }

        const all = Array.from(document.querySelectorAll('body *'));
        const topTexts = [];

        for (const el of all) {
            if (!visible(el)) continue;

            const r = el.getBoundingClientRect();
            if (r.top < 0 || r.top > 520) continue;

            const text = clean(el.innerText || el.textContent);
            if (!text || text.length > 120) continue;

            const children = Array.from(el.children || []).filter(visible);
            if (children.some(c => clean(c.innerText || c.textContent) === text)) continue;

            topTexts.push({
                text,
                y: Math.round(r.top),
                tag: el.tagName,
                cls: typeof el.className === 'string' ? clean(el.className) : ''
            });
        }

        const tables = Array.from(document.querySelectorAll('table')).map(table => ({
            headers: Array.from(table.querySelectorAll('thead th'))
                .map(x => clean(x.innerText || x.textContent)),
            rows: Array.from(table.querySelectorAll('tbody tr')).map(tr =>
                Array.from(tr.querySelectorAll('th,td'))
                    .map(td => clean(td.innerText || td.textContent))
            )
        }));

        return {
            topTexts,
            tables,
            pageText: document.body ? document.body.innerText : ''
        };
    """)

    indicator = None
    for item in sorted(raw.get("topTexts", []), key=lambda x: x.get("y", 99999)):
        parsed = parse_live_indicator(item.get("text", ""))
        if parsed:
            indicator = parsed
            break

    if not indicator:
        raise RuntimeError("Geen live statusindicator gevonden op de pagina")

    history = []
    for table in raw.get("tables", []):
        headers = " | ".join(x.lower() for x in table.get("headers", []))
        if "vanaf" in headers and "datum" in headers:
            for r in table.get("rows", [])[:10]:
                if len(r) >= 3:
                    history.append({
                        "from": r[0] if len(r) > 0 else "",
                        "to": r[1] if len(r) > 1 else "",
                        "date": r[2] if len(r) > 2 else "",
                        "duration": r[3] if len(r) > 3 else "",
                    })
            break

    now = datetime.now(TIMEZONE)
    status = indicator["status"]

    if indicator["type"] == "remaining":
        mins = indicator["minutes"]
        close_time = now + timedelta(minutes=mins)
        message = "De brug is open"
        detail = f"Naar verwachting nog ongeveer {mins} minuten open • sluiting rond {close_time:%H:%M}"

    elif indicator["type"] == "open_overdue":
        message = "De brug is open"
        detail = "Langer open dan verwacht • sluitingstijd is momenteel niet betrouwbaar"

    elif indicator["type"] == "closed_overdue":
        message = "De brug is dicht"
        detail = "Langer dicht dan verwacht"

    elif indicator["type"] == "recently_closed":
        message = "De brug is dicht"
        detail = "Onlangs gesloten"

    else:
        message = "De brug is dicht"
        detail = f"Laatste opening: {indicator['raw']}"

    return {
        "status": status,
        "live_text": indicator["raw"],
        "message": message,
        "detail": detail,
        "history": history,
        "checked": now,
    }


def apply_result(result):
    """Update the last valid status; the first observation establishes a baseline."""
    with lock:
        previous = state["status"]
        state.update({
            "status": result["status"], "live_text": result["live_text"],
            "message": result["message"], "detail": result["detail"], "history": result["history"],
            "last_checked": result["checked"].isoformat(), "last_success": result["checked"].isoformat(),
            "stale": False, "error": None,
        })
        if previous and previous != result["status"]:
            state["last_change"] = result["checked"].isoformat()
    if relay:
        try:
            relay.transition(previous, result["status"], result["detail"])
        except Exception:
            __import__("logging").getLogger(__name__).exception("Pushmelding in wachtrij zetten mislukt")


def publish_cloud():
    if relay:
        with lock:
            payload = dict(state)
        if payload["status"] in ("OPEN", "DICHT"):
            relay.publish(payload)


def monitor_loop():
    global driver

    while not stop_event.is_set():
        try:
            if driver is None:
                driver = make_driver()

            result = scrape_once()

            apply_result(result)

        except WebDriverException as e:
            with lock:
                state["last_checked"] = datetime.now(TIMEZONE).isoformat()
                state["stale"] = True
                state["error"] = f"Chrome/Selenium fout: {str(e)[:180]}"

            try:
                if driver:
                    driver.quit()
            except Exception:
                pass
            driver = None

        except Exception as e:
            # BELANGRIJK:
            # Geen ONBEKEND-status zetten. Laatst bekende OPEN/DICHT blijft behouden.
            with lock:
                state["last_checked"] = datetime.now(TIMEZONE).isoformat()
                state["stale"] = True
                state["error"] = str(e)[:220]

        try:
            publish_cloud()
        except Exception:
            __import__("logging").getLogger(__name__).exception("Cloud-publicatie tijdelijk niet beschikbaar")

        for _ in range(CHECK_INTERVAL * 10):
            if stop_event.is_set():
                break
            time.sleep(0.1)


@app.get("/")
def index():
    return send_from_directory(BASE_DIR, "index.html")


@app.get("/api/status")
def api_status():
    with lock:
        payload = dict(state)

    payload["source"] = URL
    payload["poll_interval_seconds"] = CHECK_INTERVAL
    return jsonify(payload)


@app.get("/api/health")
def health():
    with lock:
        return jsonify({
            "ok": state["status"] in ("OPEN", "DICHT") and not state["stale"],
            "status": state["status"],
            "stale": state["stale"],
            "last_success": state["last_success"],
            "firebase_enabled": bool(relay),
            "firestore_publish_ok": relay.publish_ok if relay else False,
            "firestore_last_publish": relay.last_publish_at if relay else None,
        })


if __name__ == "__main__":
    from server import run
    run(sys.modules[__name__])
