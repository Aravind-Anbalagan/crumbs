import { useNavigate } from 'react-router-dom';

export default function Dashboard() {
  const navigate = useNavigate();
  // Retrieve the email we saved during login
  const userEmail = localStorage.getItem('userEmail') || 'System User';

  const handleLogout = () => {
    // Clear the memory and instantly kick them to the login screen
    localStorage.removeItem('userEmail');
    navigate('/signin');
  };

  return (
    <div style={styles.layout}>
      {/* Top Navigation Bar */}
      <nav style={styles.nav}>
        <h1 style={styles.logo}>Crumbs Dashboard</h1>
        <div style={styles.userInfo}>
          <span style={styles.emailText}>{userEmail}</span>
          <button onClick={handleLogout} style={styles.logoutButton}>Logout</button>
        </div>
      </nav>

      {/* Main Content Area */}
      <main style={styles.main}>
        <div style={styles.card}>
          <h2>System Status</h2>
          <p>Welcome to your control center. All backend services are currently connected.</p>
        </div>

        <div style={styles.grid}>
          <div style={styles.card}>
            <h3>Active Scanners</h3>
            <p className="text-gray-500">Awaiting configuration...</p>
          </div>
          <div style={styles.card}>
            <h3>Recent Activity</h3>
            <p className="text-gray-500">No recent logs.</p>
          </div>
        </div>
      </main>
    </div>
  );
}

// Scoped styling for the dashboard
const styles = {
  layout: { minHeight: '100vh', backgroundColor: '#f3f4f6', fontFamily: 'system-ui, sans-serif' },
  nav: { display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '1rem 2rem', backgroundColor: '#1f2937', color: '#ffffff' },
  logo: { margin: 0, fontSize: '1.25rem', fontWeight: 'bold' },
  userInfo: { display: 'flex', alignItems: 'center', gap: '1rem' },
  emailText: { fontSize: '0.875rem', color: '#d1d5db' },
  logoutButton: { backgroundColor: '#ef4444', color: 'white', border: 'none', padding: '0.5rem 1rem', borderRadius: '4px', cursor: 'pointer', fontWeight: '600' },
  main: { padding: '2rem', maxWidth: '1200px', margin: '0 auto', display: 'flex', flexDirection: 'column', gap: '1.5rem' },
  grid: { display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(300px, 1fr))', gap: '1.5rem' },
  card: { backgroundColor: '#ffffff', padding: '1.5rem', borderRadius: '8px', boxShadow: '0 1px 3px rgba(0,0,0,0.1)' }
};