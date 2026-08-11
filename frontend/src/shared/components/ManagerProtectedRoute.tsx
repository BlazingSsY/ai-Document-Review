import { Navigate } from 'react-router-dom';
import useAuthStore from '../../features/auth/store/authStore';

function ManagerProtectedRoute({ children }: { children: React.ReactNode }) {
  const role = useAuthStore((state) => state.user?.role);
  return role === 'supervisor' || role === 'admin'
    ? <>{children}</>
    : <Navigate to="/dashboard" replace />;
}

export default ManagerProtectedRoute;
