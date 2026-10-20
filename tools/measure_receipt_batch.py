#!/usr/bin/env python3
"""Evaluate saved receipt batches against independent visual references.

This tool only reads offline evidence and writes an evaluation report. It never
calls the Android app, an OCR engine, or a PDF generator, and never changes text.
Unreferenced, ambiguous, missing, and mismatching samples remain in the complete
batch denominator. Character CER alone is not a test of document exactness.
"""

import argparse
from collections import Counter
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import tempfile
import unicodedata


NUMBERS = re.compile(r"[+\-−]?(?:\d+(?:[.,:/\-]\d+)*|[.,]\d+)")


def nfc(text):
    return unicodedata.normalize("NFC", text)


def strict_tokens(text):
    """Retain word/code boundaries and every non-whitespace symbol, in order."""
    result, current = [], []
    for char in nfc(text):
        if unicodedata.category(char)[0] in ("L", "M", "N"):
            current.append(char)
        else:
            if current:
                result.append("".join(current))
                current = []
            if not char.isspace():
                result.append(char)
    if current:
        result.append("".join(current))
    return result


def characters(text):
    return "".join(char for char in nfc(text) if not char.isspace())


def numeric_tokens(text):
    # Keep literal strings, leading zeros, decimal marks, dates, times and signs.
    # No numeric conversion, rounding, O/0 substitution or punctuation repair.
    return NUMBERS.findall(nfc(text))


def alignment(reference, actual):
    """Exact Levenshtein distance and an inspectable deterministic edit list."""
    matrix = [list(range(len(actual) + 1))]
    for i, expected in enumerate(reference, 1):
        row = [i]
        for j, observed in enumerate(actual, 1):
            row.append(min(matrix[-1][j] + 1, row[-1] + 1,
                           matrix[-1][j - 1] + (expected != observed)))
        matrix.append(row)
    i, j = len(reference), len(actual)
    operations = []
    while i or j:
        if i and j and matrix[i][j] == matrix[i - 1][j - 1] + (reference[i - 1] != actual[j - 1]):
            if reference[i - 1] != actual[j - 1]:
                operations.append({"operation": "substitute", "reference_index": i - 1,
                                   "actual_index": j - 1, "expected": reference[i - 1],
                                   "actual": actual[j - 1]})
            i -= 1
            j -= 1
        elif i and matrix[i][j] == matrix[i - 1][j] + 1:
            operations.append({"operation": "delete", "reference_index": i - 1,
                               "actual_index": j, "expected": reference[i - 1], "actual": None})
            i -= 1
        else:
            operations.append({"operation": "insert", "reference_index": i,
                               "actual_index": j - 1, "expected": None, "actual": actual[j - 1]})
            j -= 1
    operations.reverse()
    return {
        "reference_count": len(reference), "actual_count": len(actual),
        "edit_distance": matrix[-1][-1],
        "error_rate": matrix[-1][-1] / len(reference) if reference else None,
        "counts": {kind: sum(op["operation"] == kind for op in operations)
                   for kind in ("substitute", "delete", "insert")},
        "operations": operations,
    }


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def ambiguity_records(reference):
    result = []
    for item in reference.get("ambiguities", []):
        result.append({"line": None, "detail": item})
    for region in reference.get("untranscribable_regions", []):
        result.append({"line": None, "detail": region})
    for line in reference.get("lines", []):
        for item in line.get("ambiguities", []):
            result.append({"line": line.get("line"), "detail": item})
    return result


