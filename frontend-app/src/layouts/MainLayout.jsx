import { useEffect, useRef, useState } from 'react';
import { Link, Outlet, useLocation, useNavigate } from 'react-router-dom';
import './MainLayout.css';

/* ============================================================================
 * Navigation Configuration
 * Add or reorder routes here — layout and active states update automatically.
 * ============================================================================ */
const MENU_GROUPS = [
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
      { to: '/dashboard/stock', label: 'Stock Analysis', desc: 'Screen and evaluate equities' },
      { to: '/dashboard/advisory', label: 'Advisory', desc: 'Signals & guidance' },
      { to: '/dashboard/future', label: 'Future', desc: 'Directional futures positions' },
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
      { to: '/dashboard/straddle', label: 'Short Straddle', desc: 'Premium-collecting neutral setup' },
      { to: '/dashboard/vwap-short-straddle', label: 'VWAP Short Straddle', desc: 'Watchlist CP vs CV hit monitor & alerts' },
      { to: '/dashboard/oi-tracker', label: '⚡ OI Tracker', desc: 'Live OI momentum & PCR skew' },
      { to: '/dashboard/straddle-trend', label: 'Straddle Trend', desc: 'Multi-index VWAP + OI trend & alerts' },
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
      { to: '/dashboard/option-price', label: 'Live Scanner', desc: 'Real-time RSI & MA triggers' },
      { to: '/dashboard/intraday', label: 'Intraday', desc: 'Live price action, same-day moves' },
      { to: '/dashboard/trend', label: 'Market Trend', desc: 'Broader direction & momentum' },
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
      { to: '/dashboard/orders', label: 'Order Book', desc: 'Live & filled orders' },
      { to: '/dashboard/history', label: 'Analytics', desc: 'Historical performance & reports' },
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
      { to: '/dashboard/strategy-setup', label: 'Strategy Setup', desc: 'Configure automated strategies' },
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
      { to: '/dashboard/log', label: 'Logs', desc: 'System & execution activity' },
    ],
  },
];

/* ============================================================================
 * Brand Logo SVG
 * ============================================================================ */
function CrumbsLogo({ theme }) {
  const isDark = theme === 'dark';
  const strokeMain = isDark ? '#ffffff' : '#0f172a';

  return (
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
          <stop offset="0%" stopColor={isDark ? '#00f2fe' : '#006064'} />
          <stop offset="45%" stopColor={isDark ? '#4facfe' : '#0288d1'} />
          <stop offset="100%" stopColor={isDark ? '#a855f7' : '#7c3aed'} />
        </linearGradient>

        <radialGradient id="crumbsInnerGlow" cx="48%" cy="45%" r="62%">
          <stop offset="0%" stopColor={isDark ? '#4facfe' : '#0288d1'} stopOpacity="0.32" />
          <stop offset="100%" stopColor={isDark ? '#0b0f19' : '#ffffff'} stopOpacity="0" />
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
        cx="50"
        cy="50"
        r="39"
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
        stroke={strokeMain}
        strokeWidth="4"
        strokeLinecap="round"
        strokeLinejoin="round"
        opacity="0.9"
      />

      <circle cx="36" cy="52" r="3.8" fill={strokeMain} />
      <circle cx="48" cy="44" r="3.8" fill={strokeMain} />
      <circle cx="59" cy="55" r="3.8" fill={strokeMain} />
      <circle cx="39" cy="30" r="2.5" fill="#ffffff" opacity="0.8" />
    </svg>
  );
}

/* ============================================================================
 * Main Layout Component
 * ============================================================================ */
export default function MainLayout() {
  const navigate = useNavigate();
  const location = useLocation();
  const navRef = useRef(null);

  const [theme, setTheme] = useState(() => localStorage.getItem('crumbs-theme') || 'dark');
  const [logoClicked, setLogoClicked] = useState(false);
  const [openGroup, setOpenGroup] = useState(null);

  const isActive = (path) => location.pathname.startsWith(path);
  const groupIsActive = (group) => group.items.some((item) => isActive(item.to));

  const toggleTheme = () => {
    setTheme((prev) => {
      const next = prev === 'dark' ? 'light' : 'dark';
      localStorage.setItem('crumbs-theme', next);
      return next;
    });
  };

  const handleLogout = () => {
    localStorage.removeItem('userEmail');
    localStorage.removeItem('isLoggedIn');
    sessionStorage.removeItem('isLoggedIn');
    navigate('/signin');
  };

  const handleLogoClick = () => {
    setLogoClicked(true);
    setTimeout(() => setLogoClicked(false), 450);
  };

  // Close open dropdown on outside click or Escape
  useEffect(() => {
    const onMouseDown = (e) => {
      if (navRef.current && !navRef.current.contains(e.target)) {
        setOpenGroup(null);
      }
    };
    const onKeyDown = (e) => {
      if (e.key === 'Escape') setOpenGroup(null);
    };

    document.addEventListener('mousedown', onMouseDown);
    document.addEventListener('keydown', onKeyDown);
    return () => {
      document.removeEventListener('mousedown', onMouseDown);
      document.removeEventListener('keydown', onKeyDown);
    };
  }, []);

  // Close dropdown on navigation
  useEffect(() => {
    setOpenGroup(null);
  }, [location.pathname]);

  return (
    <div className={`layout-canvas theme-${theme}`}>
      <header className="glass-topbar">
        <Link
          to="/dashboard"
          className={`brand ${logoClicked ? 'logo-clicked' : ''}`}
          onClick={handleLogoClick}
        >
          <CrumbsLogo theme={theme} />
          <span>crumbs</span>
        </Link>

        <nav className="center-menu" ref={navRef}>
          {MENU_GROUPS.map((group) => {
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
                  onClick={() => setOpenGroup((prev) => (prev === group.key ? null : group.key))}
                >
                  <svg width="18" height="18" viewBox="0 0 32 32" aria-hidden="true">
                    {group.icon('var(--group-accent)')}
                  </svg>
                  <span>{group.label}</span>
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
          <button
            type="button"
            className="icon-btn"
            onClick={toggleTheme}
            title={`Switch to ${theme === 'dark' ? 'light' : 'dark'} theme`}
          >
            {theme === 'dark' ? '☀️' : '🌙'}
          </button>

          <button type="button" className="logout-btn" onClick={handleLogout}>
            Disconnect
          </button>
        </div>
      </header>

      <main className="main-content">
        <Outlet context={{ theme }} />
      </main>
    </div>
  );
}