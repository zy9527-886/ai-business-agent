# aiAgent Python 多智能体服务

该服务直接调用聊天与 Embedding 模型，并用 LangGraph 编排 Guard、Router、Knowledge、Business、Web、Answer 和 Reviewer Agent。Java 仍负责用户鉴权、业务/知识数据、文件与 PGVector；两端使用 HMAC-SHA256、时间戳和一次性 nonce 通信。

## 本地启动

```powershell
cd ai_knowledge/aiAgent-python
Copy-Item .env.example .env
python -m pip install -e ".[test]"
python -m uvicorn app.main:app --host 127.0.0.1 --port 8000
```

`.env` 至少需要配置与 Java 相同的 `AGENT_SHARED_SECRET`、`CHAT_MODEL_API_KEY` 和 `EMBEDDING_MODEL_API_KEY`。启用持久化时设置 `AGENT_DATABASE_URL`；启用真实 Web Agent 时设置 `AGENT_TAVILY_API_KEY`。

| 环境变量 | 用途 | 必需 |
| --- | --- | --- |
| `AGENT_JAVA_BASE_URL` | Java 内部工具地址 | 是 |
| `AGENT_SHARED_SECRET` | Java/Python HMAC 共享密钥 | 是 |
| `CHAT_MODEL_API_KEY` | `profiles.yaml` 默认聊天模型凭据 | 是 |
| `EMBEDDING_MODEL_API_KEY` | 默认向量模型凭据 | 是 |
| `AGENT_DATABASE_URL` | `agent_runtime` 持久化与 LangGraph checkpoint | 生产建议 |
| `AGENT_TAVILY_API_KEY` | Tavily Web 搜索；缺失时 Web Agent 自动禁用 | 否 |

模型名、Base URL、维度和温度由 `app/profiles.yaml` 管理，密钥只引用环境变量，禁止写入 YAML 或提交仓库。Embedding 维度必须与 Java `pgvector.dimension` 一致。

## 接口

- `GET /health/live`、`GET /health/ready`
- `POST /api/v1/embeddings`
- `GET /api/v1/capabilities/models`
- `POST /api/v1/chat/stream`
- `POST /api/v1/handoffs/search`、`PATCH /api/v1/handoffs/{id}`

除健康检查外均为内部接口，需要 HMAC 请求头。聊天 SSE 事件顺序为 `meta/status/sources/error*/review/handoff?/message*/done`；回答在 Review 完成前不会输出。

## 验证

```powershell
$env:PYTHONDONTWRITEBYTECODE='1'
python -m pytest -p no:cacheprovider -q
```

真实 PostgreSQL 集成测试仅在设置 `TEST_DATABASE_URL` 时运行。真实模型/Tavily 冒烟测试必须显式提供测试凭据，不应使用生产密钥。

## Docker

```bash
docker build -t aiagent-python ./ai_knowledge/aiAgent-python
docker run --rm -p 8000:8000 --env-file ./ai_knowledge/aiAgent-python/.env aiagent-python
```