def evaluate_sample(filename, reference_path, directory):
    report = {"filename": filename, "exact_document_established": False,
              "pdf_from_exact_transcription": False, "evaluation_status": "non_evaluable",
              "non_evaluable_reasons": []}
    document_path = directory / "document.json"
    outcome_path = directory / "outcome.json"
    document = json.loads(document_path.read_text()) if document_path.is_file() else None
    outcome = json.loads(outcome_path.read_text()) if outcome_path.is_file() else {}
    report["document_path"] = str(document_path.resolve()) if document else None
    report["engine_status"] = document.get("status") if document else None
    report["pdf_generated"] = (directory / "result.pdf").is_file()
    report["reference_path"] = str(reference_path.resolve()) if reference_path else None
    report["reference_available"] = reference_path is not None
    report["reference_ambiguity_count"] = 0
    if not reference_path:
        report["non_evaluable_reasons"].append("No independent visual reference exists for this sample.")
        return report

    reference = json.loads(reference_path.read_text())
    ambiguities = ambiguity_records(reference)
    report["reference_sha256"] = sha256(reference_path)
    report["reference_method"] = reference.get("method")
    report["reference_notes"] = reference.get("notes", [])
    report["reference_ambiguities"] = ambiguities
    report["reference_ambiguity_count"] = len(ambiguities)
    if ambiguities:
        report["non_evaluable_reasons"].append("Reference contains unresolved character or reading ambiguities.")
    lines = reference.get("lines", [])
    reference_text = "\n".join(line["text"] for line in lines)
    if not reference_text.strip():
        report["non_evaluable_reasons"].append("Reference has no transcribed text.")
    if not document:
        report["non_evaluable_reasons"].append("Saved production document is missing.")
        return report
    pages = document.get("pages", [])
    if len(pages) != 1:
        report["non_evaluable_reasons"].append("Camera-photo reference requires exactly one saved OCR page.")
    if document.get("isDesignExample", False):
        report["non_evaluable_reasons"].append("Design example is not a camera-photo OCR result.")
    source = directory / ("source" + Path(filename).suffix)
    expected_hash = reference.get("image_sha256")
    actual_hash = sha256(source) if source.is_file() else None
    imported_hash = outcome.get("imported_sha256")
    report["source_integrity"] = {
        "reference_image_sha256": expected_hash,
        "evidence_image_sha256": actual_hash,
        "repository_imported_sha256": imported_hash,
        "all_identical": bool(expected_hash) and expected_hash == actual_hash == imported_hash,
    }
    if not report["source_integrity"]["all_identical"]:
        report["non_evaluable_reasons"].append("Original/reference/imported image identity is not verified.")

    actual_text = "\n\n".join(page.get("text", "") for page in pages)
    expected_tokens, actual_tokens = strict_tokens(reference_text), strict_tokens(actual_text)
    expected_numbers, actual_numbers = numeric_tokens(reference_text), numeric_tokens(actual_text)
    character_result = alignment(characters(reference_text), characters(actual_text))
    token_result = alignment(expected_tokens, actual_tokens)
    number_result = alignment(expected_numbers, actual_numbers)
    report.update({
        "reference_text": reference_text, "recognized_text": actual_text,
        "strict_tokens": {"expected": expected_tokens, "actual": actual_tokens,
                          "ordered_match": expected_tokens == actual_tokens, **token_result},
        "characters_without_whitespace": {
            "ordered_match": characters(reference_text) == characters(actual_text),
            "cer": character_result["error_rate"], **character_result},
        "numeric_tokens": {"expected": expected_numbers, "actual": actual_numbers,
                           "ordered_match": expected_numbers == actual_numbers, **number_result},
    })
    # An unresolved reference never establishes exactness, even if the chosen
    # textual guess happens to agree with OCR. It still counts in the denominator.
    evaluable = not report["non_evaluable_reasons"]
    exact = evaluable and expected_tokens == actual_tokens and expected_numbers == actual_numbers
    report["evaluation_status"] = "exact" if exact else "inexact" if evaluable else "non_evaluable"
    report["exact_document_established"] = exact
    report["pdf_from_exact_transcription"] = exact and report["pdf_generated"] and document.get("status") == "ready"
    return report


