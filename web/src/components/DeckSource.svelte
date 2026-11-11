<script lang="ts">
  /**
   * 卡组来源一行：路径输入 + 「从文件载入」。
   * 浏览器拿不到所选文件的真实路径，只能读内容——文件载入与「粘贴 ydk」走同一通路（deckYdk），
   * 后端要求 deckYdk 与 deckPath 必须且只能提供一个，故载入文件会清空路径。
   */
  let {
    deckPath = $bindable(''),
    deckYdk = $bindable(''),
    label = '卡组路径',
    size = 30,
    placeholder = '/path/to/卡组.ydk',
  }: {
    deckPath?: string
    deckYdk?: string
    label?: string
    size?: number
    placeholder?: string
  } = $props()

  let fileInput: HTMLInputElement | null = $state(null)
  let fileName = $state('')
  let loadError = $state('')

  async function loadFile(e: Event) {
    const input = e.target as HTMLInputElement
    const file = input.files?.[0]
    input.value = '' // 允许再次选择同一个文件
    if (!file) return
    loadError = ''
    try {
      deckYdk = await file.text()
      deckPath = ''
      fileName = file.name
    } catch (err: any) {
      loadError = `读取 ${file.name} 失败：${String(err.message ?? err)}`
    }
  }
</script>

<label>{label} <input bind:value={deckPath} {size} {placeholder} /></label>
<button type="button" class="filebtn" onclick={() => fileInput?.click()}>从文件载入</button>
<input type="file" accept=".ydk,.txt" hidden bind:this={fileInput} onchange={loadFile} />
{#if fileName}<span class="picked muted">已载入 {fileName}</span>{/if}
{#if loadError}<span class="error">{loadError}</span>{/if}

<style>
  button.filebtn {
    background: #141821;
    color: #7aa2f7;
    border: 1px solid #2f4470;
    border-radius: 6px;
    padding: 6px 10px;
    cursor: pointer;
    font-family: inherit;
    font-size: 13px;
  }
  button.filebtn:hover {
    border-color: #7aa2f7;
  }
  .picked {
    font-size: 11px;
  }
</style>
