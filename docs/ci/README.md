# CI workflows (pending install)

These YAML files could not be pushed to `.github/workflows/` because the GitHub
OAuth token used for `git push` lacks the `workflow` scope.

## Install (one-time)

```bash
gh auth refresh -h github.com -s workflow,repo,read:org,gist
cp docs/ci/verify-pins.yml .github/workflows/verify-pins.yml
cp docs/ci/pin-watch.yml .github/workflows/pin-watch.yml
patch -p1 < docs/ci/release.yml.pin-gate.patch   # or re-apply the pin gate step manually
git add .github/workflows/
git commit -m "ci: add certificate pin verify + watch workflows"
git push origin main
```

- `verify-pins.yml` — PR + push to main
- `pin-watch.yml` — weekly Mon 01:00 UTC; opens `certificate-pins` issue on failure
- `release.yml.pin-gate.patch` — runs verifier before `assembleRelease`
