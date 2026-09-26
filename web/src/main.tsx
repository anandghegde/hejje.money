import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { App } from './App';
import { applyTheme, storedTheme } from './lib/theme';
import './styles/tokens.css';
import './styles/base.css';

applyTheme(storedTheme()); // before the first render, so a forced theme never flashes the OS one

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
