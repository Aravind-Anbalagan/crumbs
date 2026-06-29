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

  const toggleTheme = () =>
    setTheme((prev) => (prev === 'dark' ? 'light' : 'dark'));

  const isActive = (path) => location.pathname.startsWith(path);

  return (
    <>
      <style>{`
        * {
          box-sizing: border-box;
        }

        body,
        html {
          margin: 0;
          padding: 0;
          height: 100%;
          width: 100%;
        }

        /* Theme Variables */
        .theme-dark {
          --bg-main: #0b0f19;
          --text-main: #f1f5f9;
          --text-muted: rgba(255, 255, 255, 0.6);

          --glass-bg: rgba(11, 15, 25, 0.82);
          --glass-border: rgba(255, 255, 255, 0.08);

          --brand-gradient: linear-gradient(
            135deg,
            #00f2fe 0%,
            #4facfe 45%,
            #a855f7 100%
          );

          --nav-hover: rgba(79, 172, 254, 0.15);
          --nav-active: #4facfe;

          --danger-bg: rgba(239, 68, 68, 0.1);
          --danger-text: #ef4444;

          --logo-line: #ffffff;
          --logo-fill: rgba(79, 172, 254, 0.08);
          --logo-shadow: rgba(79, 172, 254, 0.6);
        }

        .theme-light {
          --bg-main: #ffffff;
          --text-main: #0f172a;
          --text-muted: rgba(0, 0, 0, 0.6);

          --glass-bg: rgba(255, 255, 255, 0.85);
          --glass-border: rgba(0, 0, 0, 0.08);

          --brand-gradient: linear-gradient(
            135deg,
            #006064 0%,
            #0288d1 45%,
            #7c3aed 100%
          );

          --nav-hover: rgba(2, 136, 209, 0.1);
          --nav-active: #0288d1;

          --danger-bg: rgba(239, 68, 68, 0.1);
          --danger-text: #b91c1c;

          --logo-line: #0f172a;
          --logo-fill: rgba(2, 136, 209, 0.08);
          --logo-shadow: rgba(2, 136, 209, 0.45);
        }

        .layout-canvas {
          height: 100vh;
          display: flex;
          flex-direction: column;
          background: var(--bg-main);
          color: var(--text-main);
          font-family: 'Inter', system-ui, -apple-system, BlinkMacSystemFont, sans-serif;
          transition: background 0.3s ease, color 0.3s ease;
        }

        .glass-topbar {
          height: 60px;
          display: flex;
          justify-content: space-between;
          align-items: center;
          padding: 0 1.5rem;
          background: var(--glass-bg);
          backdrop-filter: blur(14px);
          -webkit-backdrop-filter: blur(14px);
          border-bottom: 1px solid var(--glass-border);
          flex-shrink: 0;
          position: relative;
          z-index: 10;
        }

        .brand {
          font-size: 1.35rem;
          font-weight: 900;
          background: var(--brand-gradient);
          -webkit-background-clip: text;
          background-clip: text;
          -webkit-text-fill-color: transparent;
          text-decoration: none;
          display: flex;
          align-items: center;
          gap: 0.7rem;
          letter-spacing: -0.6px;
          white-space: nowrap;
        }

        .brand svg {
          flex-shrink: 0;
          transition: transform 0.3s ease, filter 0.3s ease;
        }

        .brand:hover svg {
          transform: scale(1.08) rotate(2deg);
          filter: drop-shadow(0 0 10px var(--logo-shadow));
        }

        .center-menu {
          display: flex;
          gap: 0.5rem;
          align-items: center;
        }

        .nav-link {
          text-decoration: none;
          color: var(--text-muted);
          font-size: 0.85rem;
          font-weight: 600;
          padding: 6px 12px;
          border-radius: 6px;
          transition: all 0.2s ease;
          white-space: nowrap;
        }

        .nav-link:hover {
          background: var(--nav-hover);
          color: var(--text-main);
        }

        .nav-link.active {
          background: var(--nav-hover);
          color: var(--nav-active);
        }

        .utility-cluster {
          display: flex;
          gap: 1rem;
          align-items: center;
        }

        .icon-btn {
          background: transparent;
          border: 1px solid var(--glass-border);
          color: var(--text-main);
          width: 32px;
          height: 32px;
          border-radius: 6px;
          cursor: pointer;
          display: flex;
          align-items: center;
          justify-content: center;
          transition: all 0.2s ease;
        }

        .icon-btn:hover {
          background: var(--nav-hover);
          transform: translateY(-1px);
        }

        .logout-btn {
          background: var(--danger-bg);
          color: var(--danger-text);
          border: 1px solid var(--danger-text);
          padding: 4px 12px;
          border-radius: 6px;
          font-weight: 600;
          font-size: 0.8rem;
          cursor: pointer;
          transition: all 0.2s ease;
        }

        .logout-btn:hover {
          transform: translateY(-1px);
          opacity: 0.9;
        }

        .main-content {
          flex: 1;
          display: flex;
          flex-direction: column;
          overflow: hidden;
        }

        @media (max-width: 1100px) {
          .glass-topbar {
            padding: 0 1rem;
          }

          .center-menu {
            gap: 0.25rem;
          }

          .nav-link {
            font-size: 0.78rem;
            padding: 6px 8px;
          }

          .brand {
            font-size: 1.15rem;
          }
        }
      `}</style>

      <div className={`layout-canvas theme-${theme}`}>
        <header className="glass-topbar">
          {/* Rich Vibrant Logo */}
          <Link to="/dashboard" className="brand">
            <svg
              width="40"
              height="40"
              viewBox="0 0 100 100"
              xmlns="http://www.w3.org/2000/svg"
              aria-hidden="true"
            >
              <defs>
                <linearGradient
                  id="richGrad"
                  x1="0%"
                  y1="100%"
                  x2="100%"
                  y2="0%"
                >
                  <stop
                    offset="0%"
                    stopColor={theme === 'dark' ? '#00f2fe' : '#006064'}
                  />
                  <stop
                    offset="45%"
                    stopColor={theme === 'dark' ? '#4facfe' : '#0288d1'}
                  />
                  <stop
                    offset="100%"
                    stopColor={theme === 'dark' ? '#a855f7' : '#7c3aed'}
                  />
                </linearGradient>

                <radialGradient id="innerGlow" cx="50%" cy="45%" r="60%">
                  <stop
                    offset="0%"
                    stopColor={theme === 'dark' ? '#4facfe' : '#0288d1'}
                    stopOpacity="0.35"
                  />
                  <stop
                    offset="100%"
                    stopColor={theme === 'dark' ? '#0b0f19' : '#ffffff'}
                    stopOpacity="0"
                  />
                </radialGradient>

                <filter
                  id="logoGlow"
                  x="-50%"
                  y="-50%"
                  width="200%"
                  height="200%"
                >
                  <feGaussianBlur stdDeviation="3" result="blur" />
                  <feMerge>
                    <feMergeNode in="blur" />
                    <feMergeNode in="SourceGraphic" />
                  </feMerge>
                </filter>
              </defs>

              {/* Outer rich hexagon */}
              <polygon
                points="50,8 86,29 86,71 50,92 14,71 14,29"
                fill="none"
                stroke="url(#richGrad)"
                strokeWidth="6"
                strokeLinejoin="round"
                filter="url(#logoGlow)"
              />

              {/* Inner soft glass fill */}
              <polygon
                points="50,17 78,33 78,67 50,83 22,67 22,33"
                fill="url(#innerGlow)"
                stroke="rgba(255,255,255,0.14)"
                strokeWidth="1"
              />

              {/* Connection line base */}
              <path
                d="M 28 38 L 50 50 L 72 62"
                stroke={theme === 'dark' ? '#ffffff' : '#0f172a'}
                strokeWidth="5"
                strokeLinecap="round"
                strokeLinejoin="round"
                opacity="0.95"
              />

              {/* Vibrant neural nodes */}
              <circle
                cx="50"
                cy="50"
                r="10"
                fill="url(#richGrad)"
                filter="url(#logoGlow)"
              />
              <circle
                cx="28"
                cy="38"
                r="7"
                fill="url(#richGrad)"
                filter="url(#logoGlow)"
              />
              <circle
                cx="72"
                cy="62"
                r="7"
                fill="url(#richGrad)"
                filter="url(#logoGlow)"
              />

              {/* Premium highlight dots */}
              <circle cx="46" cy="46" r="2.2" fill="#ffffff" opacity="0.85" />
              <circle cx="25" cy="35" r="1.8" fill="#ffffff" opacity="0.75" />
              <circle cx="69" cy="59" r="1.8" fill="#ffffff" opacity="0.75" />

              {/* Small orbit accent */}
              <circle
                cx="64"
                cy="35"
                r="3"
                fill={theme === 'dark' ? '#a855f7' : '#7c3aed'}
                opacity="0.95"
              />
            </svg>

            crumbs
          </Link>

          <nav className="center-menu">
            <Link
              to="/dashboard/stock"
              className={`nav-link ${
                isActive('/dashboard/stock') ? 'active' : ''
              }`}
            >
              Stock Analysis
            </Link>

            <Link
              to="/dashboard/premarket"
              className={`nav-link ${
                isActive('/dashboard/premarket') ? 'active' : ''
              }`}
            >
              Pre-Market
            </Link>

            <Link
              to="/dashboard/straddle"
              className={`nav-link ${
                isActive('/dashboard/straddle') ? 'active' : ''
              }`}
            >
              Short Straddle
            </Link>

            <Link
              to="/dashboard/trend"
              className={`nav-link ${
                isActive('/dashboard/trend') ? 'active' : ''
              }`}
            >
              Market Trend
            </Link>

            <Link
              to="/dashboard/orders"
              className={`nav-link ${
                isActive('/dashboard/orders') ? 'active' : ''
              }`}
            >
              Order Book
            </Link>

            <Link
              to="/history"
              className={`nav-link ${isActive('/history') ? 'active' : ''}`}
            >
              Analytics
            </Link>

            <Link
              to="/dashboard/strategy-setup"
              className={`nav-link ${
                isActive('/dashboard/strategy-setup') ? 'active' : ''
              }`}
            >
              ⚙️ Strategy Setup
            </Link>

            <Link
              to="/dashboard/log"
              className={`nav-link ${
                isActive('/dashboard/log') ? 'active' : ''
              }`}
            >
              Logs
            </Link>
          </nav>

          <div className="utility-cluster">
            <button onClick={toggleTheme} className="icon-btn" type="button">
              {theme === 'dark' ? '☀️' : '🌙'}
            </button>

            <button onClick={handleLogout} className="logout-btn" type="button">
              Disconnect
            </button>
          </div>
        </header>

        <main className="main-content">
          <Outlet />
        </main>
      </div>
    </>
  );
}