def evaluate_batch(evidence, references):
    manifest = json.loads((evidence / "sources.json").read_text())
    summary_path = evidence / "summary.json"
    summary = json.loads(summary_path.read_text()) if summary_path.is_file() else {}
    reference_paths = {}
    for path in sorted(references.glob("*.reference.json")):
        reference = json.loads(path.read_text())
        filename = Path(reference["image"]).name
        if filename in reference_paths:
            raise ValueError(f"Duplicate reference for {filename}")
        reference_paths[filename] = path
    filenames = [item["filename"] for item in manifest["samples"]]
    if len(set(filenames)) != len(filenames):
        raise ValueError("The batch manifest has duplicate filenames.")
    results = [evaluate_sample(filename, reference_paths.get(filename), evidence / Path(filename).stem)
               for filename in filenames]
    counts = Counter(item["evaluation_status"] for item in results)
    measured = [item for item in results if "strict_tokens" in item]
    evaluable = [item for item in measured if not item["non_evaluable_reasons"]]
    chars = sum(item["characters_without_whitespace"]["reference_count"] for item in evaluable)
    edits = sum(item["characters_without_whitespace"]["edit_distance"] for item in evaluable)
    token_count = sum(item["strict_tokens"]["reference_count"] for item in evaluable)
    token_edits = sum(item["strict_tokens"]["edit_distance"] for item in evaluable)
    total = len(results)
    return {
        "schema_version": 1, "created_at": datetime.now(timezone.utc).isoformat(),
        "run_label": summary.get("run_label", evidence.name),
        "application_version": summary.get("application_version"),
        "evidence_directory": str(evidence.resolve()), "references_directory": str(references.resolve()),
        "evaluation_only": True, "references_supplied_to_production": False,
        "normalization": {
            "unicode": "NFC only; no case folding or O/0, I/l/1, accent, punctuation or amount substitutions.",
            "tokens": "Ordered runs of Unicode letters/marks/numbers and individual nonwhitespace symbols. Layout whitespace can vary; word/code boundaries cannot.",
            "cer": "Additional Levenshtein character error rate after removing whitespace only. CER=0 does not establish exactness when word boundaries differ.",
            "numbers": "Ordered literal numeric strings including leading zeros, signs, decimal punctuation, dates and times; no rounding or correction.",
            "reading_order": "The reference line order and the saved production page text order are compared directly. No reordering from geometry or best-match line assignment.",
        },
        "counts": {
            "batch_denominator": total,
            "with_reference": sum(item["reference_available"] for item in results),
            "without_reference": sum(not item["reference_available"] for item in results),
            "with_ambiguous_reference": sum(item["reference_ambiguity_count"] > 0 for item in results),
            "reference_ambiguity_count": sum(item["reference_ambiguity_count"] for item in results),
            "exact": counts["exact"], "inexact": counts["inexact"],
            "non_evaluable": counts["non_evaluable"],
            "exact_document_fraction_of_full_batch": counts["exact"] / total if total else None,
            "pdfs_from_exact_transcriptions": sum(item["pdf_from_exact_transcription"] for item in results),
            "pdfs_generated_by_production": sum(item["pdf_generated"] for item in results),
        },
        "partial_reference_metrics": {
            "evaluable_referenced_documents": len(evaluable),
            "strict_ordered_token_matches": sum(item["strict_tokens"]["ordered_match"] for item in evaluable),
            "ordered_numeric_token_matches": sum(item["numeric_tokens"]["ordered_match"] for item in evaluable),
            "reference_characters_without_whitespace": chars,
            "character_edit_count": edits,
            "micro_cer_without_whitespace": edits / chars if chars else None,
            "reference_token_count": token_count,
            "token_edit_count": token_edits,
            "micro_token_error_rate": token_edits / token_count if token_count else None,
            "limitation": "These partial metrics cover only references with resolved text and verified image identity. They are not a whole-batch accuracy estimate; every other document remains non-evaluable in the full denominator.",
        },
        "batch_cer": edits / chars if chars and len(evaluable) == total else None,
        "pdf_scope": "This comparator scores saved OCR text, not PDF text extraction or rendering. A PDF from an exact OCR result still requires independent PDF verification.",
        "selection_limit": "References were selected for visual readability, not random sampling. No general accuracy or perfect-transcription claim follows from this batch.",
        "samples": results,
    }


def brief_report(report):
    counts = report["counts"]
    lines = [f"Comparación {report['run_label']}: {counts['batch_denominator']} fotos en el denominador.",
             f"Exactas: {counts['exact']}; inexactas: {counts['inexact']}; no evaluables: {counts['non_evaluable']}.",
             f"Con referencia: {counts['with_reference']}; sin referencia: {counts['without_reference']}; referencias ambiguas: {counts['with_ambiguous_reference']}.",
             f"PDF generados: {counts['pdfs_generated_by_production']}; PDF procedentes de OCR exacto: {counts['pdfs_from_exact_transcriptions']}."]
    for item in report["samples"]:
        if "strict_tokens" in item:
            lines.append(f"{item['filename']}: {item['evaluation_status']}; "
                         f"ediciones tokens={item['strict_tokens']['edit_distance']}; "
                         f"CER sin blancos={item['characters_without_whitespace']['cer']:.4%}; "
                         f"cifras ordenadas={'coinciden' if item['numeric_tokens']['ordered_match'] else 'difieren'}.")
    lines.append(f"CER parcial no representa exactitud de las {counts['batch_denominator']} fotos; ninguna muestra sin referencia o ambigua se excluye del denominador de documentos exactos.")
    lines.append("Se compara el OCR guardado. El contenido y la presentación del PDF requieren una revisión independiente.")
    return "\n".join(lines) + "\n"


