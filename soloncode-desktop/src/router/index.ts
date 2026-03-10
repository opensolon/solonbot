import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router';
import ChatPage from '../views/ChatPage.vue';

const routes: RouteRecordRaw[] = [
  {
    path: '/',
    name: 'Chat',
    component: ChatPage
  }
];

const router = createRouter({
  history: createWebHistory(),
  routes
});

export default router;
