import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import AutoImport from 'unplugin-auto-import/vite'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'

/**
 * Vite 构建配置。
 *
 * - `unplugin-auto-import` / `unplugin-vue-components` 按需自动导入
 *   Element Plus 组件、API 与样式（含 ElMessage / ElMessageBox 等命令式 API）。
 * - 构建产物直接输出到 Spring Boot 静态资源目录（src/main/resources/static），
 *   打包进 jar 后由 Spring Boot 直接提供，单 jar 部署。
 * - 开发模式通过 proxy 把 /api 转发到后端（默认 localhost:8080）。
 */
export default defineConfig({
  plugins: [
    vue(),
    AutoImport({
      resolvers: [ElementPlusResolver()],
      imports: ['vue', 'vue-router', 'pinia']
    }),
    Components({
      resolvers: [ElementPlusResolver()]
    })
  ],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  build: {
    // 产物进后端静态资源目录，清空目录避免残留旧文件
    outDir: '../src/main/resources/static',
    emptyOutDir: true,
    chunkSizeWarningLimit: 1500
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  }
})
