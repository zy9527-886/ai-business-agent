<template>
  <div>
    <el-alert
        title="模型调用已迁移到 Python 多智能体服务"
        description="此页仅展示 Python profiles.yaml 暴露的能力。API Key 通过 Python 环境变量配置，不再写入 Java 数据库。"
        type="info"
        :closable="false"
        show-icon
        style="margin-bottom: 12px"
    />
    <div class="card">
      <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:12px">
        <strong>模型 Profiles</strong>
        <el-button type="primary" plain :loading="data.loading" @click="load">刷新能力</el-button>
      </div>
      <el-table stripe :data="data.profiles">
        <el-table-column prop="name" label="Profile" min-width="180" />
        <el-table-column prop="kind" label="类型" width="120">
          <template #default="scope">
            <el-tag :type="scope.row.kind === 'chat' ? 'primary' : 'warning'">
              {{ scope.row.kind === 'chat' ? '聊天模型' : '向量模型' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="model" label="模型" min-width="220" />
        <el-table-column prop="dimension" label="向量维度" width="120">
          <template #default="scope">{{ scope.row.dimension || '-' }}</template>
        </el-table-column>
        <el-table-column prop="enabled" label="运行状态" width="120">
          <template #default="scope">
            <el-tag :type="scope.row.enabled ? 'success' : 'danger'">
              {{ scope.row.enabled ? '凭据已配置' : '凭据未配置' }}
            </el-tag>
          </template>
        </el-table-column>
      </el-table>
    </div>
  </div>
</template>

<script setup>
import { reactive } from 'vue'
import { ElMessage } from 'element-plus'
import request from '@/utils/request.js'

const data = reactive({ profiles: [], loading: false })

const load = () => {
  data.loading = true
  request.get('/knowledgeChat/capabilities').then(res => {
    if (res.code === '200') data.profiles = res.data || []
    else ElMessage.error(res.msg || '模型能力加载失败')
  }).finally(() => {
    data.loading = false
  })
}

load()
</script>
