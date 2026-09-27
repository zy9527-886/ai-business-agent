# PostgreSQL 初始化与运行

项目只使用 PostgreSQL。业务表和向量表共用数据源、连接池与事务管理器。

- 数据库：`neondb`
- Schema：`agent`（不是另一个数据库）
- 用户：`neondb_owner`
- pgvector 扩展：已安装在 `agent`，脚本不会重装或移动扩展。

## 初始化

连接 Neon PostgreSQL，依次执行根目录的 `ai_knowledge.sql` 和 `ai_knowledge_vector.sql`。
前者创建 5 张业务表并导入示例数据，后者创建向量表并导入 10 条 1024 维示例向量。

两份脚本都在事务内执行，使用 `CREATE TABLE IF NOT EXISTS` 和 `ON CONFLICT DO NOTHING`，重复执行不清空表、不覆盖已有数据。导入后同步自增序列，新增记录不会与导入 ID 冲突。所有对象限定在 `agent`，不修改其他 schema。

脚本用于初始化，不会自动迁移其他已有业务库。向量数据依赖第一份脚本中的知识片段，应按顺序导入。

## 后端配置

文件：`ai_knowledge/aiAgent-back/src/main/resources/application.yml`。

```text
jdbc:postgresql://ep-cool-moon-atxv7qg3.c-9.us-east-1.aws.neon.tech/neondb?sslmode=require
```

YAML 已配置数据库密码。连接池会显式将会话 schema 设置为 `agent`；`pgvector` 连接统一使用 `spring.datasource`。

## 构建与测试

使用 Java 21、Maven、Node.js：

```powershell
cd ai_knowledge/aiAgent-back
mvn package
cd ../aiAgent-front
npm ci
npm run build
```

真实数据库集成测试需先初始化数据库，在后端目录设置环境变量后执行：

```powershell
$env:TEST_DB_URL = 'jdbc:postgresql://xxxxxxx'
$env:DB_USERNAME = 'admin'
# 在当前终端设置 DB_PASSWORD，不要把真实密码写入仓库。
mvn test
```

未设置 `TEST_DB_URL` 时跳过真实数据库测试。测试覆盖 5 组 Mapper 的 CRUD、主键回填、分页、中文和关联查询，以及向量 upsert、相似度检索、级联删除和业务/向量共同回滚。测试记录会回滚，自增序列正常前进。

## 启动

安装好依赖后在项目根目录执行：

```powershell
./scripts/start.ps1
```

未设置 `DB_PASSWORD` 时安全提示输入密码。脚本在后台启动：

- 前端：`http://localhost:5173`
- 后端：`http://localhost:9090`
- 日志和进程 ID：根目录 `.runtime/`

后端工作目录为 `ai_knowledge`，读取现有的 `ai_knowledge/files` 文档。示例管理员账号：`admin`，密码：`admin`。

模型由数据库 `ai_model_config` 动态配置，不需要额外的全局 OpenAI Key。示例模型 API Key 为空，真实向量化和大模型问答需在管理端填写可用的 Key；向量模型应返回 1024 维向量。
