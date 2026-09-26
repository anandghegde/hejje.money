// tokens and base styles first, so component and page stylesheets (imported through App) come after them in the cascade
import './styles/tokens.css';
import './styles/base.css';
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { App } from './App';
import { applyTheme, storedTheme } from './lib/theme';

applyTheme(storedTheme()); // before the first render, so a forced theme never flashes the OS one

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
