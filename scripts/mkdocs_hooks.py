"""MkDocs hooks for the documentation site (mkdocs.yml).

The guides of docs/ are written for GitHub, where `../README.md` or `../examples/` resolve. On the
site those targets do not exist: every relative link to a file the site does not publish is
rewritten to its page on GitHub, and the "Back to the README" line, useless on the site, is dropped.
"""
import posixpath
import re

REPO = "https://github.com/IBRAHIMDANS/i18nSupportPlus/blob/main/"
# A blob page is HTML: an image left on GitHub is shown from its raw file.
RAW = "https://raw.githubusercontent.com/IBRAHIMDANS/i18nSupportPlus/main/"
IMAGE = re.compile(r"\.(gif|png|jpe?g|svg|webp)$", re.IGNORECASE)
BACK_TO_README = re.compile(r"^\[← Back to the README\]\(\.\./README\.md\)[ \t]*\n", re.MULTILINE)
# ](target) and src="target", leaving anchors, absolute URLs and mailto: alone.
LINK = re.compile(r'(\]\(|src=")(?!#|[a-zA-Z][a-zA-Z0-9+.-]*:)([^)"\s]+)')


def on_page_markdown(markdown, page, config, files):
    page_dir = posixpath.dirname(page.file.src_uri)

    def rewrite(match):
        prefix, target = match.groups()
        path, sep, anchor = target.partition("#")
        if not path:
            return match.group(0)
        in_docs = posixpath.normpath(posixpath.join(page_dir, path))
        published = files.get_file_from_path(in_docs)
        if not in_docs.startswith("../") and published is not None and published.inclusion.is_included():
            return match.group(0)
        in_repo = posixpath.normpath(posixpath.join("docs", in_docs))
        base = RAW if IMAGE.search(in_repo) else REPO
        return f"{prefix}{base}{in_repo}{sep}{anchor}"

    return LINK.sub(rewrite, BACK_TO_README.sub("", markdown))
