# Roomote website

Buildless HTML/CSS source for [roomote.crabfish.dev](https://roomote.crabfish.dev/).
There are no npm dependencies, remote fonts, analytics scripts, forms, client-side
JavaScript or application cookies. Preserve the `no-transform` response directive
and script-blocking CSP; these prevent Cloudflare's automatic analytics injection.

## Preview

From this directory:

```sh
python3 -m http.server 4173 --bind 127.0.0.1 --directory dist
```

Open `http://127.0.0.1:4173/`. The privacy, support and test-download pages are at
`/privacy/`, `/support/` and `/test/`. Stop the server when finished. Python's static
server does not apply Cloudflare's `_headers` or `_redirects` configuration.

## Deploy with Cloudflare CLI

Use your own Cloudflare account and an authorized domain:

```sh
cp cloudflare.example.json cloudflare.json
# Edit cloudflare.json with your account, Worker name and domain.
python3 prepare-deploy.py
cf deploy --prebuilt
```

The local `cloudflare.json` and generated `.cloudflare/` directory are ignored.
The packager writes Cloudflare CLI's prebuilt output format. Keep CLI credentials
outside this checkout. Before deploying a fork, replace publisher/contact details,
canonical and social URLs, sitemap URLs and policy text with accurate information
for your distribution.

`verify-deploy.py` is the maintainer's HTTP verifier. Set its `base` URL for your
own deployment, provide the assets referenced by its route list, and run it with
the version ID returned by the CLI:

```sh
python3 verify-deploy.py --version VERSION_ID_FROM_DEPLOY
```

It checks HTTPS responses, source hashes, security headers, the privacy alias,
custom 404, download headers, and the absence of scripts/cookies. Generated
`evidence/` is ignored.

## Source map

- `dist/index.html`: landing page and compatibility overview.
- `dist/privacy/index.html`: privacy information for the app, website and support.
- `dist/support/index.html`: setup and troubleshooting.
- `dist/test/index.html`: direct APK testing instructions.
- `dist/styles.css`: shared responsive styles.
- `dist/assets/`: original branding, social artwork and an app screenshot.
- `dist/sitemap.xml`, `robots.txt`: search indexing configuration.
- `dist/404.html`, `_headers`, `_redirects`: errors, headers and URL aliases.

The social PNG is 1200 × 630 pixels. The test page is marked `noindex` and omitted
from the sitemap. The app is free with no ads or purchases; no `app-ads.txt` is
needed for that configuration.

## Direct APK testing

The maintained download is at [roomote.crabfish.dev/test/](https://roomote.crabfish.dev/test/).
Generated APKs are distributed separately and are excluded from Git. To preview
or deploy the download locally, place the exact tested APK at the path linked by
`dist/test/index.html` and covered by `_headers`, currently
`dist/downloads/roomote-1.3.0.apk`. Do not overwrite a versioned URL with a different
binary. Verify the public download against the tested APK's SHA-256.

A manually signed APK can have a different certificate from a Google Play build.
Moving between them requires uninstalling and pairing TVs again. Keep that
notice on the download page.

## Store availability

The landing page currently says **Google Play release in preparation**. Only
replace that text and add a Play install action after verifying a public listing.
An uploaded bundle or submitted review is not evidence that the app is available.

The privacy policy reflects the present implementation. Recheck it whenever
SDKs, permissions, data flows, support handling or hosting behavior change.
