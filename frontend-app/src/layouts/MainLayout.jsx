import { Outlet, Link, useNavigate, useLocation } from 'react-router-dom';
import { useState, useRef, useEffect } from 'react';

/* ------------------------------------------------------------------ */
/* Menu model                                                          */
/* ------------------------------------------------------------------ */
/*
  Grouping, confirmed:

  - Stock            -> Stock Analysis, Advisory, Future
  - Option Selling    -> Short Straddle
  - Option Buying      -> Intraday, Market Trend
  - Order              -> Order Book, Analytics
  - Strategy Setup    -> Strategy Setup
  - Log                -> Logs
*/

const MENU = [
  {
    key: 'stock',
    label: 'Stock',
    accent: '--grp-stock',
    icon: (c) => (
      <path
        d="M4 27L11 18L16 22L23 12L28 16"
        stroke={c}
        strokeWidth="2.4"
        strokeLinecap="round"
        strokeLinejoin="round"
        fill="none"
      />
    ),
    items: [
      {
        to: '/dashboard/stock',
        label: 'Stock Analysis',
        desc: 'Screen and evaluate equities',
      },
      {
        to: '/dashboard/advisory',
        label: 'Advisory',
        desc: 'Signals & guidance',
      },
      {
        to: '/dashboard/future',
        label: 'Future',
        desc: 'Directional futures positions',
      },
    ],
  },
  {
    key: 'selling',
    label: 'Option Selling',
    accent: '--grp-selling',
    icon: (c) => (
      <path
        d="M6 10L26 10M26 10V16M26 10L15 21L10 16L4 22"
        stroke={c}
        strokeWidth="2.4"
        strokeLinecap="round"
        strokeLinejoin="round"
        fill="none"
      />
    ),
    items: [
      {
        to: '/dashboard/straddle',
        label: 'Short Straddle',
        desc: 'Premium-collecting neutral setup',
      },
    ],
  },
  {
    key: 'buying',
    label: 'Option Buying',
    accent: '--grp-buying',
    icon: (c) => (
      <path
        d="M6 22L26 22M26 22V16M26 22L15 11L10 16L4 10"
        stroke={c}
        strokeWidth="2.4"
        strokeLinecap="round"
        strokeLinejoin="round"
        fill="none"
      />
    ),
    items: [
        {
                to: '/dashboard/option-price', // 👈 Added here
                label: 'Live Scanner',
                desc: 'Real-time RSI & MA triggers',
              },
      {
        to: '/dashboard/intraday',
        label: 'Intraday',
        desc: 'Live price action, same-day moves',
      },
      {
        to: '/dashboard/trend',
        label: 'Market Trend',
        desc: 'Broader direction & momentum',
      },
    ],
  },
  {
    key: 'order',
    label: 'Order',
    accent: '--grp-order',
    icon: (c) => (
      <path
        d="M8 4H22L24 8V26H6V8L8 4Z M6 8H24 M12 13H18 M12 17H18"
        stroke={c}
        strokeWidth="2.2"
        strokeLinecap="round"
        strokeLinejoin="round"
        fill="none"
      />
    ),
    items: [
      {
        to: '/dashboard/orders',
        label: 'Order Book',
        desc: 'Live & filled orders',
      },
      {
        to: '/dashboard/history',
        label: 'Analytics',
        desc: 'Historical performance & reports',
      },
    ],
  },
  {
    key: 'strategy',
    label: 'Strategy Setup',
    accent: '--grp-strategy',
    icon: (c) => (
      <path
        d="M16 4V8 M16 24V28 M4 16H8 M24 16H28 M16 11a5 5 0 100 10 5 5 0 000-10z"
        stroke={c}
        strokeWidth="2.2"
        strokeLinecap="round"
        strokeLinejoin="round"
        fill="none"
      />
    ),
    items: [
      {
        to: '/dashboard/strategy-setup',
        label: 'Strategy Setup',
        desc: 'Configure automated strategies',
      },
    ],
  },
  {
    key: 'log',
    label: 'Log',
    accent: '--grp-log',
    icon: (c) => (
      <path
        d="M6 5H26V27H6V5Z M10 11H22 M10 16H22 M10 21H18"
        stroke={c}
        strokeWidth="2.2"
        strokeLinecap="round"
        strokeLinejoin="round"
        fill="none"
      />
    ),
    items: [
      {
        to: '/dashboard/log',
        label: 'Logs',
        desc: 'System & execution activity',
      },
    ],
  },
];

