#!/usr/bin/env python3
"""Measure a saved real-worker probe against visually transcribed source regions.

No OCR engine, rewriting, rounding, case folding, or fuzzy acceptance occurs here.
Unresolved geometry remains a failure to establish an exact transcription.
"""
import argparse
import hashlib
import json
import re
import unicodedata
from pathlib import Path


def normalize(text):
    return re.sub(r"\s+", "", unicodedata.normalize("NFC", text))


def exact_tokens(text):
    """Keep boundaries within words/codes while ignoring only layout whitespace."""
    tokens, word = [], []
    for char in unicodedata.normalize("NFC", text):
        if unicodedata.category(char)[0] in ("L", "M", "N"):
            word.append(char)
        else:
            if word:
                tokens.append("".join(word))
                word = []
            if not char.isspace():
                tokens.append(char)
    if word:
        tokens.append("".join(word))
    return tokens


def edits(reference, actual):
    """Exact Levenshtein alignment, including every edit for inspection."""
    a, b = normalize(reference), normalize(actual)
    matrix = [list(range(len(b) + 1))]
    for i, char in enumerate(a, 1):
        row = [i]
        for j, other in enumerate(b, 1):
            row.append(min(matrix[-1][j] + 1, row[-1] + 1,
                           matrix[-1][j - 1] + (char != other)))
        matrix.append(row)
    i, j = len(a), len(b)
    operations = []
    while i or j:
        if i and j and matrix[i][j] == matrix[i - 1][j - 1] + (a[i - 1] != b[j - 1]):
            if a[i - 1] != b[j - 1]:
                operations.append({"operation": "substitute", "reference_index": i - 1,
                                   "actual_index": j - 1, "expected": a[i - 1], "actual": b[j - 1]})
            i -= 1
            j -= 1
        elif i and matrix[i][j] == matrix[i - 1][j] + 1:
            operations.append({"operation": "delete", "reference_index": i - 1,
                               "actual_index": j, "expected": a[i - 1], "actual": ""})
            i -= 1
        else:
            operations.append({"operation": "insert", "reference_index": i,
                               "actual_index": j - 1, "expected": "", "actual": b[j - 1]})
            j -= 1
    operations.reverse()
    counts = {kind: sum(op["operation"] == kind for op in operations)
              for kind in ("substitute", "delete", "insert")}
    return {"reference_characters": len(a), "recognized_characters": len(b),
            "edit_distance": matrix[-1][-1], "cer": matrix[-1][-1] / len(a) if a else None,
            "counts": counts, "operations": operations}


def intersection(a, b):
    return max(0, min(a[2], b[2]) - max(a[0], b[0])) * max(0, min(a[3], b[3]) - max(a[1], b[1]))


def reading_order(fragments):
    rows = []
    for item in sorted(fragments, key=lambda x: ((x["box"][1] + x["box"][3]) / 2, x["box"][0])):
        box = item["box"]
        center = (box[1] + box[3]) / 2
        row = next((r for r in reversed(rows[-8:]) if
                    abs(center - sum((p["box"][1] + p["box"][3]) / 2 for p in r) / len(r)) <=
                    min(box[3] - box[1], min(p["box"][3] - p["box"][1] for p in r)) * .6), None)
        if row is None:
            rows.append([item])
        else:
            row.append(item)
    return "\n".join(" ".join(p["text"] for p in sorted(row, key=lambda x: x["box"][0])) for row in rows)


def score_regions(regions, fragments, geometry_available):
    assigned = {region["id"]: [] for region in regions}
    ambiguous = {region["id"]: [] for region in regions}
    unassigned = []
    for item in fragments:
        box = item["box"]
        area = (box[2] - box[0]) * (box[3] - box[1])
        overlaps = [(r, intersection(box, r["bbox_px"]) / area) for r in regions]
        strong = [r for r, overlap in overlaps if overlap >= .8]
        if len(strong) == 1:
            assigned[strong[0]["id"]].append(item)
        else:
            touched = [r for r, overlap in overlaps if overlap >= .1]
            for region in touched:
                ambiguous[region["id"]].append(item)
            unassigned.append({**item, "touches": [r["id"] for r in touched]})
    details = []
    for region in regions:
        actual = reading_order(assigned[region["id"]])
        unresolved = not geometry_available or bool(ambiguous[region["id"]])
        exact = not unresolved and exact_tokens(actual) == exact_tokens(region["text"])
        measurement = edits(region["text"], actual)
        details.append({"id": region["id"], "expected": region["text"], "actual": actual,
                        "exact": exact, "status": "unresolved" if unresolved else "exact" if exact else "mismatch",
                        "bbox_px": region["bbox_px"],
                        "expected_tokens": exact_tokens(region["text"]), "actual_tokens": exact_tokens(actual),
                        "unresolved_fragments": ambiguous[region["id"]],
                        "assigned_fragments": assigned[region["id"]],
                        "alignment": measurement})
    resolved = [r for r in details if r["status"] != "unresolved"]
    resolved_chars = sum(r["alignment"]["reference_characters"] for r in resolved)
    resolved_edits = sum(r["alignment"]["edit_distance"] for r in resolved)
    return {
        "region_count": len(regions), "exact_count": sum(r["exact"] for r in details),
        "unresolved_count": len(details) - len(resolved),
        "mismatch_count": sum(r["status"] == "mismatch" for r in details),
        "all_exact": all(r["exact"] for r in details),
        "cer_of_resolved_regions_only": resolved_edits / resolved_chars if resolved_chars else None,
        "resolved_reference_characters": resolved_chars,
        "total_reference_characters": sum(r["alignment"]["reference_characters"] for r in details),
        "whole_set_cer": None if len(resolved) != len(details) or unassigned else
                         resolved_edits / resolved_chars if resolved_chars else None,
        "cer_caution": "CER covers only spatially resolvable regions. Unresolved regions still count against exactness; they are never skipped from the denominator for exact-region counts. Partial CER is not a whole-document accuracy score.",
        "unassigned_fragments": unassigned, "regions": details,
        "discrepancies": [r["id"] for r in details if not r["exact"]],
    }


