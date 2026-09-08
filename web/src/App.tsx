import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom';
import { AuthProvider, useAuth } from './auth/AuthContext';
import { Layout } from './components/Layout';
import { Login } from './pages/Login';
import { Broker } from './pages/Broker';
import { Orders } from './pages/Orders';
import { Positions } from './pages/Positions';
import { Trades } from './pages/Trades';
import { Risk } from './pages/Risk';
import { System } from './pages/System';
import { Settings } from './pages/Settings';
import { ReactNode } from 'react';

const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });

function Protected({ children }: { children: ReactNode }) {
  const { authenticated } = useAuth();
  return authenticated ? <Layout>{children}</Layout> : <Navigate to="/login" replace />;
}

export function App() {
  return (
    <QueryClientProvider client={qc}>
      <AuthProvider>
        <BrowserRouter>
          <Routes>
            <Route path="/login" element={<Login />} />
            <Route path="/orders" element={<Protected><Orders /></Protected>} />
            <Route path="/positions" element={<Protected><Positions /></Protected>} />
            <Route path="/trades" element={<Protected><Trades /></Protected>} />
            <Route path="/risk" element={<Protected><Risk /></Protected>} />
            <Route path="/broker" element={<Protected><Broker /></Protected>} />
            <Route path="/system" element={<Protected><System /></Protected>} />
            <Route path="/settings" element={<Protected><Settings /></Protected>} />
            <Route path="*" element={<Navigate to="/orders" replace />} />
          </Routes>
        </BrowserRouter>
      </AuthProvider>
    </QueryClientProvider>
  );
}
