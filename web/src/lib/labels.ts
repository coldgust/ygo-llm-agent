/** 对局状态、规划器子类型的展示词（原始枚举值只用于逻辑判断，不上屏）。 */
export const STATUS_LABELS: Record<string, string> = {
  STARTING: '准备中', RUNNING: '进行中', STOPPING: '停止中',
  STOPPED: '已停止', FINISHED: '已结束', FAILED: '异常结束',
}

export function statusLabel(s?: string | null): string {
  return (s && STATUS_LABELS[s]) || s || '—'
}

/** 对局终态：终态后解除按钮、停止订阅、允许触发入库询问。 */
export const TERMINAL_STATUSES = ['FINISHED', 'STOPPED', 'FAILED']
