#!/usr/bin/env python3
"""Independent, offline page detector/recognizer benchmark; never edits originals.

Detection follows pinned PaddleOCR DBPostProcess/DetResizeForTest and crop
utilities (Apache-2.0; full source/license hashes recorded in provenance.json).
Recognition deliberately uses the existing Android Latin recognizer's dynamic
width preprocessing to isolate the effect of independent page detection.
References are read only by the separate `compare` command after run.json freezes
the hashes of every output. Nothing here creates or certifies a PDF.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
from pathlib import Path
import platform
import time

import cv2
import numpy as np
import onnxruntime as ort
import pyclipper
from PIL import Image, ImageDraw, ImageOps
from shapely.geometry import Polygon

PROJECT = Path(__file__).resolve().parents[1]
DEFAULT_OUTPUT = PROJECT / "output/qa/ocr-v028/full-page-benchmark"
DET_SHA = "a431985659dc921974177a95adcfbb90fd9e51989a5e04d70d0b75f597b6e61d"
REC_SHA = "7888113072263cb471b93f66dd5e2ad70548dc526fa1ace760d0d973dd121498"
V6_DET_SHA = "d73e0058b7a8086bbd57f3d10b8bcd4ff95363f67e06e2762b5e814fe9c9410e"
V6_REC_SHA = "5435fd747c9e0efe15a96d0b378d5bd157e9492ed8fd80edf08f30d02fa24634"


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def write_json(path, value):
    Path(path).write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n")


def mini_box(contour):
    rect = cv2.minAreaRect(np.asarray(contour, dtype=np.float32))
    points = sorted(cv2.boxPoints(rect), key=lambda p: p[0])
    i1, i4 = (0, 1) if points[1][1] > points[0][1] else (1, 0)
    i2, i3 = (2, 3) if points[3][1] > points[2][1] else (3, 2)
    return np.array([points[i1], points[i2], points[i3], points[i4]]), min(rect[1])


def box_score(probability, box):
    h, w = probability.shape
    box = box.copy()
    xmin = int(np.clip(np.floor(box[:, 0].min()), 0, w - 1))
    xmax = int(np.clip(np.ceil(box[:, 0].max()), 0, w - 1))
    ymin = int(np.clip(np.floor(box[:, 1].min()), 0, h - 1))
    ymax = int(np.clip(np.ceil(box[:, 1].max()), 0, h - 1))
    mask = np.zeros((ymax - ymin + 1, xmax - xmin + 1), dtype=np.uint8)
    box -= (xmin, ymin)
    cv2.fillPoly(mask, box.reshape(1, -1, 2).astype(np.int32), 1)
    return float(cv2.mean(probability[ymin:ymax + 1, xmin:xmax + 1], mask)[0])


def order_quad(box):
    # Same clockwise order as PaddleOCR TextDetector.filter_tag_det_res.
    result = np.zeros((4, 2), dtype=np.float32)
    sums = box.sum(axis=1)
    result[0], result[2] = box[np.argmin(sums)], box[np.argmax(sums)]
    remaining = np.delete(box, (np.argmin(sums), np.argmax(sums)), axis=0)
    differences = np.diff(remaining, axis=1)
    result[1], result[3] = remaining[np.argmin(differences)], remaining[np.argmax(differences)]
    return result


def decode_boxes(probability, source_w, source_h, parameters=None):
    parameters = parameters or {"thresh": .3, "box_thresh": .6, "max_candidates": 1000, "unclip_ratio": 1.5}
    bitmap = (probability > parameters["thresh"]).astype(np.uint8) * 255
    contours, _ = cv2.findContours(bitmap, cv2.RETR_LIST, cv2.CHAIN_APPROX_SIMPLE)
    detections = []
    rejected = {"small": 0, "score": 0, "unclip": 0, "filter": 0}
    map_h, map_w = probability.shape
    for contour_id, contour in enumerate(contours[:parameters["max_candidates"]]):
        box, short_side = mini_box(contour)
        if short_side < 3:
            rejected["small"] += 1
            continue
        score = box_score(probability, box)
        if score < parameters["box_thresh"]:
            rejected["score"] += 1
            continue
        polygon = Polygon(box)
        offset = pyclipper.PyclipperOffset()
        offset.AddPath(box, pyclipper.JT_ROUND, pyclipper.ET_CLOSEDPOLYGON)
        expanded = offset.Execute(polygon.area * parameters["unclip_ratio"] / polygon.length)
        if len(expanded) != 1:
            rejected["unclip"] += 1
            continue
        box, short_side = mini_box(np.array(expanded[0]).reshape(-1, 1, 2))
        if short_side < 5:
            rejected["small"] += 1
            continue
        box[:, 0] = np.clip(np.round(box[:, 0] / map_w * source_w), 0, source_w)
        box[:, 1] = np.clip(np.round(box[:, 1] / map_h * source_h), 0, source_h)
        box = order_quad(box.astype(np.int32))
        box[:, 0] = np.clip(box[:, 0], 0, source_w - 1)
        box[:, 1] = np.clip(box[:, 1], 0, source_h - 1)
        if int(np.linalg.norm(box[0] - box[1])) <= 3 or int(np.linalg.norm(box[0] - box[3])) <= 3:
            rejected["filter"] += 1
            continue
        detections.append({"contour_id": contour_id, "polygon": box.tolist(), "detection_score": score})
    # Preserve every accepted occurrence. This is the upstream stable reading
    # order, not a reference-based assignment or text-deduplication heuristic.
    detections.sort(key=lambda item: (item["polygon"][0][1], item["polygon"][0][0]))
    for i in range(len(detections) - 1):
        for j in range(i, -1, -1):
            a, b = detections[j]["polygon"][0], detections[j + 1]["polygon"][0]
            if abs(b[1] - a[1]) < 10 and b[0] < a[0]:
                detections[j], detections[j + 1] = detections[j + 1], detections[j]
            else:
                break
    return detections, {"contours": len(contours), "examined": min(len(contours), parameters["max_candidates"]), "rejected": rejected}


def detector_tensor(bgr, mode, min_side=736):
    h, w = bgr.shape[:2]
    if mode == "model-yaml":
        ratio = 960 / max(h, w)
        rh, rw = int(h * ratio), int(w * ratio)
        rh, rw = (rh + 127) // 128 * 128, (rw + 127) // 128 * 128
    elif mode == "cli-max":
        ratio = min(1., 960 / max(h, w))
        rh, rw = max(32, round(int(h * ratio) / 32) * 32), max(32, round(int(w * ratio) / 32) * 32)
    elif mode == "v6-yaml":
        ratio = max(1., min_side / min(h, w))
        rh, rw = int(h * ratio), int(w * ratio)
        if max(rh, rw) > 4000:
            ratio = 4000 / max(rh, rw)
            rh, rw = int(rh * ratio), int(rw * ratio)
        rh, rw = max(32, round(rh / 32) * 32), max(32, round(rw / 32) * 32)
    else:
        raise ValueError(mode)
    resized = cv2.resize(bgr, (rw, rh), interpolation=cv2.INTER_LINEAR)
    values = resized.astype(np.float32) / np.float32(255.)
    values = (values - np.array([.485, .456, .406], np.float32)) / np.array([.229, .224, .225], np.float32)
    return np.ascontiguousarray(values.transpose(2, 0, 1)[None])


def rectify(bgr, polygon):
    points = np.array(polygon, np.float32)
    width = int(max(np.linalg.norm(points[0] - points[1]), np.linalg.norm(points[2] - points[3])))
    height = int(max(np.linalg.norm(points[0] - points[3]), np.linalg.norm(points[1] - points[2])))
    matrix = cv2.getPerspectiveTransform(points, np.array([[0, 0], [width, 0], [width, height], [0, height]], np.float32))
    crop = cv2.warpPerspective(bgr, matrix, (width, height), flags=cv2.INTER_CUBIC, borderMode=cv2.BORDER_REPLICATE)
    rotated = height / width >= 1.5
    if rotated:
        crop = np.rot90(crop)
    return crop, {"homography": matrix.tolist(), "rotation90": rotated, "size": list(crop.shape[:2][::-1])}


def recognize(crop, session, alphabet):
    started = time.perf_counter()
    h, w = crop.shape[:2]
    proportional_width = min(3200, max(1, math.ceil(48 * w / h)))
    resized = cv2.resize(crop, (proportional_width, 48), interpolation=cv2.INTER_LINEAR)
    if proportional_width < 16:
        padded = np.full((48, 16, 3), 255, np.uint8)
        padded[:, :proportional_width] = resized
        resized = padded
    tensor = np.ascontiguousarray(((resized.astype(np.float32) / np.float32(127.5)) - 1).transpose(2, 0, 1)[None])
    probabilities = session.run(None, {session.get_inputs()[0].name: tensor})[0][0]
    indices = probabilities.argmax(axis=1).tolist()
    scores = probabilities.max(axis=1).tolist()
    characters, previous = [], -1
    for step, (index, score) in enumerate(zip(indices, scores)):
        if index and index != previous:
            characters.append({"text": alphabet[index - 1], "confidence": score, "timestep": step, "class_index": index})
        previous = index
    return {"text": "".join(item["text"] for item in characters), "confidence": float(np.mean([item["confidence"] for item in characters])) if characters else 0.,
            "characters": characters, "timestep_argmax": indices, "timestep_scores": scores,
            "tensor_shape": list(tensor.shape), "elapsed_ms": (time.perf_counter() - started) * 1000}


def session(path):
    options = ort.SessionOptions()
    options.intra_op_num_threads = 2
    options.inter_op_num_threads = 1
    options.log_severity_level = 3
    return ort.InferenceSession(str(path), options, providers=["CPUExecutionProvider"])


def run(args):
    output = args.output
    results = output / "results"
    if (output / "run.json").exists():
        raise SystemExit("Frozen run exists: use a new output directory rather than overwrite evidence.")
    results.mkdir(parents=True, exist_ok=True)
    det_path = args.detector
    rec_path = args.recognizer
    alphabet_path = rec_path.with_name("characters.json")
    detector_sha, recognizer_sha = sha(det_path), sha(rec_path)
    assert detector_sha in (DET_SHA, V6_DET_SHA) and recognizer_sha in (REC_SHA, V6_REC_SHA)
    det_parameters = {"thresh": .2, "box_thresh": .45, "max_candidates": 3000, "unclip_ratio": 1.4} if detector_sha == V6_DET_SHA else {"thresh": .3, "box_thresh": .6, "max_candidates": 1000, "unclip_ratio": 1.5}
    start = time.perf_counter()
    detector, recognizer = session(det_path), session(rec_path)
    load_ms = (time.perf_counter() - start) * 1000
    alphabet = json.loads(alphabet_path.read_text())
    photos = sorted(args.photos.glob("*.jpg"))
    manifest = {"started_unix": time.time(), "runtime": {"python": platform.python_version(), "onnxruntime": ort.__version__, "opencv": cv2.__version__, "provider": "CPUExecutionProvider", "intra_op_threads": 2, "inter_op_threads": 1},
                "detector": {"path": str(det_path.resolve()), "sha256": detector_sha, "resize": args.det_resize, "min_side": args.det_min_side, "max_raster_pixels": args.max_raster_pixels, "postprocess": det_parameters, "input": detector.get_inputs()[0].shape, "output": detector.get_outputs()[0].shape},
                "recognizer": {"path": str(rec_path.resolve()), "sha256": recognizer_sha, "alphabet_sha256": sha(alphabet_path), "preprocessing": "Existing Android BGR dynamic-width: height48, ceil proportional width capped3200, minimum16 white padding; /127.5-1; CTC blank0."},
                "model_load_ms": load_ms, "script_sha256": sha(__file__), "samples": [],
                "policy": "Original EXIF-transposed pixels only. Independent detector polygons, no other reader's boxes/text, no references, no manual image changes, no per-photo parameters, no confidence-based text omission. Each detection occurrence kept. Only detector score rejects are upstream DB defaults."}
    if args.frozen_detections:
        manifest["frozen_detector_run"] = {"path": str(args.frozen_detections.resolve()), "sha256": sha(args.frozen_detections / "run.json"), "policy": "Only previously frozen detector quadrilaterals and scores are reused; all old recognized text/confidences discarded before recognition."}
    write_json(output / "run-started.json", manifest)
    total_start = time.perf_counter()
    for photo in photos:
        started = time.perf_counter()
        source_sha = sha(photo)
        target = results / photo.stem
        target.mkdir(exist_ok=True)
        rgb = np.asarray(ImageOps.exif_transpose(Image.open(photo)).convert("RGB"))
        bgr = np.ascontiguousarray(rgb[:, :, ::-1])
        tensor = detector_tensor(bgr, args.det_resize, args.det_min_side)
        if args.max_raster_pixels and tensor.shape[2] * tensor.shape[3] > args.max_raster_pixels:
            result = {"filename": photo.name, "source_path": str(photo.resolve()), "source_sha256": source_sha,
                      "upright_size": [rgb.shape[1], rgb.shape[0]], "detector_tensor_shape": list(tensor.shape),
                      "detector_map_shape": [], "detection_ms": 0., "detections": [], "text": "",
                      "processing_error": "detector_raster_exceeds_complete_page_resource_cap",
                      "max_raster_pixels": args.max_raster_pixels, "source_unchanged": sha(photo) == source_sha,
                      "elapsed_ms": (time.perf_counter() - started) * 1000}
            assert result["source_unchanged"]
            write_json(target / "result.json", result)
            (target / "text.txt").write_text("")
            manifest["samples"].append({"filename": photo.name, "source_sha256": source_sha,
                "result_sha256": sha(target / "result.json"), "text_sha256": sha(target / "text.txt"),
                "detections": 0, "elapsed_ms": result["elapsed_ms"], "processing_error": result["processing_error"]})
            print(photo.name, "RESOURCE_REJECTED", list(tensor.shape), flush=True)
            continue
        detector_start = time.perf_counter()
        if args.frozen_detections:
            old_path = args.frozen_detections / "results" / photo.stem / "result.json"
            old = json.loads(old_path.read_text())
            assert old["source_sha256"] == source_sha
            boxes = [{k: box[k] for k in ("contour_id", "polygon", "detection_score")} for box in old["detections"]]
            details = {"frozen_geometry_source_sha256": sha(old_path), "no_detection_rerun": True}
            detector_ms, map_shape = 0., old["detector_map_shape"]
            del old
        else:
            probability = detector.run(None, {detector.get_inputs()[0].name: tensor})[0][0, 0]
            detector_ms = (time.perf_counter() - detector_start) * 1000
            boxes, details = decode_boxes(probability, bgr.shape[1], bgr.shape[0], det_parameters)
            map_shape = list(probability.shape)
            Image.fromarray(np.clip(probability * 255, 0, 255).astype(np.uint8)).save(target / "probability.png")
        preview = Image.fromarray(rgb)
        draw = ImageDraw.Draw(preview)
        for index, detection in enumerate(boxes):
            crop, info = rectify(bgr, detection["polygon"])
            crop_path = target / f"crop-{index:03d}.png"
            cv2.imwrite(str(crop_path), crop)
            detection.update({"occurrence": index, "crop_path": crop_path.name, "crop_sha256": sha(crop_path), "rectification": info, "recognition": recognize(crop, recognizer, alphabet)})
            polygon = [tuple(point) for point in detection["polygon"]]
            draw.line(polygon + polygon[:1], fill="red", width=2)
            draw.text(polygon[0], str(index), fill="blue", stroke_width=1, stroke_fill="white")
        preview.save(target / "overlay.png")
        result = {"filename": photo.name, "source_path": str(photo.resolve()), "source_sha256": source_sha, "upright_size": [rgb.shape[1], rgb.shape[0]], "detector_tensor_shape": list(tensor.shape), "detector_map_shape": map_shape, "detection_ms": detector_ms, "detector_details": details, "detections": boxes, "text": "\n".join(item["recognition"]["text"] for item in boxes), "elapsed_ms": (time.perf_counter() - started) * 1000}
        assert sha(photo) == source_sha
        result["source_unchanged"] = True
        write_json(target / "result.json", result)
        (target / "text.txt").write_text(result["text"] + "\n")
        manifest["samples"].append({"filename": photo.name, "source_sha256": source_sha, "result_sha256": sha(target / "result.json"), "text_sha256": sha(target / "text.txt"), "detections": len(boxes), "elapsed_ms": result["elapsed_ms"]})
        print(photo.name, len(boxes), f'{result["elapsed_ms"]:.0f}ms', flush=True)
    manifest["elapsed_ms"] = (time.perf_counter() - total_start) * 1000
    manifest["completed_unix"] = time.time()
    manifest["frozen_before_reference_comparison"] = True
    write_json(output / "run.json", manifest)
    print("FROZEN", len(photos), f'{manifest["elapsed_ms"]:.0f}ms', flush=True)


def compare(args):
    # Importing evaluator functions does not read any reference. This command is
    # deliberately a separate invocation so recognition output is immutable first.
    from measure_receipt_batch import alignment, ambiguity_records, characters, numeric_tokens, strict_tokens
    manifest_path = args.output / "run.json"
    manifest = json.loads(manifest_path.read_text())
    assert manifest["frozen_before_reference_comparison"]
    references = {}
    for path in sorted(args.references.glob("*.reference.json")):
        reference = json.loads(path.read_text())
        references[Path(reference["image"]).name] = (path, reference)
    report = {"created_unix": time.time(), "run_sha256": sha(manifest_path), "comparison_only": True, "references_supplied_to_recognition": False, "no_pdf_generated": True, "normalization": "Same strict_tokens/numeric_tokens/characters/alignment functions as tools/measure_receipt_batch.py. Ordered literal text, case, punctuation and numeric groups preserved. Ambiguities remain non-evaluable.", "samples": []}
    for item in manifest["samples"]:
        result_path = args.output / "results" / Path(item["filename"]).stem / "result.json"
        assert sha(result_path) == item["result_sha256"]
        result = json.loads(result_path.read_text())
        row = {"filename": item["filename"], "status": "non_evaluable", "reasons": []}
        if result.get("processing_error"):
            row["reasons"].append(result["processing_error"])
        if item["filename"] not in references:
            row["reasons"].append("No independent visual reference exists.")
        else:
            path, reference = references[item["filename"]]
            row.update({"reference_sha256": sha(path), "reference_path": str(path.resolve()), "reference_ambiguities": ambiguity_records(reference)})
            if row["reference_ambiguities"]:
                row["reasons"].append("Reference contains unresolved ambiguities.")
            if reference.get("image_sha256") != item["source_sha256"]:
                row["reasons"].append("Reference source hash differs.")
            expected = "\n".join(line["text"] for line in reference["lines"])
            actual = result["text"]
            for label, func in (("strict_tokens", strict_tokens), ("numeric_tokens", numeric_tokens), ("characters_without_whitespace", characters)):
                left, right = func(expected), func(actual)
                row[label] = {"ordered_match": left == right, **alignment(left, right)}
            row.update({"reference_text": expected, "actual_text": actual})
            if not row["reasons"]:
                row["status"] = "exact" if row["strict_tokens"]["ordered_match"] and row["numeric_tokens"]["ordered_match"] else "inexact"
        report["samples"].append(row)
    report["counts"] = {"batch_denominator": len(report["samples"]), **{status: sum(row["status"] == status for row in report["samples"]) for status in ("exact", "inexact", "non_evaluable")}}
    write_json(args.output / "comparison.json", report)
    print(json.dumps(report["counts"], indent=2))
    for row in report["samples"]:
        print(row["filename"], row["status"], "tokens", row.get("strict_tokens", {}).get("edit_distance"), "numbers", row.get("numeric_tokens", {}).get("edit_distance"))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["run", "compare"])
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--photos", type=Path, default=PROJECT / "imagenes-prueba/fotos-reales/Fotos")
    parser.add_argument("--detector", type=Path, default=DEFAULT_OUTPUT / "models/PP-OCRv5_mobile_det_onnx/inference.onnx")
    parser.add_argument("--recognizer", type=Path, default=PROJECT / "android/app/src/main/assets/paddle/inference.onnx")
    parser.add_argument("--frozen-detections", type=Path)
    parser.add_argument("--det-resize", choices=["model-yaml", "cli-max", "v6-yaml"], default="model-yaml")
    parser.add_argument("--det-min-side", type=int, default=736)
    parser.add_argument("--max-raster-pixels", type=int, default=0)
    parser.add_argument("--references", type=Path, default=PROJECT / "output/qa/real-receipts/references")
    args = parser.parse_args()
    {"run": run, "compare": compare}[args.command](args)


if __name__ == "__main__":
    main()
