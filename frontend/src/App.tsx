import { Routes, Route, Navigate } from 'react-router-dom';
import ProtectedRoute from './components/ProtectedRoute';
import AppLayout from './components/AppLayout';
import LoginPage from './pages/LoginPage';
import DashboardPage from './pages/DashboardPage';
import RuleScenarioPage from './pages/RuleScenarioPage';
import ReviewWorkspacePage from './pages/ReviewWorkspacePage';
import ModelConfigPage from './pages/ModelConfigPage';
import ProfilePage from './pages/ProfilePage';
import UserManagementPage from './pages/UserManagementPage';

function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route
        path="/"
        element={
          <ProtectedRoute>
            <AppLayout />
          </ProtectedRoute>
        }
      >
        <Route index element={<Navigate to="/dashboard" replace />} />
        <Route path="dashboard" element={<DashboardPage />} />
        <Route path="rules" element={<RuleScenarioPage />} />
        <Route path="scenarios" element={<RuleScenarioPage />} />
        <Route path="review" element={<Navigate to="/dashboard" replace />} />
        <Route path="review/:taskId" element={<ReviewWorkspacePage />} />
        <Route path="models" element={<ModelConfigPage />} />
        <Route path="profile" element={<ProfilePage />} />
        <Route path="users" element={<UserManagementPage />} />
      </Route>
      <Route path="*" element={<Navigate to="/dashboard" replace />} />
    </Routes>
  );
}

export default App;
