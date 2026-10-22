#!/usr/bin/env python3
"""Verify public release pages and save reproducible, non-secret HTTP evidence."""
import argparse
import hashlib
import json
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from html.parser import HTMLParser
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen

parser = argparse.ArgumentParser()
parser.add_argument("--version", required=True, help="Version ID returned by cf deploy")
args = parser.parse_args()
root = Path(__file__).resolve().parent
base = "https://roomote.crabfish.dev"
expected_apks = {
    "/downloads/roomote-1.3.0.apk": {
        "filename": "Roomote-1.3.0.apk",
        "sha256": "a3d063210b158fe0e922a8625746ae3a229b870990d5d61652980e917a94ccad",
    },
    "/downloads/roomote-1.4.0.apk": {
        "filename": "Roomote-1.4.0.apk",
        "sha256": "93df0ea33806b558a884cc933185dcca944472b926232c3aa54bee2c2fc9f4de",
    },
}
routes = {
    "/": ("index.html", 200),
    "/privacy/": ("privacy/index.html", 200),
    "/support/": ("support/index.html", 200),
    "/test/": ("test/index.html", 200),
    **{route: (route.lstrip("/"), 200) for route in expected_apks},
    "/sitemap.xml": ("sitemap.xml", 200),
    "/robots.txt": ("robots.txt", 200),
    "/assets/icon.svg": ("assets/icon.svg", 200),
    "/assets/social.png": ("assets/social.png", 200),
    "/assets/remote-screen.png": ("assets/remote-screen.png", 200),
    "/styles.css": ("styles.css", 200),
    "/privacy-policy": ("privacy/index.html", 200),
    "/roomote-verification-missing-page": ("404.html", 404),
}


class Scripts(HTMLParser):
    def __init__(self):
        super().__init__()
        self.count = 0

    def handle_starttag(self, tag, attrs):
        if tag == "script":
            self.count += 1


def inspect(route):
    request = Request(base + route, headers={"User-Agent": "Roomote-release-verification/1.0"})
    try:
        response = urlopen(request, timeout=30)
    except HTTPError as error:
        response = error
    with response:
        body = response.read()
        headers = {key.lower(): value for key, value in response.headers.items()}
        local, expected_status = routes[route]
        scripts = Scripts()
        if "text/html" in headers.get("content-type", ""):
            scripts.feed(body.decode("utf-8"))
        return {
            "path": route,
            "status": response.status,
            "expected_status": expected_status,
            "final_url": response.url,
            "bytes": len(body),
            "sha256": hashlib.sha256(body).hexdigest(),
            "matches_deployed_source": body == (root / "dist" / local).read_bytes(),
            "headers": {
                key: headers[key]
                for key in (
                    "content-type", "content-security-policy", "x-content-type-options",
                    "referrer-policy", "permissions-policy", "x-frame-options",
                    "cache-control", "server", "cf-ray", "location", "content-disposition",
                )
                if key in headers
            },
            "sets_cookie": "set-cookie" in headers,
            "script_elements": scripts.count,
            "analytics_script_found": b"cloudflareinsights" in body or b"googletagmanager" in body,
        }


with ThreadPoolExecutor(max_workers=5) as pool:
    results = list(pool.map(inspect, routes))

apk_results = [item for item in results if item["path"] in expected_apks]
checks = {
    "expected_http_statuses": all(item["status"] == item["expected_status"] for item in results),
    "response_matches_source": all(item["matches_deployed_source"] for item in results),
    "csp_present": all("content-security-policy" in item["headers"] for item in results),
    "no_transform_present": all("no-transform" in item["headers"].get("cache-control", "") for item in results),
    "no_injected_scripts": all(item["script_elements"] == 0 for item in results),
    "no_analytics_beacon": all(not item["analytics_script_found"] for item in results),
    "no_set_cookie": all(not item["sets_cookie"] for item in results),
    "privacy_alias_redirects": next(item for item in results if item["path"] == "/privacy-policy")["final_url"] == base + "/privacy/",
    "apk_content_type": all(item["headers"].get("content-type") == "application/vnd.android.package-archive" for item in apk_results),
    "apk_download_filename": all(
        item["headers"].get("content-disposition") == f'attachment; filename="{expected_apks[item["path"]]["filename"]}"'
        for item in apk_results
    ),
    "apk_matches_verified_binary": all(item["sha256"] == expected_apks[item["path"]]["sha256"] for item in apk_results),
}
evidence = {
    "verified_at": datetime.now(timezone.utc).isoformat(),
    "deployment": {
        "worker": "roomote-site",
        "version_id": args.version,
        "domain": base,
        "command": "cf deploy --prebuilt",
    },
    "checks": checks,
    "https_requests": results,
    "source_assets": {
        str(path.relative_to(root / "dist")): hashlib.sha256(path.read_bytes()).hexdigest()
        for path in sorted((root / "dist").rglob("*")) if path.is_file()
    },
}
(root / "evidence").mkdir(exist_ok=True)
(root / "evidence" / "production-verification.json").write_text(json.dumps(evidence, indent=2) + "\n")
for item in results:
    print(json.dumps({key: item[key] for key in ("path", "status", "matches_deployed_source", "script_elements", "analytics_script_found")}))
print(json.dumps(checks, sort_keys=True))
if not all(checks.values()):
    raise SystemExit("One or more deployment checks failed; inspect evidence/production-verification.json")
