import { createContext, useContext, useEffect, useMemo, useState, ReactNode } from 'react';
import { getAccessToken, login as apiLogin, logout as apiLogout, setUnauthorizedHandler } from '../api/client';

interface AuthState {
  authenticated: boolean;
  login: (username: string, password: string) => Promise<void>;
  logout: () => Promise<void>;
}

const AuthContext = createContext<AuthState | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [authenticated, setAuthenticated] = useState<boolean>(!!getAccessToken());

  useEffect(() => {
    setUnauthorizedHandler(() => setAuthenticated(false));
  }, []);

  const value = useMemo<AuthState>(() => ({
    authenticated,
    login: async (u, p) => { await apiLogin(u, p); setAuthenticated(true); },
    logout: async () => { await apiLogout(); setAuthenticated(false); },
  }), [authenticated]);

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth outside AuthProvider');
  return ctx;
}
