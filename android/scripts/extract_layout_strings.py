#!/usr/bin/env python3
"""One-off: move hardcoded android:text literals in res/layout into strings.xml.

- Reuses an existing string resource when its value already matches.
- Names new entries from the view id when present, else from the text.
- Keeps tools:text previews intact (only the runtime android:text is touched).
"""
import re
import sys
import html
import pathlib

ROOT = pathlib.Path(__file__).resolve().parent.parent
LAYOUT = ROOT / "app/src/main/res/layout"
STRINGS = ROOT / "app/src/main/res/values/strings.xml"

CAMEL = re.compile(r"(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])")


def snake(s: str) -> str:
    s = CAMEL.sub("_", s)
    s = re.sub(r"[^0-9a-zA-Z]+", "_", s).strip("_").lower()
    return s


def name_from_id(view_id: str) -> str:
    for p in ("tv", "btn", "et", "iv", "img", "txt"):
        if view_id.startswith(p) and len(view_id) > len(p) and view_id[len(p)].isupper():
            view_id = view_id[len(p):]
            break
    return snake(view_id)


def slug_from_text(text: str) -> str:
    words = re.findall(r"[A-Za-z0-9]+", text)
    return snake("_".join(words[:5])) or "label"


def main() -> int:
    strings_src = STRINGS.read_text()
    # value -> resource name (existing)
    value_to_name = {}
    for m in re.finditer(r'<string name="([^"]+)"[^>]*>(.*?)</string>', strings_src, re.S):
        raw = m.group(2)
        val = html.unescape(
            raw.replace("\\'", "'").replace('\\"', '"').replace("\\n", "\n")
        )
        value_to_name.setdefault(val, m.group(1))

    used_names = set(re.findall(r'<string name="([^"]+)"', strings_src))
    new_entries = []  # (name, xml_escaped_value, original)
    name_value = {}  # name -> value

    attr_re = re.compile(r'(android:text=")([^"@?][^"]*)(")')

    changed_files = 0
    replaced = 0

    for path in sorted(LAYOUT.glob("*.xml")):
        src = path.read_text()
        if not attr_re.search(src):
            continue
        out = src
        for m in list(attr_re.finditer(src)):
            raw_val = m.group(2)
            if raw_val.strip() == "":
                continue
            val = html.unescape(raw_val)
            # Reuse when the exact value already exists.
            if val in value_to_name:
                name = value_to_name[val]
            else:
                # Prefer the nearest id in the same element.
                element_start = src.rfind("<", 0, m.start())
                element = src[element_start: src.find(">", m.end()) + 1]
                idm = re.search(r'android:id="@\+id/([A-Za-z0-9_]+)"', element)
                base = name_from_id(idm.group(1)) if idm else slug_from_text(val)
                if not base or base[0].isdigit():
                    base = "label_" + base
                name = base
                i = 2
                while name in used_names and name_value.get(name) != val:
                    name = f"{base}_{i}"
                    i += 1
                used_names.add(name)
                name_value[name] = val
                value_to_name[val] = name
                esc = val.replace("&", "&amp;").replace("<", "&lt;").replace("'", "\\'").replace('"', '\\"').replace("%", "%%")
                new_entries.append((name, esc, val))
            out = out.replace(m.group(0), f'{m.group(1)}@string/{name}{m.group(3)}', 1)
            replaced += 1
        if out != src:
            path.write_text(out)
            changed_files += 1

    if new_entries:
        block = ["", "    <!-- Extracted from layouts (hardcoded android:text). -->"]
        for name, esc, _orig in new_entries:
            block.append(f'    <string name="{name}">{esc}</string>')
        block.append("</resources>")
        strings_src = strings_src.replace("</resources>", "\n".join(block))
        STRINGS.write_text(strings_src)

    print(f"layouts changed: {changed_files}")
    print(f"attrs replaced: {replaced}")
    print(f"new string resources: {len(new_entries)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
