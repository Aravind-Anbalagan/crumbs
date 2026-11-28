import { BrowserRouter as Router, Routes, Route, Navigate } from 'react-router-dom';
import SignIn from './pages/SignIn';
import SignUp from './pages/SignUp';
import Dashboard from './pages/Dashboard';
import MainLayout from './layouts/MainLayout';
import StrategySetup from './pages/StrategySetup';
import OrderHistory from './pages/OrderHistory';
import OITracker from './pages/OITracker';
import AdvisoryDashboard from './pages/AdvisoryDashboard';
import OptionPrice from './pages/OptionPrice';
import VwapStraddle from './pages/VwapStraddle';
import VwapStraddlePage from './pages/VwapStraddlePage';
import TrendDashboard from './pages/TrendDashboard';

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
          <Route path="dashboard" element={<Navigate to="/dashboard/stock" replace />} />
          <Route path="dashboard/strategy-setup" element={<StrategySetup />} />
          <Route path="dashboard/history" element={<OrderHistory />} />
          <Route path="dashboard/oi-tracker" element={<OITracker />} />
          <Route path="dashboard/advisory" element={<AdvisoryDashboard />} />
          <Route path="dashboard/option-price" element={<OptionPrice />} />
          <Route path="dashboard/vwap-straddle" element={<VwapStraddle />} />
          <Route
            path="dashboard/vwap-short-straddle"
            element={
              <div style={{ height: '100%', overflowY: 'auto' }}>
                <VwapStraddlePage />
              </div>
            }
          />
          <Route path="dashboard/straddle-trend" element={<TrendDashboard />} />

          {/* Dynamic fallback for remaining dashboard pages */}
          <Route path="dashboard/:pageName" element={<Dashboard />} />

          {/* Direct access redirects */}
          <Route path="stock" element={<Navigate to="/dashboard/stock" replace />} />
          <Route path="result" element={<Navigate to="/dashboard/result" replace />} />
          <Route path="pre-market" element={<Navigate to="/dashboard/pre-market" replace />} />
          <Route path="intraday" element={<Navigate to="/dashboard/intraday" replace />} />
          <Route path="short-straddle" element={<Navigate to="/dashboard/short-straddle" replace />} />
          <Route path="straddle" element={<Navigate to="/dashboard/short-straddle" replace />} />
          <Route path="future" element={<Dashboard />} />
          <Route path="futures" element={<Dashboard />} />
        </Route>

        {/* Catch-all for bad URLs */}
        <Route path="*" element={<Navigate to="/signin" replace />} />
      </Routes>
    </Router>
  );
}