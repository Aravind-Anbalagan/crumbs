import { BrowserRouter as Router, Routes, Route, Navigate } from 'react-router-dom';
import SignIn from './pages/SignIn';
import SignUp from './pages/SignUp';
import Dashboard from './pages/Dashboard';
import MainLayout from './layouts/MainLayout';
// 1. Import your new native React page
import StrategySetup from './pages/StrategySetup'; 
import OrderHistory from './pages/OrderHistory';

const ProtectedRoute = ({ children }) => {
  const isLoggedIn = localStorage.getItem('isLoggedIn') === 'true';
  return isLoggedIn ? children : <Navigate to="/signin" replace />;
};

const RootRedirect = () => {
  const isLoggedIn = localStorage.getItem('isLoggedIn') === 'true';
  return isLoggedIn ? <Navigate to="/dashboard/stock" replace /> : <Navigate to="/signin" replace />;
};

export default function App() {
  return (
    <Router>
      <Routes>
        <Route path="/" element={<RootRedirect />} />
        
        {/* Public Routes */}
        <Route path="/signin" element={<SignIn />} />
        <Route path="/signup" element={<SignUp />} />

        {/* Protected Routes */}
        <Route element={<ProtectedRoute><MainLayout /></ProtectedRoute>}>
          {/* 2. Add your new native React route */}
          <Route path="dashboard/strategy-setup" element={<StrategySetup />} />
          <Route path="dashboard/history" element={<OrderHistory />} />
          {/* Dynamic dashboard route for legacy iframes */}
          <Route path="dashboard/:pageName" element={<Dashboard />} />
          <Route path="dashboard" element={<Navigate to="/dashboard/stock" replace />} />
          
          {/* Direct access redirects */}
          <Route path="stock" element={<Navigate to="/dashboard/stock" replace />} />
          <Route path="result" element={<Navigate to="/dashboard/result" replace />} />
          <Route path="pre-market" element={<Navigate to="/dashboard/pre-market" replace />} />
          <Route path="short-straddle" element={<Navigate to="/dashboard/short-straddle" replace />} />
          <Route path="straddle" element={<Navigate to="/dashboard/short-straddle" replace />} />
        </Route>

        {/* Catch-all for bad URLs */}
        <Route path="*" element={<Navigate to="/signin" replace />} />
      </Routes>
    </Router>
  );
}