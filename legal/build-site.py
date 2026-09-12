#!/usr/bin/env python3
"""
Renders the legal Markdown into the static site served by GitHub Pages.

    python legal/build-site.py

Output goes to docs/ (the folder Pages serves from). Re-run after editing any .md here, then
commit both. No dependencies beyond the standard library, on purpose.
"""
import html
import io
import os
import re

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
OUT = os.path.join(ROOT, "docs")

CSS = """
:root{color-scheme:light dark;--bg:#0b1420;--fg:#e8f0fa;--dim:#9db0c6;--accent:#5fd7ff;--line:#243447}
@media (prefers-color-scheme: light){:root{--bg:#f6f9fc;--fg:#0e1a26;--dim:#4a5d72;--accent:#0b7fa8;--line:#d5dee8}}
body{margin:0;background:var(--bg);color:var(--fg);font:16px/1.6 system-ui,-apple-system,Segoe UI,Roboto,sans-serif}
main{max-width:760px;margin:0 auto;padding:40px 20px 80px}
h1{font-size:2rem;margin:0 0 .25rem}h2{margin-top:2rem;border-bottom:1px solid var(--line);padding-bottom:.3rem}
.meta{color:var(--dim);margin-bottom:2rem}a{color:var(--accent)}
table{border-collapse:collapse;width:100%;font-size:.92rem}th,td{border:1px solid var(--line);padding:.5rem;text-align:left;vertical-align:top}
th{background:rgba(127,127,127,.08)}code{background:rgba(127,127,127,.15);padding:.1em .35em;border-radius:4px}
nav{color:var(--dim);font-size:.9rem;margin-bottom:1.5rem}nav a{margin-right:1rem}
blockquote{border-left:3px solid var(--line);margin:0;padding:.2rem 1rem;color:var(--dim)}
"""

NAV = ('<nav><a href="index.html">Aether Tides</a><a href="privacy.html">Privacy Policy</a>'
       '<a href="terms.html">Terms of Service</a><a href="delete-account.html">Delete Account</a></nav>')

LINK_MAP = {"privacy-policy.md": "privacy.html", "terms-of-service.md": "terms.html",
            "account-deletion.md": "delete-account.html"}


def inline(t):
    t = html.escape(t, quote=False)
    t = re.sub(r"\*\*(.+?)\*\*", r"<strong>\1</strong>", t)
    t = re.sub(r"(?<!\*)\*(?!\*)(.+?)(?<!\*)\*(?!\*)", r"<em>\1</em>", t)
    t = re.sub(r"`(.+?)`", r"<code>\1</code>", t)
    t = re.sub(r"\[(.+?)\]\((.+?)\)",
               lambda m: '<a href="%s">%s</a>' % (LINK_MAP.get(m.group(2), m.group(2)), m.group(1)), t)
    return t


def md_to_html(md):
    out, para = [], []
    in_table = in_list = False

    def flush():
        if para:
            out.append("<p>%s</p>" % inline(" ".join(para)))
            para.clear()

    for ln in md.split("\n"):
        if ln.startswith("|"):
            flush()
            if in_list:
                out.append("</ul>"); in_list = False
            cells = [c.strip() for c in ln.strip().strip("|").split("|")]
            if all(re.fullmatch(r"-{3,}", c) for c in cells):
                continue
            if not in_table:
                out.append("<table><thead><tr>%s</tr></thead><tbody>" % "".join("<th>%s</th>" % inline(c) for c in cells))
                in_table = True
            else:
                out.append("<tr>%s</tr>" % "".join("<td>%s</td>" % inline(c) for c in cells))
            continue
        if in_table:
            out.append("</tbody></table>"); in_table = False
        if ln.startswith("# "):
            flush(); out.append("<h1>%s</h1>" % inline(ln[2:])); continue
        if ln.startswith("## "):
            flush()
            if in_list:
                out.append("</ul>"); in_list = False
            out.append("<h2>%s</h2>" % inline(ln[3:])); continue
        if ln.startswith("> "):
            flush(); out.append("<blockquote>%s</blockquote>" % inline(ln[2:])); continue
        if ln.startswith("- "):
            flush()
            if not in_list:
                out.append("<ul>"); in_list = True
            out.append("<li>%s</li>" % inline(ln[2:])); continue
        if ln.strip() == "":
            flush()
            if in_list:
                out.append("</ul>"); in_list = False
            continue
        if ln.startswith("**Effective:**") or ln.startswith("**Operator:**"):
            flush(); out.append('<div class="meta">%s</div>' % inline(ln)); continue
        para.append(ln.strip())
    flush()
    if in_list:
        out.append("</ul>")
    if in_table:
        out.append("</tbody></table>")
    return "\n".join(out)


def page(title, body):
    return ('<!doctype html><html lang="en"><head><meta charset="utf-8">'
            '<meta name="viewport" content="width=device-width,initial-scale=1">'
            '<title>%s</title><style>%s</style></head><body><main>%s%s</main></body></html>'
            % (html.escape(title), CSS, NAV, body))


def read(name):
    return io.open(os.path.join(HERE, name), encoding="utf-8").read()


def write(name, content):
    os.makedirs(OUT, exist_ok=True)
    io.open(os.path.join(OUT, name), "w", encoding="utf-8").write(content)
    print("wrote docs/" + name)


INDEX = """# Aether Tides

**One sails. One drowns them.** An asymmetric two-player sailing duel for Android, by
Mythron Technologies.

- [Privacy Policy](privacy-policy.md)
- [Terms of Service](terms-of-service.md)
- [Delete your account](account-deletion.md)

Support: polarizenterprises@gmail.com
"""

if __name__ == "__main__":
    write("index.html", page("Aether Tides", md_to_html(INDEX)))
    write("privacy.html", page("Aether Tides — Privacy Policy", md_to_html(read("privacy-policy.md"))))
    write("terms.html", page("Aether Tides — Terms of Service", md_to_html(read("terms-of-service.md"))))
    write("delete-account.html", page("Aether Tides — Delete your account", md_to_html(read("account-deletion.md"))))
    # Tell Pages to serve these files as-is rather than running them through Jekyll.
    write(".nojekyll", "")
