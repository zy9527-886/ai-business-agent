<template>
  <div class="chat-page">
    <div class="chat-toolbar card">
      <el-select v-model="data.chatModelProfile" clearable style="width: 220px" placeholder="请选择聊天 Profile">
        <el-option v-for="item in data.chatModelList" :key="item.name" :label="item.name + '（' + item.model + '）'" :value="item.name"></el-option>
      </el-select>
      <el-select v-model="data.embeddingModelProfile" clearable style="width: 220px" placeholder="请选择向量 Profile">
        <el-option v-for="item in data.embeddingModelList" :key="item.name" :label="item.name + '（' + item.model + '）'" :value="item.name"></el-option>
      </el-select>
      <div class="toolbar-field">
        <span>TopK</span>
        <el-input-number v-model="data.topK" :min="1" :max="10" controls-position="right" style="width: 110px" />
      </div>
      <el-checkbox-group v-model="data.enabledAgents">
        <el-checkbox-button label="knowledge">知识</el-checkbox-button>
        <el-checkbox-button label="business">演示业务</el-checkbox-button>
        <el-checkbox-button label="web">网页</el-checkbox-button>
      </el-checkbox-group>
      <div class="toolbar-field threshold-field">
        <span>相似度</span>
        <el-slider v-model="data.similarityThreshold" :min="0" :max="1" :step="0.05" style="width: 160px" />
        <span class="threshold-value">{{ data.similarityThreshold.toFixed(2) }}</span>
      </div>
    </div>

    <div class="chat-main">
      <section class="chat-window card">
        <div class="message-list" ref="messageListRef">
          <div v-for="(item, index) in data.messages" :key="index" :class="['message-row', item.role]">
            <div class="message-bubble">
              <div class="message-role">{{ item.role === 'user' ? '管理员' : '智能客服' }}</div>
              <div class="message-content" v-if="item.role === 'user'">{{ item.content }}</div>
              <div class="message-content markdown-body" v-else v-html="renderMarkdown(item.content)"></div>
            </div>
          </div>
        </div>
        <div class="question-box">
          <el-input
              v-model="data.question"
              type="textarea"
              :rows="3"
              resize="none"
              placeholder="请输入企业知识库问题，例如：产品支持私有化部署吗？"
              @keyup.ctrl.enter="ask"
          ></el-input>
          <el-button type="primary" :loading="data.loading" @click="ask">发送问题</el-button>
        </div>
      </section>

      <aside class="source-panel card">
        <div class="source-header">
          <div>
            <h3>资料来源</h3>
            <p>展示本次回答命中的知识片段</p>
          </div>
          <el-tag type="success" v-if="data.sources.length">{{ data.sources.length }} 条</el-tag>
          <el-tag type="info" v-else>暂无</el-tag>
        </div>
        <div style="display:flex; gap:8px; flex-wrap:wrap; margin-bottom:12px">
          <el-tag>{{ data.status || '等待提问' }}</el-tag>
          <el-tag v-if="data.review" :type="data.review.decision === 'PASS' ? 'success' : 'warning'">审查 {{ data.review.decision }}</el-tag>
          <el-tag v-if="data.handoff" type="danger">已转人工</el-tag>
        </div>
        <div class="source-list" v-if="data.sources.length">
          <div class="source-card" v-for="item in data.sources" :key="item.sourceId">
            <div class="source-meta">
              <span>{{ item.title || item.sourceId }}</span>
              <el-tag v-if="item.score != null" size="small" type="warning">相关度 {{ formatSimilarity(item.score) }}</el-tag>
              <el-tag v-if="item.demo" size="small" type="danger">演示数据</el-tag>
            </div>
            <p>{{ item.content }}</p>
            <a v-if="item.url" :href="item.url" target="_blank" rel="noopener noreferrer">查看网页来源</a>
          </div>
        </div>
        <el-empty v-else description="发送问题后显示资料来源" />
      </aside>
    </div>
  </div>
</template>

<script setup>
import { nextTick, reactive, ref } from "vue";
import request from "@/utils/request.js";
import { ElMessage } from "element-plus";
import { consumeSseResponse, parseSseData } from "@/utils/sse.js";

const baseUrl = import.meta.env.VITE_BASE_URL
const user = JSON.parse(localStorage.getItem('sys-user') || '{}')

const messageListRef = ref()

