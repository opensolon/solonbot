<script setup lang="ts">
import { ref } from 'vue';

interface Emits {
  (e: 'send', message: string): void;
}

const emit = defineEmits<Emits>();
const userInput = ref('');

async function sendMessage() {
  if (!userInput.value.trim()) return;
  emit('send', userInput.value);
  userInput.value = '';
}

function handleKeyPress(event: KeyboardEvent) {
  if (event.key === 'Enter' && !event.shiftKey) {
    event.preventDefault();
    sendMessage();
  }
}
</script>

<template>
  <div class="input-area">
    <div class="input-container">
      <textarea
        v-model="userInput"
        class="message-input"
        placeholder="输入你的问题..."
        rows="1"
        @keydown="handleKeyPress"
      ></textarea>
      <button
        class="send-button"
        @click="sendMessage"
        :disabled="!userInput.trim()"
      >
        <svg width="20" height="20" viewBox="0 0 20 20" fill="none">
          <path
            d="M2.5 10L17.5 3L10 17.5M2.5 10L10 17.5M2.5 10H10"
            stroke="currentColor"
            stroke-width="1.5"
            stroke-linecap="round"
            stroke-linejoin="round"
          />
        </svg>
      </button>
    </div>
    <div class="input-footer">
      <span class="input-hint">Enter 发送，Shift + Enter 换行</span>
    </div>
  </div>
</template>

<style scoped>
.input-area {
  padding: 16px 24px;
  border-top: 1px solid var(--cb-vscode-panel-border);
  background-color: var(--cb-vscode-editor-background);
  transition: background-color 0.3s, border-color 0.3s;
}

.input-container {
  display: flex;
  gap: 12px;
  align-items: flex-end;
  background-color: var(--cb-vscode-input-background);
  border: 1px solid var(--cb-vscode-widget-border);
  border-radius: 8px;
  padding: 8px;
  transition: border-color 0.2s, background-color 0.3s;
}

.input-container:focus-within {
  border-color: var(--cb-accent);
}

.message-input {
  flex: 1;
  background: transparent;
  border: none;
  color: var(--cb-text-primary);
  font-size: 14px;
  line-height: 1.5;
  resize: none;
  min-height: 24px;
  max-height: 200px;
  overflow-y: auto;
  font-family: inherit;
  transition: color 0.3s;
}

.message-input:focus {
  outline: none;
}

.message-input::placeholder {
  color: var(--cb-text-secondary);
}

.send-button {
  width: 32px;
  height: 32px;
  background-color: var(--cb-accent);
  color: white;
  border: none;
  border-radius: 6px;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: background-color 0.2s;
  flex-shrink: 0;
}

.send-button:hover:not(:disabled) {
  background-color: var(--cb-accent-light);
}

.send-button:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.input-footer {
  margin-top: 8px;
  text-align: center;
}

.input-hint {
  font-size: 11px;
  color: var(--cb-text-secondary);
}
</style>
