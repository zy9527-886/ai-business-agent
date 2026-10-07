export const parseSseData = (value) => {
  try {
    return JSON.parse(value)
  } catch {
    return value
  }
}

export const createSseParser = (onEvent, onMalformed = () => {}) => {
  let buffer = ''

  const parseFrame = (frame) => {
    if (!frame.trim()) return
    let name = 'message'
    let id = null
    const data = []
    let malformed = false
    frame.split('\n').forEach(line => {
      if (!line || line.startsWith(':')) return
      const separator = line.indexOf(':')
      if (separator < 0) {
        malformed = true
        return
      }
      const field = line.slice(0, separator)
      const value = line.slice(separator + 1).replace(/^ /, '')
      if (field === 'event') name = value || 'message'
      else if (field === 'data') data.push(value)
      else if (field === 'id') id = value
    })
    if (malformed || data.length === 0) {
      onMalformed(frame)
      return
    }
    onEvent({ name, data: data.join('\n'), id })
  }

  const drain = () => {
    buffer = buffer.replace(/\r\n/g, '\n').replace(/\r/g, '\n')
    let boundary = buffer.indexOf('\n\n')
    while (boundary >= 0) {
      parseFrame(buffer.slice(0, boundary))
      buffer = buffer.slice(boundary + 2)
      boundary = buffer.indexOf('\n\n')
    }
  }

  return {
    push(chunk) {
      buffer += chunk
      drain()
    },
    finish() {
      drain()
      if (buffer.trim()) parseFrame(buffer)
      buffer = ''
    }
  }
}

export const consumeSseResponse = async (response, onEvent, onMalformed) => {
  if (!response.ok || !response.body) {
    throw new Error(`SSE request failed with status ${response.status}`)
  }
  const parser = createSseParser(onEvent, onMalformed)
  const reader = response.body.getReader()
  const decoder = new TextDecoder('utf-8')
  try {
    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      parser.push(decoder.decode(value, { stream: true }))
    }
    parser.push(decoder.decode())
    parser.finish()
  } finally {
    reader.releaseLock()
  }
}
