import { fileURLToPath, URL } from 'node:url'
import { defineConfig, loadEnv } from 'vite'
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
 * - 开发模式通过 proxy 把 /api 与 /rtp 转发到后端 / 媒体服务。
 *   目标地址可在 `frontend/.env.development` 里改：
 *
 *   ```ini
 *   VITE_API_TARGET=http://160.202.254.65:48088   # 后端
 *   VITE_MEDIA_TARGET=http://160.202.254.65:48089 # ZLM 媒体服务
 *   ```
 *
 *   留空则默认 localhost（后端 8080 / 媒体 8081）。
 */
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const apiTarget = env.VITE_API_TARGET || 'http://localhost:8080'
  const mediaTarget = env.VITE_MEDIA_TARGET || 'http://localhost:8081'

  return {
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
      host: true,
      proxy: {
        // 后端接口
        '/api': {
          target: apiTarget,
          changeOrigin: true
        },
        // 媒体服务：ws-flv / http-flv（ws: true 让 WebSocket 升级也走代理）
        '/rtp': {
          target: mediaTarget,
          changeOrigin: true,
          ws: true
        },
        // HLS 切片与其它 ZLM 静态资源
        '/index': {
          target: mediaTarget,
          changeOrigin: true
        }
        // 注意：/jessibuca 不代理 —— 播放器文件在 public/jessibuca/ 下，
        // 由 vite 直接提供（构建后进 jar 的 static/jessibuca/）。
      }
    }
  }
})
