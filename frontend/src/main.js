import { createApp } from 'vue'
import { createPinia } from 'pinia'
import { registerElementPlusUser } from './plugins/register-element-plus-user.js'
import './style.css'
import App from './App.vue'
import router from './router'

const app = createApp(App)
registerElementPlusUser(app)
app.use(createPinia())
app.use(router)
app.mount('#app')
