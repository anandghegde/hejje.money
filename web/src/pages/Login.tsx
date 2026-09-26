import { FormEvent, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { Button, Field } from '../ui';
import '../styles/system.css';

export function Login() {
  const { login } = useAuth();
  const nav = useNavigate();
  const [username, setUsername] = useState('admin');
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');

  async function submit(e: FormEvent) {
    e.preventDefault();
    setError('');
    try { await login(username, password); nav('/orders'); }
    catch { setError('Invalid credentials'); }
  }

  return (
    <main className="login">
      <form onSubmit={submit} className="login-card stack">
        <h1>Hejje</h1>
        <Field label="Username">
          <input aria-label="username" autoComplete="username" value={username} onChange={(e) => setUsername(e.target.value)} placeholder="username" />
        </Field>
        <Field label="Password">
          <input aria-label="password" type="password" autoComplete="current-password" value={password} onChange={(e) => setPassword(e.target.value)} placeholder="password" />
        </Field>
        <Button type="submit" variant="primary">Log in</Button>
        {error && <p className="message message-loss" role="alert">{error}</p>}
      </form>
    </main>
  );
}
