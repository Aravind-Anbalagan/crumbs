import { Outlet, Link, useNavigate, useLocation } from 'react-router-dom';
import { useState } from 'react';

export default function MainLayout() {
  const navigate = useNavigate();
  const location = useLocation();
  const [theme, setTheme] = useState('dark');
  const [logoClicked, setLogoClicked] = useState(false);

  const handleLogout = () => {
    localStorage.removeItem('userEmail');
    localStorage.removeItem('isLoggedIn');
    sessionStorage.removeItem('isLoggedIn');
    navigate('/signin');
  };

  const toggleTheme = () =>
    setTheme((prev) => (prev === 'dark' ? 'light' : 'dark'));

  const isActive = (path) => location.pathname.startsWith(path);

  const handleLogoClick = () => {
    setLogoClicked(true);

    setTimeout(() => {
      setLogoClicked(false);
    }, 450);
  };

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
          position: relative;
          font-size: 1.35rem;
          font-weight: 900;
          background: var(--brand-gradient);
          background-size: 220% 220%;
          -webkit-background-clip: text;
          background-clip: text;
          -webkit-text-fill-color: transparent;
          text-decoration: none;
          display: flex;
          align-items: center;
          gap: 0.7rem;
          letter-spacing: -0.6px;
          white-space: nowrap;
          transition: transform 0.3s ease, letter-spacing 0.3s ease;
        }

        .brand svg {
          flex-shrink: 0;
          transition: transform 0.35s ease, filter 0.35s ease;
        }

        .brand:hover {
          animation: brandTextFlow 1.8s linear infinite;
          transform: translateY(-1px);
        }

        .brand:hover svg {
          transform: scale(1.1) rotate(4deg);
          filter: drop-shadow(0 0 14px var(--logo-shadow));
        }

        .brand.logo-clicked {
          animation: logoTextPop 0.45s ease;
        }

        .brand.logo-clicked svg {
          animation: logoClickPop 0.45s ease;
        }

        @keyframes brandTextFlow {
          0% {
            background-position: 0% 50%;
          }
          100% {
            background-position: 220% 50%;
          }
        }

        @keyframes logoClickPop {
          0% {
            transform: scale(1) rotate(0deg);
          }
          35% {
            transform: scale(1.22) rotate(-8deg);
          }
          70% {
            transform: scale(0.96) rotate(3deg);
          }
          100% {
            transform: scale(1) rotate(0deg);
          }
        }

        @keyframes logoTextPop {
          0% {
            letter-spacing: -0.6px;
            transform: scale(1);
          }
          50% {
            letter-spacing: 1px;
            transform: scale(1.04);
          }
          100% {
            letter-spacing: -0.6px;
            transform: scale(1);
          }
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
          {/* New Crumbs C-Orbit Animated Logo */}
          <Link
            to="/dashboard"
            className={`brand ${logoClicked ? 'logo-clicked' : ''}`}
            onClick={handleLogoClick}
          >
            <svg
              width="42"
              height="42"
              viewBox="0 0 100 100"
              xmlns="http://www.w3.org/2000/svg"
              aria-hidden="true"
            >
              <defs>
                <linearGradient
                  id="crumbsLogoGrad"
                  x1="10%"
                  y1="90%"
                  x2="90%"
                  y2="10%"
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

                <radialGradient id="crumbsInnerGlow" cx="48%" cy="45%" r="62%">
                  <stop
                    offset="0%"
                    stopColor={theme === 'dark' ? '#4facfe' : '#0288d1'}
                    stopOpacity="0.32"
                  />
                  <stop
                    offset="100%"
                    stopColor={theme === 'dark' ? '#0b0f19' : '#ffffff'}
                    stopOpacity="0"
                  />
                </radialGradient>

                <filter
                  id="crumbsGlow"
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

              {/* Outer soft orbit circle */}
              <circle
                cx="50"
                cy="50"
                r="39"
                fill="url(#crumbsInnerGlow)"
                stroke="url(#crumbsLogoGrad)"
                strokeWidth="3"
                opacity="0.95"
              />

              {/* Bold C mark */}
              <path
                d="M 67 31 C 60 24, 47 22, 37 28 C 25 35, 21 51, 28 64 C 35 78, 53 82, 66 71"
                fill="none"
                stroke="url(#crumbsLogoGrad)"
                strokeWidth="10"
                strokeLinecap="round"
                strokeLinejoin="round"
                filter="url(#crumbsGlow)"
              />

              {/* Crumb/data dots */}
              <circle
                cx="69"
                cy="31"
                r="5.5"
                fill="url(#crumbsLogoGrad)"
                filter="url(#crumbsGlow)"
              />
              <circle
                cx="72"
                cy="50"
                r="4.5"
                fill="url(#crumbsLogoGrad)"
              />
              <circle
                cx="67"
                cy="70"
                r="5.5"
                fill="url(#crumbsLogoGrad)"
                filter="url(#crumbsGlow)"
              />

              {/* Inner analytics signal line */}
              <path
                d="M 36 52 L 48 44 L 59 55 L 72 50"
                fill="none"
                stroke={theme === 'dark' ? '#ffffff' : '#0f172a'}
                strokeWidth="4"
                strokeLinecap="round"
                strokeLinejoin="round"
                opacity="0.9"
              />

              {/* Inner signal nodes */}
              <circle
                cx="36"
                cy="52"
                r="3.8"
                fill={theme === 'dark' ? '#ffffff' : '#0f172a'}
              />
              <circle
                cx="48"
                cy="44"
                r="3.8"
                fill={theme === 'dark' ? '#ffffff' : '#0f172a'}
              />
              <circle
                cx="59"
                cy="55"
                r="3.8"
                fill={theme === 'dark' ? '#ffffff' : '#0f172a'}
              />

              {/* Premium highlight */}
              <circle cx="39" cy="30" r="2.5" fill="#ffffff" opacity="0.8" />
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
