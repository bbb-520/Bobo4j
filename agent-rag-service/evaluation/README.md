# 中文文档问答评测

复用 [corpus.json](corpus.json) 的固定数据集：12 个文档族、32 个校准问题、16 个留出问题。标注包含原文 gold spans 及无证据问题；长文档的末页内容用于检验截断或遗漏。

## 离线数据检查

在仓库根目录执行，不调用模型、不连接数据库：

```powershell
python agent-rag-service/evaluation/evaluate.py --export-documents tmp/rag-evaluation-documents --output tmp/rag-corpus-report.json
```

未提供真实排名时报告保持 `modelQualityExecution=NOT_EXECUTED`，此命令只证明语料结构有效。导出的 HTML 用于真实入库，不能把人工合成的排名填成真实供应商观测。

## 真实采集与评分

[collect.py](collect.py) 通过已登录网关上传、摘要和问答执行，保存稳定文档/执行 ID，失败后可用同一 state 续采；它需要显式 `--allow-paid-models`，会产生正常模型消费。运行前配置自己的 `SESSION_COOKIE`、`--model-versions-json` 和 Milvus 版本，输出按敏感业务数据保护，不提交会话或真实回答。

采集客户端在首次写请求前获取 `/api/auth/csrf`，同时保留登录 Cookie、服务端 CSRF Cookie 和对应请求头。CSRF 或业务请求失败时停止，不自动重发收费请求；会话失效后重新登录并显式续采。运行 `python -m unittest discover -s agent-rag-service/evaluation -p 'test_*.py'` 验证本地 HTTP 协议，此测试不连接真实服务。

使用 `python agent-rag-service/evaluation/collect.py --help` 查看参数；完整采集需 48 个问题，记录 dense/BM25、20 个融合候选重排及最多 60 条召回证据。采集完成后评分：

```powershell
python agent-rag-service/evaluation/evaluate.py --rankings tmp/recorded-observations.json --output tmp/rag-quality-report.json
```

报告计算 spanRecall、MRR、nDCG@8 和失败问题；人工标注 `humanJudgment` 才产生事实支持率、引用正确率和拒答准确性。不要把缺失人工评估视为通过。

## 版本比较

固定语料 SHA-256、模型/索引版本、候选配置和留出分组。先用校准组选择阈值，再冻结参数重新采集留出组；RRF 权重改变会改变重排输入，仅回放旧候选分数不能证明新流程的端到端质量。

每轮同时记录调用用量/成本、端到端耗时分位数、拒绝率与错误类型，按发布目标比较质量和资源开销。当前脚本主要计算检索与人工质量指标，耗时和费用需结合执行审计及监控收集，不能从检索分数推算。本次优化仅运行离线语料检查，未执行付费采集。
