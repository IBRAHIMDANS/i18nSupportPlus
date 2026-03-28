import {StrictMode, Suspense} from 'react'
import { createRoot } from 'react-dom/client'
import './index.css'
import App from './App.tsx'
import './i18n'; // Initialize i18next before rendering

createRoot(document.getElementById('root')!).render(
  <StrictMode>
      <Suspense fallback={<div>Loading...</div>}>
          <App />
      </Suspense>
  </StrictMode>,
)
