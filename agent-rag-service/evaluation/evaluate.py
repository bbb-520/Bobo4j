"""Offline scoring/calibration of RECORDED retrievals. Never calls or simulates a model."""
import argparse, hashlib, html, itertools, json, math, pathlib, statistics

ROOT = pathlib.Path(__file__).resolve().parent

def corpus():
    data = json.loads((ROOT / "corpus.json").read_text(encoding="utf-8"))
    families = data["families"]
    assert len(families) >= 12 and len({f["id"] for f in families}) == len(families)
    questions = []
    for family in families:
        assert len(family["questions"]) == 4
        for i, question in enumerate(family["questions"]):
            q = dict(question, id=f"{family['id']}-{i+1}", family=family["id"], split=family["split"])
            assert all(span in "\n".join(family["sections"]) for span in q["goldSpans"])
            assert bool(q["goldSpans"]) == (q["answer"] is not None)
            questions.append(q)
    assert sum(q["split"] == "calibration" for q in questions) == 32
    assert sum(q["split"] == "holdout" for q in questions) == 16
    assert not ({q["family"] for q in questions if q["split"] == "calibration"} & {q["family"] for q in questions if q["split"] == "holdout"})
    hashes = [hashlib.sha256("\n".join(f["sections"]).encode()).hexdigest() for f in families]
    assert len(set(hashes)) == len(hashes), "Duplicate document across families"
    return data, questions

def fuse(dense, keyword, weight):
    values, scores = {}, {}
    for hits, lane_weight in [(dense[:30], weight), (keyword[:30], 1.)]:
        seen = set()
        for rank, hit in enumerate(hits, 1):
            if hit["id"] in seen: continue
            seen.add(hit["id"])
            values[hit["id"]] = hit
            scores[hit["id"]] = scores.get(hit["id"], 0.) + lane_weight / (60 + rank)
    return [values[x] for x in sorted(values, key=lambda x: (-scores[x], x))][:20]

def grade(q, hit):
    if hit["family"] != q["family"]: return 0
    return 2 if any(span in hit["text"] for span in q["goldSpans"]) else 0

