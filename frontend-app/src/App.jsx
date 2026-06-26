import { BrowserRouter as Router, Routes, Route, Navigate } from 'react-router-dom';
import SignIn from './pages/SignIn';
import SignUp from './pages/SignUp';
import Dashboard from './pages/Dashboard';
import MainLayout from './layouts/MainLayout';

const ProtectedRoute = ({ children }) => {
  const isLoggedIn = localStorage.getItem('isLoggedIn') === 'true';
  return isLoggedIn ? children : <Navigate to="/signin" replace />;
};

// ADD THIS NEW COMPONENT
const RootRedirect = () => {
  const isLoggedIn = localStorage.getItem('isLoggedIn') === 'true';
  return isLoggedIn ? <Navigate to="/dashboard/stock" replace /> : <Navigate to="/signin" replace />;
};

export default function App() {
  return (
    <Router>
      <Routes>
        {/* REPLACE THE OLD HARDCODED ROUTE WITH YOUR NEW SMART ROUTE */}
        <Route path="/" element={<RootRedirect />} />
        
        {/* Public Routes */}
        <Route path="/signin" element={<SignIn />} />
        <Route path="/signup" element={<SignUp />} />

        {/* Protected Routes */}
        <Route element={<ProtectedRoute><MainLayout /></ProtectedRoute>}>
          <Route path="dashboard/:pageName" element={<Dashboard />} />
          <Route path="dashboard" element={<Navigate to="/dashboard/stock" replace />} />
          
          <Route path="stock" element={<Navigate to="/dashboard/stock" replace />} />
          <Route path="result" element={<Navigate to="/dashboard/result" replace />} />
          <Route path="pre-market" element={<Navigate to="/dashboard/pre-market" replace />} />
          <Route path="short-straddle" element={<Navigate to="/dashboard/short-straddle" replace />} />
        </Route>

        {/* Catch-all for bad URLs */}
        <Route path="*" element={<Navigate to="/signin" replace />} />
      </Routes>
    </Router>
  );
}