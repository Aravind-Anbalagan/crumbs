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

  // Helper to determine alert color based on success or error
  const isSuccess = message.startsWith('✅');

  return (
    <>
      {/* Heavy CSS Injection for Glassmorphism & Animations */}
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
          border-radius: 8px;
          padding: 1rem;
          margin-bottom: 1.5rem;
          text-align: center;
          font-size: 0.9rem;
          backdrop-filter: blur(5px);
        }

        .glass-alert-error {
          background: rgba(255, 100, 100, 0.2);
          border: 1px solid rgba(255, 100, 100, 0.4);
        }

        .glass-alert-success {
          background: rgba(100, 255, 100, 0.2);
          border: 1px solid rgba(100, 255, 100, 0.4);
        }
      `}</style>

      <div className="glass-canvas">
        {/* Decorative background orbs */}
        <div className="orb orb-1"></div>
        <div className="orb orb-2"></div>

        <div className="glass-card">
          <h2 className="glass-title">Create Account</h2>
          <p className="glass-subtitle">Join us and start your journey</p>
          
          {message && (
            <div className={`glass-alert ${isSuccess ? 'glass-alert-success' : 'glass-alert-error'}`}>
              {message}
            </div>
          )}

          <form onSubmit={handleSignUpSubmit}>
            <div className="glass-input-group">
              <label className="glass-label">Full Name</label>
              <input 
                type="text" 
                required 
                className="glass-input"
                placeholder="Your Name"
                value={name}
                onChange={(e) => setName(e.target.value)}
              />
            </div>

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
              Sign Up
            </button>
          </form>

          <p className="glass-footer">
            Already have an account?{' '}
            <Link to="/signin" className="glass-link">Sign In</Link>
          </p>
        </div>
      </div>
    </>
  );
}