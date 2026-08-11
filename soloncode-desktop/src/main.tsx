import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import App from './App.tsx';
import './index.css';

function preloadEditor() {
  void import('./components/editor/EditorPanel').catch(error => {
    console.warn('[startup] editor preload failed:', error);
  });
}

// Monaco remains outside the initial UI bundle, but is warmed once the first
// screen is idle so the first source file does not pay the full parse cost.
if ('requestIdleCallback' in window) {
  window.requestIdleCallback(preloadEditor, { timeout: 2_000 });
} else {
  window.setTimeout(preloadEditor, 1_000);
}

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