def evaluate(reference, probe):
    document = probe.get("document", probe)
    pages = document.get("pages", [])
    width, height = reference["image_width"], reference["image_height"]
    raw_lines = pages[0].get("lines", []) if len(pages) == 1 else []
    fragments = []
    invalid = []
    for index, line in enumerate(raw_lines):
        try:
            coords = [float(line[key]) for key in ("left", "top", "right", "bottom")]
            if not (all(0 <= c <= 1 for c in coords) and coords[2] > coords[0] and coords[3] > coords[1]):
                raise ValueError("invalid normalized box")
            fragments.append({"index": index, "text": line["text"],
                              "box": [coords[0] * width, coords[1] * height, coords[2] * width, coords[3] * height]})
        except (KeyError, ValueError, TypeError):
            invalid.append({"index": index, "line": line})
    aspect_ok = ("processed_width" not in probe or "processed_height" not in probe or
                 abs(probe["processed_width"] / probe["processed_height"] - width / height) < .01)
    geometry_available = bool(fragments) and not invalid and aspect_ok and len(pages) == 1
    blocks = score_regions(reference["blocks"], fragments, geometry_available)
    fields = score_regions(reference["critical_fields"], fragments, geometry_available)
    return {
        "reference_id": reference["id"], "engine_status": document.get("status"),
        "is_design_example": document.get("isDesignExample", False),
        "reference_method": reference["reference_method"],
        "human_reference_validated": reference["human_reference_validated"],
        "normalization": "Exactness uses Unicode NFC word/code tokens (consecutive letters, marks and numbers) plus each nonwhitespace punctuation/symbol token. Layout whitespace may vary but DE LA differs from DELA and AB12 differs from AB 12. CER separately removes whitespace; a zero character CER alone does not establish exactness.",
        "spatial_rule": "A fragment is assigned only if at least 80% of its box is inside exactly one reference region. Other overlaps of at least 10% mark that region unresolved. No text is sliced or inferred from a partial box.",
        "geometry_available": geometry_available, "aspect_ratio_matches": aspect_ok,
        "invalid_fragments": invalid, "blocks": blocks, "critical_fields": fields,
        "exact_document_established": (geometry_available and blocks["all_exact"] and
                                       not blocks["unassigned_fragments"] and not document.get("isDesignExample", False)),
        "generalization_limit": reference["generalization_limit"],
    }


def self_test():
    assert edits("a 1.00", "a1.00")["edit_distance"] == 0
    assert edits("SOLES", "sOLES")["counts"]["substitute"] == 1
    assert edits("12.20", "12.2")["counts"]["delete"] == 1
    assert edits("AB", "ACB")["counts"]["insert"] == 1
    assert exact_tokens("DE  LA") == exact_tokens("DE\nLA")
    assert exact_tokens("DE LA") != exact_tokens("DELA")
    assert exact_tokens("AB12") != exact_tokens("AB 12")
    assert exact_tokens("12.20") == exact_tokens("12 . 20")
    assert exact_tokens("SOLES") != exact_tokens("sOLES")
    assert exact_tokens("Emisio\u0301n") == exact_tokens("Emisión")
    regions = [{"id": "left", "bbox_px": [0, 0, 10, 10], "text": "A"},
               {"id": "right", "bbox_px": [10, 0, 20, 10], "text": "B"}]
    ambiguous = score_regions(regions, [{"text": "AB", "box": [0, 0, 20, 10]}], True)
    assert ambiguous["exact_count"] == 0 and ambiguous["unresolved_count"] == 2
    exact = score_regions(regions, [{"text": "A", "box": [0, 0, 9, 10]},
                                    {"text": "B", "box": [11, 0, 20, 10]}], True)
    assert exact["all_exact"] and exact["whole_set_cer"] == 0
    missing = score_regions(regions, [], False)
    assert missing["unresolved_count"] == 2 and missing["exact_count"] == 0
    print("Accuracy comparator self-tests passed")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--reference", type=Path)
    parser.add_argument("--probe", type=Path)
    parser.add_argument("--image", type=Path, help="Original raster; verifies its SHA-256 against the reference")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test()
        return
    if not args.reference or not args.probe:
        parser.error("--reference and --probe are required")
    reference = json.loads(args.reference.read_text())
    probe = json.loads(args.probe.read_text())
    if args.image and hashlib.sha256(args.image.read_bytes()).hexdigest() != reference["image_sha256"]:
        parser.error("Input image does not match the ground-truth raster SHA-256")
    result = evaluate(reference, probe)
    result["input_sha256_verified"] = bool(args.image)
    output = json.dumps(result, ensure_ascii=False, indent=2) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(output)
        print(f"Exact blocks: {result['blocks']['exact_count']}/{result['blocks']['region_count']}; "
              f"unresolved: {result['blocks']['unresolved_count']}")
        print(f"Exact critical fields: {result['critical_fields']['exact_count']}/{result['critical_fields']['region_count']}; "
              f"unresolved: {result['critical_fields']['unresolved_count']}")
        print(f"Exact document established: {result['exact_document_established']}")
    else:
        print(output, end="")


if __name__ == "__main__":
    main()