const data = reactive({
  chatModelList: [],
  embeddingModelList: [],
  chatModelProfile: 'default-chat',
  embeddingModelProfile: 'default-embedding',
  enabledAgents: ['knowledge', 'business', 'web'],
  topK: 5,
  similarityThreshold: 0.2,
  question: '',
  loading: false,
  status: '',
  review: null,
  handoff: null,
  conversationId: null,
  messages: [
    {
      role: 'assistant',
      content: '你好，我是企业知识库智能客服。你可以输入一个问题，我会基于已向量化的企业资料回答，并在右侧展示资料来源。'
    }
  ],
  sources: []
})

const loadModelList = () => {
  request.get('/knowledgeChat/capabilities').then(res => {
    if (res.code === '200') {
      const list = res.data || []
      data.chatModelList = list.filter(item => item.kind === 'chat' && item.enabled)
      data.embeddingModelList = list.filter(item => item.kind === 'embedding' && item.enabled)
      if (data.chatModelList.length) {
        data.chatModelProfile = data.chatModelList[0].name
      }
      if (data.embeddingModelList.length) {
        data.embeddingModelProfile = data.embeddingModelList[0].name
      }
    }
  })
}

const ask = () => {
  if (!data.chatModelProfile) {
    ElMessage.warning('请选择聊天模型')
    return
  }
  if (!data.embeddingModelProfile) {
    ElMessage.warning('请选择向量模型')
    return
  }
  if (!data.question.trim()) {
    ElMessage.warning('请输入问题')
    return
  }

  const question = data.question.trim()
  data.messages.push({
    role: 'user',
    content: question
  })
  data.question = ''
  data.loading = true
  scrollToBottom()

  // 清空上一次来源，并先放一条空的客服消息，流式内容会不断追加到它上面
  data.sources = []
  data.review = null
  data.handoff = null
  data.status = '正在编排智能体'
  data.messages.push({ role: 'assistant', content: '' })
  const answer = data.messages[data.messages.length - 1]

  streamAsk('/knowledgeChat/askStream', {
    question,
    requestId: crypto.randomUUID(),
    conversationId: data.conversationId || (data.conversationId = crypto.randomUUID()),
    chatModelProfile: data.chatModelProfile,
    embeddingModelProfile: data.embeddingModelProfile,
    enabledAgents: data.enabledAgents,
    topK: data.topK,
    similarityThreshold: data.similarityThreshold
  }, answer).finally(() => {
    data.loading = false
  })
}

// 通用 SSE 流式请求：sources 事件填充右侧来源，message 事件把增量追加到 answer.content
const streamAsk = async (url, payload, answer) => {
  try {
    const response = await fetch(baseUrl + url, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json;charset=utf-8',
        token: user.token || ''
      },
      body: JSON.stringify(payload)
    })
    if (!response.ok || !response.body) {
      ElMessage.error('AI 流式生成失败')
      return
    }
    await consumeSseResponse(response, event => handleSseEvent(event, answer))
  } catch (e) {
    console.error(e)
    ElMessage.error('AI 流式生成失败')
  }
}

const handleSseEvent = ({ name, data: eventData }, answer) => {
  if (name === 'sources') {
    // sources 事件携带命中的知识片段 JSON，渲染到右侧来源区
    try {
      data.sources = parseSseData(eventData) || []
    } catch (e) {
      data.sources = []
    }
    return
  }
  if (name === 'status') {
    const value = parseSseData(eventData)
    data.status = value.phase || eventData
    return
  }
  if (name === 'review') {
    data.review = parseSseData(eventData)
    return
  }
  if (name === 'handoff') {
    data.handoff = parseSseData(eventData)
    return
  }
  if (name === 'message') {
    // message 是模型增量文本，直接追加
    answer.content += eventData
    scrollToBottom()
    return
  }
  if (name === 'error') {
    const value = parseSseData(eventData)
    ElMessage.error(value.publicMessage || eventData || 'AI 流式生成失败')
  }
}

const scrollToBottom = () => {
  nextTick(() => {
    if (messageListRef.value) {
      messageListRef.value.scrollTop = messageListRef.value.scrollHeight
    }
  })
}

const formatSimilarity = (value) => {
  return Number(value || 0).toFixed(2)
}

const escapeHtml = (value = '') => {
  return String(value)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#39;')
}

