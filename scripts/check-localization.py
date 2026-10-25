#!/usr/bin/env python3
"""Offline resource coverage/format audit. Completeness is not native-speaker quality approval."""
import argparse
import collections
import hashlib
import json
from pathlib import Path
import re
import zipfile

ROOT = Path(__file__).resolve().parents[1]
SLOT = re.compile(r"%(?:(\d+)\$)?([sdf])|%%")


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("Duplicate JSON key: " + key)
        result[key] = value
    return result


def decode(raw):
    return json.loads(raw.decode("utf-8"), object_pairs_hook=unique_object)


def slots(value):
    result, implicit, end = collections.Counter(), 1, 0
    for match in SLOT.finditer(value):
        if "%" in value[end:match.start()]:
            raise ValueError("Malformed percent instruction: " + value)
        if match[0] == "%%":
            result["literal-percent"] += 1
        else:
            index = int(match[1]) if match[1] else implicit
            if index < 1:
                raise ValueError("Nonpositive parameter index: " + value)
            if not match[1]:
                implicit += 1
            result[str(index) + match[2]] += 1
        end = match.end()
    if "%" in value[end:]:
        raise ValueError("Trailing malformed percent instruction: " + value)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    prefix = "assets/millenairekingdoms/"
    archive = zipfile.ZipFile(args.jar) if args.jar else None

    def resource(name):
        return archive.read(prefix + name) if archive else (ROOT / "src/main/resources" / prefix / name).read_bytes()

    manifest = decode(resource("locale-manifest.json"))
    locales = manifest["locales"]
    catalogs = {locale: decode(resource("lang/" + locale + ".json")) for locale in locales}
    english = catalogs["en_us"]
    for locale, catalog in catalogs.items():
        if catalog.keys() != english.keys():
            raise ValueError(f"{locale}: missing={sorted(english.keys() - catalog.keys())}, extra={sorted(catalog.keys() - english.keys())}")
        for key, source in english.items():
            target = catalog[key]
            if not isinstance(target, str) or not target.strip() or re.search(r"ZX\s*\d+\s*ZX", target, re.I):
                raise ValueError(f"{locale}/{key}: empty/non-string/unrestored protected token")
            if slots(source) != slots(target):
                raise ValueError(f"{locale}/{key}: semantic parameter identity/type/multiplicity differs")

    original = ROOT / "millenaire-9.0.3.jar"
    if original.exists():
        with zipfile.ZipFile(original) as native:
            codes = sorted(re.fullmatch(r"assets/millenaire/lang/([^/]+)\.json", name)[1]
                           for name in native.namelist() if re.fullmatch(r"assets/millenaire/lang/([^/]+)\.json", name))
        if sorted(locales) != codes or len(codes) != 26:
            raise ValueError("Locale manifest differs from the original9.0.3 asset locale list")

    baseline = ROOT / "releases/1.2.0-rc8-alpha.dev8/millenairekingdoms-1.2.0-rc8-alpha.dev8.jar"
    if not baseline.exists():
        raise ValueError("Preserved EN/TR dev8 resource baseline is required for this repository audit")
    with zipfile.ZipFile(baseline) as old:
        for locale in ("en_us", "tr_tr"):
            legacy = decode(old.read(prefix + "lang/" + locale + ".json"))
            if any(catalogs[locale].get(key) != value for key, value in legacy.items()):
                raise ValueError(locale + ": released459 GUI/narrative values changed")

    public = decode(resource("public-text-sources.json"))
    for message, key in public["errors"].items():
        expected = "millenairekingdoms.text." + hashlib.sha256(message.encode()).hexdigest()[:16]
        if expected != key or key not in english:
            raise ValueError("Uncatalogued public diagnostic: " + message)
    for template in public["patterns"]:
        if "millenairekingdoms.text." + hashlib.sha256(template.encode()).hexdigest()[:16] not in english:
            raise ValueError("Uncatalogued dynamic public diagnostic: " + template)

    # The English baseline must also cover literal PublicText call sites, not just match its own target key sets.
    source_templates = set()
    for source_file in (ROOT / "src/main/java").rglob("*.java"):
        for match in re.finditer(r'PublicText\.of\(\s*("(?:\\.|[^"\\])*")', source_file.read_text()):
            template = json.loads(match[1])
            source_templates.add(template)
            key = "millenairekingdoms.text." + hashlib.sha256(template.encode()).hexdigest()[:16]
            if english.get(key) != template:
                raise ValueError(f"Uncatalogued/changed literal public template in {source_file}: {template}")

    # This list is a review queue, NOT proof that identical strings are wrong or linguistically approved.
    identical = {locale: [key for key in english if catalogs[locale][key] == english[key]]
                 for locale in locales if locale != "en_us"}
    long_unchanged = {locale: [key for key in keys if len(re.findall(r"[A-Za-z]+", SLOT.sub("", english[key]))) >= 5]
                      for locale, keys in identical.items()}
    result = {
        "artifact": str(args.jar) if args.jar else "source resources",
        "artifact_sha256": hashlib.sha256(args.jar.read_bytes()).hexdigest() if args.jar else None,
        "locales": locales, "locale_count": len(locales), "keys_per_locale": len(english),
        "checked_values": len(english) * len(locales), "missing_keys": 0, "malformed_formats": 0,
        "legacy_en_tr_values_preserved_per_locale": 459,
        "narrative_keys_per_locale": sum(key.startswith("millenairekingdoms.chronicle.") for key in english),
        "public_literal_errors": len(public["errors"]), "public_dynamic_patterns": len(public["patterns"]),
        "source_public_templates_checked": len(source_templates),
        "quality": "New translations are offline machine-assisted drafts; coverage/format checks are not linguistic approval.",
        "native_speaker_approval": False, "identical_to_english_review_queue": identical,
        "long_unchanged_english_review_queue": long_unchanged,
        "actual_graphical_rtl_long_text_fonts_input_scales": "manual/unverified",
    }
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps({key: value for key, value in result.items() if not key.endswith("review_queue")}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
