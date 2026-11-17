<template>
  <a v-if="href" class="entry-link" :href="href" target="_blank" rel="noopener noreferrer" :title="`在新标签页打开：${url}`">
    <span><slot>{{ url }}</slot></span><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" aria-hidden="true"><path d="M7 17 17 7M7 7h10v10" /></svg>
  </a>
  <span v-else class="entry-url"><slot>{{ url || '未生成' }}</slot></span>
</template>

<script>
import { webLinkHref } from '@/lib/publicLink.mjs'

export default {
  props: { url: { type: String, default: '' } },
  computed: { href() { return webLinkHref(this.url) } }
}
</script>
