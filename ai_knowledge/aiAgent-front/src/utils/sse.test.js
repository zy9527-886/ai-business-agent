import { describe, expect, it, vi } from 'vitest'
import { createSseParser, parseSseData } from './sse.js'

describe('SSE parser', () => {
  it('handles split chunks, CRLF, multiline data and all workflow event names', () => {
    const events = []
    const parser = createSseParser(event => events.push(event))
    parser.push('event: meta\r\ndata: {"requestId":"r1"}\r\n\r\nevent: stat')
    parser.push('us\ndata: {"phase":"routing"}\n\nevent: sources\ndata: [{"id":1},\ndata: {"id":2}]\n\n')
    ;['review', 'handoff', 'error', 'message', 'done'].forEach(name => {
      parser.push(`event: ${name}\ndata: ${name}\n\n`)
    })
    parser.finish()

    expect(events.map(item => item.name)).toEqual([
      'meta', 'status', 'sources', 'review', 'handoff', 'error', 'message', 'done'
    ])
    expect(events[2].data).toBe('[{"id":1},\n{"id":2}]')
  })

  it('isolates malformed frames and leaves malformed JSON as text', () => {
    const onMalformed = vi.fn()
    const events = []
    const parser = createSseParser(event => events.push(event), onMalformed)
    parser.push('broken line\n\nevent: message\ndata: {bad json}\n\n')
    parser.finish()

    expect(onMalformed).toHaveBeenCalledOnce()
    expect(events).toEqual([{ name: 'message', data: '{bad json}', id: null }])
    expect(parseSseData(events[0].data)).toBe('{bad json}')
  })

  it('does not treat CRLF split across chunks as an empty frame', () => {
    const events = []
    const malformed = vi.fn()
    const parser = createSseParser(event => events.push(event), malformed)
    parser.push('event: message\r')
    parser.push('\ndata: hello\r')
    parser.push('\n\r')
    parser.push('\n')
    parser.finish()

    expect(events).toEqual([{ name: 'message', data: 'hello', id: null }])
    expect(malformed).not.toHaveBeenCalled()
  })
})
