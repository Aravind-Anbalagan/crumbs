import { BrowserRouter as Router, Routes, Route, Navigate } from 'react-router-dom';
import SignIn from './pages/SignIn';
import SignUp from './pages/SignUp';
import Dashboard from './pages/Dashboard';
import MainLayout from './layouts/MainLayout';

const ProtectedRoute = ({ children }) => {
  const isLoggedIn = localStorage.getItem('isLoggedIn') === 'true';
  return isLoggedIn ? children : <Navigate to="/signin" replace />;
};

export default function App() {
  return (
    <Router>
      <Routes>
        {/* 1. Add this line: Redirect root path to signin */}
        <Route path="/" element={<Navigate to="/signin" replace />} />
        
        {/* Public Routes */}
        <Route path="/signin" element={<SignIn />} />
        <Route path="/signup" element={<SignUp />} />

        {/* Protected Routes */}
        <Route element={<ProtectedRoute><MainLayout /></ProtectedRoute>}>
          <Route path="dashboard/:pageName" element={<Dashboard />} />
          <Route path="dashboard" element={<Navigate to="/dashboard/stock" replace />} />
          
          {/* Also handle direct access to modules if you want */}
          <Route path="stock" element={<Navigate to="/dashboard/stock" replace />} />
        </Route>

        {/* 2. Keep your catch-all for bad URLs */}
        <Route path="*" element={<Navigate to="/signin" replace />} />
      </Routes>
    </Router>
  );
}