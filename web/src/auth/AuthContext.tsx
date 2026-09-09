import { createContext, useContext, useEffect, useMemo, useState, ReactNode } from 'react';
import { getAccessToken, login as apiLogin, logout as apiLogout, setUnauthorizedHandler, tryRefresh } from '../api/client';

interface AuthState {
  /** false until the session restore attempt (refresh cookie) has finished */
  ready: boolean;
  authenticated: boolean;
  login: (username: string, password: string) => Promise<void>;
  logout: () => Promise<void>;
}

const AuthContext = createContext<AuthState | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [authenticated, setAuthenticated] = useState<boolean>(!!getAccessToken());
  const [ready, setReady] = useState<boolean>(!!getAccessToken());

  useEffect(() => {
    setUnauthorizedHandler(() => setAuthenticated(false));
    if (!getAccessToken()) {
      tryRefresh().then((ok) => { setAuthenticated(ok); setReady(true); });
    }
  }, []);

  const value = useMemo<AuthState>(() => ({
    ready,
    authenticated,
    login: async (u, p) => { await apiLogin(u, p); setAuthenticated(true); },
    logout: async () => { await apiLogout(); setAuthenticated(false); },
  }), [authenticated, ready]);

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth outside AuthProvider');
  return ctx;
}
