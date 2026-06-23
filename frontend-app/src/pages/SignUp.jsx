import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';

export default function SignUp() {
  const [name, setName] = useState('');
  const [identifier, setIdentifier] = useState(''); // Stores email
  const [password, setPassword] = useState('');
  const [message, setMessage] = useState('');
  const navigate = useNavigate();

  const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || (import.meta.env.DEV ? 'http://localhost:8080' : '');

  const handleSignUpSubmit = (e) => {
    e.preventDefault();
    setMessage('⏳ Creating account in Spring Boot...');

    const payload = { name, identifier, password };

    fetch(`${API_BASE_URL}/api/auth/signup`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload)
    })
    .then(async (res) => {
      const data = await res.json();
      if (!res.ok) throw new Error(data.message || 'Registration failed');
      return data;
    })
    .then((data) => {
      setMessage(`✅ ${data.message}`);
      // Redirect to signin page after a small delay so they can read the message
      setTimeout(() => {
        navigate('/signin');
      }, 1500);
    })
    .catch((err) => {
      console.error(err);
      setMessage(`❌ ${err.message}`);
    });
  };

  return (
    <div style={styles.container}>
      <div style={styles.card}>
        <h2 style={styles.title}>Create Account</h2>
        
        {message && <div style={styles.alert}>{message}</div>}

        <form onSubmit={handleSignUpSubmit} style={styles.form}>
          <div style={styles.inputGroup}>
            <label style={styles.label}>Full Name</label>
            <input 
              type="text" 
              required 
              style={styles.input} 
              placeholder="Your Name"
              value={name}
              onChange={(e) => setName(e.target.value)}
            />
          </div>

          <div style={styles.inputGroup}>
            <label style={styles.label}>Email Address</label>
            <input 
              type="email" 
              required 
              style={styles.input} 
              placeholder="name@domain.com"
              value={identifier}
              onChange={(e) => setIdentifier(e.target.value)}
            />
          </div>

          <div style={styles.inputGroup}>
            <label style={styles.label}>Password</label>
            <input 
              type="password" 
              required 
              style={styles.input} 
              placeholder="••••••••"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
            />
          </div>

          <button type="submit" style={styles.primaryButton}>
            Sign Up
          </button>
        </form>

        <p style={styles.switchText}>
          Already have an account?{' '}
          <Link to="/signin" style={styles.switchLink}>
            Sign In
          </Link>
        </p>
      </div>
    </div>
  );
}

const styles = {
  container: { display: 'flex', justifyContent: 'center', alignItems: 'center', minHeight: '100vh', backgroundColor: '#f3f4f6', fontFamily: 'system-ui, sans-serif' },
  card: { backgroundColor: '#ffffff', padding: '2.5rem', borderRadius: '12px', boxShadow: '0 4px 6px -1px rgba(0,0,0,0.1)', width: '100%', maxWidth: '400px' },
  title: { fontSize: '1.75rem', fontWeight: 'bold', textAlign: 'center', marginBottom: '1.5rem', color: '#1f2937' },
  form: { display: 'flex', flexDirection: 'column', gap: '1.25rem' },
  inputGroup: { display: 'flex', flexDirection: 'column', gap: '0.5rem' },
  label: { fontSize: '0.875rem', fontWeight: '500', color: '#4b5563' },
  input: { padding: '0.75rem', borderRadius: '6px', border: '1px solid #d1d5db', fontSize: '1rem', outline: 'none' },
  primaryButton: { backgroundColor: '#10b981', color: '#ffffff', padding: '0.75rem', borderRadius: '6px', border: 'none', fontSize: '1rem', fontWeight: '600', cursor: 'pointer', marginTop: '0.5rem' },
  switchText: { textAlign: 'center', marginTop: '1.5rem', fontSize: '0.875rem', color: '#4b5563' },
  switchLink: { color: '#10b981', fontWeight: '600', textDecoration: 'underline' },
  alert: { padding: '0.75rem', borderRadius: '6px', backgroundColor: '#eff6ff', border: '1px solid #bfdbfe', color: '#1e40af', fontSize: '0.875rem', marginBottom: '1rem', textAlign: 'center' }
};