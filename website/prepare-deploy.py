#!/usr/bin/env python3
"""Package static assets using cf CLI's Build Output Specification v0."""
import json
import shutil
from pathlib import Path

root = Path(__file__).resolve().parent
config_path = root / "cloudflare.json"
if not config_path.is_file():
    raise SystemExit("Copy cloudflare.example.json to cloudflare.json and configure your Cloudflare account and domain first.")
config = json.loads(config_path.read_text())
output = root / ".cloudflare/output/v0"
worker = output / "workers/default"
worker.mkdir(parents=True, exist_ok=True)
assets = worker / "assets"
if assets.exists():
    shutil.rmtree(assets)
shutil.copytree(root / "dist", assets, ignore=shutil.ignore_patterns(".DS_Store", "._*"))
(output / "config.json").write_text(json.dumps({
    "accountId": config["accountId"], "buildContext": {"isPreview": False}
}, indent=2) + "\n")
(worker / "worker.config.json").write_text(json.dumps(config["worker"], indent=2) + "\n")
print("Prepared static assets for cf deploy --prebuilt.")
