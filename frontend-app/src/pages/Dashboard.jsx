import { useEffect } from 'react';
import { useNavigate, useParams, Navigate } from 'react-router-dom';
import LegacyPortal from '../components/LegacyPortal';

export default function Dashboard() {
  const navigate = useNavigate();
  // Using URL parameters allows us to use one Dashboard file for all pages
  const { pageName } = useParams(); 

  useEffect(() => {
    const isLoggedIn = localStorage.getItem('isLoggedIn') === 'true';
    if (!isLoggedIn) {
      navigate('/signin');
    }
  }, [navigate]);

  // Map URL params to file names
  const pageMap = {
    'stock': '/stock.html',
    'premarket': '/premarket.html',
    'straddle': '/shortstraddle.html',
    'trend': '/trend.html',
    'orders': '/orders.html',
    'log': '/log.html',
    'future': '/future.html',   // 👈 ADD THIS LINE
    'futures': '/future.html'   // 👈 ADD THIS TOO (Bulletproof!)
  };

  const src = pageMap[pageName] || '/stock.html';

  return (
    <div style={{ height: '100%', display: 'flex', flexDirection: 'column' }}>
      <LegacyPortal srcFile={src} />
    </div>
  );
}