export default function MainLayout() {
  const navigate = useNavigate();
  const location = useLocation();
  const [theme, setTheme] = useState('dark');
  const [logoClicked, setLogoClicked] = useState(false);
  const [openGroup, setOpenGroup] = useState(null);
  const navRef = useRef(null);

  const handleLogout = () => {
    localStorage.removeItem('userEmail');
    localStorage.removeItem('isLoggedIn');
    sessionStorage.removeItem('isLoggedIn');
    navigate('/signin');
  };

  const toggleTheme = () =>
    setTheme((prev) => (prev === 'dark' ? 'light' : 'dark'));

  const isActive = (path) => location.pathname.startsWith(path);
  const groupIsActive = (group) =>
    group.items.some((item) => isActive(item.to));

  const handleLogoClick = () => {
    setLogoClicked(true);
    setTimeout(() => setLogoClicked(false), 450);
  };

  // Close dropdown on outside click / escape / route change.
  useEffect(() => {
    const onClick = (e) => {
      if (navRef.current && !navRef.current.contains(e.target)) {
        setOpenGroup(null);
      }
    };
    const onKey = (e) => {
      if (e.key === 'Escape') setOpenGroup(null);
    };
    document.addEventListener('mousedown', onClick);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('mousedown', onClick);
      document.removeEventListener('keydown', onKey);
    };
  }, []);

  useEffect(() => {
    setOpenGroup(null);
  }, [location.pathname]);

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
          --text-faint: rgba(255, 255, 255, 0.38);

          --glass-bg: rgba(11, 15, 25, 0.82);
          --glass-border: rgba(255, 255, 255, 0.08);

          --panel-bg: rgba(15, 20, 32, 0.98);
          --panel-border: rgba(255, 255, 255, 0.1);
          --panel-item-hover: rgba(255, 255, 255, 0.05);

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

          --grp-stock: #4facfe;
          --grp-selling: #fb7185;
          --grp-buying: #34d399;
          --grp-order: #fbbf24;
          --grp-strategy: #c084fc;
          --grp-log: #94a3b8;
        }

        .theme-light {
          --bg-main: #ffffff;
          --text-main: #0f172a;
          --text-muted: rgba(0, 0, 0, 0.6);
          --text-faint: rgba(0, 0, 0, 0.38);

          --glass-bg: rgba(255, 255, 255, 0.85);
          --glass-border: rgba(0, 0, 0, 0.08);

          --panel-bg: rgba(255, 255, 255, 0.99);
          --panel-border: rgba(0, 0, 0, 0.08);
          --panel-item-hover: rgba(2, 136, 209, 0.06);

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

          --grp-stock: #0288d1;
          --grp-selling: #e11d48;
          --grp-buying: #059669;
          --grp-order: #b45309;
          --grp-strategy: #7c3aed;
          --grp-log: #475569;
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
          z-index: 30;
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

        .brand svg.logo-mark {
          flex-shrink: 0;
          transition: transform 0.35s ease, filter 0.35s ease;
        }

        .brand:hover {
          animation: brandTextFlow 1.8s linear infinite;
          transform: translateY(-1px);
        }

        .brand:hover svg.logo-mark {
          transform: scale(1.1) rotate(4deg);
          filter: drop-shadow(0 0 14px var(--logo-shadow));
        }

        .brand.logo-clicked {
          animation: logoTextPop 0.45s ease;
        }

        .brand.logo-clicked svg.logo-mark {
          animation: logoClickPop 0.45s ease;
        }

        @keyframes brandTextFlow {
          0% { background-position: 0% 50%; }
          100% { background-position: 220% 50%; }
        }

        @keyframes logoClickPop {
          0% { transform: scale(1) rotate(0deg); }
          35% { transform: scale(1.22) rotate(-8deg); }
          70% { transform: scale(0.96) rotate(3deg); }
          100% { transform: scale(1) rotate(0deg); }
        }

        @keyframes logoTextPop {
          0% { letter-spacing: -0.6px; transform: scale(1); }
          50% { letter-spacing: 1px; transform: scale(1.04); }
          100% { letter-spacing: -0.6px; transform: scale(1); }
        }

        /* ---------------- Grouped mega-menu nav ---------------- */

        .center-menu {
          display: flex;
          gap: 0.25rem;
          align-items: center;
          height: 100%;
        }

        .nav-group {
          position: relative;
          height: 100%;
          display: flex;
          align-items: center;
        }

        .nav-group-btn {
          display: flex;
          align-items: center;
          gap: 6px;
          background: transparent;
          border: 1px solid transparent;
          color: var(--text-muted);
          font-size: 0.95rem;
          font-weight: 600;
          padding: 6px 10px;
          border-radius: 7px;
          cursor: pointer;
          white-space: nowrap;
          font-family: inherit;
          transition: background 0.18s ease, color 0.18s ease, border-color 0.18s ease, transform 0.15s ease;
        }

        .nav-group-btn:active {
          transform: scale(0.96);
        }

        .nav-group-btn:hover {
          background: var(--nav-hover);
          color: var(--text-main);
        }

        .nav-group.is-active .nav-group-btn {
          color: var(--group-accent);
          background: var(--nav-hover);
        }

        .nav-group.is-open .nav-group-btn {
          background: var(--panel-item-hover);
          border-color: var(--panel-border);
          color: var(--text-main);
        }

        .nav-group-dot {
          width: 6px;
          height: 6px;
          border-radius: 50%;
          background: var(--group-accent);
          flex-shrink: 0;
          box-shadow: 0 0 0 3px transparent;
          transition: box-shadow 0.18s ease;
        }

        .nav-group.is-active .nav-group-dot {
          box-shadow: 0 0 0 3px color-mix(in srgb, var(--group-accent) 22%, transparent);
        }

        .chevron {
          transition: transform 0.18s ease;
          opacity: 0.6;
          flex-shrink: 0;
        }

        .nav-group.is-open .chevron {
          transform: rotate(180deg);
        }

        .nav-panel {
          position: absolute;
          top: calc(100% + 10px);
          left: 0;
          min-width: 260px;
          background: var(--panel-bg);
          border: 1px solid var(--panel-border);
          border-radius: 12px;
          padding: 8px;
          box-shadow: 0 18px 40px -12px rgba(0, 0, 0, 0.45);
          backdrop-filter: blur(16px);
          -webkit-backdrop-filter: blur(16px);
          opacity: 0;
          transform: translateY(-6px) scale(0.98);
          pointer-events: none;
          transform-origin: top left;
          transition: opacity 0.16s ease, transform 0.16s ease;
          z-index: 40;
        }

        .nav-group.is-open .nav-panel {
          opacity: 1;
          transform: translateY(0) scale(1);
          pointer-events: auto;
        }

        .nav-panel::before {
          content: '';
          position: absolute;
          top: -6px;
          left: 22px;
          width: 11px;
          height: 11px;
          background: var(--panel-bg);
          border-left: 1px solid var(--panel-border);
          border-top: 1px solid var(--panel-border);
          transform: rotate(45deg);
        }

        @keyframes panelItemIn {
          from {
            opacity: 0;
            transform: translateY(-6px);
          }
          to {
            opacity: 1;
            transform: translateY(0);
          }
        }

        .panel-item {
          display: flex;
          align-items: flex-start;
          gap: 10px;
          padding: 9px 10px;
          border-radius: 8px;
          text-decoration: none;
          color: var(--text-main);
          border-left: 2px solid transparent;
          opacity: 0;
          transition: background 0.15s ease, border-color 0.15s ease, padding-left 0.15s ease;
        }

        .nav-group.is-open .panel-item {
          animation: panelItemIn 0.22s ease forwards;
        }

        .nav-group.is-open .panel-item:nth-child(1) { animation-delay: 0.02s; }
        .nav-group.is-open .panel-item:nth-child(2) { animation-delay: 0.06s; }
        .nav-group.is-open .panel-item:nth-child(3) { animation-delay: 0.1s; }
        .nav-group.is-open .panel-item:nth-child(4) { animation-delay: 0.14s; }

        .panel-item:hover {
          background: var(--panel-item-hover);
          border-left-color: var(--group-accent);
          padding-left: 12px;
        }

        .panel-item.active {
          background: var(--panel-item-hover);
          border-left-color: var(--group-accent);
        }

        .panel-item-bullet {
          width: 6px;
          height: 6px;
          margin-top: 6px;
          border-radius: 50%;
          background: var(--text-faint);
          flex-shrink: 0;
          transition: background 0.15s ease;
        }

        .panel-item.active .panel-item-bullet,
        .panel-item:hover .panel-item-bullet {
          background: var(--group-accent);
        }

        .panel-item-title {
          font-size: 0.97rem;
          font-weight: 600;
          line-height: 1.25;
        }

        .panel-item.active .panel-item-title {
          color: var(--group-accent);
        }

        .panel-item-desc {
          font-size: 0.82rem;
          color: var(--text-faint);
          margin-top: 2px;
          line-height: 1.3;
        }

        /* ---------------- Utility cluster ---------------- */

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

        @media (max-width: 1200px) {
          .glass-topbar {
            padding: 0 1rem;
          }

          .center-menu {
            gap: 0.1rem;
          }

          .nav-group-btn {
            font-size: 0.86rem;
            padding: 6px 8px;
          }

          .brand {
            font-size: 1.15rem;
          }
        }
      `}</style>

      <div className={`layout-canvas theme-${theme}`}>
        <header className="glass-topbar">
          {/* Crumbs C-Orbit Animated Logo */}
          <Link
            to="/dashboard"
            className={`brand ${logoClicked ? 'logo-clicked' : ''}`}
            onClick={handleLogoClick}
          >
            <svg
              className="logo-mark"
              width="42"
              height="42"
              viewBox="0 0 100 100"
              xmlns="http://www.w3.org/2000/svg"
              aria-hidden="true"
            >
              <defs>
                <linearGradient id="crumbsLogoGrad" x1="10%" y1="90%" x2="90%" y2="10%">
                  <stop offset="0%" stopColor={theme === 'dark' ? '#00f2fe' : '#006064'} />
                  <stop offset="45%" stopColor={theme === 'dark' ? '#4facfe' : '#0288d1'} />
                  <stop offset="100%" stopColor={theme === 'dark' ? '#a855f7' : '#7c3aed'} />
                </linearGradient>

                <radialGradient id="crumbsInnerGlow" cx="48%" cy="45%" r="62%">
                  <stop offset="0%" stopColor={theme === 'dark' ? '#4facfe' : '#0288d1'} stopOpacity="0.32" />
                  <stop offset="100%" stopColor={theme === 'dark' ? '#0b0f19' : '#ffffff'} stopOpacity="0" />
                </radialGradient>

                <filter id="crumbsGlow" x="-50%" y="-50%" width="200%" height="200%">
                  <feGaussianBlur stdDeviation="3" result="blur" />
                  <feMerge>
                    <feMergeNode in="blur" />
                    <feMergeNode in="SourceGraphic" />
                  </feMerge>
                </filter>
              </defs>

              <circle
                cx="50" cy="50" r="39"
                fill="url(#crumbsInnerGlow)"
                stroke="url(#crumbsLogoGrad)"
                strokeWidth="3"
                opacity="0.95"
              />

              <path
                d="M 67 31 C 60 24, 47 22, 37 28 C 25 35, 21 51, 28 64 C 35 78, 53 82, 66 71"
                fill="none"
                stroke="url(#crumbsLogoGrad)"
                strokeWidth="10"
                strokeLinecap="round"
                strokeLinejoin="round"
                filter="url(#crumbsGlow)"
              />

              <circle cx="69" cy="31" r="5.5" fill="url(#crumbsLogoGrad)" filter="url(#crumbsGlow)" />
              <circle cx="72" cy="50" r="4.5" fill="url(#crumbsLogoGrad)" />
              <circle cx="67" cy="70" r="5.5" fill="url(#crumbsLogoGrad)" filter="url(#crumbsGlow)" />

              <path
                d="M 36 52 L 48 44 L 59 55 L 72 50"
                fill="none"
                stroke={theme === 'dark' ? '#ffffff' : '#0f172a'}
                strokeWidth="4"
                strokeLinecap="round"
                strokeLinejoin="round"
                opacity="0.9"
              />

              <circle cx="36" cy="52" r="3.8" fill={theme === 'dark' ? '#ffffff' : '#0f172a'} />
              <circle cx="48" cy="44" r="3.8" fill={theme === 'dark' ? '#ffffff' : '#0f172a'} />
              <circle cx="59" cy="55" r="3.8" fill={theme === 'dark' ? '#ffffff' : '#0f172a'} />

              <circle cx="39" cy="30" r="2.5" fill="#ffffff" opacity="0.8" />
            </svg>

            crumbs
          </Link>

          <nav className="center-menu" ref={navRef}>
            {MENU.map((group) => {
              const active = groupIsActive(group);
              const open = openGroup === group.key;
              return (
                <div
                  key={group.key}
                  className={`nav-group ${active ? 'is-active' : ''} ${open ? 'is-open' : ''}`}
                  style={{ '--group-accent': `var(${group.accent})` }}
                >
                  <button
                    type="button"
                    className="nav-group-btn"
                    aria-expanded={open}
                    onClick={() =>
                      setOpenGroup((prev) => (prev === group.key ? null : group.key))
                    }
                  >
                    <svg width="18" height="18" viewBox="0 0 32 32" aria-hidden="true">
                      {group.icon('var(--group-accent)')}
                    </svg>
                    {group.label}
                    {group.items.length > 1 && (
                      <svg
                        className="chevron"
                        width="10"
                        height="10"
                        viewBox="0 0 10 10"
                        aria-hidden="true"
                      >
                        <path
                          d="M1.5 3L5 6.5L8.5 3"
                          stroke="currentColor"
                          strokeWidth="1.4"
                          fill="none"
                          strokeLinecap="round"
                          strokeLinejoin="round"
                        />
                      </svg>
                    )}
                  </button>

                  <div className="nav-panel" role="menu">
                    {group.items.map((item) => (
                      <Link
                        key={item.to}
                        to={item.to}
                        role="menuitem"
                        className={`panel-item ${isActive(item.to) ? 'active' : ''}`}
                      >
                        <span className="panel-item-bullet" />
                        <span>
                          <div className="panel-item-title">{item.label}</div>
                          <div className="panel-item-desc">{item.desc}</div>
                        </span>
                      </Link>
                    ))}
                  </div>
                </div>
              );
            })}
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