def self_test():
    assert strict_tokens("DE LA") != strict_tokens("DELA")
    assert characters("DE LA") == characters("DELA")
    assert strict_tokens("AB12") != strict_tokens("AB 12")
    assert strict_tokens("Total") != strict_tokens("TOTAL")
    assert strict_tokens("CO0") != strict_tokens("C00")
    assert strict_tokens("Emisio\u0301n") == strict_tokens("Emisión")
    assert strict_tokens("A\tB") == strict_tokens("A\n  B")
    assert numeric_tokens("01 .17 12.20 10:30 04/25/15 -2.00") == ["01", ".17", "12.20", "10:30", "04/25/15", "-2.00"]
    assert numeric_tokens(".17") != numeric_tokens("17")
    assert numeric_tokens("10 20") != numeric_tokens("20 10")
    assert alignment(["a", "b"], ["b", "a"])["edit_distance"] == 2
    assert alignment("AB", "ACB")["counts"]["insert"] == 1
    assert alignment("AB", "A")["counts"]["delete"] == 1
    assert ambiguity_records({"lines": [{"line": 1, "ambiguities": ["O or 0"]}]}) == [
        {"line": 1, "detail": "O or 0"}]
    # A batch with one exact reference and one unreferenced sample is 1/2,
    # never 1/1. Ambiguity and altered originals cannot establish exactness.
    with tempfile.TemporaryDirectory() as temporary:
        root = Path(temporary)
        evidence, references = root / "evidence", root / "references"
        sample = evidence / "receipt"
        sample.mkdir(parents=True)
        references.mkdir()
        (sample / "source.jpg").write_bytes(b"unchanged source bytes")
        source_hash = sha256(sample / "source.jpg")
        (evidence / "sources.json").write_text(json.dumps({"samples": [
            {"filename": "receipt.jpg"}, {"filename": "unreferenced.jpg"}]}))
        (sample / "document.json").write_text(json.dumps({
            "status": "needs_recapture", "isDesignExample": False,
            "pages": [{"text": "DE LA 12.20"}]}))
        (sample / "outcome.json").write_text(json.dumps({"imported_sha256": source_hash}))
        reference_path = references / "receipt.reference.json"
        reference = {"image": "receipt.jpg", "image_sha256": source_hash,
                     "lines": [{"line": 1, "text": "DE LA 12.20", "ambiguities": []}]}
        reference_path.write_text(json.dumps(reference))
        batch = evaluate_batch(evidence, references)
        assert batch["counts"]["exact"] == 1
        assert batch["counts"]["batch_denominator"] == 2
        assert batch["counts"]["non_evaluable"] == 1
        assert batch["counts"]["exact_document_fraction_of_full_batch"] == .5
        reference["lines"][0]["ambiguities"] = ["One character is uncertain"]
        reference_path.write_text(json.dumps(reference))
        ambiguous = evaluate_batch(evidence, references)
        assert ambiguous["counts"]["exact"] == 0
        assert ambiguous["counts"]["non_evaluable"] == 2
        reference["lines"][0]["ambiguities"] = []
        reference["lines"][0]["text"] = "DELA 12.20"
        reference_path.write_text(json.dumps(reference))
        boundary = evaluate_sample("receipt.jpg", reference_path, sample)
        assert boundary["characters_without_whitespace"]["cer"] == 0
        assert boundary["evaluation_status"] == "inexact"
        (sample / "source.jpg").write_bytes(b"replaced source bytes")
        mismatch = evaluate_sample("receipt.jpg", reference_path, sample)
        assert mismatch["evaluation_status"] == "non_evaluable"
        assert not mismatch["exact_document_established"]
    print("Receipt batch comparator self-tests passed.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence", type=Path, help="Batch directory containing sources.json and per-photo document.json")
    parser.add_argument("--references", type=Path, default=Path("output/qa/real-receipts/references"))
    parser.add_argument("--output", type=Path)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test()
        return
    if not args.evidence:
        parser.error("--evidence is required")
    report = evaluate_batch(args.evidence, args.references)
    output = args.output or args.evidence.parent / f"{args.evidence.name}-comparison.json"
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    summary = brief_report(report)
    output.with_suffix(".txt").write_text(summary)
    print(summary, end="")
    print(f"Detailed evidence: {output}")


if __name__ == "__main__":
    main()
