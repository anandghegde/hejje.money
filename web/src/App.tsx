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
import { Today } from './pages/Today';
import { Pulse } from './pages/Pulse';
import { Strategies } from './pages/Strategies';
import { StrategyDetail } from './pages/StrategyDetail';
import { Lab } from './pages/Lab';
import { ReviewDetail, Reviews } from './pages/Reviews';
import { Analytics } from './pages/Analytics';
import { Agent } from './pages/Agent';
import { Approvals } from './pages/Approvals';
import { ReactNode } from 'react';

const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });

function Protected({ children }: { children: ReactNode }) {
  const { authenticated, ready } = useAuth();
  if (!ready) return null; // session restore in progress
  return authenticated ? <Layout>{children}</Layout> : <Navigate to="/login" replace />;
}

export function App() {
  return (
    <QueryClientProvider client={qc}>
      <AuthProvider>
        <BrowserRouter>
          <Routes>
            <Route path="/login" element={<Login />} />
            <Route path="/today" element={<Protected><Today /></Protected>} />
            <Route path="/pulse" element={<Protected><Pulse /></Protected>} />
            <Route path="/strategies" element={<Protected><Strategies /></Protected>} />
            <Route path="/strategies/:id" element={<Protected><StrategyDetail /></Protected>} />
            <Route path="/lab" element={<Protected><Lab /></Protected>} />
            <Route path="/reviews" element={<Protected><Reviews /></Protected>} />
            <Route path="/reviews/:id" element={<Protected><ReviewDetail /></Protected>} />
            <Route path="/analytics" element={<Protected><Analytics /></Protected>} />
            <Route path="/agent" element={<Protected><Agent /></Protected>} />
            <Route path="/approvals" element={<Protected><Approvals /></Protected>} />
            <Route path="/orders" element={<Protected><Orders /></Protected>} />
            <Route path="/positions" element={<Protected><Positions /></Protected>} />
            <Route path="/trades" element={<Protected><Trades /></Protected>} />
            <Route path="/risk" element={<Protected><Risk /></Protected>} />
            <Route path="/broker" element={<Protected><Broker /></Protected>} />
            <Route path="/system" element={<Protected><System /></Protected>} />
            <Route path="/settings" element={<Protected><Settings /></Protected>} />
            <Route path="*" element={<Navigate to="/today" replace />} />
          </Routes>
        </BrowserRouter>
      </AuthProvider>
    </QueryClientProvider>
  );
}
