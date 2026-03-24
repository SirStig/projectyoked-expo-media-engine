# Documentation layout

- **`docs/`** — Static HTML for GitHub Pages: home page, version switcher, [stable](docs/stable/) (`0.1.x`), [alpha](docs/alpha/) (`1.0.0-alpha.x`), and [changelog](docs/changelog.html) (renders `CHANGELOG.md`).
- **`README.md`** — Short entry point; defers detail to the hosted docs.

The **Deploy documentation** workflow copies the repository root `CHANGELOG.md` to `docs/changelog.md` before upload. That file is listed in `.gitignore` so it is not committed; the changelog page loads it in the browser via `fetch`.

**Local preview:** from the repo root, run `cp CHANGELOG.md docs/changelog.md`, then serve `docs/` with any static server (otherwise the changelog page shows a short error with a link to GitHub).

## Publishing the site (maintainers)

1. In the GitHub repository: **Settings → Pages → Build and deployment**.
2. Under **Source**, choose **GitHub Actions** (not “Deploy from a branch”).
3. Push to `main` (or run the **Deploy documentation (GitHub Pages)** workflow manually). The workflow copies `CHANGELOG.md` into `docs/`, then uploads the `docs/` directory with `actions/upload-pages-artifact@v3` and deploys with `actions/deploy-pages@v4`.

First deployment may require approving the `github-pages` environment once.

## URLs and SEO

Canonical URLs and `sitemap.xml` assume the repository is served at:

`https://sirstig.github.io/projectyoked-expo-media-engine/`

If the GitHub owner or repository name changes, update:

- `docs/index.html`, `docs/stable/index.html`, `docs/alpha/index.html`, `docs/changelog.html` — `<link rel="canonical">`
- `docs/sitemap.xml`
- `docs/robots.txt`
- `package.json` — `homepage`
