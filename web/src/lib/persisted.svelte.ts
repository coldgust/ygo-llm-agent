/**
 * localStorage 持久化的 $state：初始值从 localStorage 读，缺失/解析失败回退 fallback；赋值即写回。
 * 存原始字符串（与既有键 ygo.deckStrategy / ygo.archivePolicy 同格式），number 字段读回时 Number() 还原。
 */
export function persisted<T extends string | number>(
  key: string,
  fallback: T,
  parse?: (raw: string) => T | undefined,
) {
  const saved = localStorage.getItem(key)
  let initial: T = fallback
  if (saved != null) {
    if (parse) {
      const parsed = parse(saved)
      if (parsed !== undefined) initial = parsed
    } else if (typeof fallback === 'number') {
      const n = Number(saved)
      if (!Number.isNaN(n)) initial = n as T
    } else {
      initial = saved as T
    }
  }
  let value = $state(initial)
  return {
    get current(): T {
      return value
    },
    set current(v: T) {
      value = v
      localStorage.setItem(key, String(v))
    },
  }
}
