# Contributing

Thanks for contributing!

## License of contributions (important)
By submitting a contribution (pull request, patch, etc.), you agree that:

1) Your contribution is licensed under **AGPL-3.0-or-later**; AND  
2) You grant the project maintainer (denov) a perpetual, worldwide, royalty-free
   right to use, modify, sublicense, and relicense your contribution under
   different terms, including a commercial license.

If you do not agree, please do not submit a contribution.

## Developer Certificate of Origin
You certify that you have the right to submit the work under the terms above.

## Development

### The integration

Use **Python 3.14**, as CI does.

```sh
uv venv --python 3.14 .venv && source .venv/bin/activate   # or: python3.14 -m venv .venv
pip install -r requirements_test.txt ruff==0.14.0
ruff check .            # lint, as CI runs it
python -m pytest        # tests
```

### The app

JDK 17 and the Android SDK (platform 34, build-tools 34).

```sh
./gradlew testReleaseUnitTest assembleRelease
```

The chip logic is plain Java (`Ha.java`, `Solar.java`, `Apex.java`, `Cameras.java`) with JVM tests next to it in
`app/src/test`. Pages are built in code, without XML layouts, and have to fit a 1080p screen without scrolling: check
a screenshot from a real TV after changing one.

Keep personal data out: no tokens, IP addresses, serial numbers or entity IDs from your own house. Use placeholders
such as `<tv ip>` or `sensor.apex_temp`.