const renderInline = (text) => {
  return text
      .replace(/`([^`]+)`/g, '<code>$1</code>')
      .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
      .replace(/\*([^*]+)\*/g, '<em>$1</em>')
}

// 轻量 Markdown 渲染（先转义再替换，防 XSS）：支持标题、有序/无序列表、加粗、行内代码和段落
const renderMarkdown = (value = '') => {
  const source = escapeHtml(value).replace(/\r\n/g, '\n').trim()
  if (!source) {
    return ''
  }
  const html = []
  let listType = null
  const closeList = () => {
    if (listType) {
      html.push('</' + listType + '>')
      listType = null
    }
  }
  source.split('\n').forEach(rawLine => {
    const line = rawLine.trimEnd()
    if (!line.trim()) {
      closeList()
      return
    }
    const heading = line.match(/^(#{1,6})\s+(.*)$/)
    if (heading) {
      closeList()
      const level = heading[1].length
      html.push('<h' + level + '>' + renderInline(heading[2]) + '</h' + level + '>')
      return
    }
    const ordered = line.match(/^\s*\d+\.\s+(.*)$/)
    if (ordered) {
      if (listType !== 'ol') {
        closeList()
        html.push('<ol>')
        listType = 'ol'
      }
      html.push('<li>' + renderInline(ordered[1]) + '</li>')
      return
    }
    const unordered = line.match(/^\s*[-*]\s+(.*)$/)
    if (unordered) {
      if (listType !== 'ul') {
        closeList()
        html.push('<ul>')
        listType = 'ul'
      }
      html.push('<li>' + renderInline(unordered[1]) + '</li>')
      return
    }
    closeList()
    html.push('<p>' + renderInline(line) + '</p>')
  })
  closeList()
  return html.join('')
}

loadModelList()
</script>

<style scoped>
.chat-page {
  height: calc(100vh - 92px);
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.chat-toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  min-height: 62px;
}

.toolbar-field {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #606266;
}

.threshold-field {
  flex: 1;
  max-width: 260px;
}

.threshold-value {
  width: 42px;
  color: #7c3aed;
}

.chat-main {
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: minmax(0, 1fr) 360px;
  gap: 12px;
}

.chat-window,
.source-panel {
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.message-list {
  flex: 1;
  overflow-y: auto;
  padding: 16px;
  background: #f8fafc;
  border: 1px solid #edf0f5;
  border-radius: 6px;
}

.message-row {
  display: flex;
  margin-bottom: 14px;
}

.message-row.user {
  justify-content: flex-end;
}

.message-bubble {
  max-width: 76%;
  padding: 12px 14px;
  border-radius: 8px;
  background: #ffffff;
  border: 1px solid #e8ecf2;
  box-shadow: 0 4px 12px rgba(15, 23, 42, 0.04);
}

.message-row.user .message-bubble {
  color: #ffffff;
  background: #7c3aed;
  border-color: #7c3aed;
}

.message-role {
  margin-bottom: 6px;
  font-size: 12px;
  opacity: 0.72;
}

.message-content {
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
}

/* Markdown 渲染区：关掉 pre-wrap，交给标签本身排版 */
.message-content.markdown-body {
  white-space: normal;
}

.message-content.markdown-body :deep(p) {
  margin: 4px 0;
}

.message-content.markdown-body :deep(p:first-child) {
  margin-top: 0;
}

.message-content.markdown-body :deep(p:last-child) {
  margin-bottom: 0;
}

.message-content.markdown-body :deep(h1),
.message-content.markdown-body :deep(h2),
.message-content.markdown-body :deep(h3),
.message-content.markdown-body :deep(h4),
.message-content.markdown-body :deep(h5),
.message-content.markdown-body :deep(h6) {
  margin: 8px 0 4px;
  font-size: 15px;
  font-weight: 600;
}

.message-content.markdown-body :deep(ul),
.message-content.markdown-body :deep(ol) {
  margin: 4px 0;
  padding-left: 20px;
}

.message-content.markdown-body :deep(li) {
  margin: 2px 0;
}

.message-content.markdown-body :deep(strong) {
  font-weight: 600;
}

.message-content.markdown-body :deep(code) {
  padding: 1px 4px;
  border-radius: 3px;
  background: rgba(0, 0, 0, 0.06);
  font-family: Consolas, Monaco, monospace;
  font-size: 12px;
}

.question-box {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 110px;
  gap: 10px;
  margin-top: 12px;
}

.source-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  padding-bottom: 12px;
  border-bottom: 1px solid #edf0f5;
}

.source-header h3 {
  margin: 0 0 6px;
  font-size: 18px;
}

.source-header p {
  margin: 0;
  color: #8a95a5;
  font-size: 13px;
}

.source-list {
  flex: 1;
  overflow-y: auto;
  padding-top: 12px;
}

.source-card {
  padding: 12px;
  margin-bottom: 10px;
  border: 1px solid #e6ebf2;
  border-radius: 8px;
  background: #fbfdff;
}

.source-meta {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
  color: #606266;
  font-weight: 600;
}

.source-card p {
  margin: 0;
  color: #4b5563;
  line-height: 1.65;
  word-break: break-word;
}
</style>
