import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useGoogleLogin } from '@react-oauth/google';

export default function SignIn() {
  const [identifier, setIdentifier] = useState('');
  const [password, setPassword] = useState('');
  const [message, setMessage] = useState('');
  
  const navigate = useNavigate();

  // 1. Google Sign-In Flow
  const handleGoogleSignIn = useGoogleLogin({
    onSuccess: (tokenResponse) => {
      setMessage('⏳ Verifying ...');
      
      // RELATIVE PATH FIX: Works locally and in the cloud!
      fetch('/api/auth/google', {
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
          localStorage.setItem('isLoggedIn', 'true');
          sessionStorage.setItem('isLoggedIn', 'true'); 
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
    onError: () => setMessage('❌ Google Login Popup Window Closed or Failed'),
  });

  // 2. Standard Credential Form Submission Flow
  const handleSubmit = (e) => {
    e.preventDefault();
    setMessage('⏳ Connecting to Spring Boot to Login...');
    
    // RELATIVE PATH FIX: Works locally and in the cloud!
    fetch('/api/auth/signin', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ identifier, password })
    })
    .then(async (res) => {
      const data = await res.json();
      if (!res.ok) throw new Error(data.message || 'Login failed');
      return data;
    })
    .then((data) => {
      if (data.email) {
        localStorage.setItem('userEmail', data.email);
        localStorage.setItem('isLoggedIn', 'true');
        sessionStorage.setItem('isLoggedIn', 'true'); 
        navigate('/dashboard');
      } else {
        setMessage('❌ Login failed. No email returned.');
      }
    })
    .catch((err) => setMessage(`❌ ${err.message}`));
  };

  return (
    <>
      <style>{`
        .glass-canvas {
          margin: 0;
          min-height: 100vh;
          display: flex;
          justify-content: center;
          align-items: center;
          font-family: 'Inter', system-ui, sans-serif;
          background: linear-gradient(-45deg, #1f1c2c, #928DAB, #2c3e50, #3498db);
          background-size: 400% 400%;
          animation: gradientBg 15s ease infinite;
          overflow: hidden;
          position: relative;
        }

        @keyframes gradientBg {
          0% { background-position: 0% 50%; }
          50% { background-position: 100% 50%; }
          100% { background-position: 0% 50%; }
        }

        .orb {
          position: absolute;
          border-radius: 50%;
          filter: blur(80px);
          z-index: 0;
        }
        .orb-1 {
          width: 300px; height: 300px;
          background: rgba(255, 0, 150, 0.4);
          top: 10%; left: 15%;
          animation: float 8s ease-in-out infinite;
        }
        .orb-2 {
          width: 400px; height: 400px;
          background: rgba(0, 212, 255, 0.4);
          bottom: 10%; right: 15%;
          animation: float 12s ease-in-out infinite reverse;
        }

        @keyframes float {
          0% { transform: translateY(0px) scale(1); }
          50% { transform: translateY(-30px) scale(1.1); }
          100% { transform: translateY(0px) scale(1); }
        }

        .glass-card {
          position: relative;
          z-index: 10;
          width: 100%;
          max-width: 400px;
          padding: 3rem 2.5rem;
          background: rgba(255, 255, 255, 0.05);
          backdrop-filter: blur(20px);
          -webkit-backdrop-filter: blur(20px);
          border: 1px solid rgba(255, 255, 255, 0.15);
          border-top: 1px solid rgba(255, 255, 255, 0.3);
          border-left: 1px solid rgba(255, 255, 255, 0.3);
          border-radius: 24px;
          box-shadow: 0 8px 32px 0 rgba(0, 0, 0, 0.3);
          color: #fff;
        }

        .glass-title {
          font-size: 2rem;
          font-weight: 700;
          text-align: center;
          margin: 0 0 0.5rem 0;
          background: linear-gradient(to right, #fff, rgba(255,255,255,0.7));
          -webkit-background-clip: text;
          -webkit-text-fill-color: transparent;
        }

        .glass-subtitle {
          text-align: center;
          color: rgba(255, 255, 255, 0.6);
          font-size: 0.9rem;
          margin-bottom: 2rem;
        }

        .glass-input-group {
          display: flex;
          flex-direction: column;
          gap: 0.5rem;
          margin-bottom: 1.25rem;
        }

        .glass-label {
          font-size: 0.85rem;
          letter-spacing: 0.5px;
          color: rgba(255, 255, 255, 0.8);
          margin-left: 5px;
        }

        .glass-input {
          width: 100%;
          padding: 1rem;
          background: rgba(0, 0, 0, 0.15);
          border: 1px solid rgba(255, 255, 255, 0.1);
          border-radius: 12px;
          color: #fff;
          font-size: 1rem;
          outline: none;
          box-sizing: border-box;
          transition: all 0.3s ease;
        }

        .glass-input::placeholder {
          color: rgba(255, 255, 255, 0.4);
        }

        .glass-input:focus {
          background: rgba(0, 0, 0, 0.25);
          border-color: rgba(255, 255, 255, 0.5);
          box-shadow: 0 0 15px rgba(255, 255, 255, 0.1);
          transform: translateY(-2px);
        }

        .glass-btn-primary {
          width: 100%;
          padding: 1rem;
          margin-top: 1rem;
          background: rgba(255, 255, 255, 0.1);
          border: 1px solid rgba(255, 255, 255, 0.2);
          border-radius: 12px;
          color: #fff;
          font-size: 1rem;
          font-weight: 600;
          cursor: pointer;
          transition: all 0.3s ease;
          position: relative;
          overflow: hidden;
        }

        .glass-btn-primary:hover {
          background: rgba(255, 255, 255, 0.2);
          box-shadow: 0 0 20px rgba(255, 255, 255, 0.2);
          transform: translateY(-2px);
        }

        .glass-divider {
          display: flex;
          align-items: center;
          margin: 2rem 0;
          color: rgba(255, 255, 255, 0.4);
          font-size: 0.85rem;
        }

        .glass-divider::before, .glass-divider::after {
          content: '';
          flex: 1;
          height: 1px;
          background: rgba(255, 255, 255, 0.1);
          margin: 0 1rem;
        }

        .glass-btn-google {
          width: 100%;
          padding: 1rem;
          background: rgba(0, 0, 0, 0.2);
          border: 1px solid rgba(255, 255, 255, 0.1);
          border-radius: 12px;
          color: #fff;
          font-size: 1rem;
          font-weight: 500;
          cursor: pointer;
          display: flex;
          justify-content: center;
          align-items: center;
          gap: 10px;
          transition: all 0.3s ease;
        }

        .glass-btn-google:hover {
          background: rgba(0, 0, 0, 0.4);
          border-color: rgba(255, 255, 255, 0.3);
          transform: translateY(-2px);
        }

        .glass-footer {
          text-align: center;
          margin-top: 2rem;
          font-size: 0.9rem;
          color: rgba(255, 255, 255, 0.6);
        }

        .glass-link {
          color: #fff;
          font-weight: 600;
          text-decoration: none;
          position: relative;
        }
        
        .glass-link::after {
          content: '';
          position: absolute;
          width: 100%;
          transform: scaleX(0);
          height: 1px;
          bottom: -2px;
          left: 0;
          background-color: #fff;
          transform-origin: bottom right;
          transition: transform 0.25s ease-out;
        }
        
        .glass-link:hover::after {
          transform: scaleX(1);
          transform-origin: bottom left;
        }

        .glass-alert {
          background: rgba(255, 100, 100, 0.2);
          border: 1px solid rgba(255, 100, 100, 0.4);
          border-radius: 8px;
          padding: 1rem;
          margin-bottom: 1.5rem;
          text-align: center;
          font-size: 0.9rem;
          backdrop-filter: blur(5px);
        }
      `}</style>

      <div className="glass-canvas">
        <div className="orb orb-1"></div>
        <div className="orb orb-2"></div>

        <div className="glass-card">
          <h2 className="glass-title">Welcome Back</h2>
          <p className="glass-subtitle">Enter your credentials to access your dashboard</p>

          {message && <div className="glass-alert">{message}</div>}

          <form onSubmit={handleSubmit}>
            <div className="glass-input-group">
              <label className="glass-label">Email Address</label>
              <input 
                type="email" 
                required 
                className="glass-input"
                placeholder="name@domain.com"
                value={identifier}
                onChange={(e) => setIdentifier(e.target.value)}
              />
            </div>

            <div className="glass-input-group">
              <label className="glass-label">Password</label>
              <input 
                type="password" 
                required 
                className="glass-input"
                placeholder="••••••••"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
              />
            </div>

            <button type="submit" className="glass-btn-primary">
              Sign In
            </button>
          </form>

          <div className="glass-divider">OR</div>

          <button type="button" onClick={handleGoogleSignIn} className="glass-btn-google">
            <svg viewBox="0 0 24 24" width="20" height="20">
              <path fill="#4285F4" d="M22.56 12.25c0-.78-.07-1.53-.2-2.25H12v4.26h5.92c-.26 1.37-1.04 2.53-2.21 3.31v2.77h3.57c2.08-1.92 3.28-4.74 3.28-8.09z"/>
              <path fill="#34A853" d="M12 23c2.97 0 5.46-.98 7.28-2.66l-3.57-2.77c-.98.66-2.23 1.06-3.71 1.06-2.86 0-5.29-1.93-6.16-4.53H2.18v2.84C3.99 20.53 7.7 23 12 23z"/>
              <path fill="#FBBC05" d="M5.84 14.09c-.22-.66-.35-1.36-.35-2.09s.13-1.43.35-2.09V7.06H2.18C1.43 8.55 1 10.22 1 12s.43 3.45 1.18 4.94l2.85-2.22.81-.63z"/>
              <path fill="#EA4335" d="M12 5.38c1.62 0 3.06.56 4.21 1.64l3.15-3.15C17.45 2.09 14.97 1 12 1 7.7 1 3.99 3.47 2.18 7.06l3.66 2.84c.87-2.6 3.3-4.52 6.16-4.52z"/>
            </svg>
            Continue with Google
          </button>

          <p className="glass-footer">
            Don't have an account?{' '}
            <Link to="/signup" className="glass-link">Sign Up</Link>
          </p>
        </div>
      </div>
    </>
  );
}