def metrics(questions, observed, stage, weight=1., threshold=.25):
    recalls, reciprocal, ndcgs, failures = [], [], [], []
    for q in questions:
        row = observed[q["id"]]
        if stage == "rrf": hits = fuse(row["dense"], row["bm25"], weight)
        elif stage == "rerank": hits = [h for h in sorted(row["rerank"], key=lambda h: -h["score"]) if h["score"] >= threshold][:8]
        else: hits = row[stage][:30]
        if not q["goldSpans"]: continue
        recalled = sum(any(h["family"] == q["family"] and span in h["text"] for h in hits) for span in q["goldSpans"]) / len(q["goldSpans"])
        grades = [grade(q, h) for h in hits]
        relevant = [i for i, g in enumerate(grades, 1) if g]
        reciprocal.append(1 / relevant[0] if relevant else 0)
        dcg = sum((2**g - 1) / math.log2(i+2) for i, g in enumerate(grades[:8]))
        gold_count = max(1, len({h["id"] for lane in (row["dense"], row["bm25"], row["rerank"]) for h in lane if grade(q, h)}))
        ideal = sum(3 / math.log2(i+2) for i in range(min(gold_count, 8)))
        recalls.append(recalled); ndcgs.append(dcg / ideal)
        if recalled < 1: failures.append({"questionId": q["id"], "recall": recalled})
    return {"spanRecall": statistics.mean(recalls), "MRR": statistics.mean(reciprocal), "nDCG@8": statistics.mean(ndcgs), "failures": failures}

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--rankings", type=pathlib.Path, help="Recorded real dense/bm25/rerank observations JSON; see README")
    parser.add_argument("--output", type=pathlib.Path, default=ROOT / "validation-report.json")
    parser.add_argument("--export-documents", type=pathlib.Path)
    args = parser.parse_args()
    data, questions = corpus()
    report = {"datasetVersion": data["version"], "datasetSha256": hashlib.sha256((ROOT / "corpus.json").read_bytes()).hexdigest(), "families": 12, "calibrationQuestions": 32, "holdoutQuestions": 16, "modelQualityExecution": "NOT_EXECUTED"}
    if args.export_documents:
        args.export_documents.mkdir(parents=True, exist_ok=True)
        for family in data["families"]:
            # The last labeled section follows ~40k characters of family-specific logs.
            middle = "".join(f"<p>{html.escape(family['title'])}运行日志第{i}条：例行巡视记录，无故障结论。</p>" for i in range(1000))
            sections = family["sections"]
            body = f"<h1>{html.escape(family['title'])}</h1><h2>操作</h2><p>{html.escape(sections[0])}</p><h2>编号</h2><p>{html.escape(sections[1])}</p>{middle}<h2>末页表格</h2><table><tr><th>项目</th><th>要求</th></tr><tr><td>验收</td><td>{html.escape(sections[2])}</td></tr></table>"
            (args.export_documents / f"{family['id']}.html").write_text(f"<!doctype html><html><meta charset='utf-8'><body>{body}</body></html>", encoding="utf-8")
    if args.rankings:
        observations = json.loads(args.rankings.read_text(encoding="utf-8"))
        assert observations.get("provenance") == "recorded-real-dependencies", "Do not score invented provider outcomes"
        assert observations.get("modelVersions") and observations.get("milvusVersion")
        rows = {r["questionId"]: r for r in observations["results"]}
        assert set(rows) == {q["id"] for q in questions}, "All48 observations required"
        for row in rows.values():
            for lane in ["dense", "bm25", "rerank"]:
                assert lane in row and all({"id", "family", "text", "score"} <= h.keys() for h in row[lane])
        calibration = [q for q in questions if q["split"] == "calibration"]
        selected = max(itertools.product([.5, 1., 1.5], [0., .15, .25, .35]), key=lambda p: metrics(calibration, rows, "rrf", p[0], p[1])["nDCG@8"] + metrics(calibration, rows, "rerank", p[0], p[1])["nDCG@8"])
        report.update(modelQualityExecution="EXECUTED_ON_RECORDED_OBSERVATIONS", modelVersions=observations["modelVersions"], milvusVersion=observations["milvusVersion"], parameters={"denseRrfWeight": selected[0], "rrfK": 60, "rerankThreshold": selected[1], "recallEach": 30, "fused": 20, "final": 8})
        report["calibrationScope"] = "POSTHOC_RRF_REPLAY_AND_FIXED_CANDIDATE_RERANK"
        report["observedPipelineConfigurations"] = list({json.dumps(r.get("observedPipelineConfiguration"), sort_keys=True) for r in rows.values()})
        report["fullPipelineQualityAtSelectedConfiguration"] = "REQUIRES_FRESH_CAPTURE"
        report["calibrationLimitation"] = "Changing RRF weight can change the20 rerank candidates. Recorded rerank scores cover only the originally dispatched candidates; freeze selected parameters and capture fresh holdout observations before reporting full-pipeline quality."
        for split in ["calibration", "holdout"]:
            subset = [q for q in questions if q["split"] == split]
            report[split] = {stage: metrics(subset, rows, stage, *selected) for stage in ["dense", "bm25", "rrf", "rerank"]}
            labeled = [rows[q["id"]]["humanJudgment"] for q in subset if "humanJudgment" in rows[q["id"]]]
            report[split]["answerQuality"] = "NOT_EXECUTED" if len(labeled) != len(subset) else {"factSupportRate": statistics.mean(x["factsSupported"] for x in labeled), "citationValidRate": statistics.mean(x["citationsValid"] for x in labeled), "refusalAccuracy": statistics.mean(rows[q["id"]]["humanJudgment"]["refused"] for q in subset if not q["goldSpans"])}
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False))

if __name__ == "__main__": main()
