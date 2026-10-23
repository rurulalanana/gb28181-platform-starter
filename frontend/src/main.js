import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'
// 【必须手动引入】ElMessage / ElMessageBox 是 JS 调用式组件，模板里不出现，
// unplugin-vue-components 的 ElementPlusResolver 只给模板中实际用到的标签补样式，
// 因此不会为这两个注入 CSS。
//
// 踩过的坑：缺这两个 import 时 toast 依然会插进 DOM（DOM 里有 .el-message 节点、
// 文案也对），但没有定位、没有背景、没有淡入动画 —— 肉眼完全看不到，
// 表现为「点了保存/删除却毫无反馈」。别删这两行。
import 'element-plus/es/components/message/style/css'
import 'element-plus/es/components/message-box/style/css'
import router from './router'
import App from './App.vue'
import './styles/main.css'

const app = createApp(App)

// 注册全部图标组件（模板中直接写 <el-icon><VideoCamera /></el-icon>）
for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(key, component)
}

app.use(createPinia())
app.use(router)
app.use(ElementPlus, { locale: zhCn })
app.mount('#app')
