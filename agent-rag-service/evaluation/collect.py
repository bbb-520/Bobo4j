"""Opt-in, authenticated collector of actual application/Milvus/provider observations."""
import argparse, hashlib, json, os, pathlib, subprocess, sys, time, urllib.request, urllib.error, uuid
from evaluate import corpus, ROOT

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--api-base", default=os.environ.get("API_BASE", "http://127.0.0.1:18000"))
    parser.add_argument("--cookie-name", default=os.environ.get("COOKIE_NAME", "bbb_agent_session"))
    parser.add_argument("--allow-paid-models", action="store_true", help="Authorize normal application billing for ingestion, summaries, QA and judges")
    parser.add_argument("--model-versions-json", type=pathlib.Path, required=True, help="Operator-supplied configured embedding/rerank/chat/judge model snapshot")
    parser.add_argument("--milvus-version", default="2.6.2")
    parser.add_argument("--output", type=pathlib.Path, default=ROOT / "recorded-observations.json")
    parser.add_argument("--state", type=pathlib.Path, default=ROOT / "collector-state.json")
    parser.add_argument("--timeout", type=int, default=1800)
    args = parser.parse_args()
    if not args.allow_paid_models: parser.error("--allow-paid-models is required: this run invokes billable configured providers")
    cookie = os.environ.get("SESSION_COOKIE")
    if not cookie: parser.error("Set SESSION_COOKIE to your own signed-in session token; it is never written to outputs")
    versions = json.loads(args.model_versions_json.read_text(encoding="utf-8"))
    if not all(versions.get(k) for k in ["embedding", "rerank", "chat", "judge"]): parser.error("Model snapshot must identify embedding/rerank/chat/judge")
    data, questions = corpus()
    docs = ROOT / "documents"
    subprocess.run([sys.executable, str(ROOT / "evaluate.py"), "--export-documents", str(docs)], check=True)
    state = json.loads(args.state.read_text()) if args.state.exists() else {"documents": {}, "conversations": {}, "executions": {}}
    observations = {"provenance": "recorded-real-dependencies", "modelVersions": versions, "modelVersionProvenance": "operator-supplied-configured-model-snapshot", "milvusVersion": args.milvus_version, "datasetVersion": data["version"], "results": []}

    def persist(): args.state.write_text(json.dumps(state, indent=2), encoding="utf-8")
    def request(path, method="GET", value=None, raw=None, content_type=None, headers=None):
        body = raw if raw is not None else None if value is None else json.dumps(value).encode()
        h = {"Cookie": f"{args.cookie_name}={cookie}", "Accept": "application/json"}
        if body is not None: h["Content-Type"] = content_type or "application/json"
        if headers: h.update(headers)
        req = urllib.request.Request(args.api_base.rstrip("/")+path, data=body, method=method, headers=h)
        try:
            with urllib.request.urlopen(req, timeout=130) as response: return json.loads(response.read(16_000_000))
        except urllib.error.HTTPError as error:
            raise RuntimeError(f"Application HTTP{error.code} for {method} {path}; check login, budget and service configuration") from None
    def wait(path, terminal):
        deadline = time.monotonic() + args.timeout
        while time.monotonic() < deadline:
            view = request(path)
            if terminal(view): return view
            if view.get("status") in {"FAILED", "STOPPED", "WAITING_FOR_BUDGET", "WAITING_FOR_RECONCILIATION", "NEEDS_INPUT", "CANCELLED"}: raise RuntimeError(f"Processing requires attention: {view['status']} ({view.get('error')})")
            time.sleep(2)
        raise RuntimeError("Collector timeout; saved state permits a later explicit run without new upload/execution IDs")
    for family in data["families"]:
        fid = family["id"]
        if fid not in state["documents"]:
            content = (docs / f"{fid}.html").read_bytes(); boundary = "rag-evaluation-"+uuid.uuid4().hex
            raw = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{fid}.html\"\r\nContent-Type: text/html\r\n\r\n".encode()+content+f"\r\n--{boundary}--\r\n".encode())
            request_id = "eval-"+hashlib.sha256(content).hexdigest()[:48]
            view = request("/api/documents", "POST", raw=raw, content_type=f"multipart/form-data; boundary={boundary}", headers={"X-Request-ID": request_id})
            state["documents"][fid] = view["documentId"]; persist()
        doc_id = state["documents"][fid]
        wait(f"/api/documents/{doc_id}", lambda d: d.get("indexStatus") == "READY")
        if fid not in state["conversations"]:
            view = request("/api/document-conversations", "POST", {"documentIds": [doc_id]}); state["conversations"][fid] = view["conversationId"]; persist()
        conversation = state["conversations"][fid]
        for q in (q for q in questions if q["family"] == fid):
            if q["id"] not in state["executions"]:
                request_id = "eval-"+hashlib.sha256((data["version"]+conversation+q["id"]).encode()).hexdigest()[:48]
                view = request("/api/agent-executions", "POST", {"requestId": request_id, "type": "DOCUMENT_QA", "question": q["question"], "documentConversationId": conversation}); state["executions"][q["id"]] = view["executionId"]; persist()
            execution = wait("/api/agent-executions/"+state["executions"][q["id"]], lambda e: e.get("status") == "COMPLETED")
            traces = request(f"/api/document-conversations/{conversation}/retrieval-traces")
            trace = next((t["payload"] for t in traces if t["payload"].get("originalQuestion") == q["question"]), None)
            if trace is None: raise RuntimeError(f"No owner-scoped retrieval trace for {q['id']}")
            chunks = {c["id"]: c for c in trace["recallEvidence"]}; candidates = trace["candidates"]
            def hit(c, score): return {"id": c["id"], "family": fid, "documentId": c["documentId"], "indexVersion": c["version"], "text": c["text"], "score": score}
            row = {"questionId": q["id"], "executionId": state["executions"][q["id"]], "dense": [hit(chunks[h["chunkId"]], h["score"]) for h in trace["dense"]], "bm25": [hit(chunks[h["chunkId"]], h["score"]) for h in trace["bm25"]], "rerank": [hit(candidates[h["index"]], h["score"]) for h in trace["rerank"]], "answer": execution.get("answer"), "degraded": trace["degraded"]}
            row["observedPipelineConfiguration"] = trace.get("pipelineConfiguration")
            row["rerankInputs"] = trace.get("rerankInputs")
            observations["results"].append(row); args.output.write_text(json.dumps(observations, ensure_ascii=False, indent=2), encoding="utf-8")
            print(q["id"], "recorded", flush=True)
    print("All48 actual observations captured; humanJudgment remains absent until independently labeled.")

if __name__ == "__main__": main()
