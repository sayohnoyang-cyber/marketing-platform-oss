<template>
  <span v-if="links.length" class="icp-filing">
    <a v-for="link in links" :key="link.text" :href="link.href" target="_blank" rel="noopener noreferrer">{{ link.text }}</a>
  </span>
</template>

<script>
import { ICP_FILING_NUMBER, ICP_FILING_URL, PUBLIC_SECURITY_FILING_NUMBER } from '@/config/icp.mjs'
import { filingLinks } from '@/lib/icpFiling.mjs'

export default {
  data() {
    return {
      links: filingLinks({
        icpNumber: ICP_FILING_NUMBER,
        icpUrl: ICP_FILING_URL,
        publicSecurityNumber: PUBLIC_SECURITY_FILING_NUMBER
      })
    }
  }
}
</script>

<style scoped>
.icp-filing {
  display: inline-flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 4px 14px;
  color: var(--muted, #6b6a64);
  font-size: 12px;
  line-height: 1.6;
}
.icp-filing a { color: inherit; }
.icp-filing a:hover { text-decoration: underline; text-underline-offset: 3px; }
</style>
