# Certificate pinning

This app pins TLS certificates with **OkHttp `CertificatePinner`** (`AppModule.kt`) and Android
**Network Security Config** (`network_security_config.xml`). NSC uses one shared pin-set that is a
**superset** of every host’s OkHttp pins — that is intentional and correct.

## Strategy (third-party client)

We do **not** operate `rd.ry.mk`, `up.ry.mk`, or `auth.ry.mk`, and we get **no advance notice** of
Let’s Encrypt leaf rotations. Therefore:

| Hosts | What we pin | Why |
| --- | --- | --- |
| `rd.ry.mk`, `up.ry.mk`, `auth.ry.mk` | **YE1 + YE2 intermediates only** | Survives routine leaf rotation; LE randomly picks YE1 or YE2 |
| `bgm.tv`, `lain.bgm.tv` | Current leaf + YE1 + YE2 | Leaf still matches today; intermediates cover LE intermediate choice |

**Never** pin Let’s Encrypt **leaf** SPKIs as the required match for hosts you do not control.
Leaves rotate every ~60–90 days. OkHttp accepts a connection if **any** certificate in the
presented chain matches a configured pin, so intermediate pins are enough.

Official intermediates (verify pins from these PEMs before changing code):

- [YE1](https://letsencrypt.org/certs/gen-y/int-ye1.pem)
- [YE2](https://letsencrypt.org/certs/gen-y/int-ye2.pem)

## When to dual-pin

Dual-pin means shipping an app build that temporarily includes **both** the old and the new
**intermediate** SPKI while a known Let’s Encrypt intermediate migration is underway (rare).

That dual-pin window is something **this fork** controls when cutting an APK — it is **not**
coordination with `*.ry.mk` server operators (we cannot change their ACME config or ask them to
wait).

Typical intermediate migration:

1. Detect new intermediate via `tools/verify-certificate-pins.py` / pin-watch CI.
2. Add the new intermediate pin alongside the old one; ship an APK ASAP.
3. After enough users update (or old intermediate is fully retired), remove the obsolete pin in a
   later release.

## Verifying pins locally

```bash
python3 tools/verify-certificate-pins.py
python3 tools/verify-certificate-pins.py --check-expiry
python3 tools/verify-certificate-pins.py --json
```

Pass criteria per host:

1. Live chain matches **at least one** AppModule pin.
2. NSC pin-set contains **all** AppModule pins for that host (extra NSC pins OK).

## CI and monitoring

| Workflow | When | What |
| --- | --- | --- |
| `verify-pins.yml` | PR + push to `main` | Fail the job if pins drift vs live chain |
| `release.yml` | Before `assembleRelease` | Same verifier — a tag cannot ship with stale pins |
| `pin-watch.yml` | Cron (Mon 01:00 UTC ≈ 09:00 HKT) | `--check-expiry`; opens/updates a `certificate-pins` issue on failure |

On pin-watch / verifier failure: update `AppModule.kt` + `network_security_config.xml`, bump
version, tag a release, and push the APK so users can update.

## How you see failures

- **PR / main push:** red “Verify certificate pins” check.
- **Release tag:** release job fails before building the APK.
- **Scheduled watch:** failed workflow run and/or GitHub Issue labeled `certificate-pins`.
