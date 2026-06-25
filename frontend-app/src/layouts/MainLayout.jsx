import { Outlet, Link, useNavigate, useLocation } from 'react-router-dom';
import { useState } from 'react';

export default function MainLayout() {
  const navigate = useNavigate();
  const location = useLocation();
  const [theme, setTheme] = useState('dark');

  const handleLogout = () => {
    localStorage.removeItem('userEmail');
    localStorage.removeItem('isLoggedIn');
    sessionStorage.removeItem('isLoggedIn');
    navigate('/signin');
  };

  const toggleTheme = () => setTheme(prev => prev === 'dark' ? 'light' : 'dark');

  const isActive = (path) => location.pathname.startsWith(path);

  return (
    <>
      <style>{`
        /* Reset & Base */
        * { box-sizing: border-box; }
        body, html { margin: 0; padding: 0; height: 100%; width: 100%; }

        .theme-dark {
          --bg-main: #0b0f19; --text-main: #f1f5f9; --text-muted: rgba(255, 255, 255, 0.6);
          --glass-bg: rgba(11, 15, 25, 0.8); --glass-border: rgba(255, 255, 255, 0.08);
          --brand-gradient: linear-gradient(to right, #00f2fe, #4facfe);
          --nav-hover: rgba(79, 172, 254, 0.15); --nav-active: #4facfe;
          --danger-bg: rgba(239, 68, 68, 0.1); --danger-text: #ef4444;
        }

        .layout-canvas {
          height: 100vh; /* Fixed height for the entire viewport */
          display: flex;
          flex-direction: column;
          background: var(--bg-main);
          color: var(--text-main);
          font-family: 'Inter', system-ui, sans-serif;
        }

        .glass-topbar {
          height: 60px;
          display: flex; justify-content: space-between; align-items: center; 
          padding: 0 1.5rem; background: var(--glass-bg);
          backdrop-filter: blur(12px); border-bottom: 1px solid var(--glass-border);
          flex-shrink: 0; /* Prevents topbar from being crushed */
        }

        .brand {
          font-size: 1.2rem; font-weight: 800; background: var(--brand-gradient);
          -webkit-background-clip: text; -webkit-text-fill-color: transparent; text-decoration: none;
        }

        .center-menu { display: flex; gap: 0.5rem; }
        .nav-link {
          text-decoration: none; color: var(--text-muted); font-size: 0.85rem;
          font-weight: 600; padding: 6px 12px; border-radius: 6px; transition: all 0.2s;
        }
        .nav-link:hover { background: var(--nav-hover); color: var(--text-main); }
        .nav-link.active { background: var(--nav-hover); color: var(--nav-active); }
        
        .utility-cluster { display: flex; gap: 1rem; align-items: center; }
        .icon-btn { background: transparent; border: 1px solid var(--glass-border); color: var(--text-main); width: 32px; height: 32px; border-radius: 6px; cursor: pointer; }
        .logout-btn { background: var(--danger-bg); color: var(--danger-text); border: 1px solid var(--danger-text); padding: 4px 12px; border-radius: 6px; font-weight: 600; font-size: 0.8rem; cursor: pointer; }
        
        .main-content {
          flex: 1; /* Occupies all remaining space */
          display: flex;
          flex-direction: column;
          overflow: hidden; /* Important for iframe containment */
        }
      `}</style>

      <div className={`layout-canvas theme-${theme}`}>
        <header className="glass-topbar">
          <Link to="/" className="brand">🍞 Crumbs</Link>
          
          <nav className="center-menu">
            <Link to="/dashboard/stock" className={`nav-link ${isActive('/dashboard/stock') ? 'active' : ''}`}>Stock Analysis</Link>
            <Link to="/dashboard/premarket" className={`nav-link ${isActive('/dashboard/premarket') ? 'active' : ''}`}>Pre-Market</Link>
            <Link to="/dashboard/straddle" className={`nav-link ${isActive('/dashboard/straddle') ? 'active' : ''}`}>Short Straddle</Link>
            <Link to="/dashboard/trend" className={`nav-link ${isActive('/dashboard/trend') ? 'active' : ''}`}>Market Trend</Link>
            <Link to="/dashboard/orders" className={`nav-link ${isActive('/dashboard/orders') ? 'active' : ''}`}>Order Book</Link>
            <Link to="/dashboard/log" className={`nav-link ${isActive('/dashboard/log') ? 'active' : ''}`}>Logs</Link>
          </nav>

          <div className="utility-cluster">
            <button onClick={toggleTheme} className="icon-btn">{theme === 'dark' ? '☀️' : '🌙'}</button>
            <button onClick={handleLogout} className="logout-btn">Disconnect</button>
          </div>
        </header>

        <main className="main-content">
          <Outlet />
        </main>
      </div>
    </>
  );
}