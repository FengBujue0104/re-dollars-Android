# CI workflows

Installed under `.github/workflows/`:

- `verify-pins.yml` — PR + push to `main`
- `pin-watch.yml` — weekly Mon 01:00 UTC; opens/updates Issue labeled `certificate-pins` on failure
- `release.yml` — runs `tools/verify-certificate-pins.py` before `assembleRelease`

Sources in this folder are kept as copies for reference. Edit `.github/workflows/` for live CI.
