# AI 企业知识库

一个面向企业资料管理与智能客服场景的 RAG 知识库系统。管理员可以上传并解析企业文档，将内容切分、向量化后写入 PostgreSQL；用户提问时，系统会检索相关知识片段并调用大模型生成回答，同时支持展示资料来源。

## 核心功能

- **企业智能客服**：提供面向用户的问答入口，基于企业知识库进行流式回答。
- **知识库管理**：维护不同业务知识库及其切片长度、重叠长度和启用状态。
- **文档管理**：支持上传 TXT、DOCX、PDF 等资料，完成文本解析与知识片段生成。
- **知识片段与向量化**：查看文档切片，并使用指定 Embedding 模型进行单条或批量向量化。
- **RAG 问答与溯源**：支持设置 TopK 和相似度阈值，展示回答命中的资料来源。
- **AI 模型配置**：统一管理聊天模型与向量模型，兼容 OpenAI API 风格的模型服务。
- **系统管理**：包含用户管理、角色区分、个人资料和密码维护等基础功能。

## 功能展示

### 1. 用户端智能客服

用户可以在首页打开智能客服，围绕产品手册、售后流程或公司制度等企业资料进行提问。

![用户端智能客服](./files/system/img1.png)

### 2. 知识库管理

管理员可以创建多个业务知识库，分别维护知识库编码、描述、切片参数和启用状态。

![知识库管理](./files/system/img2.png)

### 3. 知识文档管理

文档上传后可以执行解析、生成知识片段、查看解析文本和下载等操作，并跟踪解析与向量化状态。

![知识文档管理](./files/system/img3.png)

### 4. 知识片段管理

系统按照知识库配置将文档切分为知识片段，支持查看片段内容、选择向量模型以及批量向量化。

![知识片段管理](./files/system/img4.png)

### 5. 智能问答与资料溯源

管理端可以选择聊天模型和向量模型，调整 TopK、相似度阈值，并查看回答引用的知识片段。

![智能问答与资料溯源](./files/system/img5.png)

### 6. AI 模型配置

支持分别配置聊天模型与向量模型，包括服务商、模型名称、接口地址、密钥和启用状态。

![AI 模型配置](./files/system/img6.png)

## 技术栈

### 前端

- Vue 3
- Vite 5
- Element Plus
- Vue Router
- Axios
- Sass

### 后端

- Java 21
- Spring Boot 3.5
- Spring AI 1.1
- MyBatis + PageHelper
- JWT
- Apache POI（Word 文档解析）
- Apache PDFBox（PDF 文档解析）

### 数据与 AI

- PostgreSQL
- pgvector
- OpenAI API 兼容的聊天模型与 Embedding 模型
- SSE 流式问答

## 工作流程

1. 创建知识库并配置文档切片参数。
2. 上传企业文档，解析文本并生成知识片段。
3. 选择 Embedding 模型，将知识片段写入向量库。
4. 用户提交问题，系统对问题进行向量化并召回相关片段。
5. 将召回内容作为上下文交给聊天模型，流式生成答案并返回资料来源。

## 项目结构

```text
.
├── ai_knowledge/
│   ├── aiAgent-front/        # Vue 前端
│   └── aiAgent-back/         # Spring Boot 后端
├── files/system/             # README 功能截图
├── ai_knowledge.sql          # 业务表初始化脚本
└── ai_knowledge_vector.sql   # pgvector 初始化脚本
```

## 快速启动

### 1. 初始化数据库

准备 PostgreSQL 数据库并启用 pgvector，依次执行：

```text
ai_knowledge.sql
ai_knowledge_vector.sql
```

然后在 `ai_knowledge/aiAgent-back/src/main/resources/application.yml` 中配置数据库连接。请勿将真实数据库密码或模型密钥提交到公共仓库。

### 2. 启动后端

```bash
cd ai_knowledge/aiAgent-back
mvn spring-boot:run
```

后端默认端口为 `9090`。

### 3. 启动前端

```bash
cd ai_knowledge/aiAgent-front
npm install
npm run dev
```

前端接口地址由 `.env.development` 或 `.env.production` 中的 `VITE_BASE_URL` 配置。

## 使用提示

- 向量模型输出维度需要与 pgvector 表中的向量维度保持一致。
- 使用智能问答前，至少需要启用一个聊天模型和一个向量模型。
- 企业资料应先完成解析、切片和向量化，才能参与知识检索。
