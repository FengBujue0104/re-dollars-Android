#!/usr/bin/env python3
"""Compare live TLS chain SPKI pins with AppModule and network security config."""

from __future__ import annotations

import base64
import hashlib
import re
import socket
import ssl
import subprocess
import sys
import tempfile
from pathlib import Path
from xml.etree import ElementTree

ROOT = Path(__file__).resolve().parents[1]
APP_MODULE = ROOT / "app/src/main/java/mk/ry/redollars/di/AppModule.kt"
NETWORK_CONFIG = ROOT / "app/src/main/res/xml/network_security_config.xml"
HOSTS = ("rd.ry.mk", "up.ry.mk", "auth.ry.mk", "bgm.tv", "lain.bgm.tv")


def app_pins() -> dict[str, set[str]]:
    pins = {host: set() for host in HOSTS}
    pattern = re.compile(r'\.add\("([^\"]+)", "sha256/([^\"]+)"\)')
    for host, pin in pattern.findall(APP_MODULE.read_text()):
        if host in pins:
            pins[host].add(pin)
    return pins


def xml_pins() -> dict[str, set[str]]:
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


def live_chain(host: str) -> list[tuple[str, str]]:
    proc = subprocess.run(
        ["openssl", "s_client", "-connect", f"{host}:443", "-servername", host, "-showcerts"],
        input="\n",
        text=True,
        capture_output=True,
        check=True,
        timeout=20,
    )
    certs = re.findall(
        r"-----BEGIN CERTIFICATE-----.*?-----END CERTIFICATE-----",
        proc.stdout,
        re.DOTALL,
    )
    if not certs:
        raise RuntimeError(f"no certificates returned by {host}")

    chain = []
    for pem in certs:
        der = ssl.PEM_cert_to_DER_cert(pem)
        with tempfile.NamedTemporaryFile("wb", suffix=".der") as cert_file:
            cert_file.write(der)
            cert_file.flush()
            pubkey = subprocess.check_output(
                ["openssl", "x509", "-inform", "DER", "-in", cert_file.name, "-pubkey", "-noout"]
            )
            subject = subprocess.check_output(
                ["openssl", "x509", "-inform", "DER", "-in", cert_file.name, "-subject", "-noout"],
                text=True,
            ).strip().removeprefix("subject=")
        spki = subprocess.check_output(
            ["openssl", "pkey", "-pubin", "-outform", "DER"], input=pubkey
        )
        pin = base64.b64encode(hashlib.sha256(spki).digest()).decode()
        chain.append((subject, pin))
    return chain


def main() -> int:
    configured = app_pins()
    network = xml_pins()
    print("Host          Live matching chain cert  SPKI SHA-256 (base64)                          Config/XML")
    print("------------  ------------------------  --------------------------------------------  ----------")
    ok = True
    for host in HOSTS:
        chain = live_chain(host)
        matches = [(subject, pin) for subject, pin in chain if pin in configured[host]]
        synced = configured[host] == network[host] and len(configured[host]) == 2
        matched = bool(matches)
        ok &= synced and matched
        if matches:
            subject, pin = matches[0]
            label = "leaf" if matches[0] == chain[0] else subject[-22:]
        else:
            pin = chain[0][1]
            label = "NO MATCH"
        print(f"{host:<12}  {label:<24}  {pin:<44}  {'MATCH' if matched and synced else 'FAIL'}")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
