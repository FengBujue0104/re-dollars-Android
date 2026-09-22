#!/usr/bin/env python3
"""Compare live TLS chain SPKI pins with AppModule and network security config.

Rules (per host):
  - Live chain must match **at least one** AppModule pin for that host (MATCH).
  - NSC shared pin-set must contain **all** AppModule pins for that host (superset OK).
  - Exit nonzero on mismatch.

Optional:
  --check-expiry  warn if leaf notAfter < 30 days (or pinned intermediate if leaf not matched)
  --json          emit a CI-friendly JSON summary on stdout after the table
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import re
import ssl
import subprocess
import sys
import tempfile
from datetime import datetime, timezone
from pathlib import Path
from xml.etree import ElementTree

ROOT = Path(__file__).resolve().parents[1]
APP_MODULE = ROOT / "app/src/main/java/mk/ry/redollars/di/AppModule.kt"
NETWORK_CONFIG = ROOT / "app/src/main/res/xml/network_security_config.xml"
HOSTS = ("rd.ry.mk", "up.ry.mk", "auth.ry.mk", "bgm.tv", "lain.bgm.tv")
EXPIRY_WARN_DAYS = 30


def app_pins() -> dict[str, set[str]]:
    pins = {host: set() for host in HOSTS}
    pattern = re.compile(r'\.add\("([^"]+)", "sha256/([^"]+)"\)')
    for host, pin in pattern.findall(APP_MODULE.read_text()):
        if host in pins:
            pins[host].add(pin)
    return pins


def xml_pins() -> dict[str, set[str]]:
    """Map each known host to the pin-set of its domain-config (shared set = same pins)."""
    result = {host: set() for host in HOSTS}
    root = ElementTree.parse(NETWORK_CONFIG).getroot()
    for config in root.findall("domain-config"):
        domains = {node.text.strip() for node in config.findall("domain") if node.text}
        pins = {
            node.text.strip()
            for node in config.findall("./pin-set/pin")
            if node.text
        }
        for host in HOSTS:
            if host in domains:
                result[host].update(pins)
    return result


def live_chain(host: str) -> list[tuple[str, str, datetime | None]]:
    """Return [(subject, spki_b64, not_after_utc_or_None), ...] leaf first."""
    proc = subprocess.run(
        ["openssl", "s_client", "-connect", f"{host}:443", "-servername", host, "-showcerts"],
        input="\n",
        text=True,
        capture_output=True,
        check=False,
        timeout=20,
    )
    if proc.returncode != 0 and "BEGIN CERTIFICATE" not in (proc.stdout or ""):
        raise RuntimeError(f"openssl s_client failed for {host}: {proc.stderr.strip()[:200]}")

    certs = re.findall(
        r"-----BEGIN CERTIFICATE-----.*?-----END CERTIFICATE-----",
        proc.stdout,
        re.DOTALL,
    )
    if not certs:
        raise RuntimeError(f"no certificates returned by {host}")

    chain: list[tuple[str, str, datetime | None]] = []
    for pem in certs:
        der = ssl.PEM_cert_to_DER_cert(pem)
        with tempfile.NamedTemporaryFile("wb", suffix=".der") as cert_file:
            cert_file.write(der)
            cert_file.flush()
            pubkey = subprocess.check_output(
                ["openssl", "x509", "-inform", "DER", "-in", cert_file.name, "-pubkey", "-noout"]
            )
            subject = (
                subprocess.check_output(
                    ["openssl", "x509", "-inform", "DER", "-in", cert_file.name, "-subject", "-noout"],
                    text=True,
                )
                .strip()
                .removeprefix("subject=")
            )
            enddate = (
                subprocess.check_output(
                    ["openssl", "x509", "-inform", "DER", "-in", cert_file.name, "-enddate", "-noout"],
                    text=True,
                )
                .strip()
                .removeprefix("notAfter=")
            )
        spki = subprocess.check_output(
            ["openssl", "pkey", "-pubin", "-outform", "DER"], input=pubkey
        )
        pin = base64.b64encode(hashlib.sha256(spki).digest()).decode()
        not_after: datetime | None
        try:
            # OpenSSL default: "Sep  2 23:59:59 2028 GMT"
            not_after = datetime.strptime(enddate, "%b %d %H:%M:%S %Y %Z").replace(
                tzinfo=timezone.utc
            )
        except ValueError:
            not_after = None
        chain.append((subject, pin, not_after))
    return chain


def short_label(subject: str, is_leaf: bool) -> str:
    if is_leaf:
        return "leaf"
    # Prefer CN=...
    m = re.search(r"CN=([^,/]+)", subject)
    return (m.group(1) if m else subject)[-24:]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--check-expiry",
        action="store_true",
        help=f"warn if leaf notAfter is within {EXPIRY_WARN_DAYS} days",
    )
    parser.add_argument(
        "--json",
        action="store_true",
        help="print a JSON summary object after the human-readable table",
    )
    args = parser.parse_args()

    configured = app_pins()
    network = xml_pins()
    now = datetime.now(timezone.utc)

    out = sys.stderr if args.json else sys.stdout
    print(
        f"{'Host':<12}  {'Live match':<24}  {'SPKI SHA-256 (base64)':<44}  "
        f"{'App↔Live':<8}  {'NSC⊇App':<7}  Status",
        file=out,
    )
    print(
        f"{'-'*12}  {'-'*24}  {'-'*44}  "
        f"{'-'*8}  {'-'*7}  ------",
        file=out,
    )

    ok = True
    warnings: list[str] = []
    results: list[dict] = []

    for host in HOSTS:
        try:
            chain = live_chain(host)
        except Exception as exc:  # noqa: BLE001 — report per-host and fail
            ok = False
            print(f"{host:<12}  {'ERROR':<24}  {'':<44}  {'FAIL':<8}  {'':7}  FAIL ({exc})", file=out)
            results.append({"host": host, "status": "FAIL", "error": str(exc)})
            continue

        app = configured[host]
        nsc = network[host]
        matches = [(subj, pin, na) for subj, pin, na in chain if pin in app]
        live_ok = bool(matches)
        nsc_ok = app.issubset(nsc) and bool(app)
        host_ok = live_ok and nsc_ok
        ok &= host_ok

        if matches:
            subject, pin, _na = matches[0]
            is_leaf = matches[0] == chain[0]
            label = short_label(subject, is_leaf)
        else:
            pin = chain[0][1]
            label = "NO MATCH"
            subject = chain[0][0]

        status = "PASS" if host_ok else "FAIL"
        print(
            f"{host:<12}  {label:<24}  {pin:<44}  "
            f"{'MATCH' if live_ok else 'MISS':<8}  "
            f"{'OK' if nsc_ok else 'MISS':<7}  {status}",
            file=out,
        )

        leaf_subj, leaf_pin, leaf_na = chain[0]
        expiry_warn = False
        days_left: int | None = None
        if args.check_expiry and leaf_na is not None:
            days_left = (leaf_na - now).days
            if days_left < EXPIRY_WARN_DAYS:
                expiry_warn = True
                msg = (
                    f"{host}: leaf notAfter in {days_left}d "
                    f"({leaf_na.date().isoformat()} UTC) — informational for intermediate pins; "
                    f"urgent only if this leaf is the sole remaining pin"
                )
                warnings.append(msg)
                print(f"  WARN  {msg}", file=sys.stderr)

        # Also warn if a matched intermediate itself is near expiry
        if args.check_expiry and matches:
            for m_subj, m_pin, m_na in matches:
                if m_na is None:
                    continue
                m_days = (m_na - now).days
                if m_days < EXPIRY_WARN_DAYS:
                    msg = (
                        f"{host}: matched pin cert ({short_label(m_subj, False)}) "
                        f"expires in {m_days}d — ship a new APK with updated intermediate pins"
                    )
                    warnings.append(msg)
                    print(f"  WARN  {msg}", file=sys.stderr)

        results.append(
            {
                "host": host,
                "status": status,
                "live_match": live_ok,
                "nsc_superset": nsc_ok,
                "matched_label": label,
                "matched_pin": pin if live_ok else None,
                "app_pins": sorted(app),
                "nsc_pins": sorted(nsc),
                "live_pins": [p for _, p, _ in chain],
                "leaf_pin": leaf_pin,
                "leaf_not_after": leaf_na.isoformat() if leaf_na else None,
                "leaf_days_left": days_left,
                "expiry_warn": expiry_warn,
            }
        )

    summary = {
        "ok": ok,
        "hosts": results,
        "warnings": warnings,
    }
    if args.json:
        print(json.dumps(summary, indent=2))
    else:
        if not ok:
            print(
                "\nFAIL: each host needs (1) ≥1 live chain cert in AppModule pins and "
                "(2) NSC pin-set ⊇ that host's AppModule pins.",
                file=sys.stderr,
            )
        else:
            print("\nAll hosts PASS (live ∩ AppModule nonempty; NSC ⊇ AppModule).")

    if not ok:
        if args.json:
            print(
                "FAIL: live chain / AppModule / NSC pin mismatch — see JSON above.",
                file=sys.stderr,
            )
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
