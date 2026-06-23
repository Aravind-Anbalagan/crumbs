import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useGoogleLogin } from '@react-oauth/google';

export default function SignIn() {
  const [identifier, setIdentifier] = useState(''); // This explicitly stores the email now
  const [password, setPassword] = useState('');
  const [message, setMessage] = useState('');
  
  const navigate = useNavigate();

  // Dynamically use the Vite env variable, or fallback to localhost
  // Automatically uses localhost if you run 'npm run dev', otherwise uses relative paths for the integrated build!
const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || (import.meta.env.DEV ? 'http://localhost:8080' : '');

  // 1. Google Sign-In Flow
  const handleGoogleSignIn = useGoogleLogin({
    onSuccess: (tokenResponse) => {
      setMessage('⏳ Verifying with Spring Boot...');
      
      fetch(`${API_BASE_URL}/api/auth/google`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ token: tokenResponse.access_token })
      })
      .then((res) => {
        if (!res.ok) throw new Error('Backend rejected token');
        return res.json();
      })
      .then((data) => {
        if (data.email) {
          localStorage.setItem('userEmail', data.email);
          navigate('/dashboard'); 
        } else {
          setMessage('❌ Backend verification failed.');
        }
      })
      .catch((err) => {
        console.error(err);
        setMessage('❌ Server error. Is your Spring Boot app running?');
      });
    },
    onError: () => {
      setMessage('❌ Google Login Popup Window Closed or Failed');
    },
  });

  // 2. Standard Credential Form Submission Flow (Email Only)
  const handleSubmit = (e) => {
    e.preventDefault();
    setMessage('⏳ Connecting to Spring Boot to Login...');
    
    const payload = { identifier, password };

    fetch(`${API_BASE_URL}/api/auth/signin`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload)
    })
    .then(async (res) => {
      const data = await res.json();
      if (!res.ok) {
        throw new Error(data.message || 'Login failed');
      }
      return data;
    })
    .then((data) => {
      if (data.email) {
        localStorage.setItem('userEmail', data.email);
        navigate('/dashboard');
      } else {
        setMessage('❌ Login failed. No email returned.');
      }
    })
    .catch((err) => {
      console.error(err);
      setMessage(`❌ ${err.message}`);
    });
  };

  return (
    <div className="auth-container" style={styles.container}>
      <div style={styles.card}>
        <h2 style={styles.title}>Welcome Back</h2>
        
        {message && <div style={styles.alert}>{message}</div>}

        <form onSubmit={handleSubmit} style={styles.form}>
          <div style={styles.inputGroup}>
            <label style={styles.label}>Email Address</label>
            <input 
              type="email" // Changed to HTML5 email validation
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
            Sign In
          </button>
        </form>

        <div style={styles.dividerContainer}>
          <div style={styles.dividerLine}></div>
          <span style={styles.dividerText}>or</span>
          <div style={styles.dividerLine}></div>
        </div>

        {/* Custom Look Google Sign-In Trigger Button */}
        <button type="button" onClick={handleGoogleSignIn} style={styles.googleButton}>
          <svg style={styles.googleIcon} viewBox="0 0 24 24" width="18" height="18">
            <path fill="#4285F4" d="M22.56 12.25c0-.78-.07-1.53-.2-2.25H12v4.26h5.92c-.26 1.37-1.04 2.53-2.21 3.31v2.77h3.57c2.08-1.92 3.28-4.74 3.28-8.09z"/>
            <path fill="#34A853" d="M12 23c2.97 0 5.46-.98 7.28-2.66l-3.57-2.77c-.98.66-2.23 1.06-3.71 1.06-2.86 0-5.29-1.93-6.16-4.53H2.18v2.84C3.99 20.53 7.7 23 12 23z"/>
            <path fill="#FBBC05" d="M5.84 14.09c-.22-.66-.35-1.36-.35-2.09s.13-1.43.35-2.09V7.06H2.18C1.43 8.55 1 10.22 1 12s.43 3.45 1.18 4.94l2.85-2.22.81-.63z"/>
            <path fill="#EA4335" d="M12 5.38c1.62 0 3.06.56 4.21 1.64l3.15-3.15C17.45 2.09 14.97 1 12 1 7.7 1 3.99 3.47 2.18 7.06l3.66 2.84c.87-2.6 3.3-4.52 6.16-4.52z"/>
          </svg>
          Continue with Google
        </button>

        <p style={styles.switchText}>
          Don't have an account?{' '}
          <Link to="/signup" style={styles.switchLink}>
            Sign Up
          </Link>
        </p>
      </div>
    </div>
  );
}

// Layout configurations
const styles = {
  container: { display: 'flex', justifyContent: 'center', alignItems: 'center', minHeight: '100vh', backgroundColor: '#f3f4f6', fontFamily: 'system-ui, sans-serif' },
  card: { backgroundColor: '#ffffff', padding: '2.5rem', borderRadius: '12px', boxShadow: '0 4px 6px -1px rgba(0,0,0,0.1)', width: '100%', maxWidth: '400px' },
  title: { fontSize: '1.75rem', fontWeight: 'bold', textAlign: 'center', marginBottom: '1.5rem', color: '#1f2937' },
  form: { display: 'flex', flexDirection: 'column', gap: '1.25rem' },
  inputGroup: { display: 'flex', flexDirection: 'column', gap: '0.5rem' },
  label: { fontSize: '0.875rem', fontWeight: '500', color: '#4b5563' },
  input: { padding: '0.75rem', borderRadius: '6px', border: '1px solid #d1d5db', fontSize: '1rem', outline: 'none' },
  primaryButton: { backgroundColor: '#2563eb', color: '#ffffff', padding: '0.75rem', borderRadius: '6px', border: 'none', fontSize: '1rem', fontWeight: '600', cursor: 'pointer', marginTop: '0.5rem' },
  dividerContainer: { display: 'flex', alignItems: 'center', margin: '1.5rem 0' },
  dividerLine: { flex: 1, height: '1px', backgroundColor: '#e5e7eb' },
  dividerText: { margin: '0 1rem', color: '#9ca3af', fontSize: '0.875rem' },
  googleButton: { display: 'flex', alignItems: 'center', justifyContent: 'center', gap: '0.75rem', backgroundColor: '#ffffff', color: '#374151', border: '1px solid #d1d5db', padding: '0.75rem', borderRadius: '6px', fontSize: '1rem', fontWeight: '500', cursor: 'pointer', width: '100%' },
  googleIcon: { display: 'block' },
  switchText: { textAlign: 'center', marginTop: '1.5rem', fontSize: '0.875rem', color: '#4b5563' },
  switchLink: { color: '#2563eb', fontWeight: '600', cursor: 'pointer', textDecoration: 'underline' },
  alert: { padding: '0.75rem', borderRadius: '6px', backgroundColor: '#eff6ff', border: '1px solid #bfdbfe', color: '#1e40af', fontSize: '0.875rem', marginBottom: '1rem', textAlign: 'center' }
};