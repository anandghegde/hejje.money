import { FormEvent, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';

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
    <form onSubmit={submit} style={{ maxWidth: 320, margin: '80px auto', fontFamily: 'system-ui' }}>
      <h1>Hejje</h1>
      <input aria-label="username" value={username} onChange={(e) => setUsername(e.target.value)} placeholder="username" style={{ display: 'block', width: '100%', margin: '8px 0' }} />
      <input aria-label="password" type="password" value={password} onChange={(e) => setPassword(e.target.value)} placeholder="password" style={{ display: 'block', width: '100%', margin: '8px 0' }} />
      <button type="submit">Log in</button>
      {error && <p style={{ color: '#c0392b' }} role="alert">{error}</p>}
    </form>
  );
}
